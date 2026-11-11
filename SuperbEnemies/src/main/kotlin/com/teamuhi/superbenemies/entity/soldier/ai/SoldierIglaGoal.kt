package com.teamuhi.superbenemies.entity.soldier.ai

import com.atsuishio.superbwarfare.entity.projectile.ITrackableProjectile
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.init.ModTags
import com.atsuishio.superbwarfare.tools.SeekTool
import com.teamuhi.superbenemies.config.SoldierConfig
import com.teamuhi.superbenemies.entity.boss.common.air.BossAircraft
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedBosses
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedProjectiles
import com.teamuhi.superbenemies.entity.dummy.ai.DummyAi
import com.teamuhi.superbenemies.entity.soldier.DummySoldierEntity
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.levelgen.Heightmap
import java.util.EnumSet
import kotlin.math.abs

/**
 * The soldier's "smart ADS": it finds a boss aircraft it can lock like a player with an Igla would, aims down the
 * sights, holds the lock until the Igla's lock time is up, fires, and keeps aiming for as long as its missile flies,
 * because Superb Warfare's Igla only steers while the shooter aims. The goal holds MOVE, LOOK and JUMP, so the soldier
 * stands still and looks at the boss while it runs.
 *
 * Target scan (every [SCAN_INTERVAL] ticks, over [TrackedBosses] only): a boss qualifies when it is neither crippled nor
 * a wreck, between `minEngageRange` and `engageRange` away, at least [MIN_TARGET_HEIGHT] blocks above the ground
 * (the Igla's `MinTargetHeight`), in line of sight (eye to hitbox centre, the rule of SBW's client `noClip`) and out of
 * smoke. The one nearest to where the soldier already looks wins, then the nearest.
 *
 * Phases:
 *  1. REACT: `reactionTicks` facing the boss, standing still.
 *  2. RAISE: the launcher goes up ([RAISE_TICKS], the Igla's zoom-in); the synced `aiming` flag is set here.
 *  3. SEEK: the soldier swings onto the boss at `aimTurnSpeed` degrees per tick, with a small wobble. A counter rises
 *     while the boss is inside the [LOCK_CONE] degree cone, in sight and out of smoke; any break resets it (SBW's
 *     `seekFailure`). Sight lost for more than [SIGHT_LOST_ABORT] ticks aborts.
 *  4. LOCKED once the counter exceeds `lockTimeTicks`: fire, unless `holdFireOnFlares` and a decoy burns near the boss
 *     (held for up to [FLARE_HOLD_MAX] ticks) or `staggerShots` and another soldier's missile already tracks the same boss
 *     (held for up to [STAGGER_HOLD_MAX] ticks). Losing the lock while holding goes back to SEEK.
 *  5. GUIDE: keeps aiming and tracking until the missile is gone. The soldier entity's watchdog then judges hit or miss,
 *     and reloads. Ending this goal in any other way lowers the launcher, and the watchdog sets the missile lost.
 *
 * Aborts: the boss dies, is crippled or leaves the level, the soldier dies, loses its missile or has to take cover.
 * After an abort the scan waits [RETRY_AFTER_ABORT] ticks. The line of sight, smoke, decoy and stagger checks are
 * sampled every [CHECK_INTERVAL] ticks and cached in between.
 */
class SoldierIglaGoal(private val soldier: DummySoldierEntity) : Goal() {

    private enum class Phase { REACT, RAISE, SEEK, LOCKED, GUIDE }

    private var target: Entity? = null
    private var candidate: Entity? = null
    private var phase = Phase.REACT
    private var aborted = false

    private var nextScanTick = 0
    private var nextCheckTick = 0

    private var phaseTicks = 0
    private var engagedTicks = 0
    private var seekTicks = 0
    private var sightLostTicks = 0
    private var flareHold = 0
    private var staggerHold = 0

    // Cached results of the sampled checks.
    private var lockable = true
    private var flaresNear = false
    private var staggered = false

    private val aiming = SoldierAiming(soldier)

    /** The idle scan skipped a boss because the shared raycast budget was spent (see [DummyAi.sightRays]). */
    private var rayDeferred = false

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun canUse(): Boolean {
        if (!soldier.isAlive || soldier.isPassenger || soldier.ammo.loaded <= 0 || soldier.activeMissile != null) {
            soldier.thought.think("igla.canUse") {
                "no: " + when {
                    !soldier.isAlive -> "dead"
                    soldier.isPassenger -> "riding something"
                    soldier.ammo.loaded <= 0 -> "launcher empty"
                    else -> "a missile is still flying"
                }
            }
            return false
        }
        val now = soldier.tickCount
        if (now < nextScanTick) return false
        nextScanTick = now + SCAN_INTERVAL
        if (soldier.wantsCover()) {
            soldier.thought.think("igla.canUse") { "no: wants cover" }
            return false
        }
        rayDeferred = false
        candidate = scan()
        // Other soldiers used this tick's raycasts: look again next tick instead of waiting a full scan interval.
        if (candidate == null && rayDeferred) nextScanTick = now + 1
        soldier.thought.think("igla.canUse") {
            val found = candidate
            if (found != null) "yes: ${found.name.string} is lockable" else "no: no lockable boss aircraft"
        }
        return candidate != null
    }

    override fun canContinueToUse(): Boolean {
        val reason = continuesReason()
        if (reason != null) soldier.thought.think("igla.canContinueToUse") { "no: $reason" }
        return reason == null
    }

    /** Why the engagement cannot go on, or null when it can. */
    private fun continuesReason(): String? {
        if (aborted) return "engagement aborted"
        if (!soldier.isAlive || soldier.isPassenger) return "soldier unable to fight"
        val boss = target ?: return "no target"
        if (!usable(boss)) return "target no longer usable"
        if (phase == Phase.GUIDE) {
            // The missile can drop the lock by itself (overshoot: SBW sets its target to "none"): stop aiming at once.
            val missile = soldier.activeMissile
            return when {
                missile == null || missile.isRemoved -> "missile is gone"
                missile.isLost() || missile.getTargetUUID() != boss.stringUUID -> "missile lost its lock"
                phaseTicks >= GUIDE_TIMEOUT -> "guidance timed out"
                else -> null
            }
        }
        if (soldier.ammo.loaded <= 0) return "launcher empty"
        if (soldier.wantsCover()) return "wants cover"
        return null
    }

    override fun start() {
        soldier.thought.event("igla.start") { "engaging ${candidate?.name?.string}" }
        target = candidate
        candidate = null
        aborted = false
        engagedTicks = 0
        seekTicks = 0
        sightLostTicks = 0
        flareHold = 0
        staggerHold = 0
        lockable = true
        flaresNear = false
        staggered = false
        aiming.reset()
        nextCheckTick = 0
        soldier.navigation.stop()
        if (SoldierConfig.REACTION_TICKS.get() > 0) {
            phase = Phase.REACT
            phaseTicks = 0
        } else {
            enterRaise()
        }
    }

    override fun stop() {
        soldier.thought.event("igla.stop") { "engagement over (phase $phase), launcher lowered" }
        // Lowering the launcher is what ends the missile's guidance: the soldier's watchdog sets a flying missile lost.
        soldier.aim(false)
        target = null
        candidate = null
        phase = Phase.REACT
    }

    override fun tick() {
        val boss = target ?: return abort("no target")
        val now = soldier.tickCount
        phaseTicks++
        engagedTicks++
        aiming.hold()

        when (phase) {
            Phase.REACT -> {
                soldier.thought.think("igla.tick") { "reacting to ${boss.name.string}" }
                turnTo(boss)
                if (phaseTicks >= SoldierConfig.REACTION_TICKS.get()) enterRaise()
            }
            Phase.RAISE -> {
                soldier.thought.think("igla.tick") { "raising the launcher towards ${boss.name.string}" }
                turnTo(boss)
                if (phaseTicks >= RAISE_TICKS) enterSeek()
            }
            Phase.SEEK -> tickSeek(boss, now)
            Phase.LOCKED -> tickLocked(boss, now)
            Phase.GUIDE -> {
                soldier.thought.think("igla.tick") { "guiding the missile onto ${boss.name.string}" }
                turnTo(boss)
            }
        }
        if (phase != Phase.GUIDE && engagedTicks > ENGAGE_TIMEOUT) abort("engagement timed out")
    }

    // ================================================================================================== phases

    private fun enterRaise() {
        soldier.thought.event("igla.phase") { "$phase -> RAISE" }
        phase = Phase.RAISE
        phaseTicks = 0
        soldier.aim(true)
    }

    private fun enterSeek() {
        soldier.thought.event("igla.phase") { "$phase -> SEEK" }
        phase = Phase.SEEK
        phaseTicks = 0
        seekTicks = 0
        nextCheckTick = 0
    }

    private fun enterLocked() {
        soldier.thought.event("igla.phase") { "$phase -> LOCKED" }
        phase = Phase.LOCKED
        phaseTicks = 0
        flareHold = 0
        staggerHold = 0
        nextCheckTick = 0
        play(ModSounds.MISSILE_LOCKED.get())
    }

    private fun tickSeek(boss: Entity, now: Int) {
        turnTo(boss)
        refreshChecks(boss, now)
        if (sightLostTicks > SIGHT_LOST_ABORT) return abort("lost sight of ${boss.name.string}")

        val inCone = lockable && angleTo(boss) <= LOCK_CONE
        if (inCone) seekTicks++ else seekTicks = 0
        soldier.thought.think("igla.tickSeek") {
            if (inCone) "locking on ${boss.name.string}"
            else "searching for a lock on ${boss.name.string}: ${if (!lockable) "no clear line, range, height or smoke" else "launcher off target"}"
        }
        if (seekTicks == LOCKING_SOUND_TICK) play(ModSounds.MISSILE_LOCKING.get())
        if (seekTicks > SoldierConfig.LOCK_TIME_TICKS.get()) enterLocked()
    }

    private fun tickLocked(boss: Entity, now: Int) {
        turnTo(boss)
        refreshChecks(boss, now)
        if (sightLostTicks > SIGHT_LOST_ABORT) return abort("lost sight of ${boss.name.string}")

        // The lock is gone: back to seeking, which restarts the count.
        if (!lockable || angleTo(boss) > LOCK_CONE) {
            soldier.thought.event("igla.tickLocked") { "lock on ${boss.name.string} lost, seeking again" }
            enterSeek()
            return
        }
        if (flaresNear && flareHold < FLARE_HOLD_MAX) {
            flareHold++
            soldier.thought.think("igla.tickLocked") { "holding fire: decoys near ${boss.name.string}" }
            return
        }
        if (staggered && staggerHold < STAGGER_HOLD_MAX) {
            staggerHold++
            soldier.thought.think("igla.tickLocked") { "holding fire: another soldier's missile is already homing on ${boss.name.string}" }
            return
        }
        soldier.thought.event("igla.tickLocked") { "locked on ${boss.name.string}, launching the missile" }
        if (soldier.launchMissile(boss)) {
            phase = Phase.GUIDE
            phaseTicks = 0
        } else {
            abort("launch failed")
        }
    }

    private fun abort(why: String) {
        soldier.thought.event("igla.abort") { "engagement aborted: $why" }
        aborted = true
        nextScanTick = soldier.tickCount + RETRY_AFTER_ABORT
    }

    // ================================================================================================== sampled checks

    /** Every [CHECK_INTERVAL] ticks: lock conditions, plus decoys and other soldiers' missiles while locked. */
    private fun refreshChecks(boss: Entity, now: Int) {
        if (now < nextCheckTick) return
        nextCheckTick = now + CHECK_INTERVAL
        lockable = lockable(boss, SoldierConfig.ENGAGE_RANGE.get().toDouble(), SoldierConfig.MIN_ENGAGE_RANGE.get().toDouble())
        sightLostTicks = if (lockable) 0 else sightLostTicks + CHECK_INTERVAL
        if (phase == Phase.LOCKED) {
            flaresNear = SoldierConfig.HOLD_FIRE_ON_FLARES.get() && decoyNear(boss)
            staggered = SoldierConfig.STAGGER_SHOTS.get() && otherSoldierTracks(boss)
        }
    }

    /** A decoy (flare, smoke) tagged `superbwarfare:decoy` within [FLARE_RADIUS] blocks of the boss. A local box, not a level scan. */
    private fun decoyNear(boss: Entity): Boolean {
        val box = boss.boundingBox.inflate(FLARE_RADIUS)
        return soldier.level().getEntities(boss, box) { it.isAlive && it.type.`is`(ModTags.EntityTypes.DECOY) }.isNotEmpty()
    }

    /** Another soldier's live, still guided missile is homing on [boss]. */
    private fun otherSoldierTracks(boss: Entity): Boolean {
        val uuid = boss.stringUUID
        for (entity in TrackedProjectiles.all(soldier.level())) {
            if (entity !is ITrackableProjectile || entity === soldier.activeMissile) continue
            if (entity.isLost() || entity.getTargetUUID() != uuid) continue
            val owner = (entity as? Projectile)?.owner
            if (owner is DummySoldierEntity && owner !== soldier) return true
        }
        return false
    }

    // ================================================================================================== target selection

    /** The best lockable boss, or null. Smallest angle off the current look first, then the nearest. */
    private fun scan(): Entity? {
        val range = SoldierConfig.ENGAGE_RANGE.get().toDouble()
        val minRange = SoldierConfig.MIN_ENGAGE_RANGE.get().toDouble()
        var best: Entity? = null
        var bestAngle = Double.MAX_VALUE
        var bestDistance = Double.MAX_VALUE
        for (entity in TrackedBosses.near(soldier.level(), soldier.position(), range)) {
            if (!lockable(entity, range, minRange, budgeted = true)) continue
            val angle = angleTo(entity)
            val distance = soldier.distanceToSqr(entity)
            if (angle < bestAngle - ANGLE_EPSILON || (abs(angle - bestAngle) <= ANGLE_EPSILON && distance < bestDistance)) {
                best = entity
                bestAngle = angle
                bestDistance = distance
            }
        }
        return best
    }

    /** A boss aircraft (the Igla ignores ground bosses), alive, in the same level, not crippled and not a wreck. */
    private fun usable(boss: Entity): Boolean {
        if (boss !is BossAircraft || boss.crippled) return false
        if (boss.isRemoved || !boss.isAlive || boss.level() !== soldier.level()) return false
        return (boss as? VehicleEntity)?.isWreck != true
    }

    /** [usable], within range, high enough above the ground, in line of sight and out of smoke (cheapest tests first). */
    private fun lockable(boss: Entity, range: Double, minRange: Double, budgeted: Boolean = false): Boolean {
        if (!usable(boss)) return false
        val distance = soldier.distanceToSqr(boss)
        if (distance > range * range || distance < minRange * minRange) return false
        if (!highEnough(boss)) return false
        // The idle scan shares a per-tick raycast budget across all soldiers; an engaged soldier's checks never wait.
        if (budgeted && !DummyAi.sightRays.tryTake(soldier.level())) {
            rayDeferred = true
            return false
        }
        return aiming.clearLine(soldier.eyePosition, aiming.centreOf(boss)) && SeekTool.NOT_IN_SMOKE.test(boss)
    }

    /** The Igla's `MinTargetHeight`: the boss is at least [MIN_TARGET_HEIGHT] blocks above the top blocking block under it. */
    private fun highEnough(boss: Entity): Boolean {
        val x = Mth.floor(boss.x)
        val z = Mth.floor(boss.z)
        val chunk = soldier.level().chunkSource.getChunkNow(x shr 4, z shr 4) ?: return false
        val ground = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x and 15, z and 15)
        return boss.y - ground >= MIN_TARGET_HEIGHT
    }

    // ================================================================================================== aiming

    private fun angleTo(boss: Entity): Double = aiming.angleTo(boss)

    /** Swings onto the boss at `aimTurnSpeed` degrees per tick (see [SoldierAiming.turnTo]). */
    private fun turnTo(boss: Entity) = aiming.turnTo(boss, SoldierConfig.AIM_TURN_SPEED.get().toFloat())

    private fun play(sound: SoundEvent) {
        soldier.level().playSound(null, soldier.x, soldier.y, soldier.z, sound, SoundSource.PLAYERS, SOUND_VOLUME, 1f)
    }

    private companion object {
        /** The boss scan runs this often (ticks) while the goal is idle. */
        const val SCAN_INTERVAL = 10

        /** Line of sight, smoke, range, decoys and other soldiers' missiles are sampled this often (ticks). */
        const val CHECK_INTERVAL = 4

        /** The Igla's zoom-in: seeking starts once the sights are up (SBW: zoom above 0.7 of ZoomTime 10). */
        const val RAISE_TICKS = 7

        /** The Igla's `SeekAngle`. */
        const val LOCK_CONE = 20.0

        /** The Igla's `MinTargetHeight` (blocks above the ground). */
        const val MIN_TARGET_HEIGHT = 16

        /** Sight (or range, height, smoke) lost for longer than this many ticks aborts the engagement. */
        const val SIGHT_LOST_ABORT = 20

        /** SBW plays the locking tone on the second seek tick. */
        const val LOCKING_SOUND_TICK = 2

        /** Longest hold on decoys near the boss / on another soldier's missile, in ticks. */
        const val FLARE_HOLD_MAX = 60
        const val STAGGER_HOLD_MAX = 200

        /** Decoys count as "near the boss" within this many blocks (the radius of SBW's decoy seduction). */
        const val FLARE_RADIUS = 32.0

        /** Pause (ticks) before the scan looks again after an abort. */
        const val RETRY_AFTER_ABORT = 40

        /** Safety nets: no engagement before the launch lasts longer than this, and a missile never guides longer than this. */
        const val ENGAGE_TIMEOUT = 600
        const val GUIDE_TIMEOUT = 340

        const val ANGLE_EPSILON = 1.0e-3

        const val SOUND_VOLUME = 2f
    }
}
