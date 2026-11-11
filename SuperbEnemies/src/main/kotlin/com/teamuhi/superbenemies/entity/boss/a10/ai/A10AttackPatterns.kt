package com.teamuhi.superbenemies.entity.boss.a10.ai

import com.teamuhi.superbenemies.config.BossConfig
import com.teamuhi.superbenemies.entity.boss.a10.A10WarthogBossEntity
import com.teamuhi.superbenemies.entity.boss.common.ai.LastKnownLocation
import com.teamuhi.superbenemies.entity.boss.common.air.FlightController
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.air.TerrainGuard
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The A-10 boss's attack patterns: the missile orbit, the bomb run, the gun / rocket dive with its pull-out, the bombing
 * variant of the gun run (dive-bomb pass and toss), the stall turn between the two passes of a double run and the
 * taunt. [A10BossBrain] decides *which* attack to fly and when to leave it (targeting, selection, evade, resupply,
 * terrain safety net); this class flies the chosen one, one `tick...` method per [BossState] and the per-attack state
 * that goes with it.
 *
 * It owns no schedule of its own: the brain calls the matching `tick...` every tick while it is in that state,
 * [onEnter] when it enters one, and [prepare] when the selector has drawn an attack. State changes go back through
 * [Host.enter].
 *
 * Per instance, no statics, like the brain.
 */
class A10AttackPatterns(
    private val boss: A10WarthogBossEntity,
    private val flight: FlightController,
    private val guard: TerrainGuard,
    private val fire: FireControl,
    private val lastKnown: LastKnownLocation,
    private val host: Host
) {

    /** What the patterns need from the brain: its state machine, the current aim and the guarded flight helpers. */
    interface Host {
        val state: BossState
        val stateTicks: Int

        /** What the weapons are aimed at (the target, its vehicle, or the ghost of a hidden target). */
        val aim: Entity?
        val aimVelocity: Vec3

        fun enter(next: BossState, why: String = "")
        fun fly(dir: Vec3, throttle: Float, maxBank: Float, airBrake: Boolean, converge: Boolean, guarded: Boolean)
        fun cruiseAltitude(): Double
        fun currentHeading(): Vec3
        fun awayFrom(entity: Entity): Vec3
        fun towards(point: Vec3): Vec3
        fun horizontalSpeed(): Double

        /** Points SBW's autopilot at a circle (see the brain's loiter helpers). */
        fun ensureLoiter(x: Double, altitude: Double, z: Double, radius: Double)
        fun trimOrbitThrottle(cruise: Double)

        /** An attack cooldown scaled by the boss's health phase. */
        fun cooldown(base: Int): Int

        /** Ends the attack in progress: the selector draws the next one. */
        fun selectNext()
    }

    private val state: BossState get() = host.state
    private val stateTicks: Int get() = host.stateTicks
    private val aim: Entity? get() = host.aim
    private val aimVelocity: Vec3 get() = host.aimVelocity

    // ================================================================================================== attacks

    /**
     * Each attack uses one weapon type, except ROCKET_MISSILE_RUN (rockets and missiles together). ORBIT_BARRAGE is the
     * homing missile attack, DOUBLE_GUN_RUN is a gun run, a stall turn and a second gun run; CANNON_ROCKET_STALL
     * and ROCKET_BOMB_STALL are the same shape with a different weapon on each pass (cannon then rockets, rockets
     * then bombs); TAUNT fires nothing.
     */
    enum class Attack(val entryState: BossState) {
        GUN_DIVE(BossState.CLIMB_FOR_DIVE),
        ROCKET_RUN(BossState.CLIMB_FOR_DIVE),
        ROCKET_MISSILE_RUN(BossState.CLIMB_FOR_DIVE),
        DOUBLE_GUN_RUN(BossState.CLIMB_FOR_DIVE),
        CANNON_ROCKET_STALL(BossState.CLIMB_FOR_DIVE),
        ROCKET_BOMB_STALL(BossState.CLIMB_FOR_DIVE),
        ORBIT_BARRAGE(BossState.ORBIT_BARRAGE),
        BOMB_RUN(BossState.BOMB_RUN),
        NUKE_RUN(BossState.BOMB_RUN),
        TAUNT(BossState.TAUNT)
    }

    private enum class TauntPhase { SETUP, RUN }

    /** Stall turn: straight up, the tail swings round while the nose hangs up, then the nose drops onto the target. */
    private enum class StallPhase { CLIMB, SWING, DROP }

    /** The only weapon fired during a CLIMB_FOR_DIVE -> GUN_DIVE run. BOMBS: lines up, then a dive-bomb pass (see [bombPassAim]). */
    private enum class DiveWeapon { CANNON, ROCKETS, BOMBS }

    private enum class BombPhase { SETUP, RUN, STICK }

    // ================================================================================================== state

    // --- gun razing (the cannon run scatters its aim point around the last known position) ---
    private var razeOffset: Vec3 = Vec3.ZERO
    private var nextRazeJitterTick = 0

    // --- per-attack bookkeeping ---
    private var orbitDuration = 350
    private var nextMissileTick = 0
    private var diveWeapon = DiveWeapon.CANNON
    private var diveRocketsLeft = 0
    private var nextDiveRocketTick = 0

    /** Rocket run with homing missiles: launches [DIVE_MISSILES] AGM-65s during the dive, alongside the rockets. */
    private var missileSalvoPending = false
    private var diveMissilesLeft = 0

    /** Stall-turn attacks: a second pass is still to fly after the current one (see [tickPullOut], [tickStallTurn]). */
    private var secondPassPending = false

    /** The weapon of that second pass (the cannon for the plain double gun run). */
    private var secondPassWeapon = DiveWeapon.CANNON

    /**
     * The current dive is the second pass of a stall-turn attack. It starts slow (the plane hung at the top of the
     * climb), so it pulls out earlier and holds more power (see [pullOutBase], [tickGunDive]).
     */
    private var stallPass = false

    // --- stall turn (between the two passes of a double gun run) ---
    private var stallPhase = StallPhase.CLIMB
    private var stallPhaseTicks = 0

    /** Heading held through the climb: the velocity direction is meaningless once the climb is near vertical. */
    private var stallHeading: Vec3? = null

    /** Set when the current gun run is the bombing variant (cannon from afar, bombs when close; see [tickGunDive]). */
    private var bombComboPending = false
    private var nukeRun = false

    // --- bombing variant of the cannon run ---
    /** The run may still drop its bombs: armed on entering GUN_DIVE, cleared by a release, a miss or the next attack. */
    private var tossArmed = false

    /** Dive-bomb pass: integrated aim correction beyond the target (see [bombPassAim]). */
    private var bombAimShift: Vec3 = Vec3.ZERO

    /** The predicted impact has been short of the target during the descent, so the later crossing is the real one. */
    private var tossPrimed = false
    private var tossReleased = false
    private var previousTossAlong = 0.0

    /** Bomb run: the target has been ahead of the plane since the turn-in (see [bombRun]). */
    private var bombApproached = false
    private var extraClimb = 0.0
    var diveAimId = -1
        private set
    private var nextCorridorCheckTick = 0

    private var bombPhase = BombPhase.SETUP
    private var stickEndTick = 0
    private var previousMiss = Double.MAX_VALUE

    /** Mk 84 run: passes flown so far (an overflown miss lines up again, see [retryNukePass]). */
    private var nukePasses = 0

    private var tauntPhase = TauntPhase.SETUP
    private var tauntRoll = false
    private var tauntPassed = false
    private var rolling = false
    private var rollDone = false
    private var rollSide = 1.0
    private var rolledDegrees = 0.0
    private var rollTicks = 0
    private var lastRoll = 0f
    private var lastRollTick = 0
    private var flaresDropped = 0
    private var nextTauntFlareTick = 0


    // ================================================================================================== host shortcuts

    private fun enter(next: BossState, why: String = "") = host.enter(next, why)

    /** What the weapons are aimed at, by name, for the console. */
    private fun who(entity: Entity?): String = when {
        entity == null -> "nobody"
        lastKnown.isGhost(entity) -> "the last known spot"
        else -> entity.name.string
    }

    private fun fly(
        dir: Vec3,
        throttle: Float,
        maxBank: Float = 60f,
        airBrake: Boolean = false,
        converge: Boolean = false,
        guarded: Boolean = true
    ) = host.fly(dir, throttle, maxBank, airBrake, converge, guarded)

    private fun cruiseAltitude(): Double = host.cruiseAltitude()

    private fun currentHeading(): Vec3 = host.currentHeading()

    private fun awayFrom(entity: Entity): Vec3 = host.awayFrom(entity)

    private fun towards(point: Vec3): Vec3 = host.towards(point)

    private fun horizontalSpeed(): Double = host.horizontalSpeed()

    private fun ensureLoiter(x: Double, altitude: Double, z: Double, radius: Double) =
        host.ensureLoiter(x, altitude, z, radius)

    private fun trimOrbitThrottle(cruise: Double) = host.trimOrbitThrottle(cruise)

    private fun cooldown(base: Int): Int = host.cooldown(base)

    private fun randomBetween(min: Int, max: Int): Int = min + boss.getRandom().nextInt(max - min + 1)

    // ================================================================================================== brain interface

    /** The bombing gun run's release was missed: the selector follows it with a separate bomb run on the same target. */
    fun fallbackBombDue(lastAttack: Attack?): Boolean =
        bombComboPending && lastAttack == Attack.GUN_DIVE && !tossReleased && bombsAvailable()

    /** Starts that separate bomb run: the gun run's flags are dropped. */
    fun startFallbackBomb() {
        bombComboPending = false
        nukeRun = false
    }

    /** The run is over (or a new attack is being chosen): the toss has nothing left to wait for. */
    fun disarmToss() {
        tossArmed = false
    }

    /** The bombing variant's toss may still release on the pull-up and the extension. */
    fun tossIfArmed() {
        if (tossArmed && !tossReleased) tryToss()
    }

    fun bombsAvailable(): Boolean =
        BossConfig.BOMBS_ENABLED.get() && fire.hasAmmo(FireControl.BOMB)

    /**
     * Whether low ground clearance ends the attack in progress (the safety net pulls up either way). The bomb run's
     * SETUP leg only opens the distance while climbing back to run altitude (always the case right after a gun dive), so
     * low clearance there must not cancel the pass; only the actual run is abandoned. The stall turn's climb may start
     * low (it is what gets the plane up); the swing must not go on near the ground.
     */
    fun abandonsOnLowClearance(): Boolean =
        (state == BossState.BOMB_RUN && bombPhase != BombPhase.SETUP && !nukeRun) ||
                (state == BossState.STALL_TURN && stallPhase == StallPhase.SWING)

    /**
     * The terrain safety net for the dives. The stall turn's drop is a dive too: it gets the dive's emergency rule, not
     * the level-flight buffer (which grows with the descent rate and would cancel every drop within a few ticks).
     * Returns true when the plane is in a dive (this handled it), false for every other state.
     */
    fun guardDive(): Boolean {
        if (state != BossState.GUN_DIVE && !(state == BossState.STALL_TURN && stallPhase == StallPhase.DROP)) return false
        val v = boss.deltaMovement
        // Last resort for the dive: the terrain right below and just ahead (the pull-out altitude normally acts first).
        var ground = guard.terrainAt(boss.x, boss.z, boss.y - 100.0)
        for (ticks in EMERGENCY_LOOK_AHEAD_TICKS) {
            val ahead = boss.position().add(v.scale(ticks))
            ground = max(ground, guard.terrainAt(ahead.x, ahead.z, boss.y - 100.0))
        }
        val emergency = if (stallPass) STALL_DIVE_EMERGENCY_CLEARANCE else DIVE_EMERGENCY_CLEARANCE
        val descentFactor = if (stallPass) STALL_DIVE_DESCENT_FACTOR else DIVE_DESCENT_FACTOR
        if (boss.y - ground < emergency + max(0.0, -v.y) * descentFactor) {
            boss.thought.event("patterns.guardDive") { "dive emergency: ${(boss.y - ground).toInt()} blocks above the ground, pulling out" }
            flight.pullUp(currentHeading(), 1.0)
            enter(BossState.PULL_OUT, "dive emergency, ground too close")
        }
        return true
    }

    // ================================================================================================== transitions

    /**
     * Resets the bookkeeping of an attack state the brain is entering (not for a resume after an evade). States without
     * attack bookkeeping are ignored.
     */
    fun onEnter(next: BossState) {
        when (next) {
            BossState.ORBIT_BARRAGE -> {
                orbitDuration = randomBetween(100, 150)
                nextMissileTick = boss.tickCount + cooldown(30)
            }

            BossState.BOMB_RUN -> {
                bombPhase = BombPhase.SETUP
                bombApproached = false
                previousMiss = Double.MAX_VALUE
                nukePasses = 0
                fire.selectBombVariant(nukeRun)
            }

            BossState.CLIMB_FOR_DIVE -> {
                extraClimb = 0.0
                stallPass = false
            }

            BossState.GUN_DIVE -> {
                aim?.let { lastKnown.known = it.position() } // the pilot has eyes on the target as the run starts
                diveAimId = aim?.id ?: -1
                nextCorridorCheckTick = 0
                diveRocketsLeft = randomBetween(DIVE_ROCKETS_MIN, DIVE_ROCKETS_MAX)
                nextDiveRocketTick = 0
                diveMissilesLeft = if (missileSalvoPending) DIVE_MISSILES else 0
                tossArmed = bombsAvailable() &&
                        ((bombComboPending && diveWeapon == DiveWeapon.CANNON) || diveWeapon == DiveWeapon.BOMBS)
                tossPrimed = false
                tossReleased = false
                previousTossAlong = 0.0
                bombAimShift = Vec3.ZERO
            }

            BossState.STALL_TURN -> {
                stallPhase = StallPhase.CLIMB
                stallPhaseTicks = 0
                stallHeading = currentHeading()
                stallPass = true // the dive that follows is the slow second pass
            }

            BossState.TAUNT -> {
                tauntPhase = TauntPhase.SETUP
                tauntRoll = boss.getRandom().nextBoolean()
                tauntPassed = false
                rolling = false
                rollDone = false
                rolledDegrees = 0.0
                rollTicks = 0
                rollSide = if (boss.getRandom().nextBoolean()) 1.0 else -1.0
                flaresDropped = 0
                nextTauntFlareTick = 0
            }

            else -> {}
        }
    }

    /** The selector drew [attack]: sets the weapons and flags of its passes up (see [A10BossBrain.selectNext]). */
    fun prepare(attack: Attack, gunPlain: Int, gunBombing: Int) {
        // The first pass's weapon; the stall-turn attacks name the second pass's weapon separately.
        val rocketAttack = attack == Attack.ROCKET_RUN || attack == Attack.ROCKET_MISSILE_RUN ||
                attack == Attack.ROCKET_BOMB_STALL
        diveWeapon = if (rocketAttack) DiveWeapon.ROCKETS else DiveWeapon.CANNON
        missileSalvoPending = attack == Attack.ROCKET_MISSILE_RUN
        secondPassPending = attack == Attack.DOUBLE_GUN_RUN || attack == Attack.CANNON_ROCKET_STALL ||
                attack == Attack.ROCKET_BOMB_STALL
        secondPassWeapon = when (attack) {
            Attack.CANNON_ROCKET_STALL -> DiveWeapon.ROCKETS
            Attack.ROCKET_BOMB_STALL -> DiveWeapon.BOMBS
            else -> DiveWeapon.CANNON
        }
        stallPass = false
        // Each gun run picks its variant up front; the plain one (and every other attack) clears the flag.
        val gunWeight = gunPlain + gunBombing
        bombComboPending = attack == Attack.GUN_DIVE && gunWeight > 0 &&
            boss.getRandom().nextInt(gunWeight) < gunBombing
        nukeRun = attack == Attack.NUKE_RUN
        fire.selectBombVariant(false)
        if (nukeRun) fire.selectBombVariant(true)
        boss.thought.event("patterns.prepare") {
            "$attack prepared: first pass $diveWeapon" +
                    (if (secondPassPending) ", second pass $secondPassWeapon" else "") +
                    (if (missileSalvoPending) ", with missiles" else "") +
                    (if (bombComboPending) ", bombing variant" else "") +
                    (if (nukeRun) ", Mk 84" else "")
        }
    }

    /**
     * The configured weight of every attack type, 0 for a type whose weapon is switched off. [read] gets them live from the
     * config; [dropSpent] zeroes the types that need a weapon that is out of ammunition.
     */
    class AttackChances(
        var gunPlain: Int, var gunBombing: Int, var rocketRun: Int, var bombRun: Int, var nuke: Int,
        var missileOrbit: Int, var rocketMissile: Int, var doubleGun: Int, var cannonRocket: Int, var rocketBomb: Int
    ) {
        fun total(): Int = gunPlain + gunBombing + rocketRun + bombRun + nuke + missileOrbit + rocketMissile + doubleGun +
                cannonRocket + rocketBomb

        fun dropSpent(fire: FireControl) {
            if (!fire.hasAmmo(FireControl.CANNON)) { gunPlain = 0; gunBombing = 0; doubleGun = 0; cannonRocket = 0 }
            if (!fire.hasAmmo(FireControl.BOMB)) { gunBombing = 0; bombRun = 0; rocketBomb = 0 }
            if (!fire.hasAmmo(FireControl.NUKE)) nuke = 0
            if (!fire.hasAmmo(FireControl.ROCKET)) { rocketRun = 0; rocketMissile = 0; cannonRocket = 0; rocketBomb = 0 }
            if (!fire.hasAmmo(FireControl.MISSILE)) { missileOrbit = 0; rocketMissile = 0 }
        }

        companion object {
            fun read(): AttackChances {
                val cannon = BossConfig.CANNON_ENABLED.get()
                val bombs = BossConfig.BOMBS_ENABLED.get()
                val missiles = BossConfig.MISSILES_ENABLED.get()
                val rockets = BossConfig.ROCKETS_ENABLED.get()
                return AttackChances(
                    gunPlain = if (cannon) BossConfig.GUN_RUN_CHANCE.get() else 0,
                    gunBombing = if (cannon && bombs) BossConfig.GUN_RUN_BOMB_CHANCE.get() else 0,
                    rocketRun = if (rockets) BossConfig.ROCKET_RUN_CHANCE.get() else 0,
                    bombRun = if (bombs) BossConfig.BOMB_RUN_CHANCE.get() else 0,
                    nuke = if (bombs && BossConfig.NUKE_ENABLED.get()) BossConfig.NUKE_RUN_CHANCE.get() else 0,
                    missileOrbit = if (missiles) BossConfig.MISSILE_ORBIT_CHANCE.get() else 0,
                    rocketMissile = if (rockets && missiles) BossConfig.ROCKET_MISSILE_RUN_CHANCE.get() else 0,
                    doubleGun = if (cannon) BossConfig.DOUBLE_GUN_RUN_CHANCE.get() else 0,
                    cannonRocket = if (cannon && rockets) BossConfig.CANNON_ROCKET_STALL_CHANCE.get() else 0,
                    rocketBomb = if (rockets && bombs) BossConfig.ROCKET_BOMB_STALL_CHANCE.get() else 0
                )
            }
        }
    }

    // ================================================================================================== states

    fun tickOrbit(target: Entity) {
        val cruise = cruiseAltitude()
        boss.thought.think("patterns.tickOrbit") { "missile orbit around ${who(target)}" }
        ensureLoiter(target.x, cruise, target.z, BossConfig.ORBIT_RADIUS.get().toDouble())
        trimOrbitThrottle(cruise)

        // The autopilot sheds altitude in tight banked turns: climb back instead of orbiting low.
        if (boss.y < cruise - ALTITUDE_RECOVERY && stateTicks > 60) {
            enter(BossState.INGRESS, "orbit sank, climbing back")
            return
        }

        orbitFire(target)
        if (stateTicks >= orbitDuration) host.selectNext()
    }

    /** The orbit fires nothing but AGM-65s, one every 60-90 ticks (shortened in the final phase). */
    private fun orbitFire(target: Entity) {
        val now = boss.tickCount
        if (!BossConfig.MISSILES_ENABLED.get() || BossConfig.MISSILE_ORBIT_CHANCE.get() <= 0) {
            boss.thought.think("patterns.orbitFire") { "holding fire: missiles disabled" }
            return
        }
        boss.thought.think("patterns.orbitFire") { if (now < nextMissileTick) "missile cooling down" else "locking ${who(target)}" }
        if (now >= nextMissileTick && fire.fireMissile(target, guided = !lastKnown.isGhost(target))) {
            nextMissileTick = now + cooldown(randomBetween(60, 90))
        }
    }

    /** A random point on a ring 6-12 blocks around the target, led by the target's motion. */
    private fun rocketRipplePoint(target: Entity): Vec3 {
        val angle = boss.getRandom().nextDouble() * Math.PI * 2.0
        val radius = 6.0 + boss.getRandom().nextDouble() * 6.0
        val speed = boss.getProjectileVelocity("Rocket").toDouble().coerceAtLeast(1.0)
        val base = Vec3(target.x + cos(angle) * radius, target.y, target.z + sin(angle) * radius)
        val flightTicks = boss.position().distanceTo(base) / speed
        return base.add(aimVelocity.scale(flightTicks))
    }

    // ---------------------------------------------------------------------------------------------- bomb run

    fun tickBombRun(target: Entity) {
        val runAltitude = max(cruiseAltitude(), target.y + BOMB_MIN_HEIGHT)
        val altitudeError = runAltitude - boss.y
        boss.thought.think("patterns.tickBombRun") {
            "${if (nukeRun) "Mk 84" else "bomb"} run on ${who(target)}: " + when (bombPhase) {
                BombPhase.SETUP -> "opening the distance"
                BombPhase.RUN -> "lined up, tracking the release point"
                BombPhase.STICK -> "stick leaving the rack"
            }
        }

        when (bombPhase) {
            BombPhase.SETUP -> {
                // A straight run needs room: first open the distance, then line up.
                val distance = FlightMath.horizontalDistance(boss.position(), target.position())
                if (distance < BOMB_RUN_IN_DISTANCE) {
                    fly(FlightMath.tilted(awayFrom(target), (altitudeError * 0.1).coerceIn(-6.0, 12.0)), 2.4f)
                } else {
                    bombPhase = BombPhase.RUN
                    previousMiss = Double.MAX_VALUE
                }
                if (stateTicks > BOMB_SETUP_TIMEOUT) enter(BossState.EXTEND, "bomb run setup timed out")
            }

            BombPhase.RUN -> bombRun(target, altitudeError)

            BombPhase.STICK -> {
                // Hold course and altitude while the rest of the stick leaves the rack.
                fly(FlightMath.tilted(currentHeading(), (altitudeError * 0.05).coerceIn(-3.0, 3.0)), 2.0f, maxBank = 10f)
                if (boss.tickCount >= stickEndTick) enter(BossState.EXTEND, "stick released")
            }
        }
    }

    private fun bombRun(target: Entity, altitudeError: Double) {
        // Aim for where the target will be when the bombs arrive, live: the Mk 84 run included (a point frozen when the
        // attack was drawn is long stale after the run-in).
        val predicted = fire.bombLeadPoint(target)
        val heading = towards(predicted)
        fly(FlightMath.tilted(heading, (altitudeError * 0.08).coerceIn(-5.0, 8.0)), 2.0f, maxBank = 45f)

        // Far from the release point the flat-ground estimate is enough; SBW's terrain-sampling prediction (hundreds of
        // heightmap reads per call) only runs once the plane is close.
        // A Mk-84 should still release over roofs and enclosed targets. Terrain sampling treats a rooftop as the
        // impact surface and can keep the precise pipper outside the narrow Mk-84 release window indefinitely.
        val flat = fire.estimateBombImpact(target.y)
        val impact = if (nukeRun || FlightMath.horizontalDistance(flat, predicted) > COARSE_MISS_RANGE) {
            flat
        } else {
            fire.predictBombImpact()
        }

        // The stick falls along the flight path, one bomb per STICK_INTERVAL ticks. Release the first bomb one spacing
        // short of the target so the middle bomb lands on it and the others straddle it.
        val track = FlightMath.horizontal(boss.deltaMovement, heading)
        val spacing = horizontalSpeed() * STICK_INTERVAL
        // A Mk-84 run releases one bomb; applying the Mk-82 stick spacing makes the single bomb land short.
        val aimPoint = if (nukeRun) predicted else predicted.subtract(track.scale(spacing))
        val miss = FlightMath.horizontalDistance(impact, aimPoint)

        val ahead = (predicted.x - boss.x) * track.x + (predicted.z - boss.z) * track.z
        // Release at the closest approach: the miss was shrinking and has started to grow.
        val pastMinimum = previousMiss < (if (nukeRun) NUKE_RELEASE_WINDOW else RELEASE_WINDOW) && miss > previousMiss
        previousMiss = miss

        // SETUP ends with the plane flying away from the target, so "behind" only means overflown once the turn-in is
        // done and the target has been ahead at least once (otherwise every pass was abandoned on its first tick).
        if (ahead > 0.0) bombApproached = true

        val overflown = bombApproached && ahead < -spacing - 20.0
        when {
            // The single Mk 84 only goes inside a tight window, and past the closest approach only while that is still
            // near the target: a bomb never leaves for "wherever the plane happens to be".
            nukeRun && (miss < NUKE_RELEASE_RADIUS || (pastMinimum && miss < NUKE_MAX_MISS)) ->
                releaseStick(miss, predicted, impact)
            // Overflown without a release: line up again for another pass instead of dropping it off target.
            nukeRun && overflown -> retryNukePass()
            !nukeRun && (miss < RELEASE_RADIUS || pastMinimum) -> releaseStick(miss, predicted, impact)
            overflown -> enter(BossState.EXTEND, "target overflown without a release") // the pass is lost
            stateTicks > BOMB_SETUP_TIMEOUT + BOMB_RUN_TIMEOUT -> enter(BossState.EXTEND, "never lined up") // never lined up
        }
    }

    /** The Mk 84 pass went by without a release: turn in again for another, up to [NUKE_MAX_PASSES]; then the run ends. */
    private fun retryNukePass() {
        if (++nukePasses >= NUKE_MAX_PASSES) {
            enter(BossState.EXTEND, "Mk 84 passes used up")
            return
        }
        boss.thought.event("patterns.retryNukePass") { "Mk 84 pass $nukePasses went by without a release: lining up again" }
        bombPhase = BombPhase.SETUP
        bombApproached = false
        previousMiss = Double.MAX_VALUE
    }

    private fun releaseStick(predictedMiss: Double, predictedTarget: Vec3, impact: Vec3) {
        if (!BossConfig.BOMBS_ENABLED.get()) { // switched off mid-run: abandon the pass
            enter(BossState.EXTEND, "bombs disabled mid-run")
            return
        }
        if (nukeRun) {
            if (!fire.dropNuke()) return // rack busy: try again next tick while the release window holds
            boss.thought.event("patterns.releaseStick") {
                "Mk 84 release: predicted miss ${"%.1f".format(predictedMiss)} blocks, impact $impact, predicted target $predictedTarget, target now ${aim?.position()}"
            }
            nukeRun = false
            fire.selectBombVariant(false)
            enter(BossState.EXTEND, "Mk 84 away")
            return
        }
        if (!fire.dropBomb()) return // rack busy: try again next tick (the pass times out via the overfly check)
        fire.dropBombStick(BOMBS_PER_STICK - 1, STICK_INTERVAL)
        bombPhase = BombPhase.STICK
        stickEndTick = boss.tickCount + (BOMBS_PER_STICK - 1) * STICK_INTERVAL + STICK_EXTRA_TICKS
        boss.thought.event("patterns.releaseStick") {
            "bomb release: predicted miss ${"%.1f".format(predictedMiss)} blocks, impact $impact, predicted target $predictedTarget, target now ${aim?.position()}"
        }
    }

    // ---------------------------------------------------------------------------------------------- gun dive

    /** Gap between the planned dive from [from] to [known] and the terrain under it (see [TerrainGuard.diveClearance]). */
    private fun diveClearance(from: Vec3, known: Vec3): Double {
        val ground = max(guard.terrainAt(known.x, known.z, known.y), known.y)
        return guard.diveClearance(
            from, Vec3(known.x, ground + 0.5, known.z), ground + pullOutBase(), known.y + UNKNOWN_TERRAIN_OFFSET
        )
    }

    /** Height above the target ground at which the pull-out bottoms out; the slow second pass of a stall turn keeps more. */
    private fun pullOutBase(): Double = if (stallPass) STALL_PULL_OUT_BASE else PULL_OUT_BASE

    private fun pullOutSpeedFactor(): Double = if (stallPass) STALL_PULL_OUT_SPEED_FACTOR else PULL_OUT_SPEED_FACTOR

    /**
     * Climbs away from the target to the dive start height, then checks that the straight dive line (and the run-out
     * beyond the target) clears every ridge. A blocked line is answered with a higher start, up to
     * [DIVE_MAX_EXTRA_CLIMB] blocks; if it is still blocked (or the climb times out) the attack is abandoned.
     */
    fun tickClimbForDive(target: Entity) {
        val diveTop = min(cruiseAltitude() + DIVE_TOP_EXTRA + extraClimb, boss.level().maxBuildHeight + 60.0)
        val altitudeError = diveTop - boss.y
        val distance = FlightMath.horizontalDistance(boss.position(), target.position())
        // At a low cruise height the run-in is what gives the gun time to fire, so never start it close.
        // The bombing variant starts farther out, so the cannon still gets a long strafe before the bomb pass.
        val minStandoff = if (bombComboPending && diveWeapon == DiveWeapon.CANNON) BOMB_PASS_STANDOFF else MIN_DIVE_STANDOFF
        val standoff = max(DIVE_STANDOFF_FACTOR * (diveTop - target.y), minStandoff)

        boss.thought.think("patterns.tickClimbForDive") {
            "climbing to dive start, target ${who(target)}, weapon $diveWeapon" + if (extraClimb > 0.0) ", start raised for terrain" else ""
        }
        fly(FlightMath.tilted(awayFrom(target), (altitudeError * 0.12).coerceIn(-4.0, 20.0)), 2.4f)

        val timedOut = stateTicks > CLIMB_TIMEOUT
        if ((distance >= standoff * 0.95 && altitudeError < 20.0) || timedOut) {
            val known = lastKnown.known ?: target.position()
            when {
                diveClearance(boss.position(), known) >= DIVE_CLEARANCE_MIN -> enter(BossState.GUN_DIVE, "dive line is clear")
                !timedOut && extraClimb < DIVE_MAX_EXTRA_CLIMB -> {
                    extraClimb += DIVE_CLIMB_STEP
                    boss.thought.event("patterns.tickClimbForDive") { "dive line blocked by terrain: dive start raised by ${extraClimb.toInt()} blocks" }
                }
                else -> enter(BossState.EXTEND, if (timedOut) "climb timed out" else "dive line blocked even at the highest start")
            }
        }
    }

    /**
     * The cannon run razes the target's last known position: no lead, a scatter of a few blocks that is re-rolled every
     * few ticks and long bursts, so a player who keeps moving is missed and one who stands still is shredded. Rocket
     * runs still lead the live target. Without a live target (it left or died mid-run) the run finishes on the
     * last known position.
     */
    fun tickGunDive(target: Entity?) {
        val known = lastKnown.known ?: target?.position() ?: boss.homePos
        val origin = boss.getShootPos("Cannon", 1f)
        val speed = boss.deltaMovement.length()

        // Bombing variant: the cannon strafes from afar, then inside BOMB_PASS_RANGE the run turns into a dive-bomb pass.
        val bombPass = tossArmed && !tossReleased && bombsAvailable() &&
                FlightMath.horizontalDistance(boss.position(), known) < BOMB_PASS_RANGE
        boss.thought.think("patterns.tickGunDive") {
            "diving on ${if (target != null) who(target) else "the last known spot"} with $diveWeapon" +
                    (if (bombPass) ", bomb pass" else "") + (if (stallPass) ", second pass" else "")
        }

        val aimPoint = when {
            bombPass -> bombPassAim(known)
            diveWeapon == DiveWeapon.CANNON -> fire.cannonAimAt(razePoint(known))
            diveWeapon == DiveWeapon.BOMBS -> fire.cannonAimAt(known) // lines up; the bombs only go inside BOMB_PASS_RANGE
            target != null -> fire.cannonLeadPoint(target)
            else -> fire.cannonAimAt(known)
        }
        val wanted = aimPoint.subtract(origin)

        // Unguarded on purpose: the dive goes low by design and ends at the pull-out altitude below. The slow second
        // pass of a stall turn keeps more power on so it does not sink into the ground at low speed.
        val throttle = if (stallPass) STALL_DIVE_THROTTLE else DIVE_THROTTLE
        fly(wanted, throttle, maxBank = 70f, airBrake = speed > DIVE_BRAKE_SPEED, converge = true, guarded = false)

        // Mountains: re-check the dive line every few ticks and break off while there is still room to pull out.
        if (boss.tickCount >= nextCorridorCheckTick) {
            nextCorridorCheckTick = boss.tickCount + CORRIDOR_CHECK_INTERVAL
            if (diveClearance(boss.position(), known) < DIVE_ABORT_CLEARANCE) {
                enter(BossState.PULL_OUT, "dive line blocked by terrain")
                return
            }
        }

        val error = flight.angleErrorDeg(wanted)
        val range = origin.distanceTo(known)
        // No line-of-sight test: the barrage is fired at the spot whatever stands in between (the shells break blocks).
        val clear = range < MAX_GUN_RANGE
        // One weapon type per run.
        when (diveWeapon) {
            DiveWeapon.CANNON -> if (!bombPass && error < RAZE_FIRE_ANGLE && clear && BossConfig.CANNON_ENABLED.get()) {
                fire.fireCannon(raze = true) // burst timing and cooldown are handled inside FireControl
            }

            DiveWeapon.ROCKETS -> if (target != null && clear && diveRocketsLeft > 0 && error < DIVE_ROCKET_ANGLE &&
                BossConfig.ROCKETS_ENABLED.get() &&
                range < MAX_DIVE_ROCKET_RANGE && boss.tickCount >= nextDiveRocketTick &&
                fire.fireRocket(rocketRipplePoint(target))
            ) {
                diveRocketsLeft--
                nextDiveRocketTick = boss.tickCount + ROCKET_INTERVAL
            }

            DiveWeapon.BOMBS -> {} // dropped by the bomb pass / toss below
        }
        boss.thought.think("patterns.gunDiveFire") {
            when (diveWeapon) {
                DiveWeapon.CANNON -> when {
                    !BossConfig.CANNON_ENABLED.get() -> "holding fire: cannon disabled"
                    bombPass -> "holding fire: bomb pass"
                    !clear -> "holding fire: target out of range"
                    error >= RAZE_FIRE_ANGLE -> "holding fire: nose not on the aim point"
                    else -> "cannon razing the aim point"
                }

                DiveWeapon.ROCKETS -> when {
                    !BossConfig.ROCKETS_ENABLED.get() -> "holding fire: rockets disabled"
                    target == null -> "holding fire: no target to ripple"
                    diveRocketsLeft <= 0 -> "holding fire: rockets for this run spent"
                    !clear || range >= MAX_DIVE_ROCKET_RANGE -> "holding fire: target out of range"
                    error >= DIVE_ROCKET_ANGLE -> "holding fire: nose not on the target"
                    else -> "rocket ripple"
                }

                DiveWeapon.BOMBS -> if (bombPass) "bomb pass, waiting for the release point" else "closing on the bomb pass"
            }
        }

        // Rocket run with homing missiles: the AGM-65s leave while the rockets are being fired (FireControl paces them).
        if (diveWeapon == DiveWeapon.ROCKETS && target != null && diveMissilesLeft > 0 && clear &&
            BossConfig.MISSILES_ENABLED.get() && range < MAX_DIVE_ROCKET_RANGE &&
            fire.fireMissile(target, guided = !lastKnown.isGhost(target))
        ) {
            diveMissilesLeft--
        }

        val ground = max(guard.terrainAt(known.x, known.z, known.y), known.y)
        val basePullOut = ground + pullOutBase() + pullOutSpeedFactor() * speed * speed
        // Also respect terrain on the way (a mountain beside the target), but never demand more than a bounded raise.
        val pullOutAltitude = max(basePullOut, min(guard.floorAhead(known.y + UNKNOWN_TERRAIN_OFFSET), basePullOut + 60.0))
        val passedTarget = FlightMath.horizontalDistance(boss.position(), known) < 120.0 &&
                (known.x - boss.x) * currentHeading().x + (known.z - boss.z) * currentHeading().z < 0.0
        val diveOver = boss.y <= pullOutAltitude || passedTarget || stateTicks > DIVE_TIMEOUT

        // Bombs away (then climb out), or still short when the dive has to end: drop anyway if close enough to count.
        if (bombPass && tryBombPassRelease(known, lastDive = diveOver)) {
            enter(BossState.PULL_OUT, "bombs away")
            return
        }
        if (diveOver) {
            enter(
                BossState.PULL_OUT,
                when {
                    boss.y <= pullOutAltitude -> "reached the pull-out altitude"
                    passedTarget -> "passed the target"
                    else -> "dive timed out"
                }
            )
        }

        // Still armed after the pass (bomb rack busy, or the stick would have landed too far off): the toss on the pull-up
        // is the second chance (it primes here, during the dive).
        if (tossArmed && !tossReleased) tryToss()
    }

    /** Where the bomb released now would land: flat-ground estimate, terrain-aware close to the target. */
    private fun bombImpactNear(known: Vec3): Vec3 {
        val ground = max(guard.terrainAt(known.x, known.z, known.y), known.y)
        val flat = fire.estimateBombImpact(ground)
        return if (FlightMath.horizontalDistance(flat, known) > TOSS_PRECISE_RANGE) flat else fire.predictBombImpact()
    }

    /**
     * Dive-bomb steering: in a dive aimed at the target the bomb falls short (gravity), so the aim point is pushed beyond
     * the target by an integrated correction ([bombAimShift]) until the predicted impact sits on it. That flattens the
     * dive slightly, the way a pilot walks the CCIP pipper onto the target.
     */
    private fun bombPassAim(known: Vec3): Vec3 {
        val impact = bombImpactNear(known)
        val shortfall = Vec3(known.x - impact.x, 0.0, known.z - impact.z)
        var shift = bombAimShift.add(shortfall.scale(BOMB_AIM_GAIN))
        if (shift.length() > BOMB_AIM_MAX_SHIFT) shift = shift.normalize().scale(BOMB_AIM_MAX_SHIFT)
        bombAimShift = shift
        return known.add(shift).add(0.0, 0.5, 0.0)
    }

    /**
     * Releases the stick when the predicted impact is within [BOMB_PASS_LEAD] short of the target (so the stick straddles
     * it) and within [TOSS_MAX_LATERAL] of the track; on the [lastDive] tick also whenever the miss is under
     * [BOMB_PASS_MAX_MISS] (near enough for the blast). Returns true once the bombs are away.
     */
    private fun tryBombPassRelease(known: Vec3, lastDive: Boolean): Boolean {
        val impact = bombImpactNear(known)
        val track = currentHeading()
        val dx = impact.x - known.x
        val dz = impact.z - known.z
        val along = dx * track.x + dz * track.z
        val lateral = abs(dx * track.z - dz * track.x)
        val onTarget = along >= -BOMB_PASS_LEAD && lateral < TOSS_MAX_LATERAL
        val closeEnough = lastDive && hypot(dx, dz) < BOMB_PASS_MAX_MISS
        if (!onTarget && !closeEnough) return false
        if (!fire.dropBomb()) return false // rack busy: next tick, or the pull-up toss
        fire.dropBombStick(BOMBS_PER_STICK - 1, BOMB_PASS_STICK_INTERVAL)
        tossReleased = true
        tossArmed = false
        boss.thought.event("patterns.gunRunBomb") {
            "gun run bomb release (${if (onTarget) "on target" else "end of dive"}): along ${"%.1f".format(along)}, lateral ${"%.1f".format(lateral)}, impact $impact, target $known"
        }
        return true
    }

    /**
     * Toss bombing for the bombing variant of the cannon run. In a dive a bomb's predicted impact is always short of the
     * aim point (gravity); once the nose comes up the impact point sweeps forward through the target. This releases the
     * stick at that crossing, which is why it runs on the dive tail, the pull-up and the extension.
     *
     * Primed while descending and still short of the target (so the turn-in at the dive start, when the impact point can
     * be anywhere, never releases); released when the impact reaches [TOSS_LEAD] short of the last known position and is
     * within [TOSS_MAX_LATERAL] of the track. Giving up (crossed off to the side, swept [TOSS_GIVE_UP] past, or the
     * plane stopped descending without ever being primed) disarms it, and [selectNext] flies the separate bomb run.
     */
    private fun tryToss() {
        if (!bombsAvailable()) { // switched off or spent mid-run
            tossArmed = false
            boss.thought.event("patterns.bombToss") { "bomb toss cancelled: bombs unavailable" }
            return
        }
        val known = lastKnown.known ?: return
        val ground = max(guard.terrainAt(known.x, known.z, known.y), known.y)
        // Far from the target the flat-ground estimate is enough; the terrain-aware prediction only runs close in.
        val flat = fire.estimateBombImpact(ground)
        val impact = if (FlightMath.horizontalDistance(flat, known) > TOSS_PRECISE_RANGE) flat else fire.predictBombImpact()

        val track = currentHeading()
        val dx = impact.x - known.x
        val dz = impact.z - known.z
        val along = dx * track.x + dz * track.z
        val lateral = abs(dx * track.z - dz * track.x)
        val descending = boss.deltaMovement.y < 0.0
        boss.thought.think("patterns.tryToss") {
            if (tossPrimed) "toss primed, waiting for the release window" else "toss armed, waiting to prime"
        }

        if (!tossPrimed) {
            if (descending && along < -TOSS_LEAD &&
                FlightMath.horizontalDistance(boss.position(), known) < TOSS_MAX_RANGE
            ) {
                tossPrimed = true
            } else if (!descending && state != BossState.GUN_DIVE) {
                tossArmed = false // climbing and never lined up: nothing left to wait for
                boss.thought.event("patterns.bombToss") { "bomb toss missed: never primed" }
            }
            previousTossAlong = along
            return
        }

        when {
            along >= -TOSS_LEAD && lateral < TOSS_MAX_LATERAL -> {
                if (!fire.dropBomb()) return // rack busy: try again next tick while the window holds
                fire.dropBombStick(BOMBS_PER_STICK - 1, TOSS_STICK_INTERVAL)
                tossReleased = true
                tossArmed = false
                boss.thought.event("patterns.bombToss") {
                    "bomb toss release: along ${"%.1f".format(along)}, lateral ${"%.1f".format(lateral)}, impact $impact, target $known"
                }
            }

            along >= -TOSS_LEAD || along > TOSS_GIVE_UP -> {
                tossArmed = false
                boss.thought.event("patterns.bombToss") {
                    "bomb toss missed: along ${"%.1f".format(along)}, lateral ${"%.1f".format(lateral)}, previous along ${"%.1f".format(previousTossAlong)}"
                }
            }
        }
        previousTossAlong = along
    }

    /** [known] plus a random scatter of up to [RAZE_RADIUS] blocks, re-rolled every [RAZE_JITTER_TICKS] ticks. */
    private fun razePoint(known: Vec3): Vec3 {
        if (boss.tickCount >= nextRazeJitterTick) {
            nextRazeJitterTick = boss.tickCount + RAZE_JITTER_TICKS
            val angle = boss.getRandom().nextDouble() * Math.PI * 2.0
            val radius = boss.getRandom().nextDouble() * RAZE_RADIUS
            razeOffset = Vec3(cos(angle) * radius, 0.0, sin(angle) * radius)
        }
        return known.add(razeOffset).add(0.0, 0.5, 0.0)
    }

    fun tickPullOut() {
        // Full throttle, nose well up, wings nearly level.
        // Through the terrain guard: it steepens the climb if the ground ahead is higher than the plane.
        boss.thought.think("patterns.tickPullOut") { "pulling out, nose up" + if (secondPassPending) ", second pass follows" else "" }
        fly(FlightMath.tilted(currentHeading(), 40.0), 3.0f, maxBank = 25f)
        val v = boss.deltaMovement
        val climbAngle = Math.toDegrees(atan2(v.y, hypot(v.x, v.z)))
        if (climbAngle >= PULL_OUT_CLIMB_ANGLE || stateTicks > PULL_OUT_TIMEOUT) {
            // Double gun run: the first pass is done, stall-turn round for the second one.
            if (secondPassPending) {
                secondPassPending = false
                enter(BossState.STALL_TURN, "first pass done, stall turn for the second")
            } else {
                enter(BossState.EXTEND, "pull-out complete")
            }
        }
        if (tossArmed && !tossReleased) tryToss()
    }

    /** The second pass's weapon is still enabled and in stock (read live; the first pass may have spent it). */
    private fun secondPassReady(): Boolean = when (secondPassWeapon) {
        DiveWeapon.CANNON -> BossConfig.CANNON_ENABLED.get() && fire.hasAmmo(FireControl.CANNON)
        DiveWeapon.ROCKETS -> BossConfig.ROCKETS_ENABLED.get() && fire.hasAmmo(FireControl.ROCKET)
        DiveWeapon.BOMBS -> bombsAvailable()
    }

    private fun enterStallPhase(next: StallPhase) {
        boss.thought.event("patterns.enterStallPhase") { "stall turn: $stallPhase -> $next" }
        stallPhase = next
        stallPhaseTicks = 0
    }

    /**
     * Second half of the double gun run: a stall turn (hammerhead) instead of a wide U-turn, so the plane stays over the
     * spot where the pull-out ended and never swings out over unseen terrain.
     *  - CLIMB: nose near vertical on the pull-out heading at full power, until the height it will coast up to is dive
     *    height (capped so the dive is no steeper than [STALL_MAX_DIVE_SLOPE]) or it hangs (slow), and it clears the
     *    terrain ahead by [STALL_ROTATE_CLEARANCE]. In mountains it climbs on until above the ridge, or gives the pass up
     *    when clearing the ridge would mean a dive steeper than allowed.
     *  - SWING: power cut, nose held up while the yaw is swung round to the target at [STALL_YAW_STEP] degrees per tick.
     *  - DROP: the nose falls onto a [STALL_DIVE_PITCH] degree descent at the target; once it points there and the dive
     *    line is clear it flies the second pass ([BossState.GUN_DIVE], cannon only) from the opposite side.
     * Abandoned (to EXTEND) when the climb never gets above the terrain, the line is blocked, the target is too close
     * for a dive, or a phase takes too long.
     */
    fun tickStallTurn(target: Entity) {
        val known = lastKnown.known ?: target.position()
        val toKnown = towards(known)
        val heading = stallHeading ?: currentHeading().also { stallHeading = it }
        stallPhaseTicks++
        boss.thought.think("patterns.tickStallTurn") { "stall turn ${stallPhase.name.lowercase()}, target ${who(target)}" }

        if (stateTicks > STALL_TURN_TIMEOUT) {
            enter(BossState.EXTEND, "stall turn timed out")
            return
        }

        when (stallPhase) {
            StallPhase.CLIMB -> {
                flight.steer(
                    FlightMath.tilted(heading, STALL_CLIMB_PITCH), 3.0f, maxBank = STALL_MAX_BANK, stallGuard = false
                )
                val distance = FlightMath.horizontalDistance(boss.position(), known)
                // The plane keeps rising for a while after the climb ends (speed, then the hang), so judge the height it
                // will reach, not the current one.
                val projected = boss.y + max(boss.deltaMovement.y, 0.0) * STALL_COAST_TICKS
                // Highest start that still leaves a dive no steeper than STALL_MAX_DIVE_SLOPE: higher than that and the
                // second pass is too steep to pull out of.
                val ceiling = known.y + distance * STALL_MAX_DIVE_SLOPE
                val apex = min(
                    cruiseAltitude() + DIVE_TOP_EXTRA + STALL_APEX_EXTRA, min(ceiling, boss.level().maxBuildHeight + 60.0)
                )
                val clearance = projected - guard.floorAhead(known.y + UNKNOWN_TERRAIN_OFFSET)
                val nosedUp = -boss.xRot >= STALL_MIN_NOSE_UP
                val topped = projected >= apex || boss.deltaMovement.length() < STALL_HANG_SPEED
                if (nosedUp && topped && clearance >= STALL_ROTATE_CLEARANCE) {
                    enterStallPhase(StallPhase.SWING)
                } else if (stallPhaseTicks > STALL_CLIMB_TIMEOUT || distance < STALL_MIN_DISTANCE ||
                    (projected > ceiling && clearance < STALL_ROTATE_CLEARANCE) // the ridge needs a steeper dive than allowed
                ) {
                    enter(
                        BossState.EXTEND,
                        when {
                            stallPhaseTicks > STALL_CLIMB_TIMEOUT -> "stall climb timed out"
                            distance < STALL_MIN_DISTANCE -> "too close for a second pass"
                            else -> "the ridge needs a steeper dive than allowed"
                        }
                    )
                }
            }

            StallPhase.SWING -> {
                val nosedUp = FlightMath.tilted(toKnown, STALL_CLIMB_PITCH)
                flight.steer(nosedUp, STALL_SWING_THROTTLE, maxBank = STALL_MAX_BANK, stallGuard = false)
                flight.pivotYaw(nosedUp, STALL_YAW_STEP)
                val yawError = abs(Mth.wrapDegrees(FlightMath.yaw(nosedUp) - boss.yRot))
                if (yawError < STALL_SWING_DONE || stallPhaseTicks > STALL_SWING_TIMEOUT) enterStallPhase(StallPhase.DROP)
            }

            StallPhase.DROP -> {
                val dive = FlightMath.tilted(toKnown, -STALL_DIVE_PITCH)
                flight.steer(dive, STALL_DROP_THROTTLE, maxBank = STALL_DROP_MAX_BANK, converge = true)
                if (flight.angleErrorDeg(dive) < STALL_RELEASE_ANGLE) {
                    val distance = FlightMath.horizontalDistance(boss.position(), known)
                    val shallowEnough = boss.y - known.y <= distance * STALL_MAX_ENTRY_SLOPE
                    if (distance > STALL_MIN_DISTANCE && shallowEnough && secondPassReady() &&
                        diveClearance(boss.position(), known) >= DIVE_CLEARANCE_MIN
                    ) {
                        diveWeapon = secondPassWeapon // the second pass may use another weapon than the first
                        enter(BossState.GUN_DIVE, "second pass with $secondPassWeapon")
                    } else {
                        enter(
                            BossState.EXTEND,
                            when {
                                distance <= STALL_MIN_DISTANCE -> "second pass dropped: too close"
                                !shallowEnough -> "second pass dropped: dive too steep"
                                !secondPassReady() -> "second pass dropped: weapon not ready"
                                else -> "second pass dropped: dive line blocked"
                            }
                        )
                    }
                } else if (stallPhaseTicks > STALL_DROP_TIMEOUT) {
                    enter(BossState.EXTEND, "stall drop timed out")
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- taunt

    /**
     * A show-off pass that fires nothing. Either a low flyby right over the target, or (50%) a higher pass with a
     * full barrel roll; flares are dropped while the plane is near the target in both. Like the bomb run it first opens
     * the distance so the pass is a straight run, then flies in.
     */
    fun tickTaunt(target: Entity) {
        val ground = max(target.y, guard.terrainAt(target.x, target.z, target.y))
        val passAltitude = ground + if (tauntRoll) TAUNT_ROLL_HEIGHT else TAUNT_FLYBY_HEIGHT
        val altitudeError = passAltitude - boss.y
        val tilt = (altitudeError * 0.1).coerceIn(-6.0, 12.0)
        val distance = FlightMath.horizontalDistance(boss.position(), target.position())
        boss.thought.think("patterns.tickTaunt") {
            "taunt over ${who(target)}: ${if (tauntRoll) "barrel roll pass" else "low flyby"}, ${tauntPhase.name.lowercase()}"
        }

        if (stateTicks > TAUNT_TIMEOUT) {
            enter(BossState.EXTEND, "taunt timed out")
            return
        }

        when (tauntPhase) {
            TauntPhase.SETUP -> {
                if (distance < TAUNT_RUN_IN_DISTANCE) {
                    fly(FlightMath.tilted(awayFrom(target), tilt), 2.4f)
                } else {
                    tauntPhase = TauntPhase.RUN
                }
            }

            TauntPhase.RUN -> {
                val toTarget = towards(target.position())
                val ahead = (target.x - boss.x) * currentHeading().x + (target.z - boss.z) * currentHeading().z
                if (!tauntPassed && ahead < 0.0 && distance < TAUNT_PASSED_DISTANCE) tauntPassed = true

                // After the pass keep going straight; turning back to the target would end the flyby in a circle.
                val heading = if (tauntPassed) currentHeading() else toTarget
                fly(FlightMath.tilted(heading, tilt), 2.2f, maxBank = 45f)

                val aligned = currentHeading().dot(toTarget) > TAUNT_ALIGNED_DOT
                if (tauntRoll && !rolling && !rollDone && !tauntPassed && aligned && distance < TAUNT_ROLL_START) startRoll()
                if (rolling) continueRoll()

                if (distance < TAUNT_FLARE_RANGE && flaresDropped < TAUNT_MAX_FLARES && boss.tickCount >= nextTauntFlareTick &&
                    !boss.recentlyDamaged(TAUNT_NO_FLARE_TICKS)
                ) {
                    if (flaresDropped == 0) boss.thought.event("patterns.tickTaunt") { "dropping show-off flares" }
                    fire.releaseFlare()
                    flaresDropped++
                    nextTauntFlareTick = boss.tickCount + TAUNT_FLARE_INTERVAL
                }

                if (tauntPassed && !rolling) enter(BossState.EXTEND, "flyby done")
            }
        }
    }

    private fun startRoll() {
        boss.thought.event("patterns.startRoll") { "starting a barrel roll to the ${if (rollSide > 0) "right" else "left"}" }
        rolling = true
        rolledDegrees = 0.0
        rollTicks = 0
        lastRoll = boss.roll
        lastRollTick = boss.tickCount
    }

    /** Commands a full-rate roll and counts the degrees turned until one full turn is done (or it stops progressing). */
    private fun continueRoll() {
        val now = boss.tickCount
        // An evade (or anything else) took the controls for a while: abandon the roll rather than finishing it late.
        if (now - lastRollTick > 1) {
            boss.thought.event("patterns.continueRoll") { "roll abandoned: something else took the controls" }
            rolling = false
            rollDone = true
            return
        }
        rolledDegrees += Mth.wrapDegrees(boss.roll - lastRoll) * rollSide
        lastRoll = boss.roll
        lastRollTick = now
        rollTicks++

        if (Math.abs(rolledDegrees) >= FULL_ROLL_DEGREES || rollTicks > ROLL_TIMEOUT) {
            boss.thought.event("patterns.continueRoll") {
                if (rollTicks > ROLL_TIMEOUT) "roll timed out" else "barrel roll complete"
            }
            rolling = false
            rollDone = true
            return
        }
        flight.applyRoll(rollSide)
    }

    companion object {
        /**
         * Altitude (relative to the target) assumed for chunks that are not loaded, i.e. everything beyond the nearest
         * player's render distance. It must be low: a "mountain" there (it used to be +40) failed every dive corridor
         * check whose run-out left the loaded area and forced a steep pull-up on every look-ahead into it, so with a
         * short render distance the plane kept climbing away and never started its attack. Below the target it blocks
         * nothing, and the terrain near the target (always loaded, the player is there) is still read for real.
         */
        const val UNKNOWN_TERRAIN_OFFSET = -10.0

        /** A full barrel roll counts as done at this many degrees; the roll gives up after [ROLL_TIMEOUT] ticks. */
        const val FULL_ROLL_DEGREES = 350.0
        const val ROLL_TIMEOUT = 120

        /** The orbit climbs back to cruise altitude when the autopilot has shed this many blocks. */
        private const val ALTITUDE_RECOVERY = 25.0
        private const val ROCKET_INTERVAL = 3

        // Bomb run
        private const val BOMB_MIN_HEIGHT = 40.0
        private const val BOMB_RUN_IN_DISTANCE = 200.0
        private const val BOMB_SETUP_TIMEOUT = 600
        private const val BOMB_RUN_TIMEOUT = 500
        private const val BOMBS_PER_STICK = 3
        private const val STICK_INTERVAL = 6
        private const val STICK_EXTRA_TICKS = 10
        private const val RELEASE_RADIUS = 4.0
        private const val RELEASE_WINDOW = 14.0

        /** The Mk 84 is a single bomb: released inside this radius, or past the closest approach inside the window. */
        private const val NUKE_RELEASE_RADIUS = 3.0
        private const val NUKE_RELEASE_WINDOW = 8.0

        /** Past the closest approach the Mk 84 still goes only when it would land this close to the target. */
        private const val NUKE_MAX_MISS = 10.0
        private const val NUKE_MAX_PASSES = 3
        private const val COARSE_MISS_RANGE = 90.0

        private const val TAUNT_FLYBY_HEIGHT = 28.0
        private const val TAUNT_ROLL_HEIGHT = 45.0
        private const val TAUNT_RUN_IN_DISTANCE = 170.0
        private const val TAUNT_PASSED_DISTANCE = 140.0
        private const val TAUNT_ALIGNED_DOT = 0.97
        private const val TAUNT_ROLL_START = 130.0
        private const val TAUNT_FLARE_RANGE = 90.0
        private const val TAUNT_FLARE_INTERVAL = 5
        private const val TAUNT_MAX_FLARES = 8
        private const val TAUNT_NO_FLARE_TICKS = 200
        private const val TAUNT_TIMEOUT = 700

        // Bombing gun run: cannon strafe from afar, dive-bomb pass when close
        /** Within this horizontal distance (blocks) of the target the cannon stops and the run lines up its bombs. */
        private const val BOMB_PASS_RANGE = 110.0
        /** Minimum run-in distance of the bombing variant (the plain gun run uses MIN_DIVE_STANDOFF). */
        private const val BOMB_PASS_STANDOFF = 220.0
        /** First bomb this far short of the target so the stick (3 bombs, ~9 blocks apart in a dive) straddles it. */
        private const val BOMB_PASS_LEAD = 9.0
        /** At the end of the dive the stick is still dropped if it would land within this distance (Mk 82 blast ~22). */
        private const val BOMB_PASS_MAX_MISS = 25.0
        private const val BOMB_PASS_STICK_INTERVAL = 3
        private const val BOMB_AIM_GAIN = 0.15
        private const val BOMB_AIM_MAX_SHIFT = 60.0

        // Toss bombing (second chance of the bombing gun run)
        /** The first bomb is released this far (blocks) before the predicted impact reaches the target, so the stick straddles it. */
        private const val TOSS_LEAD = 6.0
        private const val TOSS_MAX_RANGE = 220.0
        private const val TOSS_MAX_LATERAL = 14.0
        /** Closer than this (blocks) to the target the terrain-aware impact prediction replaces the flat-ground estimate. */
        private const val TOSS_PRECISE_RANGE = 30.0
        /** The impact point swept this far past the target without a release: the window is lost. */
        private const val TOSS_GIVE_UP = 40.0
        /** Ticks between bombs of a toss stick; short because the impact point sweeps fast while the nose is coming up. */
        private const val TOSS_STICK_INTERVAL = 2

        // Gun dive
        private const val DIVE_TOP_EXTRA = 25.0
        private const val DIVE_STANDOFF_FACTOR = 1.2
        private const val MIN_DIVE_STANDOFF = 110.0
        private const val DIVE_ROCKET_ANGLE = 10.0
        private const val MAX_DIVE_ROCKET_RANGE = 280.0
        /** Homing missiles launched during a rocket run with missiles. */
        private const val DIVE_MISSILES = 2

        // Double gun run: stall turn between the two passes
        /**
         * The second pass starts slow, so it bottoms its pull-out higher (plain dives: 18 + 2 v^2), holds more power and
         * takes the emergency pull-up earlier.
         */
        private const val STALL_PULL_OUT_BASE = 40.0
        private const val STALL_PULL_OUT_SPEED_FACTOR = 3.5
        private const val STALL_DIVE_THROTTLE = 1.6f
        private const val STALL_DIVE_EMERGENCY_CLEARANCE = 40.0
        private const val STALL_DIVE_DESCENT_FACTOR = 18.0
        /** Extra apex height of the climb; the dive slope cap ([STALL_MAX_DIVE_SLOPE]) still limits it. */
        private const val STALL_APEX_EXTRA = 30.0
        /** Above 75 degrees the flight controller levels the wings, which a straight-up climb needs. */
        private const val STALL_CLIMB_PITCH = 80.0
        private const val STALL_MAX_BANK = 15f
        private const val STALL_DROP_MAX_BANK = 40f
        /** The climb only ends with the nose at least this far above the horizon. */
        private const val STALL_MIN_NOSE_UP = 55.0
        /** Blocks per tick below which the plane counts as hanging at the top of the climb. */
        private const val STALL_HANG_SPEED = 1.6
        /** Required height above [TerrainGuard.floorAhead] (terrain plus its margin) before the swing starts. */
        private const val STALL_ROTATE_CLEARANCE = 40.0
        private const val STALL_SWING_THROTTLE = 0.6f
        private const val STALL_DROP_THROTTLE = 1.5f
        private const val STALL_YAW_STEP = 7f
        private const val STALL_SWING_DONE = 15.0
        private const val STALL_DIVE_PITCH = 35.0
        private const val STALL_RELEASE_ANGLE = 20.0
        /** Closer than this (blocks) to the target the drop would be too steep to pull out of: no second pass. */
        private const val STALL_MIN_DISTANCE = 70.0
        /** Steepest dive the climb may set up: tan(35 degrees), the slope of a normal gun run start. */
        private const val STALL_MAX_DIVE_SLOPE = 0.7
        /** Backstop at the end of the drop: tan(42 degrees); steeper and the second pass is dropped. */
        private const val STALL_MAX_ENTRY_SLOPE = 0.9
        /** Ticks of upward speed the plane is assumed to carry on after the climb ends. */
        private const val STALL_COAST_TICKS = 12
        private const val STALL_CLIMB_TIMEOUT = 160
        private const val STALL_SWING_TIMEOUT = 60
        private const val STALL_DROP_TIMEOUT = 100
        private const val STALL_TURN_TIMEOUT = 420
        private const val DIVE_ROCKETS_MIN = 8
        private const val DIVE_ROCKETS_MAX = 12
        private const val CLIMB_TIMEOUT = 700
        private const val DIVE_THROTTLE = 0.8f
        private const val DIVE_BRAKE_SPEED = 3.2
        private const val RAZE_FIRE_ANGLE = 6.0
        private const val RAZE_RADIUS = 6.0
        private const val RAZE_JITTER_TICKS = 3
        private const val MAX_GUN_RANGE = 450.0
        private const val PULL_OUT_BASE = 18.0
        private const val PULL_OUT_SPEED_FACTOR = 2.0
        private const val DIVE_TIMEOUT = 500
        private const val PULL_OUT_CLIMB_ANGLE = 20.0
        private const val PULL_OUT_TIMEOUT = 160
        private const val DIVE_EMERGENCY_CLEARANCE = 20.0
        private const val DIVE_DESCENT_FACTOR = 12.0
        private val EMERGENCY_LOOK_AHEAD_TICKS = doubleArrayOf(8.0, 16.0, 25.0)
        // Over flat ground the planned path bottoms out PULL_OUT_BASE (18) above the terrain, so these sit well below that.
        private const val DIVE_CLEARANCE_MIN = 4.0
        private const val DIVE_ABORT_CLEARANCE = -6.0
        private const val DIVE_MAX_EXTRA_CLIMB = 125.0
        private const val DIVE_CLIMB_STEP = 25.0
        private const val CORRIDOR_CHECK_INTERVAL = 5
    }
}
