package com.teamuhi.superbenemies.entity.boss.common.ground

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** Where a [HullSweep] ran into something: how far along the sweep, and the block column of the first blocked lane. */
class SweepHit(val distance: Double, val x: Int, val z: Int)

/**
 * A block-exact look ahead for a ground hull. [GroundTerrain] judges 4 block cells from five sampled columns, which a
 * lone pillar, a fence post, a barricade or a wall one block thick slips through; this sweeps the real footprint of the
 * hull (one lane of block columns per block of width) along a line and reports the first column the hull could not
 * enter.
 *
 * A lane keeps its own standing height and moves from column to column the way a tracked hull does: it steps up onto a
 * surface no higher than [GroundProfile.upStep] above where it stands (collision shapes count, so a slab is half a block
 * and a fence 1.5), steps down at most [dropOf] blocks, and needs [CLEARANCE] free blocks above the surface (an overpass
 * is not a wall, a house wall is). A column is blocked when no such surface exists, on a hazard ([GroundTerrain.isHazard],
 * lava), on water for a hull that sinks, and in an unloaded chunk (never loaded from here, `getChunkNow`). Leaves are
 * transparent to a hull that crushes foliage; logs are not. Server thread only.
 */
object HullSweep {

    /** Free blocks the hull needs above its standing surface. */
    const val CLEARANCE = 3

    /** Blocks added to each side of the hull's half width: the tracks and the swing of a turn. */
    private const val LANE_MARGIN = 0.5

    /** Distance between two samples along the line, in blocks. */
    private const val STEP = 1.0

    private const val EPSILON = 0.01

    /** The deepest drop a lane follows: the planner's own cliff limit. */
    fun dropOf(profile: GroundProfile): Int = ceil(profile.upStep * 2.0).toInt()

    /** Number of lanes across a hull of [profile]: one a block, at least three. */
    private fun lanesOf(profile: GroundProfile): Int = max(3, ceil((profile.halfWidth + LANE_MARGIN) * 2.0).toInt() + 1)

    /**
     * Sweeps the hull from ([ax], [az]) toward ([bx], [bz]) (the standing plane is [ref] at the start) and returns the first
     * blocked column, or null when the line is clear. The hull's centre is on the line; the lanes are spread across it.
     *
     * @param from distance along the line at which the check begins (skip the ground under the hull itself)
     * @param to distance along the line at which it ends; at most the length of the line
     */
    fun sweep(
        level: Level,
        ax: Double,
        az: Double,
        ref: Double,
        bx: Double,
        bz: Double,
        profile: GroundProfile,
        from: Double = 0.0,
        to: Double = Double.MAX_VALUE
    ): SweepHit? {
        val dx = bx - ax
        val dz = bz - az
        val length = sqrt(dx * dx + dz * dz)
        if (length < 1.0e-6) return null
        return sweepDir(level, ax, az, ref, dx / length, dz / length, from, to.coerceAtMost(length), profile)
    }

    /** Same as [sweep] along the unit direction ([ux], [uz]) for the distances [from]..[to]. */
    fun sweepDir(
        level: Level,
        ax: Double,
        az: Double,
        ref: Double,
        ux: Double,
        uz: Double,
        from: Double,
        to: Double,
        profile: GroundProfile
    ): SweepHit? {
        val lanes = lanesOf(profile)
        val half = profile.halfWidth + LANE_MARGIN
        // Left-hand normal of the direction; each lane is an offset along it.
        val nx = -uz
        val nz = ux
        val heights = DoubleArray(lanes) { ref }
        val pos = BlockPos.MutableBlockPos()
        var d = from
        while (true) {
            for (lane in 0 until lanes) {
                val offset = -half + 2.0 * half * lane / (lanes - 1)
                val x = Mth.floor(ax + ux * d + nx * offset)
                val z = Mth.floor(az + uz * d + nz * offset)
                val surface = surfaceAt(level, x, z, heights[lane], profile, pos)
                if (surface.isNaN()) return SweepHit(d, x, z)
                heights[lane] = surface
            }
            if (d >= to) return null
            d = (d + STEP).coerceAtMost(to)
        }
    }

    /**
     * The standing surface of block column ([x], [z]) for a lane that now stands at [ref]: the highest top of a collision
     * shape the hull can step onto with [CLEARANCE] free blocks above it, or NaN when the column cannot be entered.
     */
    private fun surfaceAt(level: Level, x: Int, z: Int, ref: Double, profile: GroundProfile, pos: BlockPos.MutableBlockPos): Double {
        val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return Double.NaN
        val minY = level.minBuildHeight
        val maxY = level.maxBuildHeight - 1
        val top = floor(ref + profile.upStep).toInt().coerceAtMost(maxY)
        val bottom = floor(ref - dropOf(profile)).toInt().coerceAtLeast(minY)
        var wet = false
        for (y in top downTo bottom) {
            pos.set(x, y, z)
            val state = chunk.getBlockState(pos)
            val fluid = state.fluidState
            if (fluid.`is`(FluidTags.LAVA) || GroundTerrain.isHazard(state)) return Double.NaN
            if (!fluid.isEmpty) wet = true
            if (passesThrough(chunk, pos, state, profile)) continue
            val shape = state.getCollisionShape(chunk, pos)
            val surface = y + shape.max(Direction.Axis.Y)
            if (surface > ref + profile.upStep + EPSILON) continue
            if (!headroom(chunk, pos, x, y, z, maxY, profile)) continue
            // A hull that sinks may not wade; one that floats does not mind (the planner prices the water).
            if (wet && !profile.floats) return Double.NaN
            return surface
        }
        // No ground in reach: open water holds a floating hull up, anything else is a hole, a cliff or a wall.
        return if (wet && profile.floats) ref else Double.NaN
    }

    /** True when the [CLEARANCE] blocks over the block at ([x], [y], [z]) have nothing the hull would hit. */
    private fun headroom(chunk: ChunkAccess, pos: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int, maxY: Int, profile: GroundProfile): Boolean {
        for (k in 1..CLEARANCE) {
            if (y + k > maxY) return true
            pos.set(x, y + k, z)
            val state = chunk.getBlockState(pos)
            if (GroundTerrain.isHazard(state)) return false
            if (!passesThrough(chunk, pos, state, profile)) return false
        }
        return true
    }

    /** True for a block the hull drives through: no collision at all, or leaves for a hull that crushes foliage. */
    private fun passesThrough(chunk: ChunkAccess, pos: BlockPos, state: BlockState, profile: GroundProfile): Boolean {
        if (state.isAir) return true
        if (profile.crushesFoliage && state.`is`(BlockTags.LEAVES)) return true
        return state.getCollisionShape(chunk, pos).isEmpty
    }
}
