package com.teamuhi.superbenemies.entity.soldier.ai

import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.tools.SeekTool
import com.teamuhi.superbenemies.config.AtSoldierConfig
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedBosses
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossEntity
import com.teamuhi.superbenemies.entity.boss.common.ground.TankSight
import com.teamuhi.superbenemies.entity.dummy.ai.DummyAi
import com.teamuhi.superbenemies.entity.soldier.AtSoldierEntity
import com.teamuhi.superbenemies.entity.soldier.AtWeapon
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.goal.Goal
import java.util.EnumSet
import kotlin.math.abs

/**
 * The anti-tank soldier's attack: it finds a tank boss it can hit, aims, fires one round and lets the reposition goal move
 * it. The goal holds MOVE, LOOK and JUMP, so the soldier stands still and looks at the tank while it runs. Only
 * [TankBossEntity] qualifies; planes and every other boss are left alone.
 *
 * Target scan (every [SCAN_INTERVAL] ticks, over [TrackedBosses] only): a tank qualifies when it is neither crippled nor a
 * wreck, between the weapon's minimum and maximum range away (`javelinMinRange` / `javelinRange`, `rpgMinRange` /
 * `rpgRange`), in line of sight (eye to hitbox centre, the rule of SBW's client `noClip`) and out of smoke. The one nearest
 * to where the soldier already looks wins, then the nearest. The scan only runs with a round loaded, no reposition pending,
 * out of the water and not while the soldier retreats.
 *
 * Phases:
 *  1. REACT: `reactionTicks` facing the tank, standing still.
 *  2. RAISE: the launcher goes up ([RAISE_TICKS]); the synced `aiming` flag is set here.
 *  3. Javelin, SEEK: the soldier swings onto the tank at `aimTurnSpeed` degrees per tick, with a small wobble. A counter
 *     rises while the tank is inside the [LOCK_CONE] degree cone, in sight and out of smoke; any break resets it. Once it
 *     exceeds `javelinLockTicks` the lock tone sounds and the Javelin fires. It is fire-and-forget: the missile homes in
 *     by itself, the soldier does not keep aiming.
 *     RPG, AIM: every [CHECK_INTERVAL] ticks the arc is solved ([RocketBallistics]: launch angle, with lead on a moving
 *     tank when `rpgLeadTarget`) and checked for terrain in the way. The soldier swings onto the solution and fires once
 *     it has held within [AIM_CONE] degrees for `rpgAimTicks` ticks.
 *  4. FOLLOW_THROUGH: the launcher stays up for [FOLLOW_THROUGH_TICKS] ticks, then the goal ends. With
 *     `repositionAfterShot` the soldier is marked for a move to a new spot.
 *
 * Aborts: the tank dies, is crippled, wrecked or leaves the level, the soldier dies or loses its round, sight is lost for
 * more than [SIGHT_LOST_ABORT] ticks, or the engagement takes longer than [ENGAGE_TIMEOUT] ticks. A tank that comes inside
 * the minimum range aborts as well and sends the soldier to a new spot. An RPG arc that cannot be solved or is blocked by
 * terrain aborts; the second such abort in a row (within [BLOCK_MEMORY] ticks) sends the soldier to a new firing spot.
 * After an abort the scan waits [RETRY_AFTER_ABORT] ticks. Line of sight, range and smoke are sampled every
 * [CHECK_INTERVAL] ticks and cached in between.
 */
class AtSoldierAttackGoal(private val soldier: AtSoldierEntity) : Goal() {

    private enum class Phase { REACT, RAISE, SEEK, AIM, FOLLOW_THROUGH }

    private var target: Entity? = null
    private var candidate: Entity? = null
    private var phase = Phase.REACT
    private var finished = false

    private var nextScanTick = 0
    private var nextCheckTick = 0
    private var nextSolveTick = 0

    private var phaseTicks = 0
    private var engagedTicks = 0
    private var seekTicks = 0
    private var aimedTicks = 0
    private var sightLostTicks = 0

    // Cached results of the sampled checks.
    private var lockable = true
    private var solution: RocketBallistics.Solution? = null

    /** RPG aborts in a row because the arc was blocked or unsolvable, and when the last one happened. */
    private var blockedStreak = 0
    private var lastBlockedTick = 0

    private val aiming = SoldierAiming(soldier)

    /** The idle scan skipped a tank because the shared raycast budget was spent (see [DummyAi.sightRays]). */
    private var rayDeferred = false

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP)
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun canUse(): Boolean {
        if (!soldier.isAlive || soldier.isPassenger || soldier.isInWater || soldier.ammo.loaded <= 0) {
            soldier.thought.think("attack.canUse") {
                "no: " + when {
                    !soldier.isAlive -> "dead"
                    soldier.isPassenger -> "riding something"
                    soldier.isInWater -> "in water"
                    else -> "launcher empty"
                }
            }
            return false
        }
        if (soldier.repositionPending || soldier.isRetreating()) {
            soldier.thought.think("attack.canUse") { if (soldier.repositionPending) "no: moving to a new firing spot" else "no: retreating" }
            return false
        }
        val now = soldier.tickCount
        if (now < nextScanTick) return false
        nextScanTick = now + SCAN_INTERVAL
        rayDeferred = false
        candidate = scan()
        // Other soldiers used this tick's raycasts: look again next tick instead of waiting a full scan interval.
        if (candidate == null && rayDeferred) nextScanTick = now + 1
        soldier.thought.think("attack.canUse") {
            val found = candidate
            if (found != null) "yes: ${found.name.string} is in reach" else "no: no tank boss in reach"
        }
        return candidate != null
    }

    override fun canContinueToUse(): Boolean {
        val continues = when {
            finished || !soldier.isAlive || soldier.isPassenger -> false
            phase == Phase.FOLLOW_THROUGH -> true // the round is gone, whatever happens to the tank
            else -> target?.let { usable(it) && soldier.ammo.loaded > 0 } ?: false
        }
        if (!continues) {
            soldier.thought.think("attack.canContinueToUse") {
                "no: " + when {
                    finished -> "engagement finished"
                    !soldier.isAlive || soldier.isPassenger -> "soldier unable to fight"
                    target == null -> "no target"
                    soldier.ammo.loaded <= 0 -> "launcher empty"
                    else -> "target no longer usable"
                }
            }
        }
        return continues
    }

    override fun start() {
        soldier.thought.event("attack.start") { "engaging ${candidate?.name?.string} with the ${soldier.weapon}" }
        target = candidate
        candidate = null
        target?.let { soldier.lastThreat = TankSight.eyeOf(it) }
        finished = false
        engagedTicks = 0
        seekTicks = 0
        aimedTicks = 0
        sightLostTicks = 0
        lockable = true
        solution = null
        aiming.reset()
        nextCheckTick = 0
        soldier.navigation.stop()
        if (AtSoldierConfig.REACTION_TICKS.get() > 0) {
            phase = Phase.REACT
            phaseTicks = 0
        } else {
            enterRaise()
        }
    }

    override fun stop() {
        soldier.thought.event("attack.stop") { "engagement over (phase $phase), launcher lowered" }
        soldier.aim(false)
        target = null
        candidate = null
        solution = null
        phase = Phase.REACT
    }

    override fun tick() {
        val tank = target ?: return abort("no target")
        val now = soldier.tickCount
        phaseTicks++
        engagedTicks++
        aiming.hold()

        when (phase) {
            Phase.REACT -> {
                soldier.thought.think("attack.tick") { "reacting to ${tank.name.string}" }
                turnTo(tank)
                if (phaseTicks >= AtSoldierConfig.REACTION_TICKS.get()) enterRaise()
            }
            Phase.RAISE -> {
                soldier.thought.think("attack.tick") { "raising the launcher towards ${tank.name.string}" }
                turnTo(tank)
                if (phaseTicks >= RAISE_TICKS) {
                    if (soldier.weapon == AtWeapon.JAVELIN) enterSeek() else enterAim()
                }
            }
            Phase.SEEK -> tickSeek(tank, now)
            Phase.AIM -> tickAim(tank, now)
            Phase.FOLLOW_THROUGH -> {
                soldier.thought.think("attack.tick") { "following through after the shot" }
                turnTo(tank)
                if (phaseTicks >= FOLLOW_THROUGH_TICKS) finished = true
            }
        }
        if (phase != Phase.FOLLOW_THROUGH && engagedTicks > ENGAGE_TIMEOUT) abort("engagement timed out")
    }

    // ================================================================================================== phases

    private fun enterRaise() {
        soldier.thought.event("attack.phase") { "$phase -> RAISE" }
        phase = Phase.RAISE
        phaseTicks = 0
        soldier.aim(true)
    }

    private fun enterSeek() {
        soldier.thought.event("attack.phase") { "$phase -> SEEK (Javelin lock)" }
        phase = Phase.SEEK
        phaseTicks = 0
        seekTicks = 0
        nextCheckTick = 0
    }

    private fun enterAim() {
        soldier.thought.event("attack.phase") { "$phase -> AIM (RPG arc)" }
        phase = Phase.AIM
        phaseTicks = 0
        aimedTicks = 0
        solution = null
        nextCheckTick = 0
        nextSolveTick = 0
    }

    /** Javelin: counts up the lock while the tank is in the cone, then fires. */
    private fun tickSeek(tank: Entity, now: Int) {
        turnTo(tank)
        refreshChecks(tank, now)
        if (finished) return
        if (sightLostTicks > SIGHT_LOST_ABORT) return abort("lost sight of ${tank.name.string}")

        val inCone = lockable && aiming.angleTo(tank) <= LOCK_CONE
        if (inCone) seekTicks++ else seekTicks = 0
        soldier.thought.think("attack.tickSeek") {
            if (inCone) "locking on ${tank.name.string}"
            else "searching for a lock on ${tank.name.string}: ${if (!lockable) "no clear line, range or smoke" else "launcher off target"}"
        }
        if (seekTicks == LOCKING_SOUND_TICK) play(ModSounds.MISSILE_LOCKING.get())
        if (seekTicks <= AtSoldierConfig.JAVELIN_LOCK_TICKS.get()) return

        play(ModSounds.MISSILE_LOCKED.get())
        soldier.thought.event("attack.tickSeek") { "lock held for $seekTicks ticks on ${tank.name.string}, firing the Javelin" }
        if (soldier.launchJavelin(tank)) shotFired(tank) else abort("Javelin launch failed")
    }

    /** RPG: keeps the firing solution fresh, swings onto it and fires once the aim has held long enough. */
    private fun tickAim(tank: Entity, now: Int) {
        refreshChecks(tank, now)
        if (finished) return
        if (sightLostTicks > SIGHT_LOST_ABORT) return abort("lost sight of ${tank.name.string}")

        if (solution == null || now >= nextSolveTick) {
            nextSolveTick = now + CHECK_INTERVAL
            if (!solveArc(tank, now)) return
        }
        val shot = solution ?: return
        turnToSolution(shot)
        if (aiming.angleToAngles(shot.yaw, shot.pitch) <= AIM_CONE) aimedTicks++ else aimedTicks = 0
        soldier.thought.think("attack.tickAim") {
            if (aimedTicks > 0) "launcher on the firing solution for ${tank.name.string}, steadying"
            else "swinging onto the firing solution for ${tank.name.string}"
        }
        if (aimedTicks == 0 || aimedTicks < AtSoldierConfig.RPG_AIM_TICKS.get()) return

        soldier.thought.event("attack.tickAim") { "aim held for $aimedTicks ticks, firing the rocket at ${tank.name.string}" }
        if (soldier.launchRocket(shot.direction)) shotFired(tank) else abort("rocket launch failed")
    }

    /**
     * Solves the arc to [tank] and checks it for terrain. False (and the engagement is aborted) when there is no solution
     * or the arc is blocked; the second such failure in a row sends the soldier to a new firing spot.
     */
    private fun solveArc(tank: Entity, now: Int): Boolean {
        val rocket = AtSoldierConfig.RPG_ROCKET.get()
        val from = soldier.eyePosition
        val found = RocketBallistics.solve(
            from, aiming.centreOf(tank), tank.deltaMovement, rocket, AtSoldierConfig.RPG_LEAD_TARGET.get()
        )
        if (found != null && RocketBallistics.pathClear(soldier.level(), soldier, from, found, rocket, tank)) {
            blockedStreak = 0
            solution = found
            soldier.thought.think("attack.solveArc") { "firing solution found for ${tank.name.string}" }
            return true
        }
        if (now - lastBlockedTick > BLOCK_MEMORY) blockedStreak = 0
        lastBlockedTick = now
        val reposition = ++blockedStreak >= 2
        if (reposition) {
            blockedStreak = 0
            soldier.repositionPending = true
        }
        soldier.thought.think("attack.solveArc") {
            "no usable firing solution for ${tank.name.string} (${if (found == null) "unsolvable arc" else "arc blocked by terrain"})" +
                if (reposition) ", second failure in a row: new firing spot" else ""
        }
        abort("no firing solution")
        return false
    }

    /** The round is away: remember where the tank is, mark the move to a new spot and keep the launcher up for a moment. */
    private fun shotFired(tank: Entity) {
        blockedStreak = 0
        solution = null
        soldier.lastThreat = TankSight.eyeOf(tank)
        if (AtSoldierConfig.REPOSITION_AFTER_SHOT.get()) soldier.repositionPending = true
        soldier.thought.event("attack.shotFired") {
            "${soldier.weapon} away at ${tank.name.string}, ${soldier.ammo.loaded} loaded, ${soldier.ammo.reserve} in reserve" +
                if (soldier.repositionPending) ", moving to a new spot" else ""
        }
        phase = Phase.FOLLOW_THROUGH
        phaseTicks = 0
    }

    private fun abort(why: String) {
        soldier.thought.event("attack.abort") { "engagement aborted: $why" }
        finished = true
        nextScanTick = soldier.tickCount + RETRY_AFTER_ABORT
    }

    /** A tank inside the minimum range cannot be fired at (the Javelin would not climb, the rocket would hit the soldier's feet): back off. */
    private fun fallBack() {
        soldier.repositionPending = true
        abort("target inside the minimum range, backing off")
    }

    // ================================================================================================== sampled checks

    /** Every [CHECK_INTERVAL] ticks: the minimum range, then range, line of sight and smoke. */
    private fun refreshChecks(tank: Entity, now: Int) {
        if (now < nextCheckTick) return
        nextCheckTick = now + CHECK_INTERVAL
        val minRange = AtSoldierConfig.minRange(soldier.weapon)
        if (usable(tank) && soldier.distanceToSqr(tank) < minRange * minRange) return fallBack()
        lockable = lockable(tank, AtSoldierConfig.maxRange(soldier.weapon), minRange)
        sightLostTicks = if (lockable) 0 else sightLostTicks + CHECK_INTERVAL
    }

    // ================================================================================================== target selection

    /** The best tank, or null. Smallest angle off the current look first, then the nearest. */
    private fun scan(): Entity? {
        val range = AtSoldierConfig.maxRange(soldier.weapon)
        val minRange = AtSoldierConfig.minRange(soldier.weapon)
        var best: Entity? = null
        var bestAngle = Double.MAX_VALUE
        var bestDistance = Double.MAX_VALUE
        for (entity in TrackedBosses.near(soldier.level(), soldier.position(), range)) {
            if (!lockable(entity, range, minRange, budgeted = true)) continue
            val angle = aiming.angleTo(entity)
            val distance = soldier.distanceToSqr(entity)
            if (angle < bestAngle - ANGLE_EPSILON || (abs(angle - bestAngle) <= ANGLE_EPSILON && distance < bestDistance)) {
                best = entity
                bestAngle = angle
                bestDistance = distance
            }
        }
        return best
    }

    /** A tank boss, alive, in the same level, not crippled and not a wreck. */
    private fun usable(tank: Entity): Boolean {
        if (tank !is TankBossEntity || tank.crippled) return false
        if (tank.isRemoved || !tank.isAlive || tank.level() !== soldier.level()) return false
        return !tank.isWreck
    }

    /** [usable], within range, in line of sight and out of smoke (cheapest tests first). */
    private fun lockable(tank: Entity, range: Double, minRange: Double, budgeted: Boolean = false): Boolean {
        if (!usable(tank)) return false
        val distance = soldier.distanceToSqr(tank)
        if (distance > range * range || distance < minRange * minRange) return false
        // The idle scan shares a per-tick raycast budget across all soldiers; an engaged soldier's checks never wait.
        if (budgeted && !DummyAi.sightRays.tryTake(soldier.level())) {
            rayDeferred = true
            return false
        }
        return aiming.clearLine(soldier.eyePosition, aiming.centreOf(tank)) && SeekTool.NOT_IN_SMOKE.test(tank)
    }

    // ================================================================================================== aiming

    private fun turnTo(tank: Entity) = aiming.turnTo(tank, AtSoldierConfig.AIM_TURN_SPEED.get().toFloat())

    private fun turnToSolution(shot: RocketBallistics.Solution) =
        aiming.turnToAngles(shot.yaw, shot.pitch, AtSoldierConfig.AIM_TURN_SPEED.get().toFloat())

    private fun play(sound: SoundEvent) {
        soldier.level().playSound(null, soldier.x, soldier.y, soldier.z, sound, SoundSource.PLAYERS, SOUND_VOLUME, 1f)
    }

    private companion object {
        /** The tank scan runs this often (ticks) while the goal is idle. */
        const val SCAN_INTERVAL = 10

        /** Line of sight, range, smoke and the RPG arc are sampled this often (ticks). */
        const val CHECK_INTERVAL = 4

        /** The launcher goes up before the soldier starts to lock or aim (the same pause as the Igla goal's zoom-in). */
        const val RAISE_TICKS = 7

        /** The Javelin's `SeekAngle`. */
        const val LOCK_CONE = 10.0

        /** The RPG soldier fires once its aim is within this many degrees of the firing solution. */
        const val AIM_CONE = 1.5

        /** Sight (or range, smoke) lost for longer than this many ticks aborts the engagement. */
        const val SIGHT_LOST_ABORT = 20

        /** SBW plays the locking tone on the second seek tick. */
        const val LOCKING_SOUND_TICK = 2

        /** The launcher stays up this long after the shot (the back-blast, and the missile or rocket clears the tube). */
        const val FOLLOW_THROUGH_TICKS = 8

        /** Pause (ticks) before the scan looks again after an abort. */
        const val RETRY_AFTER_ABORT = 40

        /** An RPG abort counts as "in a row" with the previous one when it happens within this many ticks. */
        const val BLOCK_MEMORY = 400

        /** Safety net: no engagement lasts longer than this before the shot. */
        const val ENGAGE_TIMEOUT = 400

        const val ANGLE_EPSILON = 1.0e-3

        const val SOUND_VOLUME = 2f
    }
}
