package com.teamuhi.superbenemies.entity.boss.common.air

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.debug.AiThought
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import net.minecraft.util.Mth
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Look-ahead terrain scan. Fans [RAY_COUNT] rays (+-[FAN_HALF_ANGLE] degrees) along the flight path, reads the
 * `MOTION_BLOCKING` heightmap every [STEP] blocks up to `speed * 60` blocks ahead and returns the lowest altitude
 * that is safe to fly at: the highest terrain on any ray plus a margin of `12 + speed * 3` (small enough for the 50 block cruise height).
 *
 * It complements the obstacle avoidance built into SBW's `aircraftLoiter`, which only runs while orbiting; the brain
 * consults this guard in every state.
 *
 * Cost: at most 7 rays * ~16 samples of heightmap reads, once every [SCAN_INTERVAL] ticks per boss; the result is cached in
 * between (the first call scans at once). Unloaded chunks are assumed to
 * be at a caller-supplied altitude. Heights are clamped to the world's minimum build height (End / void).
 */
class TerrainGuard(private val boss: VehicleEntity) {

    private val thought: AiThought? get() = (boss as? BossVehicle)?.thought

    private var cachedFloor = Double.NEGATIVE_INFINITY
    private var nextScanTick = 0

    private var memoTick = -1
    private var memoX = 0
    private var memoZ = 0
    private var memoChunk: LevelChunk? = null

    /**
     * Terrain top at [x],[z] (the first free block above the highest motion-blocking block), clamped to the minimum
     * build height, or [unknown] when the chunk is not loaded.
     */
    fun terrainAt(x: Double, z: Double, unknown: Double): Double {
        val level = boss.level()
        val bx = Mth.floor(x)
        val bz = Mth.floor(z)
        val chunk = chunkAt(bx shr 4, bz shr 4) ?: return unknown
        // Same value as Level.getHeight (first free block above the highest motion-blocking block), minus the second chunk lookup.
        return max(chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx and 15, bz and 15) + 1, level.minBuildHeight).toDouble()
    }

    /**
     * Loaded chunk at [cx],[cz] or null. Never loads or generates a chunk. The last lookup is remembered for the rest
     * of the tick: consecutive samples along a ray mostly fall into the same chunk.
     */
    private fun chunkAt(cx: Int, cz: Int): LevelChunk? {
        val now = boss.tickCount
        if (now == memoTick && cx == memoX && cz == memoZ) return memoChunk
        val chunk = boss.level().chunkSource.getChunkNow(cx, cz)
        memoTick = now
        memoX = cx
        memoZ = cz
        memoChunk = chunk
        return chunk
    }

    /**
     * Lowest safe altitude along the current flight path (cached, rescanned every [SCAN_INTERVAL] ticks).
     *
     * @param unknownY altitude assumed for unloaded chunks (everything past the nearest player's render distance); the
     *   A-10 passes a point just below the target so unseen terrain neither forces pull-ups nor blocks dive corridors
     */
    fun floorAhead(unknownY: Double = boss.y + 40.0): Double {
        val now = boss.tickCount
        if (now >= nextScanTick) {
            cachedFloor = scan(unknownY)
            nextScanTick = now + SCAN_INTERVAL
        }
        return cachedFloor
    }

    /**
     * Raises the pitch of [dir] when the plane is closer to [floorAhead] than [COMFORT_CLEARANCE] blocks, so that it
     * clears the terrain. A direction that already climbs steeply enough is returned unchanged.
     * The horizontal heading is kept.
     */
    fun adjustDesired(dir: Vec3, unknownY: Double = boss.y + 40.0): Vec3 {
        val clearance = boss.y - floorAhead(unknownY)
        if (clearance >= COMFORT_CLEARANCE) {
            thought?.think("guard.adjustDesired") { "terrain pull-up inactive: ground clear" }
            return dir
        }

        // 0 deg at the edge of the comfort zone, 25 deg at the floor, 50 deg when 80 blocks below it.
        // (COMFORT_CLEARANCE sits just below the clearance the 50 block cruise leaves over flat ground.)
        val required = if (clearance >= 0.0) {
            25.0 * (1.0 - clearance / COMFORT_CLEARANCE)
        } else {
            25.0 + 25.0 * min(-clearance / 80.0, 1.0)
        }
        if (FlightMath.elevation(dir) >= required) {
            thought?.think("guard.adjustDesired") { "terrain close, climb already steep enough" }
            return dir
        }
        thought?.think("guard.adjustDesired") {
            "terrain pull-up active: ${if (clearance < 0.0) "below the safe altitude" else "inside the comfort zone"}"
        }
        return FlightMath.tilted(FlightMath.horizontal(dir, FlightMath.dir(boss.yRot, 0f)), required)
    }

    /**
     * Smallest gap (blocks) between a planned dive and the terrain under it. The path runs straight from [from] down to
     * [to], never lower than [minAltitude] (where the pull-out bottoms out), then continues level and climbs at
     * [RUN_OUT_SLOPE] for [RUN_OUT_DISTANCE] blocks past [to]. Three parallel lines ([CORRIDOR_HALF_WIDTH] apart) cover
     * the wings. A negative result means the plane would hit a ridge. Unloaded chunks count as [unknownY].
     */
    fun diveClearance(from: Vec3, to: Vec3, minAltitude: Double, unknownY: Double): Double {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val length = hypot(dx, dz)
        if (length < 1.0) return Double.MAX_VALUE
        val ux = dx / length
        val uz = dz / length
        var worst = Double.MAX_VALUE
        var d = DIVE_STEP
        while (d <= length + RUN_OUT_DISTANCE) {
            val y = if (d <= length) max(from.y + (to.y - from.y) * (d / length), minAltitude)
            else minAltitude + (d - length) * RUN_OUT_SLOPE
            for (side in -1..1) {
                val x = from.x + ux * d - uz * side * CORRIDOR_HALF_WIDTH
                val z = from.z + uz * d + ux * side * CORRIDOR_HALF_WIDTH
                worst = min(worst, y - terrainAt(x, z, unknownY))
            }
            d += DIVE_STEP
        }
        return worst
    }

    private fun scan(unknownY: Double): Double {
        val level = boss.level()
        val velocity = boss.deltaMovement
        val speed = velocity.length()
        val horizontalSpeed = hypot(velocity.x, velocity.z)
        val heading = if (horizontalSpeed > 0.1) {
            Vec3(velocity.x / horizontalSpeed, 0.0, velocity.z / horizontalSpeed)
        } else {
            FlightMath.dir(boss.yRot, 0f).let { FlightMath.horizontal(it, Vec3(0.0, 0.0, 1.0)) }
        }

        val lookAhead = (speed * LOOK_AHEAD_TICKS).coerceIn(MIN_LOOK_AHEAD, MAX_LOOK_AHEAD)
        val steps = max(1, (lookAhead / STEP).toInt())

        var highest = terrainAt(boss.x, boss.z, unknownY)
        val half = (RAY_COUNT - 1) / 2.0
        for (ray in 0 until RAY_COUNT) {
            val offsetDegrees = (ray - half) * (FAN_HALF_ANGLE / half)
            val dir = heading.yRot(Math.toRadians(-offsetDegrees).toFloat())
            for (i in 1..steps) {
                val d = i * STEP
                highest = max(highest, terrainAt(boss.x + dir.x * d, boss.z + dir.z * d, unknownY))
            }
        }

        // Never demand more than the build limit allows.
        return min(highest + BASE_MARGIN + speed * SPEED_MARGIN, level.maxBuildHeight + 80.0)
    }

    private companion object {
        const val SCAN_INTERVAL = 5
        const val RAY_COUNT = 7
        const val FAN_HALF_ANGLE = 15.0
        const val STEP = 16.0
        const val LOOK_AHEAD_TICKS = 35.0
        const val MIN_LOOK_AHEAD = 48.0
        const val MAX_LOOK_AHEAD = 160.0
        const val BASE_MARGIN = 8.0
        const val SPEED_MARGIN = 1.5
        const val COMFORT_CLEARANCE = 24.0

        const val DIVE_STEP = 12.0
        const val CORRIDOR_HALF_WIDTH = 10.0
        const val RUN_OUT_DISTANCE = 160.0
        const val RUN_OUT_SLOPE = 0.5 // about 27 degrees: the pull-out commands 40, the exit test needs 20
    }
}
