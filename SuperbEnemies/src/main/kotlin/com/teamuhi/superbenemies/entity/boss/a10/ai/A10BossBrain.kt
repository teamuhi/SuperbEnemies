package com.teamuhi.superbenemies.entity.boss.a10.ai

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.BossConfig
import com.teamuhi.superbenemies.debug.AiVision
import com.teamuhi.superbenemies.debug.VisionState
import com.teamuhi.superbenemies.config.TargetCategory
import com.teamuhi.superbenemies.config.TargetingMethod
import com.teamuhi.superbenemies.entity.boss.a10.A10WarthogBossEntity
import com.teamuhi.superbenemies.entity.boss.a10.ai.A10AttackPatterns.Attack
import com.teamuhi.superbenemies.entity.boss.a10.ai.A10AttackPatterns.AttackChances
import com.teamuhi.superbenemies.entity.boss.a10.ai.A10AttackPatterns.Companion.FULL_ROLL_DEGREES
import com.teamuhi.superbenemies.entity.boss.a10.ai.A10AttackPatterns.Companion.ROLL_TIMEOUT
import com.teamuhi.superbenemies.entity.boss.a10.ai.A10AttackPatterns.Companion.UNKNOWN_TERRAIN_OFFSET
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.entity.boss.common.ResupplyNotices
import com.teamuhi.superbenemies.entity.boss.common.air.AirResupply
import com.teamuhi.superbenemies.entity.boss.common.air.FlightController
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.ai.SkyExposure
import com.teamuhi.superbenemies.entity.boss.common.air.StuckWatch
import com.teamuhi.superbenemies.entity.boss.common.ai.TargetChoice
import com.teamuhi.superbenemies.entity.boss.common.ai.LastKnownLocation
import com.teamuhi.superbenemies.entity.boss.common.air.TerrainGuard
import com.teamuhi.superbenemies.entity.boss.common.ai.ThreatMonitor
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedVehicles
import com.teamuhi.superbenemies.entity.boss.common.ai.categoryOf
import com.teamuhi.superbenemies.entity.boss.common.ai.dedupeByRootVehicle
import com.teamuhi.superbenemies.entity.boss.common.ai.isEnemyPlane
import com.teamuhi.superbenemies.entity.boss.common.ai.isEnemyTank
import com.teamuhi.superbenemies.entity.boss.common.ai.riddenBy
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import com.teamuhi.superbenemies.entity.dummy.TrackedDummies
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import java.util.EnumMap
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The A-10 boss's decision making: target selection, the attack state machine and the glue between the flight
 * controller, terrain guard, threat monitor and fire control.
 *
 * `tick()` runs on the server **before** `VehicleEntity.baseTick`, so the control inputs written here are in place
 * when SBW's aircraft engine consumes them in `travel()`.
 *
 * All state is per instance (no statics), so several bosses can fly at once. Scans are staggered: targeting every
 * [TARGET_SCAN_INTERVAL] ticks (offset by entity id), threats inside [ThreatMonitor], terrain inside [TerrainGuard].
 *
 * Attack flow: INGRESS -> selector -> (ORBIT_BARRAGE | BOMB_RUN | CLIMB_FOR_DIVE -> GUN_DIVE -> PULL_OUT) -> EXTEND
 * -> selector ... EVADE interrupts any state and returns to it; CRIPPLED is terminal.
 *
 * The double gun run flies GUN_DIVE -> PULL_OUT -> STALL_TURN -> GUN_DIVE -> PULL_OUT -> EXTEND (the cannon-to-rocket and
 * rocket-to-bomb runs are the same flight with another weapon on the second pass); the rocket run with
 * missiles is a rocket CLIMB_FOR_DIVE -> GUN_DIVE that also launches AGM-65s.
 *
 * The bombing variant of the gun run strafes with the cannon from afar and, inside [BOMB_PASS_RANGE], stops firing and
 * dive-bombs the target before pulling out (see [bombPassAim]). If the stick could not go then, it is tossed on the
 * pull-up (see [tryToss]); only when that is missed too does the selector follow the run with a separate BOMB_RUN.
 */
class A10BossBrain(private val boss: A10WarthogBossEntity) {

    private val flight = FlightController(boss)
    private val guard = TerrainGuard(boss)
    private val fire = FireControl(boss)
    private val threats = ThreatMonitor(boss)
    private val stuck = StuckWatch(boss)

    /**
     * Everything remembered about where the target was: the ghost that replaces a covered `DYNAMIC` target, the last
     * known and last target positions, and the last-known strike.
     */
    private val lastKnown = LastKnownLocation(boss, graceSeconds = { BossConfig.SIGHT_LOSS_GRACE_SECONDS.get() })

    /** The attack patterns (see [A10AttackPatterns]); they call back into this brain for the state machine and flight helpers. */
    private val patterns = A10AttackPatterns(boss, flight, guard, fire, lastKnown, object : A10AttackPatterns.Host {
        override val state: BossState get() = this@A10BossBrain.state
        override val stateTicks: Int get() = this@A10BossBrain.stateTicks
        override val aim: Entity? get() = this@A10BossBrain.aim
        override val aimVelocity: Vec3 get() = this@A10BossBrain.aimVelocity

        override fun enter(next: BossState, why: String) = this@A10BossBrain.enter(next, why = why)

        override fun fly(
            dir: Vec3, throttle: Float, maxBank: Float, airBrake: Boolean, converge: Boolean, guarded: Boolean
        ) = this@A10BossBrain.fly(dir, throttle, maxBank, airBrake, converge, guarded)

        override fun cruiseAltitude(): Double = this@A10BossBrain.cruiseAltitude()
        override fun currentHeading(): Vec3 = this@A10BossBrain.currentHeading()
        override fun awayFrom(entity: Entity): Vec3 = this@A10BossBrain.awayFrom(entity)
        override fun towards(point: Vec3): Vec3 = this@A10BossBrain.towards(point)
        override fun horizontalSpeed(): Double = this@A10BossBrain.horizontalSpeed()
        override fun ensureLoiter(x: Double, altitude: Double, z: Double, radius: Double) =
            this@A10BossBrain.ensureLoiter(x, altitude, z, radius)
        override fun trimOrbitThrottle(cruise: Double) = this@A10BossBrain.trimOrbitThrottle(cruise)
        override fun cooldown(base: Int): Int = this@A10BossBrain.cooldown(base)
        override fun selectNext() = this@A10BossBrain.selectNext()
    })

    /** The idle roll and the flight to the depot (see [AirResupply]); this plane's options and helpers plug in here. */
    private val resupply = AirResupply(boss, AirResupply.Settings(
        limitedAmmo = { BossConfig.LIMITED_AMMO.get() },
        idleResupplySeconds = { BossConfig.IDLE_RESUPPLY_SECONDS.get() },
        idleResupplyChance = { BossConfig.IDLE_RESUPPLY_CHANCE.get() },
        resupplySeconds = { BossConfig.RESUPPLY_SECONDS.get() },
        globalAggro = { BossConfig.GLOBAL_AGGRO.get() },
        aggroRange = { BossConfig.AGGRO_RANGE.get() },
        bossBarRange = { BossConfig.BOSS_BAR_RANGE.get() },
        loseTargetFactor = LOSE_TARGET_FACTOR,
        clearanceHalfWidth = RESUPPLY_CLEARANCE_HALF_WIDTH,
        maxClimb = RESUPPLY_MAX_CLIMB,
        huntingMessage = MSG_HUNTING
    ), object : AirResupply.Hooks {
        override val homePos: Vec3 get() = boss.homePos
        override val aim: Entity? get() = this@A10BossBrain.aim
        override val stateTicks: Int get() = this@A10BossBrain.stateTicks

        override fun fallbackAnchor(): Vec3? = lastKnown.anchor
        override fun anySpent(): Boolean = fire.anySpent()
        override fun fly(dir: Vec3, throttle: Float) = this@A10BossBrain.fly(dir, throttle)
        override fun cruiseAltitude(): Double = this@A10BossBrain.cruiseAltitude()
        override fun awayFrom(entity: Entity): Vec3 = this@A10BossBrain.awayFrom(entity)
        override fun currentHeading(): Vec3 = this@A10BossBrain.currentHeading()
        override fun towards(point: Vec3): Vec3 = this@A10BossBrain.towards(point)
        override fun enterResupply() = this@A10BossBrain.enter(BossState.RESUPPLY, why = "idle with spent weapons")
    })

    var state: BossState = BossState.INGRESS
        private set

    /** True while the boss is away rearming, including an evade flown on the way; the boss bar is hidden meanwhile. */
    val withdrawn: Boolean
        get() = state == BossState.RESUPPLY || (state == BossState.EVADE && resumeState == BossState.RESUPPLY)

    private var phase = Phase.P1
    private var stateTicks = 0
    private var lastAttack: Attack? = null

    // --- target ---
    private var targetUuid: UUID? = null
    private var targetEntity: Entity? = null

    /**
     * What the weapons are aimed at: the target (a player, player dummy or mob), or the vehicle it rides (a plane is its
     * own). While a `DYNAMIC` target is under cover this is [lastKnown]'s ghost, a zero-size stand-in at its last known
     * position; [targetEntity] stays the real one, so validity and range checks never see the ghost.
     */
    private var aim: Entity? = null
    private var aimVelocity: Vec3 = Vec3.ZERO
    private var lastAimPosition: Vec3 = Vec3.ZERO
    private var trackedAimId = -1
    private var initialScanDone = false

    // --- evade ---
    private var resumeState = BossState.INGRESS
    private var resumeTicks = 0
    private var evadeSide = 1.0
    private var evadeDuration = 35
    private var evadeManeuver = FlareManeuver.DEFAULT
    private var evadeRolled = 0.0
    private var evadeRollTicks = 0
    private var evadeLastRoll = 0f

    // --- per-attack bookkeeping ---
    private var extendDuration = 80
    private var gunStreak = 0

    // --- taunt ---
    private var guardLatched = false

    private enum class Phase {
        P1, P2, P3;

        /** Multiplier applied to attack cooldowns. */
        val cooldownScale: Double get() = if (this == P3) 0.6 else 1.0

        companion object {
            fun of(healthFraction: Float): Phase = when {
                healthFraction > 0.6f -> P1
                healthFraction > 0.3f -> P2
                else -> P3
            }
        }
    }

    private enum class FlareManeuver { DEFAULT, BARREL_ROLL, CLIMB, SIDE_FLARES }

    // ================================================================================================== main loop

    fun tick() {
        val level = boss.level() as? ServerLevel ?: return

        boss.thought.heartbeat { snapshot() }

        if (boss.crippled) {
            if (state != BossState.CRIPPLED) enterCrippled()
            flight.release()
            return
        }

        if (BossConfig.UNSTUCK_TELEPORT.get()) {
            unstuck(level)
        } else {
            stuck.reset()
        }

        phase = Phase.of(boss.healthFraction())
        stateTicks++

        fire.tick()
        updateTarget(level)
        val target = aim
        target?.let { lastKnown.anchorAt(it.position()) }
        resupply.countIdle(state == BossState.INGRESS && target == null)

        if (threats.tick()) enterEvade()

        // The target is gone: stop attacking and orbit where it was last seen (INGRESS without a target).
        if (target == null && state.needsTarget()) enter(BossState.INGRESS, why = "target lost")

        // Out of ammunition: leave to rearm now, with or without a target. Only attacks that cannot be abandoned half way
        // (dives, pull-outs, stall turns, evades) finish first; they end in EXTEND, which is covered.
        if (state in REARM_FROM && (boss.tickCount + boss.id) % AMMO_CHECK_INTERVAL == 0 && ammoExhausted()) {
            enter(BossState.RESUPPLY, why = "out of ammo")
        }

        when (state) {
            BossState.INGRESS -> tickIngress(target)
            BossState.ORBIT_BARRAGE -> if (target != null) patterns.tickOrbit(target)
            BossState.BOMB_RUN -> if (target != null) patterns.tickBombRun(target)
            BossState.CLIMB_FOR_DIVE -> if (target != null) patterns.tickClimbForDive(target)
            BossState.GUN_DIVE -> patterns.tickGunDive(target)
            BossState.PULL_OUT -> patterns.tickPullOut()
            BossState.STALL_TURN -> if (target != null) patterns.tickStallTurn(target)
            BossState.TAUNT -> if (target != null) patterns.tickTaunt(target)
            BossState.RESUPPLY -> resupply.tick()
            BossState.EXTEND -> tickExtend(target)
            BossState.EVADE -> tickEvade()
            BossState.CRIPPLED -> {}
        }

        threats.applyFlareInput()

        safetyNet()
    }

    /** The heartbeat line: state, target, range, cover, health, ammunition, phase and position. */
    private fun snapshot(): String {
        val victim = targetEntity
        val range = victim?.let { distanceTo(it).roundToInt().toString() } ?: "-"
        return "state=$state ticks=$stateTicks target=${victim?.name?.string ?: "none"} dist=$range " +
                "hidden=${if (lastKnown.hidden) "y" else "n"} hp=${(boss.healthFraction() * 100).roundToInt()}% " +
                "ammo=[${fire.ammoSummary()}] phase=$phase " +
                "pos=${boss.x.roundToInt()},${boss.y.roundToInt()},${boss.z.roundToInt()}"
    }

    /** The name of what the weapons are aimed at, for the console. */
    private fun nameOf(entity: Entity?): String = when {
        entity == null -> "nobody"
        lastKnown.isGhost(entity) -> "the last known spot"
        else -> entity.name.string
    }

    /**
     * Called every tick. The plane stayed on one spot for `unstuckSeconds`: it is put in open air (see [StuckWatch]) and
     * starts over from INGRESS, unless it was on its way to rearm, which carries on. Nothing is free: tried again after
     * another window.
     */
    private fun unstuck(level: ServerLevel) {
        val spot = stuck.rescueSpot(level, BossConfig.UNSTUCK_SECONDS.get()) ?: return
        boss.thought.event("unstuck") { "stuck at ${boss.position()}, moved to $spot" }
        boss.relaunch(spot)
        flight.release()
        guardLatched = false
        if (!withdrawn) enter(BossState.INGRESS, why = "stuck, relaunched")
    }

    private fun BossState.needsTarget(): Boolean =
        this == BossState.ORBIT_BARRAGE || this == BossState.BOMB_RUN ||
                this == BossState.CLIMB_FOR_DIVE || this == BossState.STALL_TURN || this == BossState.TAUNT

    // ================================================================================================== persistence

    fun save(tag: CompoundTag) {
        fire.saveAmmo(tag)
        // A reloaded evade restarts at INGRESS, except mid-resupply: the boss would rejoin silently with empty racks.
        val saved = if (state == BossState.EVADE && resumeState == BossState.RESUPPLY) BossState.RESUPPLY else state
        tag.putString(TAG_STATE, saved.name)
        targetUuid?.let { tag.putUUID(TAG_TARGET, it) }
        lastAttack?.let { tag.putString(TAG_LAST_ATTACK, it.name) }
    }

    fun load(tag: CompoundTag) {
        fire.loadAmmo(tag)
        state = BossState.entries.firstOrNull { it.name == tag.getString(TAG_STATE) } ?: BossState.INGRESS
        // A reloaded evade restarts from the beginning; the other states resume where they were.
        if (state == BossState.EVADE) state = BossState.INGRESS
        if (tag.hasUUID(TAG_TARGET)) targetUuid = tag.getUUID(TAG_TARGET)
        lastAttack = Attack.entries.firstOrNull { it.name == tag.getString(TAG_LAST_ATTACK) }
        stateTicks = 0
        boss.thought.event("load") {
            "restored state $state, ${if (targetUuid != null) "target remembered" else "no target"}, last attack ${lastAttack ?: "none"}"
        }
    }

    // ================================================================================================== targeting

    private fun updateTarget(level: ServerLevel) {
        val now = boss.tickCount
        var current = targetEntity
        val remembered = targetUuid
        if (current == null && remembered != null) {
            current = level.getEntity(remembered)
            targetEntity = current
        }
        if (current != null && !isValidTarget(current)) {
            // Lost but not dead or gone: keep hitting the last known spot for a while (see LastKnownLocation.startStrike).
            val lost = current
            val stillAlive = lost.isAlive && !lost.isRemoved
            boss.thought.event("updateTarget") {
                "lost target ${lost.name.string}: ${if (stillAlive) "no longer valid or out of reach" else "dead or gone"}"
            }
            if (stillAlive) lastKnown.startStrike(BossConfig.LAST_KNOWN_STRIKE_SECONDS.get())
            current = null
            targetEntity = null
            targetUuid = null
        }
        if (!initialScanDone || (now + boss.id) % TARGET_SCAN_INTERVAL == 0) {
            initialScanDone = true
            pickTarget(level)
            current = targetEntity
        }
        if (current != null) lastKnown.cancelStrike() // a real target takes over

        // A player in a vehicle is attacked (and locked onto) through the vehicle. A target under cover (DYNAMIC) is
        // replaced by the ghost at its last known position, which the refresh below then simply stops moving.
        aim = lastKnown.resolve(current?.rootVehicle, BossConfig.TARGETING_METHOD.get())
        if (current == null) aim = lastKnown.strikeAim(hasTarget = false)
        trackAim(aim)
        // A gun run keeps razing the spot it started on: a replacement target must not drag the dive somewhere else.
        aim?.let { lastKnown.track(it, allowed = state != BossState.GUN_DIVE || it.id == patterns.diveAimId) }
        reportVision(current)
    }

    /**
     * The vision debug (see [AiVision]): what the boss makes of [current]. While only the last-known strike keeps it
     * attacking (no real target), the aim is a ghost spot, which counts as hidden.
     */
    private fun reportVision(current: Entity?) {
        if (!boss.thought.enabled) return
        val state = if (current == null) (if (aim != null) VisionState.GHOST else VisionState.NONE) else lastKnown.visionState(aim)
        AiVision.report(boss, boss.thought, boss.eyePosition, current, aim, state, lastKnown.graceLeftTicks)
    }

    /** True while the boss is attacking the spot a lost target was last seen at (no real target is held). */
    private fun strikeActive(): Boolean = lastKnown.strikeActive(hasTarget = targetEntity != null)

    /** Called for a replacement boss: the fight was around [anchor], so circle there until a target turns up. */
    fun rememberFight(anchor: Vec3) {
        lastKnown.anchorAt(anchor)
    }

    /**
     * What the target weights allow: an entity is a target only when its category (see [categoryOf]) has a weight above
     * 0. That covers survival / adventure players, dummies (player dummy, dummy soldier) while their `bossesTarget...`
     * option is on (both count as players), living mobs (plain and hostile, weighted separately) and, with a plane or
     * tank weight (experimental), manned aircraft or armour. Read live, so changing the options drops a target that is no longer
     * allowed at once. A dummy whose option is off is a plain mob to [categoryOf], but never an ordinary mob target here.
     * Players, dummies and vehicles obey the aggro range; mobs obey `mobScanRange`, whatever `globalAggro` says.
     */
    private fun isValidTarget(entity: Entity): Boolean {
        val category = categoryOf(entity)
        if (category == null || BossConfig.WEIGHTS.of(category) <= 0) return false
        if (entity is VehicleEntity) {
            val enemy = when (category) {
                TargetCategory.PLANE -> isEnemyPlane(boss, entity)
                TargetCategory.TANK -> isEnemyTank(boss, entity)
                else -> false
            }
            if (!enemy) return false
            if (BossConfig.GLOBAL_AGGRO.get()) return true
            val limit = BossConfig.AGGRO_RANGE.get() * LOSE_TARGET_FACTOR
            val dx = entity.x - boss.x
            val dz = entity.z - boss.z
            return dx * dx + dz * dz <= limit * limit
        }
        val mob = when (entity) {
            is ServerPlayer -> {
                if (entity.isSpectator || entity.isCreative) return false
                false
            }
            is HumanoidDummyEntity -> {
                // Only a dummy the bosses are set to target counts (as a player); otherwise it is not a target at all.
                if (category != TargetCategory.PLAYER) return false
                false
            }
            is Mob -> {
                if (entity.isInvulnerable || !entity.isAttackable ||
                    entity is BossCrewEntity || boss.isCrew(entity) || entity.rootVehicle === boss
                ) return false
                true
            }
            else -> return false
        }
        if (!entity.isAlive || entity.isRemoved) return false
        if (entity.level() !== boss.level()) return false
        if (!mob && BossConfig.GLOBAL_AGGRO.get()) return true
        val range = if (mob) BossConfig.MOB_SCAN_RANGE.get() else BossConfig.AGGRO_RANGE.get()
        val limit = range * LOSE_TARGET_FACTOR
        val dx = entity.x - boss.x
        val dz = entity.z - boss.z
        return dx * dx + dz * dz <= limit * limit
    }

    /**
     * A mob the plane can hit: valid, and exposed to the sky (it would circle forever over a cave mob). Required in both
     * targeting methods: `DYNAMIC` only changes how a player's cover is handled, the plane never commits to a covered
     * mob. Smoke does not matter here. Never loads a chunk: [SkyExposure] counts an unloaded one as "not exposed".
     */
    private fun eligibleMob(mob: Mob): Boolean = isValidTarget(mob) && SkyExposure.isUnderOpenSky(mob)

    /** Runs that must not change victim half way: the aim point and the corridor checks were planned for one target. */
    private fun targetLocked(): Boolean =
        state == BossState.CLIMB_FOR_DIVE || state == BossState.GUN_DIVE || state == BossState.PULL_OUT ||
                state == BossState.STALL_TURN || state == BossState.BOMB_RUN

    /**
     * Every valid target in range, for the categories whose weight is above 0 (a category switched off is not even
     * scanned; [isValidTarget] also drops an individual entity whose own category weight is 0, e.g. zombies with
     * `hostileMobWeight` 0 while `mobWeight` is above 0).
     */
    private fun candidates(level: ServerLevel): MutableList<Entity> {
        val weights = BossConfig.WEIGHTS
        val aggro = if (BossConfig.GLOBAL_AGGRO.get()) Double.MAX_VALUE else BossConfig.AGGRO_RANGE.get().toDouble()
        val here = boss.position()
        val candidates = ArrayList<Entity>()
        if (weights.allowsPlayers) {
            for (player in level.players()) candidates.add(player)
            for (dummy in TrackedDummies.all(level)) {
                if (dummy is HumanoidDummyEntity && dummy.settings.targetedByBosses()) candidates.add(dummy)
            }
            candidates.removeAll {
                !(isValidTarget(it) && FlightMath.horizontalDistance(it.position(), here) <= aggro)
            }
        }
        if (weights.allowsVehicles) {
            // Aircraft and armour: isValidTarget keeps only the kinds whose own weight is above 0.
            for (vehicle in TrackedVehicles.all(level)) {
                if (isValidTarget(vehicle) && FlightMath.horizontalDistance(vehicle.position(), here) <= aggro) candidates.add(vehicle)
            }
        }
        if (weights.allowsMobs) {
            val reach = BossConfig.MOB_SCAN_RANGE.get().toDouble()
            val box = AABB(
                boss.x - reach, level.minBuildHeight.toDouble(), boss.z - reach,
                boss.x + reach, level.maxBuildHeight.toDouble(), boss.z + reach
            )
            // Dummies are Mobs too: they are player-category candidates above (or ignored), never an ordinary mob here.
            for (mob in level.getEntitiesOfClass(Mob::class.java, box) { it !is HumanoidDummyEntity && eligibleMob(it) }) {
                if (FlightMath.horizontalDistance(mob.position(), here) <= reach) candidates.add(mob)
            }
        }
        // A player riding a vehicle is listed twice (player and vehicle): keep the one with the higher weight.
        return dedupeByRootVehicle(candidates, this::weightOf)
    }

    /** The category weight of [entity] (0 for anything that is not a target). */
    private fun weightOf(entity: Entity): Int = BossConfig.WEIGHTS.of(categoryOf(entity))

    /** Horizontal distance from the boss: the plane works in the ground plane, whatever the altitude difference. */
    private fun distanceTo(entity: Entity): Double = FlightMath.horizontalDistance(entity.position(), boss.position())

    /** Whether [entity] (through the vehicle it rides, as [aim] is) is under cover; always false for `STATIC`. */
    private fun isHidden(entity: Entity, method: TargetingMethod): Boolean = lastKnown.isHidden(entity.rootVehicle, method)

    /**
     * The candidates a choice is made from. `STATIC` uses all of them. `DYNAMIC` prefers the ones the boss can see: the
     * covered ones are used only when nobody is exposed, so the boss keeps hitting a hidden target's last known spot
     * only when there is nothing better to attack.
     */
    private fun poolOf(candidates: List<Entity>, method: TargetingMethod): List<Entity> {
        if (method == TargetingMethod.STATIC) return candidates
        val (hidden, exposed) = candidates.partition { isHidden(it, method) }
        return exposed.ifEmpty { hidden }
    }

    private fun pickTarget(level: ServerLevel) {
        var held = targetEntity?.takeIf { isValidTarget(it) }
        // A run in progress finishes on its victim, on the ghost if the victim has gone under cover.
        if (held != null && targetLocked()) {
            val keeping = held
            boss.thought.think("pickTarget") { "keeping ${keeping.name.string}: attack run in progress" }
            return
        }
        val method = BossConfig.TARGETING_METHOD.get()
        val pool = poolOf(candidates(level), method)
        // A covered target is dropped as soon as somebody exposed can be attacked instead (DYNAMIC only).
        if (held != null && isHidden(held, method) && pool.any { !isHidden(it, method) }) held = null
        // Most recent attacker first, then keep the current target, then the best weighted-distance candidate. With
        // randomTargeting the target only changes when it is lost (a new one is drawn per attack, see rerollTarget),
        // by a draw proportional to the weights.
        val why: String
        val chosen = if (BossConfig.RANDOM_TARGETING.get()) {
            if (held != null) {
                why = "held, random targeting only redraws per attack"
                held
            } else {
                why = "random draw by weight"
                TargetChoice.weightedRandom(pool, this::weightOf, boss.getRandom())
            }
        } else {
            val attackerId = boss.lastAttackerUUID
            val attacker = pool.firstOrNull { riddenBy(it, attackerId) }
            when {
                attacker != null -> {
                    why = "most recent attacker"
                    attacker
                }
                held != null -> {
                    why = "held target"
                    held
                }
                else -> {
                    why = "best weighted distance"
                    TargetChoice.bestScore(pool, this::weightOf, this::distanceTo)
                }
            }
        }
        boss.thought.think("pickTarget") {
            if (chosen == null) "no target in reach" else "target ${chosen.name.string}: $why"
        }
        applyTarget(chosen, why)
    }

    private fun applyTarget(chosen: Entity?, why: String) {
        if (chosen?.uuid != targetUuid) {
            trackedAimId = -1 // new target: forget the old motion estimate
            boss.thought.event("applyTarget") {
                "target ${targetEntity?.name?.string ?: "none"} -> ${chosen?.name?.string ?: "none"} ($why)"
            }
        }
        targetEntity = chosen
        targetUuid = chosen?.uuid
    }

    /**
     * `randomTargeting`: draws a fresh random target (possibly the same one) at the start of an attack, each candidate
     * in proportion to its weight (exposed ones first under `DYNAMIC`, see [poolOf]).
     */
    private fun rerollTarget(level: ServerLevel) {
        val method = BossConfig.TARGETING_METHOD.get()
        val pool = poolOf(candidates(level), method)
        // Draw by weight among the targets within reach so a far-off draw does not park the boss on a long flight; if
        // nobody is, go for the best weighted distance.
        val near = pool.filter { distanceTo(it) <= RANDOM_TARGET_REACH }
        val drawn = TargetChoice.weightedRandom(near, this::weightOf, boss.getRandom())
        val chosen = drawn ?: TargetChoice.bestScore(pool, this::weightOf, this::distanceTo)
        if (chosen == null) {
            boss.thought.think("rerollTarget") { "no target to draw" }
            return
        }
        val why = if (drawn != null) "random draw per attack" else "nobody near, best weighted distance"
        boss.thought.think("rerollTarget") { "new victim ${chosen.name.string}: $why" }
        applyTarget(chosen, why)
        aim = lastKnown.resolve(chosen.rootVehicle, method)
        trackAim(aim)
        lastKnown.known = aim?.position()
    }

    /** Smoothed per-tick velocity estimate from position differences (server-side player velocity is unreliable). */
    private fun trackAim(entity: Entity?) {
        if (entity == null) {
            trackedAimId = -1
            aimVelocity = Vec3.ZERO
            return
        }
        val position = entity.position()
        if (entity.id != trackedAimId) {
            trackedAimId = entity.id
            lastAimPosition = position
            aimVelocity = Vec3.ZERO
            return
        }
        val delta = position.subtract(lastAimPosition)
        lastAimPosition = position
        // A teleport or respawn is not motion.
        aimVelocity = if (delta.lengthSqr() > TELEPORT_DISTANCE_SQR) Vec3.ZERO
        else aimVelocity.scale(1.0 - VELOCITY_SMOOTHING).add(delta.scale(VELOCITY_SMOOTHING))
    }

    // ================================================================================================== helpers

    private fun cooldown(base: Int): Int = max(1, (base * phase.cooldownScale).toInt())

    private fun randomBetween(min: Int, max: Int): Int = min + boss.getRandom().nextInt(max - min + 1)

    private fun referenceY(): Double = aim?.y ?: lastKnown.anchor?.y ?: boss.homePos.y

    private fun cruiseAltitude(): Double =
        min(referenceY() + BossConfig.CRUISE_HEIGHT.get(), boss.level().maxBuildHeight + 60.0)

    private fun currentHeading(): Vec3 = FlightMath.horizontal(boss.deltaMovement, FlightMath.dir(boss.yRot, 0f))

    private fun awayFrom(entity: Entity): Vec3 =
        FlightMath.horizontal(Vec3(boss.x - entity.x, 0.0, boss.z - entity.z), currentHeading())

    private fun towards(point: Vec3): Vec3 =
        FlightMath.horizontal(Vec3(point.x - boss.x, 0.0, point.z - boss.z), currentHeading())

    private fun horizontalSpeed(): Double = hypot(boss.deltaMovement.x, boss.deltaMovement.z)

    /** Flies toward [dir] after the terrain guard has raised its pitch if the ground is coming up. */
    private fun fly(
        dir: Vec3,
        throttle: Float,
        maxBank: Float = 60f,
        airBrake: Boolean = false,
        converge: Boolean = false,
        guarded: Boolean = true
    ) {
        val wanted = if (guarded) guard.adjustDesired(dir, referenceY() + UNKNOWN_TERRAIN_OFFSET) else dir
        flight.steer(wanted, throttle, maxBank, airBrake, converge)
    }

    /** Throttle trim for the SBW-flown orbit: its own throttle logic tends to settle below the lift speed. */
    private fun trimOrbitThrottle(cruise: Double) {
        val altitudeError = cruise - boss.y
        val wanted = (ORBIT_THROTTLE + altitudeError * 0.004).coerceIn(ORBIT_THROTTLE_MIN, ORBIT_THROTTLE_MAX)
        boss.power = Mth.lerp(0.1f, boss.power, wanted.toFloat())
    }

    private fun ensureLoiter(x: Double, altitude: Double, z: Double, radius: Double) {
        val p = boss.loiterParams
        if (abs(p.x() - x) > 2.0 || abs(p.y() - altitude) > 2.0 || abs(p.z() - z) > 2.0 || abs(p.w() - radius) > 1.0) {
            boss.loiterParams = Quaternionf(x.toFloat(), altitude.toFloat(), z.toFloat(), radius.toFloat())
        }
        if (!boss.loiterActive) boss.loiterActive = true
    }

    // ================================================================================================== transitions

    /** @param resume true when returning from an evade: keeps the per-state progress */
    private fun enter(next: BossState, resume: Boolean = false, why: String = "") {
        if (next == state && !resume) return
        boss.thought.event("state") { "$state -> $next" + if (why.isNotEmpty()) " ($why)" else "" }
        state = next
        stateTicks = 0
        // Only the orbit (and the idle loiter inside INGRESS) use SBW's autopilot; both re-enable it themselves.
        if (next != BossState.ORBIT_BARRAGE) boss.loiterActive = false
        if (resume) return

        when (next) {
            BossState.RESUPPLY -> {
                resupply.reset()
                boss.announce(MSG_WITHDRAW, WITHDRAW_STYLE)
            }

            BossState.EXTEND -> extendDuration = randomBetween(20, 30)
            else -> patterns.onEnter(next)
        }
    }

    /**
     * Weighted random attack choice, weighted by one `*Chance` config option per attack type: gun run, gun run with
    * bombs, rocket run, homing missile orbit and bomb run. An attack type whose weapon is
     * disabled (or spent, with limited ammo) is not drawn. The two gun run variants are one [Attack.GUN_DIVE]; the
     * bombing variant drops its bombs on the target, and only when that release was missed is the run followed by a
     * separate bomb run on the same target. A cannon run may follow itself up to [MAX_GUN_STREAK] times, the others
     * never repeat back to back.
     */
    private fun selectNext() {
        // A last-known strike attacks a ghost with no real target behind it: it is accepted as is.
        val striking = strikeActive()
        if (aim == null || (!striking && (targetEntity == null || !isValidTarget(targetEntity!!)))) {
            enter(BossState.INGRESS, why = "no valid target to attack")
            return
        }
        // Bombing variant whose toss did not happen: a separate bomb run (any phase) on the same target, while bombs are
        // enabled, chanced above 0 and in stock. Decided before the target re-roll, which that run must skip.
        val fallbackBomb = patterns.fallbackBombDue(lastAttack)
        patterns.disarmToss()
        // A new victim per attack, preferably within reach. One drawn beyond engagement range is flown to first (INGRESS never re-draws).
        if (!fallbackBomb && !striking && state != BossState.INGRESS && BossConfig.RANDOM_TARGETING.get()) {
            (boss.level() as? ServerLevel)?.let { rerollTarget(it) }
            val drawn = aim
            if (drawn == null || FlightMath.horizontalDistance(drawn.position(), boss.position()) > INGRESS_ENGAGE_DISTANCE) {
                enter(BossState.INGRESS, why = "new victim is out of engagement range, flying to it")
                return
            }
        }
        if (fallbackBomb) {
            patterns.startFallbackBomb()
            gunStreak = 0
            lastAttack = Attack.BOMB_RUN
            enter(BossState.BOMB_RUN, why = "attack picked: bomb run after a missed gun run toss")
            return
        }
        // Finite ammo: with every allowed attack spent the boss goes to rearm (also checked every second by tick(), so a
        // boss with nobody to fight does not circle with empty racks).
        if (ammoExhausted()) {
            enter(BossState.RESUPPLY, why = "out of ammo")
            return
        }
        // One configured chance per attack type. A type is only in the draw when every weapon it uses is enabled (read
        // live, so a weapon can be switched off between attacks) and its chance is above 0; with finite ammo also when
        // none of its weapons is spent.
        val chances = AttackChances.read()
        if (BossConfig.LIMITED_AMMO.get()) chances.dropSpent(fire)
        val gunPlain = chances.gunPlain
        val gunBombing = chances.gunBombing
        val rocketRun = chances.rocketRun
        val bombRun = chances.bombRun
        val nukeRunChance = chances.nuke
        val missileOrbit = chances.missileOrbit
        val rocketMissile = chances.rocketMissile
        val doubleGun = chances.doubleGun
        val cannonRocket = chances.cannonRocket
        val rocketBomb = chances.rocketBomb
        val weights = EnumMap<Attack, Int>(Attack::class.java)
        // Both gun run variants are one attack; the variant is rolled below in proportion to their chances.
        weights[Attack.GUN_DIVE] = gunPlain + gunBombing
        weights[Attack.ROCKET_RUN] = rocketRun
        weights[Attack.ROCKET_MISSILE_RUN] = rocketMissile
        weights[Attack.DOUBLE_GUN_RUN] = doubleGun
        weights[Attack.CANNON_ROCKET_STALL] = cannonRocket
        weights[Attack.ROCKET_BOMB_STALL] = rocketBomb
        weights[Attack.BOMB_RUN] = bombRun
        weights[Attack.NUKE_RUN] = nukeRunChance
        weights[Attack.ORBIT_BARRAGE] = missileOrbit
        weights.values.removeIf { it <= 0 }
        // A rare, harmless show-off pass; it never replaces a real attack when the others are all switched off.
        // Not against a hidden target (or a last-known strike) either: there is nobody to show off to.
        if (BossConfig.TAUNT_ENABLED.get() && weights.isNotEmpty() && !lastKnown.hidden && !striking) weights[Attack.TAUNT] = TAUNT_WEIGHT
        val last = lastAttack
        if (last != null && (last != Attack.GUN_DIVE || gunStreak >= MAX_GUN_STREAK) &&
            weights.entries.any { it.key != last && it.value > 0 }
        ) {
            weights.remove(last)
        }
        val total = weights.values.sum()
        var roll = if (total > 0) boss.getRandom().nextInt(total) else 0
        val rolled = roll
        // No weapon allowed (or usable): fall back to a passive orbit, orbitFire() is gated by the missile toggle.
        var attack = Attack.ORBIT_BARRAGE
        if (total > 0) {
            for ((candidate, weight) in weights) {
                if (roll < weight) {
                    attack = candidate
                    break
                }
                roll -= weight
            }
        }
        boss.thought.event("selectNext") {
            val table = weights.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none allowed" }
            "attack weights [$table], roll $rolled of $total -> $attack"
        }
        patterns.prepare(attack, gunPlain, gunBombing)
        gunStreak = if (attack == Attack.GUN_DIVE) gunStreak + 1 else 0
        lastAttack = attack
        // Picking the state we are already in (only when nothing else is allowed) still restarts it.
        if (state == attack.entryState) state = BossState.EXTEND
        enter(attack.entryState, why = "attack picked: $attack")
    }

    /**
     * Finite ammunition and every attack the config allows has lost a weapon: nothing is left to fight with. False when no
     * attack is allowed at all (nothing to run out of).
     */
    private fun ammoExhausted(): Boolean {
        if (!BossConfig.LIMITED_AMMO.get()) return false
        val chances = AttackChances.read()
        if (chances.total() == 0) return false
        chances.dropSpent(fire)
        return chances.total() == 0
    }

    private fun enterEvade() {
        if (state == BossState.CRIPPLED) return
        if (state == BossState.EVADE) {
            evadeDuration = max(evadeDuration, stateTicks + 25) // a second missile extends the break
            boss.thought.event("enterEvade") { "another missile: evade extended to $evadeDuration ticks" }
            return
        }
        resumeState = state
        resumeTicks = stateTicks
        evadeSide = if (boss.getRandom().nextBoolean()) 1.0 else -1.0
        evadeDuration = randomBetween(30, 40)
        evadeManeuver = if (BossConfig.DEFENSIVE_FLARES.get()) selectFlareManeuver() else FlareManeuver.DEFAULT
        evadeRolled = 0.0
        evadeRollTicks = 0
        evadeLastRoll = boss.roll
        boss.thought.event("enterEvade") {
            "evading from $resumeState: $evadeManeuver to the ${if (evadeSide > 0) "right" else "left"} for $evadeDuration ticks"
        }
        enter(BossState.EVADE, why = "missile inbound")
    }

    private fun selectFlareManeuver(): FlareManeuver {
        val defaultChance = BossConfig.DEFAULT_FLARE_CHANCE.get()
        val barrelRollChance = BossConfig.BARREL_ROLL_FLARE_CHANCE.get()
        val climbChance = BossConfig.CLIMB_FLARE_CHANCE.get()
        val sideFlaresChance = BossConfig.SIDE_FLARES_CHANCE.get()
        val total = defaultChance + barrelRollChance + climbChance + sideFlaresChance
        if (total <= 0) {
            boss.thought.event("selectFlareManeuver") { "no flare maneuver chances set: default break" }
            return FlareManeuver.DEFAULT
        }

        var roll = boss.getRandom().nextInt(total)
        val rolled = roll
        fun picked(maneuver: FlareManeuver): FlareManeuver {
            boss.thought.event("selectFlareManeuver") {
                "flare maneuver $maneuver (roll $rolled of $total; default $defaultChance, barrel roll $barrelRollChance, climb $climbChance, side flares $sideFlaresChance)"
            }
            return maneuver
        }
        if (roll < defaultChance) return picked(FlareManeuver.DEFAULT)
        roll -= defaultChance
        if (roll < barrelRollChance) return picked(FlareManeuver.BARREL_ROLL)
        roll -= barrelRollChance
        if (roll < climbChance) return picked(FlareManeuver.CLIMB)
        return picked(FlareManeuver.SIDE_FLARES)
    }

    private fun enterCrippled() {
        boss.thought.event("enterCrippled") { "$state -> CRIPPLED (health gone, crashing)" }
        state = BossState.CRIPPLED
        stateTicks = 0
        boss.loiterActive = false
        flight.release()
    }

    // ================================================================================================== states

    private fun tickIngress(target: Entity?) {
        val cruise = cruiseAltitude()

        if (target == null) {
            // Nobody to fight: circle the spot where the last target was seen (it died, left or logged off) until a new
            // one turns up; the home position only before the boss has had a target at all.
            val spot = lastKnown.anchor ?: boss.homePos
            boss.thought.think("tickIngress") {
                "no target, circling ${if (lastKnown.anchor != null) "where the last target was seen" else "home"}"
            }
            ensureLoiter(spot.x, cruise, spot.z, BossConfig.ORBIT_RADIUS.get().toDouble())
            trimOrbitThrottle(cruise)
            resupply.rollIdle()
            return
        }

        val altitudeError = cruise - boss.y
        boss.thought.think("tickIngress") { "flying in on ${nameOf(target)}" }
        val heading = if (FlightMath.horizontalDistance(boss.position(), target.position()) < 40.0) currentHeading()
        else towards(target.position())

        fly(FlightMath.tilted(heading, (altitudeError * 0.12).coerceIn(-4.0, 22.0)), 2.2f)

        val distance = FlightMath.horizontalDistance(boss.position(), target.position())
        if (distance < INGRESS_ENGAGE_DISTANCE && (altitudeError < INGRESS_ARRIVAL_MARGIN || stateTicks > INGRESS_TIMEOUT)) {
            boss.thought.event("tickIngress") {
                "in range of ${nameOf(target)} (${distance.roundToInt()} blocks)" +
                        (if (altitudeError >= INGRESS_ARRIVAL_MARGIN) ", climb timed out" else "") + ": choosing an attack"
            }
            selectNext()
        }
    }

    private fun tickExtend(target: Entity?) {
        val cruise = cruiseAltitude()
        val altitudeError = cruise - boss.y
        val heading = if (target != null) awayFrom(target) else currentHeading()
        boss.thought.think("tickExtend") { if (target != null) "extending away from ${nameOf(target)}" else "extending, no target" }
        fly(FlightMath.tilted(heading, (altitudeError * 0.1).coerceIn(-3.0, 15.0)), 2.2f)
        patterns.tossIfArmed()
        if (stateTicks >= extendDuration) selectNext()
    }

    // ---------------------------------------------------------------------------------------------- evade

    /** True once the evade's barrel roll has turned a full circle, or stopped progressing (terrain guard, timeout). */
    private fun evadeRollFinished(): Boolean =
        Math.abs(evadeRolled) >= FULL_ROLL_DEGREES || evadeRollTicks > ROLL_TIMEOUT

    private fun tickEvade() {
        val bearing = FlightMath.horizontal(threats.threatBearing ?: currentHeading(), currentHeading())
        val sideways = Vec3(-bearing.z * evadeSide, 0.0, bearing.x * evadeSide)
        val spare = boss.y - guard.floorAhead(referenceY() + UNKNOWN_TERRAIN_OFFSET)
        boss.thought.think("tickEvade") { "evading a missile: $evadeManeuver, will resume $resumeState" }
        when (evadeManeuver) {
            FlareManeuver.DEFAULT -> {
                fly(FlightMath.tilted(sideways, if (spare > 120.0) -8.0 else 3.0), 3.0f, maxBank = 75f)
            }

            FlareManeuver.BARREL_ROLL -> {
                fly(FlightMath.tilted(sideways, if (spare > 120.0) -4.0 else 4.0), 3.0f, maxBank = 75f)
                evadeRolled += Mth.wrapDegrees(boss.roll - evadeLastRoll) * evadeSide
                evadeLastRoll = boss.roll
                evadeRollTicks++
                if (!evadeRollFinished()) flight.applyRoll(evadeSide)
            }

            FlareManeuver.CLIMB -> {
                fly(FlightMath.tilted(currentHeading(), 38.0), 3.0f, maxBank = 35f)
            }

            FlareManeuver.SIDE_FLARES -> {
                fly(FlightMath.tilted(sideways, if (spare > 120.0) 8.0 else 3.0), 3.0f, maxBank = 75f)
            }
        }

        // A barrel roll is never cut short (that leaves the plane inverted): the break lasts until the turn is done.
        val rollPending = evadeManeuver == FlareManeuver.BARREL_ROLL && !evadeRollFinished()
        if (stateTicks >= evadeDuration && !rollPending) {
            val ticks = resumeTicks
            enter(resumeState, resume = true, why = "evade over")
            stateTicks = ticks
        }
    }

    // ---------------------------------------------------------------------------------------------- terrain guard

    /**
     * Runs after the state logic in every state and overrides the controls when ground clearance runs out.
     * The normal states already steer through [TerrainGuard.adjustDesired]; this catches what that cannot: the
     * orbit (flown by SBW's autopilot) and the dive (deliberately low).
     */
    private fun safetyNet() {
        if (state == BossState.CRIPPLED || state == BossState.PULL_OUT) return

        val v = boss.deltaMovement
        val descent = max(0.0, -v.y) * GUARD_DESCENT_FACTOR

        if (patterns.guardDive()) return

        val floor = guard.floorAhead(referenceY() + UNKNOWN_TERRAIN_OFFSET)
        val clearance = boss.y - floor
        val buffer = GUARD_BASE_BUFFER + descent
        val autopilot = state == BossState.ORBIT_BARRAGE || (state == BossState.INGRESS && aim == null)
        var pullingUp = false

        if (autopilot) {
            // SBW's loiter has its own avoidance; step in only when it is losing, and hold until well clear.
            if (clearance < buffer * 0.6) guardLatched = true else if (clearance > buffer * 1.5) guardLatched = false
            if (guardLatched) {
                pullingUp = true
                flight.pullUp(currentHeading(), (1.0 - clearance / buffer).coerceIn(0.0, 1.0))
            }
        } else {
            guardLatched = false
            if (clearance < buffer) {
                pullingUp = true
                flight.pullUp(currentHeading(), (1.0 - clearance / buffer).coerceIn(0.0, 1.0))
                if (patterns.abandonsOnLowClearance()) enter(BossState.EXTEND, why = "low ground clearance")
            }
        }
        boss.thought.think("safetyNet") {
            if (pullingUp) "terrain safety net pulling up${if (autopilot) " (autopilot orbit)" else ""}" else "terrain safety net idle"
        }
    }

    private companion object {
        const val TAG_STATE = "State"
        const val TAG_TARGET = "Target"
        const val TAG_LAST_ATTACK = "LastAttack"

        const val MSG_WITHDRAW = "message.${SuperbEnemies.MOD_ID}.a10_boss.withdraw"
        const val MSG_HUNTING = "message.${SuperbEnemies.MOD_ID}.a10_boss.hunting"
        val WITHDRAW_STYLE: Style = ResupplyNotices.WITHDRAW_STYLE

        // Targeting
        const val TARGET_SCAN_INTERVAL = 20
        const val LOSE_TARGET_FACTOR = 1.5

        const val VELOCITY_SMOOTHING = 0.3
        const val TELEPORT_DISTANCE_SQR = 20.0 * 20.0

        // Cruise / orbit
        const val INGRESS_ARRIVAL_MARGIN = 15.0
        const val INGRESS_TIMEOUT = 1500
        const val INGRESS_ENGAGE_DISTANCE = 450.0
        const val RANDOM_TARGET_REACH = 700.0
        const val MAX_GUN_STREAK = 3
        const val ORBIT_THROTTLE = 1.8
        const val ORBIT_THROTTLE_MIN = 1.6
        const val ORBIT_THROTTLE_MAX = 2.8

        // Resupply (the shared values are in AirResupply)
        const val RESUPPLY_CLEARANCE_HALF_WIDTH = 6.0
        const val RESUPPLY_MAX_CLIMB = 18.0

        /** Ticks between two checks for spent weapons outside [selectNext]. */
        const val AMMO_CHECK_INTERVAL = 20

        /** States from which an exhausted boss may leave for the depot at once. */
        val REARM_FROM = setOf(
            BossState.INGRESS, BossState.EXTEND, BossState.ORBIT_BARRAGE, BossState.TAUNT,
            BossState.CLIMB_FOR_DIVE, BossState.BOMB_RUN
        )

        // Taunt
        const val TAUNT_WEIGHT = 10
        // Terrain guard
        const val GUARD_BASE_BUFFER = 12.0
        const val GUARD_DESCENT_FACTOR = 14.0
    }
}
