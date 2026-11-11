package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath.horizontalDistance
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The ENGAGE movement of a tank that strafes (LAV-25, Bradley), inside the band where it would otherwise stand still (closing
 * in when far and backing off when close stay with the brain). One instance per brain. It drives in legs that run into one
 * another:
 *  - a strafing leg goes 25-50 degrees along the ring of 0.8-1.1 times `engageRange` around the target, the same way
 *    round for 2-4 legs and then maybe the other way; the point must be passable, not crowded and inside the leash,
 *    and the other way round is tried when this one has no such point. The leg is driven without braking
 *    (`rollOn`), and the next one is picked as soon as the tank is within [STRAFE_CHAIN_RADIUS] of the goal, so the tank
 *    never stops to plan; while the route to a new goal is still being searched it drives straight at it;
 *  - every 3-5 legs, or when the line of fire stays blocked for a moment, the leg is a hull-down one instead: to a
 *    spot where the target cannot see the body but the turret can see the target and the gun can be laid on it
 *    ([CoverAwareness.bestHullDown]). The tank parks there facing the target for 20-40 ticks, rocking back out of sight
 *    and forward again once ([tickRock]), then strafes on. With no such spot it strafes.
 * It drives at a cruise and the guns keep firing meanwhile (gunnery does not depend on the legs). The legs are dropped
 * when this branch was not in use for a moment (closing in, a cover dash, a flank) and on entering ENGAGE ([reset]).
 *
 * No leg ends on a perch (a rooftop or pinnacle, see [ElevationDetection.isPerch]).
 *
 * [facingTolerance] is the heading error (degrees) under which the hull counts as facing the target (tracks pivot, wheels creep).
 */
class StrafeManeuver(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val driver: GroundDriver,
    private val cover: CoverAwareness,
    private val moves: GroundMoves,
    private val facingTolerance: Float,
    private val isPerch: (Level, Vec3) -> Boolean
) {

    /** The point of the current leg, or null between legs. */
    private var strafeGoal: Vec3? = null
    private var strafeStart = 0

    /** The current leg ends at a hull-down spot, where the tank parks until [strafeHoldEnd] (0 = not arrived yet). */
    private var strafeHullDown = false
    private var strafeHoldEnd = 0

    /** The rock at a hull-down spot: [ROCK_PENDING] until [strafeRockAt], then backing to [strafeRockPoint], then returning, then done. */
    private var strafeRock = ROCK_DONE
    private var strafeRockAt = 0
    private var strafeRockStart = 0
    private var strafeRockPoint: Vec3? = null
    private var strafeRockHome: Vec3? = null

    /** Length (blocks) of the current leg when it was picked: the chain radius never exceeds a share of it. */
    private var strafeLegLength = 0.0

    /** Which way round the target the legs go: 1 or -1. */
    private var strafeSense = 1.0
    private var strafeLegsInSense = 0
    private var strafeFlipAfter = STRAFE_FLIP_MIN
    private var strafeLegsSinceHide = 0
    private var strafeHideAfter = STRAFE_HIDE_MIN
    private var strafeNextBlockedHide = 0
    private var strafeLastTick = Int.MIN_VALUE / 2

    /** The point of the current leg, or null between legs (other tanks keep clear of it). */
    val goal: Vec3?
        get() = strafeGoal

    /** True while the rock has the tank reversing out of sight: the main gun is held then if the line is blocked. */
    val backingOut: Boolean
        get() = strafeRock == ROCK_BACKING

    /** True during the back and the return of the rock, when the target being out of sight is no reason to give up the fight. */
    val rocking: Boolean
        get() = strafeRock == ROCK_BACKING || strafeRock == ROCK_RETURNING

    /** [blockedTicks]: how long the line of fire has been blocked (see the brain's `tickEngage`). */
    fun tick(level: Level, aim: Entity, range: Double, blockedTicks: Int) {
        val now = boss.tickCount
        val target = aim.position()
        if (now - strafeLastTick > STRAFE_STALE_TICKS) {
            boss.thought.event("strafe.tick") { "strafing resumes, old legs dropped" }
            strafeGoal = null
            strafeHullDown = false
            strafeHoldEnd = 0
            strafeRock = ROCK_DONE
        }
        strafeLastTick = now

        var goal = strafeGoal
        if (goal != null) {
            val distance = horizontalDistance(boss.position(), goal)
            val failed = now - strafeStart > STRAFE_LEG_TIMEOUT || driver.gaveUp || moves.planner.status == GroundRoutePlanner.Status.NO_ROUTE
            if (strafeHullDown && distance <= STRAFE_ARRIVE_RADIUS) {
                if (strafeHoldEnd == 0) {
                    strafeHoldEnd = now + boss.randomBetween(HULL_DOWN_HOLD_MIN, HULL_DOWN_HOLD_MAX)
                    strafeRock = if (settings.useCover.get()) ROCK_PENDING else ROCK_DONE
                    strafeRockAt = now + (strafeHoldEnd - now) * 2 / 5
                    boss.thought.event("strafe.hullDown") { "arrived at the hull-down spot, parking" + if (strafeRock == ROCK_PENDING) " (will rock once)" else "" }
                }
                if (tickRock(level, aim)) return
                if (now < strafeHoldEnd) {
                    // Parked behind cover: hull to the target, the turret keeps firing.
                    boss.thought.think("strafe.tick") { "parked hull-down, turret on ${aim.name.string}" }
                    if (driver.turnToward(target, facingTolerance)) driver.hold()
                    moves.look(null)
                    return
                }
                finishStrafeLeg()
                goal = null
            } else if (failed || (!strafeHullDown && distance <= min(STRAFE_CHAIN_RADIUS, strafeLegLength * STRAFE_CHAIN_FRACTION))) {
                finishStrafeLeg()
                goal = null
            }
        }

        if (goal == null) {
            var hullDown = false
            var next: Vec3? = null
            val hide = strafeLegsSinceHide >= strafeHideAfter || (blockedTicks > STRAFE_BLOCKED_TICKS && now >= strafeNextBlockedHide)
            if (hide) {
                strafeNextBlockedHide = now + STRAFE_BLOCKED_COOLDOWN
                strafeLegsSinceHide = 0
                strafeHideAfter = boss.randomBetween(STRAFE_HIDE_MIN, STRAFE_HIDE_MAX)
                next = cover.bestHullDown(level, aim, range, horizontalDistance(boss.position(), target))
                hullDown = next != null
                boss.thought.event("strafe.hullDown") {
                    if (hullDown) "going hull-down (${if (blockedTicks > STRAFE_BLOCKED_TICKS) "line blocked" else "scheduled"}) at ${next!!.x.toInt()},${next.z.toInt()}"
                    else "wanted a hull-down spot, found none"
                }
            }
            if (next == null) next = pickStrafePoint(level, target, range)
            if (next == null) {
                // Nowhere to go (boxed in): stand and face the target.
                boss.thought.think("strafe.tick") { "no strafing point available: standing and facing ${aim.name.string}" }
                if (driver.turnToward(target, facingTolerance)) driver.hold()
                return
            }
            strafeGoal = next
            strafeHullDown = hullDown
            strafeHoldEnd = 0
            strafeStart = now
            strafeLegLength = horizontalDistance(boss.position(), next)
            moves.newGoal()
            goal = next
            boss.thought.event("strafe.leg") {
                (if (hullDown) "hull-down leg" else "strafing leg ${if (strafeSense > 0) "anticlockwise" else "clockwise"}") +
                        " of ${strafeLegLength.toInt()} blocks"
            }
        }
        boss.thought.think("strafe.tick") { if (strafeHullDown) "driving to the hull-down spot" else "strafing round ${aim.name.string}" }
        // A leg that ends hull-down brakes and parks; every other leg rolls on into the next one.
        moves.goTo(level, goal, DriveMode.CRUISE, rollOn = !strafeHullDown)
        moves.look(null)
    }

    /**
     * The rock of a tank parked at a hull-down spot: once [strafeRockAt] has come it backs 3-6 blocks (the first distance
     * that puts the turret out of the target's sight, see [CoverAwareness.hideNear]) with its front still to the target,
     * then drives forward to where it was, and the parking goes on. It happens once per park. Without such a spot, with a
     * rear that cannot be reversed onto, or with `useCover` off, the tank stays put.
     * @return true while the rock has the wheel this tick
     */
    private fun tickRock(level: Level, aim: Entity): Boolean {
        val now = boss.tickCount
        when (strafeRock) {
            ROCK_PENDING -> {
                if (now < strafeRockAt) return false
                val position = boss.position()
                val back = if (moves.rearClear(level)) cover.hideNear(level, position, aim) else null
                if (back == null) {
                    boss.thought.event("strafe.rock") { "no way to rock back, staying put" }
                    strafeRock = ROCK_DONE
                    return false
                }
                boss.thought.event("strafe.rock") { "rocking back out of sight to ${back.x.toInt()},${back.z.toInt()}" }
                strafeRockPoint = back
                strafeRockHome = position
                strafeRockStart = now
                strafeRock = ROCK_BACKING
                moves.newGoal()
            }

            ROCK_BACKING -> {
                val point = strafeRockPoint
                if (point == null || driver.gaveUp) {
                    strafeRock = ROCK_DONE
                } else if (driver.reverseTo(point, aim.position(), arriveRadius = ROCK_ARRIVE_RADIUS) ||
                    now - strafeRockStart > ROCK_STAGE_TICKS
                ) {
                    strafeRock = ROCK_RETURNING
                    strafeRockStart = now
                    boss.thought.event("strafe.rock") { "out of sight, rolling forward again" }
                } else {
                    boss.thought.think("strafe.rock") { "reversing out of the target's sight" }
                }
            }

            ROCK_RETURNING -> {
                val home = strafeRockHome
                if (home == null || driver.gaveUp || horizontalDistance(boss.position(), home) <= ROCK_ARRIVE_RADIUS ||
                    now - strafeRockStart > ROCK_STAGE_TICKS
                ) {
                    boss.thought.event("strafe.rock") { "rock done" }
                    strafeRock = ROCK_DONE
                } else {
                    boss.thought.think("strafe.rock") { "returning to the hull-down spot" }
                    driver.drive(home, DriveMode.CRUISE)
                }
            }
        }
        val active = strafeRock == ROCK_BACKING || strafeRock == ROCK_RETURNING
        if (active) moves.look(null)
        return active
    }

    /** Ends the current leg: counts it, maybe turns the circling around, and drops the route. */
    private fun finishStrafeLeg() {
        if (!strafeHullDown) {
            strafeLegsSinceHide++
            strafeLegsInSense++
            if (strafeLegsInSense >= strafeFlipAfter) {
                strafeLegsInSense = 0
                strafeFlipAfter = boss.randomBetween(STRAFE_FLIP_MIN, STRAFE_FLIP_MAX)
                if (boss.getRandom().nextBoolean()) {
                    strafeSense = -strafeSense
                    boss.thought.event("strafe.leg") { "reversing the direction of the circle" }
                }
            }
        }
        strafeGoal = null
        strafeHullDown = false
        strafeHoldEnd = 0
        strafeRock = ROCK_DONE
        moves.newGoal()
    }

    /** Drops the legs and picks a fresh sense and fresh leg counts (on entering ENGAGE). */
    fun reset() {
        boss.thought.event("strafe.reset") { "new engagement, strafing legs reset" }
        strafeGoal = null
        strafeHullDown = false
        strafeHoldEnd = 0
        strafeRock = ROCK_DONE
        strafeLegsInSense = 0
        strafeLegsSinceHide = 0
        strafeSense = if (boss.getRandom().nextBoolean()) 1.0 else -1.0
        strafeFlipAfter = boss.randomBetween(STRAFE_FLIP_MIN, STRAFE_FLIP_MAX)
        strafeHideAfter = boss.randomBetween(STRAFE_HIDE_MIN, STRAFE_HIDE_MAX)
    }

    /**
     * The end of the next strafing leg: a point 25-50 degrees along the engage ring around [target], in the current sense
     * for the first half of the tries and the opposite one for the rest (which then becomes the sense), passable, not crowded
     * and inside the leash. Null when no try works.
     */
    private fun pickStrafePoint(level: Level, target: Vec3, range: Double): Vec3? {
        val position = boss.position()
        val bearing = FlightMath.horizontal(Vec3(position.x - target.x, 0.0, position.z - target.z), Vec3(1.0, 0.0, 0.0))
        val leash = settings.leashRange.get() + range
        repeat(STRAFE_ATTEMPTS) { i ->
            val sense = if (i < STRAFE_ATTEMPTS / 2) strafeSense else -strafeSense
            val degrees = STRAFE_ARC_MIN + boss.getRandom().nextInt(STRAFE_ARC_MAX - STRAFE_ARC_MIN + 1)
            val angle = Math.toRadians(degrees.toDouble()) * sense
            val rx = bearing.x * cos(angle) - bearing.z * sin(angle)
            val rz = bearing.x * sin(angle) + bearing.z * cos(angle)
            val radius = range * (STRAFE_RING_MIN + (STRAFE_RING_MAX - STRAFE_RING_MIN) * boss.getRandom().nextDouble())
            val point = Vec3(target.x + rx * radius, position.y, target.z + rz * radius)
            if (horizontalDistance(point, boss.homePos) <= leash && moves.passable(level, point) && !moves.crowded(level, point) &&
                !isPerch(level, point)
            ) {
                if (sense != strafeSense) {
                    strafeSense = sense
                    strafeLegsInSense = 0
                }
                return point
            }
        }
        return null
    }

    private companion object {
        /** Degrees along the engage ring one strafing leg covers. */
        const val STRAFE_ARC_MIN = 25
        const val STRAFE_ARC_MAX = 50

        /** The ring a leg ends on, as a fraction of the engage range. */
        const val STRAFE_RING_MIN = 0.8
        const val STRAFE_RING_MAX = 1.1
        const val STRAFE_ATTEMPTS = 6
        const val STRAFE_ARRIVE_RADIUS = 5.0
        const val STRAFE_LEG_TIMEOUT = 300

        /** Ticks away from the strafing branch after which the old leg is dropped. */
        const val STRAFE_STALE_TICKS = 10

        /** Legs in one direction before the sense may flip (inclusive range). */
        const val STRAFE_FLIP_MIN = 2
        const val STRAFE_FLIP_MAX = 4

        /** Strafing legs between two hull-down legs (inclusive range). */
        const val STRAFE_HIDE_MIN = 3
        const val STRAFE_HIDE_MAX = 5

        /** A line of fire blocked for this many ticks asks for a hull-down leg (at most once per cooldown). */
        const val STRAFE_BLOCKED_TICKS = 15
        const val STRAFE_BLOCKED_COOLDOWN = 200
        /** Ticks a tank parks hull-down (including its rock), inclusive range. */
        const val HULL_DOWN_HOLD_MIN = 20
        const val HULL_DOWN_HOLD_MAX = 40

        /** The next strafing leg is picked this close to the goal of the current one, at most this share of the leg's length. */
        const val STRAFE_CHAIN_RADIUS = 8.0
        const val STRAFE_CHAIN_FRACTION = 0.4

        // The rock at a hull-down spot (see tickRock)
        const val ROCK_PENDING = 0
        const val ROCK_BACKING = 1
        const val ROCK_RETURNING = 2
        const val ROCK_DONE = 3
        const val ROCK_STAGE_TICKS = 40
        const val ROCK_ARRIVE_RADIUS = 1.5
    }
}
