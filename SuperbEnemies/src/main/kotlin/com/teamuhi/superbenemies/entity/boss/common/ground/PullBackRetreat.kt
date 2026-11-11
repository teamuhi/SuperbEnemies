package com.teamuhi.superbenemies.entity.boss.common.ground

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.ai.isAirType
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.max

/**
 * Pull-back retreat: what every ground boss does when it has just taken a large share of its health in a few seconds. It
 * stops trading blows where it stands and drives to cover, holds there and then goes back to whatever it was doing (the
 * brain keeps the interrupted state, see `TankState.PULL_BACK`). One instance per brain.
 *
 * **Trigger.** On every new hit that counts as an attack (friendly fire does not), when the damage taken within
 * [DAMAGE_WINDOW] ticks ([TankBossEntity.damageTaken]) reaches `pullBackDamagePercent` of the maximum health (0 = off).
 * After a run starts the next one waits [COOLDOWN] ticks; when no cover is found it is tried again after [RETRY] ticks.
 *
 * **Where to.** The threat is the attacker's vehicle, else the target the guns are on while it is in sight, else a marker
 * at the estimated origin of the hit. The shelter depends on what it is:
 *  - [PullBackThreat.GROUND] (tanks, infantry, anything on the ground): a spot behind a wall, a building or a ridge that
 *    hides the whole hull from the attacker ([CoverAwareness.bestHide]: the scanned spots first, random samples when the
 *    scan has nothing);
 *  - [PullBackThreat.AIR] (an aircraft the guns cannot elevate to): the same search measured against the plane, in a band
 *    wide enough for one overhead, a spot where terrain or a structure is between the hull and the plane. A roof does
 *    not count: the ground model treats one as ground, so a tank never plans a route under it. With nothing found it
 *    breaks away instead (smoke and a hard turn across the plane's bearing, [Hooks.breakAway]).
 * The run is a sprint under a smoke salvo. At the spot the tank turns its front to the threat and holds [HOLD_TICKS] ticks;
 * a run that takes more than [TIMEOUT] ticks or in which the driver gives up ends as well. The guns keep working throughout.
 *
 * **Light air hits.** An aircraft the guns cannot reach that hurts the boss without the damage reaching the threshold keeps
 * the older reaction (`smokeAgainstAir`): smoke and a break across its bearing, at most once per [AIR_BREAK_COOLDOWN] ticks.
 *
 * [facingTolerance] is the heading error (degrees) under which the hull counts as facing the threat (tracks pivot, wheels creep).
 * [isPerch] tells a rooftop or pinnacle (see [ElevationDetection.isPerch]): the shelter is never one.
 * All state is per instance; server thread only.
 */
class PullBackRetreat(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val gunnery: TankGunnery,
    private val targeting: TankTargeting,
    private val cover: CoverAwareness,
    private val driver: GroundDriver,
    private val facingTolerance: Float,
    private val isPerch: (Level, Vec3) -> Boolean,
    private val hooks: Hooks
) {

    /** What the brain provides: driving along its planner, smoke, the evade state and its debug log. */
    interface Hooks {
        /** Sprints along a planned route to [goal]; true once the route has been completed. */
        fun sprintTo(level: Level, goal: Vec3): Boolean

        /** Pops a smoke salvo (rate-limited by the brain); true if the command was given. */
        fun popSmoke(): Boolean

        /** Smoke and a hard turn across the bearing to [from] (the brain's EVADE state). */
        fun breakAway(from: Vec3)

        fun debug(message: String, vararg args: Any?)
    }

    /** The cover spot of the run in progress, or null when none is on (also what other tanks keep clear of). */
    var spot: Vec3? = null
        private set

    var threat = PullBackThreat.GROUND
        private set

    private var threatEntity: Entity? = null
    private var threatPos = Vec3.ZERO
    private var startedAt = 0
    private var arrivedAt = 0
    private var handledHit = -1
    private var nextRunAt = 0
    private var nextAirBreakAt = 0

    // ================================================================================================== trigger

    /**
     * Looks at the latest hit (once per hit) and starts a run to cover when it was heavy. Call every tick while the tank is
     * in a state that can be interrupted. Light air hits and air hits with no shelter are answered right here through
     * [Hooks.breakAway].
     *
     * @param aim what the guns are on, [aimHidden] whether it is out of sight; [attacker] the entity credited with the last hit
     * @return true when a run to cover has begun: the brain switches to PULL_BACK and calls [tick] from then on
     */
    fun assess(level: Level, aim: Entity?, aimHidden: Boolean, attacker: Entity?): Boolean {
        val hit = boss.lastDamageTick()
        val now = boss.tickCount
        if (hit < 0 || hit == handledHit || now - hit > HIT_WINDOW) return false
        handledHit = hit

        val root = attacker?.takeIf { it.isAlive && !it.isRemoved }?.rootVehicle
        val air = root is VehicleEntity && isAirType(root.vehicleType) && !gunnery.canReach(root, 0.0)
        if (!heavyHit()) {
            boss.thought.think("pullback.assess") { "hit, but not hard enough for a pull-back" }
            if (air && settings.smokeEnabled.get() && settings.smokeAgainstAir.get()) breakAwayFrom(root!!, now)
            return false
        }
        if (now < nextRunAt) {
            boss.thought.think("pullback.assess") { "hit hard, but a pull-back is cooling down" }
            return false
        }

        val source = root ?: aim?.takeIf { !aimHidden } ?: boss.lastHitOrigin?.let { targeting.lastKnown.spot(it) }
        if (source == null) {
            nextRunAt = now + RETRY
            boss.thought.event("pullback.assess") { "hit hard, but no idea where from: no pull-back" }
            return false
        }
        val distance = FlightMath.horizontalDistance(boss.position(), source.position())
        val range = if (air) max(distance, AIR_MIN_BAND) else settings.engageRange.get().toDouble()
        // The cover search leaves perches out already; this is the last look at the spot it hands back.
        val pick = cover.bestHide(level, source, range, distance)?.takeIf { !isPerch(level, it) }
        if (pick == null) {
            nextRunAt = now + RETRY
            boss.thought.event("pullback.assess") { "hit hard by ${if (air) "an aircraft" else "the ground"}, no cover found" }
            if (air) breakAwayFrom(source, now)
            return false
        }

        boss.thought.event("pullback.assess") {
            "hit hard by ${if (air) "an aircraft" else "the ground"} (${source.name.string}), pulling back to ${pick.x.toInt()},${pick.z.toInt()}"
        }
        spot = pick
        threat = if (air) PullBackThreat.AIR else PullBackThreat.GROUND
        threatEntity = source
        threatPos = source.position()
        startedAt = now
        arrivedAt = 0
        nextRunAt = now + COOLDOWN
        hooks.popSmoke()
        return true
    }

    /** True when the damage of the last [DAMAGE_WINDOW] ticks reaches `pullBackDamagePercent` of the maximum health. */
    private fun heavyHit(): Boolean {
        val percent = settings.pullBackDamagePercent.get()
        if (percent <= 0) return false
        return boss.damageTaken(DAMAGE_WINDOW) >= boss.getMaxHealth() * percent / 100f
    }

    private fun breakAwayFrom(source: Entity, now: Int) {
        if (now < nextAirBreakAt) return
        nextAirBreakAt = now + AIR_BREAK_COOLDOWN
        boss.thought.event("pullback.breakAway") { "breaking away across the bearing to ${source.name.string}" }
        hooks.breakAway(source.position())
    }

    // ================================================================================================== run

    /**
     * Drives to the spot, then faces the threat and waits. @return true when the run is over (held long enough, took too
     * long, or the driver gave up); the brain then calls [end] and resumes the interrupted state
     */
    fun tick(level: Level): Boolean {
        val target = spot ?: return true
        val now = boss.tickCount
        threatEntity?.takeIf { it.isAlive && !it.isRemoved }?.let { threatPos = it.position() }

        val arrived = FlightMath.horizontalDistance(boss.position(), target) <= ARRIVE_RADIUS
        if (arrived && arrivedAt == 0) arrivedAt = now
        if ((arrivedAt != 0 && now - arrivedAt > HOLD_TICKS) || now - startedAt > TIMEOUT || driver.gaveUp) {
            boss.thought.event("pullback.tick") {
                if (arrivedAt != 0 && now - arrivedAt > HOLD_TICKS) "pull-back over: held the cover long enough"
                else if (driver.gaveUp) "pull-back over: stuck" else "pull-back over: took too long"
            }
            return true
        }

        if (!arrived) {
            boss.thought.think("pullback.tick") { "sprinting to cover under smoke" }
            hooks.sprintTo(level, target)
        } else {
            val facing = driver.turnToward(threatPos, facingTolerance)
            boss.thought.think("pullback.tick") { if (facing) "holding the cover, front to the threat" else "at the cover, turning the front to the threat" }
            if (facing) driver.hold()
        }
        return false
    }

    /** Ends the run in progress (finished, or interrupted by an evade); the cooldown and the handled hit stay. */
    fun end() {
        spot = null
        threatEntity = null
    }

    private companion object {
        /** Ticks the damage is summed over, and how old the hit may be when it is looked at. */
        const val DAMAGE_WINDOW = 60
        const val HIT_WINDOW = 40

        /** Ticks between two runs, and before a run without cover is tried again. */
        const val COOLDOWN = 160
        const val RETRY = 40

        const val TIMEOUT = 400
        const val HOLD_TICKS = 80
        const val ARRIVE_RADIUS = 4.0

        /** Smallest ring (blocks) the shelter from an aircraft is searched in: it is often overhead, so its distance means little. */
        const val AIR_MIN_BAND = 40.0
        const val AIR_BREAK_COOLDOWN = 160
    }
}

/** What a heavy hit came from, which decides where the tank looks for shelter. */
enum class PullBackThreat { GROUND, AIR }
