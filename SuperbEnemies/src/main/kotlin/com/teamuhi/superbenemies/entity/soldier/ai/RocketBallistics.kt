package com.teamuhi.superbenemies.entity.soldier.ai

import com.teamuhi.superbenemies.config.RpgRocket
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The firing solution of the RPG soldier: the launch angle and bearing that make a Superb Warfare RPG rocket arrive at a
 * (moving) target, and whether the rocket's arc is free of terrain.
 *
 * The flight is reproduced tick by tick the way `FastThrowableProjectile` and the RPG rocket entities do it (verified in the
 * source), with the rocket launched from the eyes at `Velocity` blocks per tick:
 *  1. `tickCount` goes up, the rocket sweeps `position .. position + motion` for hits;
 *  2. `projectileMove`: `position += motion`, then `motion.y -= gravity`;
 *  3. the rocket entity's own `tick`: from tick 3 on, `motion *= 1.03` (the rocket accelerates).
 * The simulation only needs two dimensions (distance along the bearing and height), because nothing pushes sideways.
 *
 * [solve] bisects the *low* arc: the elevation (between [MIN_ELEVATION] and [MAX_ELEVATION] degrees) at which the simulated
 * rocket is as high as the target when it has covered the target's horizontal distance. A moving target is led: the time of
 * flight of the first solution moves the aim point along the target's velocity, and the solution is recomputed twice.
 * Null when the target is out of reach within the rocket's [MAX_FLIGHT_TICKS] (the RPG's `ProjectileLife`).
 */
internal object RocketBallistics {

    /** A firing solution. [yaw] and [pitch] are Minecraft rotations in degrees (pitch positive down). */
    class Solution(
        val yaw: Float,
        val pitch: Float,
        /** Unit launch direction. */
        val direction: Vec3,
        /** Elevation above the horizontal in radians (the negative of [pitch]). */
        val elevation: Double,
        /** Ticks until the rocket reaches the aim point's distance (fractional). */
        val flightTicks: Double,
        /** The point aimed at, with the lead included. */
        val aim: Vec3
    )

    /**
     * The solution that sends a [rocket] from [from] to [target] (the hitbox centre), leading by [targetVelocity] (blocks per
     * tick, vertical part ignored) when [lead] is set. Null when the target is on top of the shooter or out of reach.
     */
    fun solve(from: Vec3, target: Vec3, targetVelocity: Vec3, rocket: RpgRocket, lead: Boolean): Solution? {
        var solution = solveFor(from, target, rocket) ?: return null
        if (!lead) return solution
        repeat(LEAD_PASSES) {
            val ahead = (solution.flightTicks + LEAD_EXTRA_TICKS).coerceAtMost(MAX_LEAD_TICKS)
            val aim = target.add(targetVelocity.x * ahead, 0.0, targetVelocity.z * ahead)
            solution = solveFor(from, aim, rocket) ?: return null
        }
        return solution
    }

    private fun solveFor(from: Vec3, aim: Vec3, rocket: RpgRocket): Solution? {
        val dx = aim.x - from.x
        val dz = aim.z - from.z
        val range = hypot(dx, dz)
        if (range < MIN_RANGE) return null
        val rise = aim.y - from.y

        val out = DoubleArray(2)
        var low = Math.toRadians(MIN_ELEVATION)
        var high = Math.toRadians(MAX_ELEVATION)
        // The target must lie between the flattest and the steepest arc.
        if (!crossing(range, low, rocket, out) || out[0] > rise) return null
        if (!crossing(range, high, rocket, out) || out[0] < rise) return null
        repeat(BISECTIONS) {
            val mid = (low + high) * 0.5
            if (crossing(range, mid, rocket, out) && out[0] < rise) low = mid else high = mid
        }
        val elevation = (low + high) * 0.5
        if (!crossing(range, elevation, rocket, out)) return null

        val yaw = (atan2(dz, dx) * RAD_TO_DEG).toFloat() - 90f
        val horizontal = cos(elevation)
        val direction = Vec3(dx / range * horizontal, sin(elevation), dz / range * horizontal)
        return Solution(yaw, (-Math.toDegrees(elevation)).toFloat(), direction, elevation, out[1], aim)
    }

    /**
     * Flies the rocket at [elevation] until it has covered [range] blocks along the bearing. Writes the height reached
     * (relative to the launch point) to `out[0]` and the fractional tick count to `out[1]`. False when it does not get that
     * far within [MAX_FLIGHT_TICKS].
     */
    private fun crossing(range: Double, elevation: Double, rocket: RpgRocket, out: DoubleArray): Boolean {
        var vh = rocket.velocity * cos(elevation)
        var vy = rocket.velocity * sin(elevation)
        var x = 0.0
        var y = 0.0
        for (tick in 1..MAX_FLIGHT_TICKS) {
            val nextX = x + vh
            val nextY = y + vy
            if (nextX >= range) {
                val part = (range - x) / (nextX - x)
                out[0] = y + (nextY - y) * part
                out[1] = tick - 1 + part
                return true
            }
            x = nextX
            y = nextY
            vy -= rocket.gravity
            if (tick > 2) {
                vh *= ACCELERATION
                vy *= ACCELERATION
            }
        }
        return false
    }

    /**
     * Whether the rocket flying [solution] reaches [target] without hitting terrain first. The simulated path is clipped in
     * pieces of [CLIP_TICKS] ticks (a handful of raycasts for the ranges an RPG soldier fires at, fluids count as blocking
     * like they do for the rocket). A block hit only counts as clear when it is within [END_MARGIN] blocks of the target's
     * hitbox: the last stretch of the arc may graze the ground right beside the hull.
     */
    fun pathClear(level: Level, shooter: Entity, from: Vec3, solution: Solution, rocket: RpgRocket, target: Entity): Boolean {
        val direction = solution.direction
        val horizontal = hypot(direction.x, direction.z)
        if (horizontal < 1.0e-6) return false
        val bearingX = direction.x / horizontal
        val bearingZ = direction.z / horizontal
        val range = hypot(solution.aim.x - from.x, solution.aim.z - from.z)
        val box = target.boundingBox.inflate(END_MARGIN)

        var vh = rocket.velocity * cos(solution.elevation)
        var vy = rocket.velocity * sin(solution.elevation)
        var x = 0.0
        var y = 0.0
        var previous = from
        val lastTick = ceil(solution.flightTicks).toInt().coerceIn(1, MAX_FLIGHT_TICKS)
        for (tick in 1..lastTick) {
            var nextX = x + vh
            var nextY = y + vy
            val arrived = nextX >= range
            if (arrived) { // stop exactly at the aim point's distance
                val part = (range - x) / (nextX - x)
                nextY = y + (nextY - y) * part
                nextX = range
            }
            if (arrived || tick % CLIP_TICKS == 0) {
                val point = Vec3(from.x + bearingX * nextX, from.y + nextY, from.z + bearingZ * nextX)
                val hit = level.clip(ClipContext(previous, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, shooter))
                if (hit.type == HitResult.Type.BLOCK && !box.contains(hit.location)) return false
                previous = point
            }
            if (arrived) break
            x = nextX
            y = nextY
            vy -= rocket.gravity
            if (tick > 2) {
                vh *= ACCELERATION
                vy *= ACCELERATION
            }
        }
        return true
    }

    /** The rocket's `motion *= 1.03` from its third tick on. */
    private const val ACCELERATION = 1.03

    /** The RPG's `ProjectileLife`: the rocket explodes on its own after this many ticks. */
    private const val MAX_FLIGHT_TICKS = 100

    /** Elevation limits of the low arc, degrees. */
    private const val MIN_ELEVATION = -30.0
    private const val MAX_ELEVATION = 45.0
    private const val BISECTIONS = 22

    /** Targets closer than this (blocks) horizontally have no bearing. */
    private const val MIN_RANGE = 1.0

    /** The lead is solved again this many times; each pass refines the time of flight. */
    private const val LEAD_PASSES = 2

    /** The tank keeps rolling while the rocket is on its way and the soldier takes a moment to fire. */
    private const val LEAD_EXTRA_TICKS = 3.0
    private const val MAX_LEAD_TICKS = 40.0

    private const val CLIP_TICKS = 4
    private const val END_MARGIN = 3.0
    private const val RAD_TO_DEG = 57.29577951308232
}
