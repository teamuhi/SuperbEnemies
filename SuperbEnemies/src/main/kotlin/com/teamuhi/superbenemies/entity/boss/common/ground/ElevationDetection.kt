package com.teamuhi.superbenemies.entity.boss.common.ground

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath.horizontalDistance
import com.teamuhi.superbenemies.entity.boss.common.ai.isAirType
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Elevation awareness of a tank boss: a tank gun only works between its depression and elevation limits ([TankWeapon]), so a
 * tank on a rooftop cannot lay it on a target in the street below, and the ground model gives it no way to drive off (it
 * forbids every step down taller than the hull climbs). One instance per brain; server thread only.
 *
 *  - [layStatus]: can the gun be laid on the aim from where the tank stands (see [TankGunnery.layStatus]). Aircraft are skipped:
 *    the targeting already filters them with [TankGunnery.canReach].
 *  - [standOffSpot]: a ground spot, a few blocks out on a ring around the target, from which the gun *can* be laid and the line
 *    is clear. The way out of a "target too far below" or "too far above" situation that needs no climbing.
 *  - [isPerch]: a cheap test of a point (is it a high place whose surroundings fall away?), so goal pickers can leave rooftops
 *    and pinnacles out. [perched] is the strict version for the tank itself: it really cannot drive off normally. A hilltop with
 *    slopes is no perch.
 *  - [descentGoal]: the nearest lower ground the tank should drive to when it is perched (see the brain's descent override).
 *
 * Every answer that costs samples is cached (the perch of a cell for [PERCH_CACHE_TICKS], the tank's own state and the stand-off
 * spot and descent goal for [CACHE_TICKS]) and the flood fill is capped at [FLOOD_CAP] cells, so a tank costs next to nothing on
 * open ground. Chunks are never loaded (see [GroundTerrain]).
 *
 * @param routeProfile the profile the planner routes with (the brain's, which follows `crossWater`)
 */
class ElevationDetection(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val gunnery: TankGunnery,
    private val moves: GroundMoves,
    private val routeProfile: () -> GroundProfile
) {

    private class StandOff(val aimId: Int, val target: Vec3, val status: LayStatus, val spot: Vec3?, val until: Int)

    private class PerchEntry(val perch: Boolean, val until: Int)

    private class Flood(val closed: Boolean, val lowerEdge: Boolean)

    private var standOff: StandOff? = null

    /** The perch answer per route cell (cell key), for goal pickers that ask for many spots in a row. */
    private val perchCache = HashMap<Long, PerchEntry>()

    private var perchedValue = false
    private var perchedUntil = Int.MIN_VALUE / 2
    private var perchedAt = Vec3.ZERO

    private var descentValue: Vec3? = null
    private var descentUntil = Int.MIN_VALUE / 2
    private var descentAt = Vec3.ZERO

    /** The stand-off spot found last (null when none), for other tanks to keep clear of; stale once the target moved on. */
    val spot: Vec3?
        get() = standOff?.spot

    // ================================================================================================== lay status

    /**
     * Whether the main gun (or, with it off or empty, the first enabled barrel gun) can be laid on [aim] from where the tank
     * stands. [LayStatus.OK] with no aim, for a wreck, for an aircraft (see the class comment), with no gun enabled, and for a
     * target less than [FLAT_GROUND_HEIGHT] blocks below the turret: a tank on level ground that is too near its target has
     * its ordinary back-off for that, and running to a stand-off ring would be an overreaction.
     */
    fun layStatus(aim: Entity?): LayStatus {
        if (aim == null || boss.isWreck || boss.crippled) return LayStatus.OK
        val root = aim.rootVehicle
        if (root is VehicleEntity && isAirType(root.vehicleType)) {
            boss.thought.think("elevation.lay") { "aircraft target: the gun's elevation is not checked here" }
            return LayStatus.OK
        }
        val eye = TankSight.eyeOf(boss)
        val centre = aim.boundingBox.center
        val raw = gunnery.layStatus(eye, centre)
        val status = if (raw == LayStatus.TOO_LOW && eye.y - centre.y < FLAT_GROUND_HEIGHT) LayStatus.OK else raw
        boss.thought.think("elevation.lay") {
            val name = aim.name.string
            when (status) {
                LayStatus.OK -> "the gun can be laid on $name"
                LayStatus.TOO_LOW -> "gun cannot depress onto $name"
                LayStatus.TOO_HIGH -> "gun cannot elevate onto $name"
            }
        }
        return status
    }

    // ================================================================================================== stand-off

    /**
     * A ground spot from which the gun can be laid on [aim] although it cannot from here ([status] is the answer of
     * [layStatus], not OK): the horizontal run needed is `|eye height - target height| / tan(limit - 2 degrees)` for the limit
     * that is in the way, and the spot is looked for on a ring of [STANDOFF_RADII] around the target starting at that run
     * (never further than the engage band's `ADVANCE_START` times the engage range), within [STANDOFF_SIDE] degrees either side
     * of the tank's own bearing. A spot has to be passable, no perch ([isPerch]), not crowded, inside the leash, laid-on-able
     * (the lay check at the spot's own turret height) and have a clear line to the target. The lowest one wins (closest to
     * the target's own height), then the shortest drive.
     *
     * The answer, a spot or "none", is kept for [CACHE_TICKS] ticks and thrown away once the target moved [STANDOFF_TARGET_MOVE]
     * blocks, changed, or the status turned the other way. Null as well for status OK or with no gun to lay.
     */
    fun standOffSpot(level: Level, aim: Entity, status: LayStatus): Vec3? {
        if (status == LayStatus.OK) return null
        val gun = gunnery.layGun() ?: return null
        val now = boss.tickCount
        val target = aim.position()
        val cached = standOff
        if (cached != null && cached.aimId == aim.id && cached.status == status && cached.until - now in 0..CACHE_TICKS &&
            horizontalDistance(cached.target, target) <= STANDOFF_TARGET_MOVE
        ) return cached.spot

        val spot = findStandOff(level, aim, status, gun)
        standOff = StandOff(aim.id, target, status, spot, now + CACHE_TICKS)
        boss.thought.event("elevation.standOff") {
            if (spot != null) "stand-off spot for ${aim.name.string} at ${spot.x.toInt()},${spot.y.toInt()},${spot.z.toInt()} (${status.name})"
            else "no stand-off spot for ${aim.name.string} (${status.name})"
        }
        return spot
    }

    private fun findStandOff(level: Level, aim: Entity, status: LayStatus, gun: TankWeapon): Vec3? {
        val route = routeProfile()
        val position = boss.position()
        val target = aim.position()
        val centre = aim.boundingBox.center
        val eyeHeight = boss.bbHeight * EYE_FACTOR
        val range = settings.engageRange.get().toDouble()
        val cap = range * EngageBand.ADVANCE_START

        val limit = (if (status == LayStatus.TOO_LOW) gun.maxDepression else gun.maxElevation) - STANDOFF_SLACK
        if (limit < 1.0) return null
        val run = abs(position.y + eyeHeight - centre.y) / tan(Math.toRadians(limit))
        val first = max(run, STANDOFF_MIN_RADIUS)
        if (first > cap) return null

        val bearing = FlightMath.horizontal(Vec3(position.x - target.x, 0.0, position.z - target.z), Vec3(1.0, 0.0, 0.0))
        var best: Vec3? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (extra in STANDOFF_RADII) {
            val radius = first + extra
            if (radius > cap) continue
            for (side in STANDOFF_SIDES) {
                val angle = Math.toRadians(side)
                val x = target.x + (bearing.x * cos(angle) - bearing.z * sin(angle)) * radius
                val z = target.z + (bearing.x * sin(angle) + bearing.z * cos(angle)) * radius
                if (horizontalDistance(Vec3(x, 0.0, z), boss.homePos) > settings.leashRange.get() + range) continue
                val cell = GroundTerrain.sample(level, GroundTerrain.cellOf(x, route), GroundTerrain.cellOf(z, route), route)
                if (!GroundTerrain.passable(cell, route)) continue
                val spot = Vec3(x, cell.floorY.toDouble(), z)
                if (isPerch(level, spot) || moves.crowded(level, spot)) continue
                val spotEye = spot.add(0.0, eyeHeight, 0.0)
                if (!gunnery.canLay(spotEye, centre, gun, STANDOFF_LAY_MARGIN)) continue
                if (TankSight.solidBlocks(level, spotEye, centre, 0) != 0) continue
                val score = -abs(spot.y - centre.y) - horizontalDistance(spot, position) * STANDOFF_DRIVE_WEIGHT
                if (score > bestScore) {
                    bestScore = score
                    best = spot
                }
            }
        }
        return best
    }

    // ================================================================================================== perches

    /**
     * Whether [point] is a perch: a high place whose surroundings fall away. Eight cells on a ring 6-8 blocks out are sampled,
     * and the point is a perch when at least [PERCH_MIN_LOW] of those that are loaded have a standing height of at most
     * [PERCH_HEIGHT] blocks below it. Cheap (the answer is cached per cell for [PERCH_CACHE_TICKS] ticks) and false in an
     * unloaded chunk, so goal pickers can call it for every candidate. A natural hilltop that slopes away on all sides counts
     * too; the tank itself is only [perched] when it also cannot drive off.
     */
    fun isPerch(level: Level, point: Vec3): Boolean {
        val route = routeProfile()
        val cx = GroundTerrain.cellOf(point.x, route)
        val cz = GroundTerrain.cellOf(point.z, route)
        val key = cellKey(cx, cz)
        val now = boss.tickCount
        perchCache[key]?.let { if (it.until - now in 0..PERCH_CACHE_TICKS) return it.perch }
        val result = ringIsLow(level, route, GroundTerrain.centerOf(cx, route), GroundTerrain.centerOf(cz, route), null)
        if (perchCache.size >= PERCH_CACHE_MAX) perchCache.clear()
        perchCache[key] = PerchEntry(result, now + PERCH_CACHE_TICKS)
        return result
    }

    /** The perch ring test around the world point ([x], [z]); [floorHint] (blocks) raises the base height of the centre. */
    private fun ringIsLow(level: Level, route: GroundProfile, x: Double, z: Double, floorHint: Int?): Boolean {
        val centre = GroundTerrain.sample(level, GroundTerrain.cellOf(x, route), GroundTerrain.cellOf(z, route), route)
        if (!centre.loaded) return false
        val base = max(centre.floorY, floorHint ?: Int.MIN_VALUE)
        var low = 0
        for (i in 0 until PERCH_SAMPLES) {
            val angle = Math.PI * 2.0 * i / PERCH_SAMPLES
            val radius = if (i % 2 == 0) PERCH_RING_NEAR else PERCH_RING_FAR
            val cell = GroundTerrain.sample(
                level, GroundTerrain.cellOf(x + cos(angle) * radius, route), GroundTerrain.cellOf(z + sin(angle) * radius, route), route
            )
            if (cell.loaded && cell.floorY <= base - PERCH_HEIGHT) low++
        }
        return low >= PERCH_MIN_LOW
    }

    /**
     * True when the tank is stuck on a perch: the cells it can drive to from here, with the normal profile, run out before
     * [FLOOD_CAP] cells (a flood fill on [GroundTerrain.stepCost], so the way off is closed for real; a hilltop with slopes
     * leads on and is no perch) **and** the ground at the edge of that island is [PERCH_HEIGHT] blocks or more below it (the
     * [isPerch] ring at the tank, or the cells just past the edge of the island; this second look is what finds a tank in the
     * middle of a big roof, whose ring is all roof). Kept for [CACHE_TICKS] ticks, or until the tank has moved
     * [PERCHED_MOVE] blocks.
     */
    fun perched(level: Level): Boolean {
        val now = boss.tickCount
        val position = boss.position()
        if (perchedUntil - now in 0..CACHE_TICKS && horizontalDistance(position, perchedAt) <= PERCHED_MOVE) return perchedValue
        val route = routeProfile()
        val hint = Mth.floor(position.y + FLOOR_TOLERANCE)
        val flood = flood(level, route, position, hint)
        val value = flood.closed && (flood.lowerEdge || ringIsLow(level, route, position.x, position.z, hint))
        if (value != perchedValue) {
            boss.thought.event("elevation.perched") {
                if (value) "on a perch: every way off is closed and the ground falls away" else "no longer on a perch"
            }
        }
        boss.thought.think("elevation.perched") { if (value) "perched" else "on ground it can drive off" }
        perchedValue = value
        perchedAt = position
        perchedUntil = now + CACHE_TICKS
        return value
    }

    /**
     * Flood fills the route cells from the one under [position] over steps [GroundTerrain.stepCost] allows. [Flood.closed] when
     * it ends before [FLOOD_CAP] cells; [Flood.lowerEdge] when, on the edge of what it reached, a cell it could not enter (or
     * the one beyond it) has a standing height of [PERCH_HEIGHT] blocks below [floorHint] or more.
     */
    private fun flood(level: Level, route: GroundProfile, position: Vec3, floorHint: Int): Flood {
        val cells = HashMap<Long, GroundCell>()
        fun cellAt(cx: Int, cz: Int): GroundCell = cells.getOrPut(cellKey(cx, cz)) { GroundTerrain.sample(level, cx, cz, route) }

        val startX = GroundTerrain.cellOf(position.x, route)
        val startZ = GroundTerrain.cellOf(position.z, route)
        val visited = HashSet<Long>()
        val queue = ArrayDeque<GroundCell>()
        visited.add(cellKey(startX, startZ))
        queue.add(cellAt(startX, startZ))
        var lower = false
        while (queue.isNotEmpty()) {
            val here = queue.removeFirst()
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val nx = here.cx + dx
                val nz = here.cz + dz
                if (cellKey(nx, nz) in visited) continue
                val there = cellAt(nx, nz)
                // No cutting the corner of something impassable, as in the planner.
                val corner = dx != 0 && dz != 0 &&
                        (!GroundTerrain.passable(cellAt(here.cx + dx, here.cz), route) || !GroundTerrain.passable(cellAt(here.cx, here.cz + dz), route))
                if (corner || GroundTerrain.stepCost(level, here, there, route) == GroundTerrain.IMPASSABLE) {
                    if (!lower && (isLower(there, floorHint) || isLower(cellAt(here.cx + dx * 2, here.cz + dz * 2), floorHint))) lower = true
                    continue
                }
                visited.add(cellKey(nx, nz))
                if (visited.size >= FLOOD_CAP) return Flood(closed = false, lowerEdge = lower)
                queue.add(there)
            }
        }
        return Flood(closed = true, lowerEdge = lower)
    }

    private fun isLower(cell: GroundCell, floorHint: Int): Boolean = cell.loaded && cell.floorY <= floorHint - PERCH_HEIGHT

    // ================================================================================================== descent

    /**
     * Where a perched tank drives to: the nearest passable, dry, non-perch cell within [DESCENT_RADIUS] blocks whose standing
     * height is at least [PERCH_HEIGHT] below the tank. Found with rings of cells like [GroundTerrain.nearestBank]; the nearest
     * ring that has one wins. Kept for [CACHE_TICKS] ticks (or until the tank has moved [PERCHED_MOVE] blocks), the answer
     * "none" included. Null when there is no such cell (nothing lower in reach, or its chunks are not loaded).
     */
    fun descentGoal(level: Level): Vec3? {
        val now = boss.tickCount
        val position = boss.position()
        if (descentUntil - now in 0..CACHE_TICKS && horizontalDistance(position, descentAt) <= PERCHED_MOVE) return descentValue
        val route = routeProfile()
        val goal = findDescent(level, route, position)
        descentValue = goal
        descentAt = position
        descentUntil = now + CACHE_TICKS
        boss.thought.event("elevation.descent") {
            if (goal != null) "lower ground to drive to at ${goal.x.toInt()},${goal.y.toInt()},${goal.z.toInt()}" else "no lower ground within reach"
        }
        return goal
    }

    private fun findDescent(level: Level, route: GroundProfile, position: Vec3): Vec3? {
        val cx = GroundTerrain.cellOf(position.x, route)
        val cz = GroundTerrain.cellOf(position.z, route)
        val base = Mth.floor(position.y + FLOOR_TOLERANCE)
        val maxCells = (DESCENT_RADIUS / route.cellSize).toInt()
        for (radius in 1..maxCells) {
            var best: Vec3? = null
            var bestDistance = Double.MAX_VALUE
            for (dx in -radius..radius) for (dz in -radius..radius) {
                if (max(abs(dx), abs(dz)) != radius) continue
                val cell = GroundTerrain.sample(level, cx + dx, cz + dz, route)
                if (!GroundTerrain.passable(cell, route) || cell.waterDepth > 0 || cell.foliage) continue
                if (cell.floorY > base - PERCH_HEIGHT) continue
                val x = GroundTerrain.centerOf(cell.cx, route)
                val z = GroundTerrain.centerOf(cell.cz, route)
                val distance = (x - position.x) * (x - position.x) + (z - position.z) * (z - position.z)
                if (distance >= bestDistance) continue
                val spot = Vec3(x, cell.floorY.toDouble(), z)
                if (isPerch(level, spot)) continue
                bestDistance = distance
                best = spot
            }
            if (best != null) return best
        }
        return null
    }

    // ================================================================================================== helpers

    private fun cellKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) xor (cz.toLong() and 0xFFFFFFFFL)

    companion object {
        /** Blocks lower than a place its surroundings have to be for it to be a perch, and for ground to count as "below" it. */
        const val PERCH_HEIGHT = 4

        /** A target less than this many blocks below the turret is level ground as far as [layStatus] is concerned. */
        private const val FLAT_GROUND_HEIGHT = 3.0

        /** Fraction of the hull height at which the turret eye sits (see [TankSight.eyeOf]). */
        private const val EYE_FACTOR = 0.9

        /** Blocks a standing hull's y may sit above the ground's block height. */
        private const val FLOOR_TOLERANCE = 0.25

        /** Ticks the tank's own perch state, the stand-off spot and the descent goal are kept. */
        private const val CACHE_TICKS = 40

        /** Blocks the tank may move before its cached perch state and descent goal are thrown away. */
        private const val PERCHED_MOVE = 6.0

        // Stand-off ring
        private const val STANDOFF_SLACK = 2.0
        private const val STANDOFF_LAY_MARGIN = 1.0
        private const val STANDOFF_MIN_RADIUS = 8.0
        private const val STANDOFF_TARGET_MOVE = 8.0
        private const val STANDOFF_DRIVE_WEIGHT = 0.05
        private val STANDOFF_RADII = doubleArrayOf(0.0, 5.0, 10.0)

        /** Degrees off the tank's own bearing from the target at which ring spots are tried (within +-70). */
        private val STANDOFF_SIDES = doubleArrayOf(-70.0, -35.0, 0.0, 35.0, 70.0)

        // Perch test
        private const val PERCH_SAMPLES = 8
        private const val PERCH_MIN_LOW = 6
        private const val PERCH_RING_NEAR = 6.0
        private const val PERCH_RING_FAR = 8.0
        private const val PERCH_CACHE_TICKS = 100
        private const val PERCH_CACHE_MAX = 512

        /** Most cells the flood fill from the tank looks at: an island this size (about 1000 blocks) or bigger is open ground. */
        private const val FLOOD_CAP = 64

        /** How far (blocks) the descent goal may be from the tank. */
        private const val DESCENT_RADIUS = 40.0
    }
}
