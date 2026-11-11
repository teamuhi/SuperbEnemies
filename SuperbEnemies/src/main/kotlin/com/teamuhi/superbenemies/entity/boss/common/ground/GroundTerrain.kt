package com.teamuhi.superbenemies.entity.boss.common.ground

import com.atsuishio.superbwarfare.block.DragonTeethBlock
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What a ground boss can drive over. One per boss type; the numbers come from its SBW vehicle data and its engine.
 *
 * @param upStep the vehicle's `UpStep`: the tallest ledge it climbs (LAV-25 1.5, M1A2 2.25)
 * @param floats true when it floats on water (the LAV-25); false when it sinks (the M1A2), so deep water stops it
 * @param crushesFoliage true when it drives through leaves and trees at little cost (the M1A2, collision level 4)
 * @param maxSlope the largest height change per block of ground it climbs along a route
 * @param cellSize edge of a route-finding cell in blocks; keep it a divisor of 16 so a cell never straddles a chunk
 * @param halfWidth half the hull's width in blocks: a hull wider than a cell also needs the blocks around the cell clear of trunks
 * @param halfLength half the hull's length in blocks (the hitbox is square, the hull is not): the footprint the tanks keep
 *   from each other with (see `CrowdSeparation.separate`)
 * @param maxDrop the tallest drop (blocks) a route may step down; 0 for every normal profile, where a step down is held to
 *   the same limit as a step up. Only the profile of the descent planner (see [withDrop]) sets it: a tank stuck on a rooftop
 *   has to drive off the edge, which no ordinary route may do
 */
class GroundProfile(
    val upStep: Double,
    val floats: Boolean,
    val crushesFoliage: Boolean,
    val maxSlope: Double = 0.75,
    val cellSize: Int = 4,
    val halfWidth: Double = 1.5,
    val halfLength: Double = halfWidth,
    val maxDrop: Double = 0.0
) {
    /** Largest height difference allowed between the centres of two neighbouring cells. */
    val maxCellStep: Double get() = cellSize * maxSlope

    /** The same hull, allowed to step down up to [drop] blocks in a route (see [maxDrop]). */
    fun withDrop(drop: Double): GroundProfile =
        GroundProfile(upStep, floats, crushesFoliage, maxSlope, cellSize, halfWidth, halfLength, drop)

    /** Blocks around a cell that must also be free of trunks: what the hull sticks out past the cell. */
    val margin: Int get() = max(0, Math.ceil(halfWidth - cellSize / 2.0).toInt())

    companion object {
        /** A middling wheeled vehicle that does not float; used when a boss gives no profile. */
        val DEFAULT = GroundProfile(1.5, floats = false, crushesFoliage = false)
    }
}

/**
 * One sampled route-finding cell of [GroundTerrain.sample].
 *
 * @param loaded false when the chunk is not loaded (or the column is void): the cell is then never passable
 * @param floorY the y of the standing plane at the cell centre (first free block above the ground, water ignored)
 * @param topY the y a vehicle sits at: [floorY], or the water surface when a vehicle would float there (water 2+ deep)
 * @param range largest height difference between the centre and the four corners of the cell: a cliff inside the cell
 * @param waterDepth blocks of water above the ground at the centre (0 on dry land)
 * @param foliage leaves or logs on the cell
 * @param hazard lava, fire, magma, powder snow, cactus or SBW dragon's teeth on the cell, or a tree trunk (or, for a hull
 *   that does not flatten foliage, a leaf block at ground level) anywhere on the cell and the margin the hull needs
 */
class GroundCell(
    val cx: Int,
    val cz: Int,
    val loaded: Boolean,
    val floorY: Int,
    val topY: Int,
    val range: Int,
    val waterDepth: Int,
    val foliage: Boolean,
    val hazard: Boolean
)

/** Result of [GroundTerrain.checkSpawn]. */
enum class SpawnVerdict { OK, UNLOADED, OBSTRUCTED, FLUID, LAVA }

/**
 * Terrain knowledge for the ground bosses: what a cell of the world costs to cross for a given [GroundProfile]. It only
 * reads the surface heightmap and a few blocks per column and **never loads or generates a chunk** (`getChunkNow`); an
 * unloaded cell is impassable, so a route never leads into the unknown. Server thread only.
 *
 * The surface of a column is the `MOTION_BLOCKING_NO_LEAVES` height, with fluid and logs / leaves peeled off to find the
 * ground below. A roof counts as ground (the tank "climbs" the house), which the cliff rules then forbid.
 *
 * Impassable cell: unloaded, hazard (lava, fire, magma, powder snow, cactus, dragon's teeth), a cliff inside the cell
 * higher than twice [GroundProfile.upStep] (or [GroundProfile.maxDrop], if more), or any water at all (the deepest of the cell's five sampled columns) unless the vehicle floats. Between two cells the
 * height step must stay within [GroundProfile.maxCellStep] and, at the midpoint between their centres, within the
 * vehicle's climb. Costs: slope adds up to 1.5, shallow water x1.5, deep water (floating) x3, trees x4 (x1.5 when the
 * vehicle crushes foliage).
 */
object GroundTerrain {

    /** Cost of a step that cannot be made. */
    const val IMPASSABLE = Double.POSITIVE_INFINITY

    /** Extra cost (cell lengths) per block of a drop beyond what a normal step makes, see [GroundProfile.maxDrop]. */
    private const val DROP_COST = 0.5

    /** Water at least this deep (blocks) lifts a floating vehicle onto the surface; a vehicle that sinks may not enter any. */
    private const val DEEP_WATER = 2

    /** How far down a column is peeled through fluid, logs and leaves before the ground is assumed. */
    private const val MAX_PEEL = 48

    // Scratch output of readColumn; server thread only, read straight after each call.
    private var colFloor = 0
    private var colTop = 0
    private var colDepth = 0
    private var colFoliage = false
    private var colHazard = false
    private var colCanopy = false

    // ================================================================================================== cells

    /** The cell coordinate holding the world coordinate [v] (x or z). */
    fun cellOf(v: Double, profile: GroundProfile): Int = Math.floorDiv(Mth.floor(v), profile.cellSize)

    /** World x or z of the centre of cell coordinate [c]. */
    fun centerOf(c: Int, profile: GroundProfile): Double = c * profile.cellSize + profile.cellSize / 2.0

    /** The block column whose samples represent cell coordinate [c] (its centre block). */
    private fun centerBlock(c: Int, profile: GroundProfile): Int = c * profile.cellSize + profile.cellSize / 2

    /** Samples cell ([cx], [cz]): the centre column plus the four corners for the cliff test. */
    fun sample(level: Level, cx: Int, cz: Int, profile: GroundProfile): GroundCell {
        val size = profile.cellSize
        val mx = centerBlock(cx, profile)
        val mz = centerBlock(cz, profile)
        if (!readColumn(level, mx, mz)) return GroundCell(cx, cz, false, 0, 0, 0, 0, false, false)
        val floor = colFloor
        val top = colTop
        val depth = colDepth
        var foliage = colFoliage
        var hazard = colHazard

        var low = top
        var high = top
        var canopy = colCanopy
        // The deepest water of the five columns: a hull that sinks may not touch any, and one that floats pays for it.
        var wettest = depth
        val x0 = cx * size
        val z0 = cz * size
        for (corner in 0 until 4) {
            val x = if (corner and 1 == 0) x0 else x0 + size - 1
            val z = if (corner and 2 == 0) z0 else z0 + size - 1
            if (!readColumn(level, x, z)) return GroundCell(cx, cz, false, 0, 0, 0, 0, false, false)
            low = min(low, colTop)
            high = max(high, colTop)
            wettest = max(wettest, colDepth)
            canopy = canopy || colCanopy
            foliage = foliage || colFoliage
            hazard = hazard || colHazard
        }
        // Trunks are too thin for five samples to find in a forest: only where leaves hang overhead, every column is checked.
        if (canopy && !hazard && hasObstacle(level, x0 - profile.margin, z0 - profile.margin, x0 + size - 1 + profile.margin, z0 + size - 1 + profile.margin, profile)) {
            hazard = true
        }
        return GroundCell(cx, cz, true, floor, top, high - low, wettest, foliage, hazard)
    }

    /**
     * Whether a log tops any column of the block rectangle, or (for a hull that does not flatten foliage) a leaf block sits
     * on the ground of one. Unloaded columns count as free here: the cell's own columns already decided that.
     */
    private fun hasObstacle(level: Level, minX: Int, minZ: Int, maxX: Int, maxZ: Int, profile: GroundProfile): Boolean {
        val pos = BlockPos.MutableBlockPos()
        for (x in minX..maxX) for (z in minZ..maxZ) {
            val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: continue
            val top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15)
            if (top <= level.minBuildHeight) continue
            if (chunk.getBlockState(pos.set(x, top - 1, z)).`is`(BlockTags.LOGS)) return true
            if (!profile.crushesFoliage && chunk.getBlockState(pos.set(x, top, z)).`is`(BlockTags.LEAVES)) return true
        }
        return false
    }

    /** True when [cell] can be stood on or driven through by a vehicle of [profile], ignoring how it is reached. */
    fun passable(cell: GroundCell, profile: GroundProfile): Boolean {
        if (!cell.loaded || cell.hazard) return false
        // A profile that may drop (the descent planner) drives through the cell of a roof edge, which holds the cliff itself.
        if (cell.range > max(profile.upStep * 2.0, profile.maxDrop)) return false
        return cell.waterDepth == 0 || profile.floats
    }

    /** True when the cell containing [pos] is [passable]. */
    fun passable(level: Level, pos: Vec3, profile: GroundProfile): Boolean =
        passable(sample(level, cellOf(pos.x, profile), cellOf(pos.z, profile), profile), profile)

    /**
     * Cost of driving from cell [from] into the neighbouring cell [to] (in cell lengths, so 1 for a flat, dry, open
     * orthogonal step and about 1.41 diagonally), or [IMPASSABLE]. Diagonal steps that cut a corner are the caller's job.
     */
    fun stepCost(level: Level, from: GroundCell, to: GroundCell, profile: GroundProfile): Double {
        if (!passable(to, profile)) return IMPASSABLE
        val rise = to.topY - from.topY
        val step = abs(rise)
        // Going down may be allowed further than going up (a profile with maxDrop, the descent from a rooftop).
        val downLimit = max(profile.maxCellStep, profile.maxDrop)
        if (if (rise < 0) step > downLimit else step > profile.maxCellStep) return IMPASSABLE

        // A wall between the two centres: the ground at the midpoint may not rise or fall more than the vehicle climbs.
        // Only the falling parts of a drop-capable profile are held to its maxDrop instead.
        val midX = (centerBlock(from.cx, profile) + centerBlock(to.cx, profile)) shr 1
        val midZ = (centerBlock(from.cz, profile) + centerBlock(to.cz, profile)) shr 1
        if (readColumn(level, midX, midZ)) {
            val limit = min(profile.upStep, profile.cellSize / 2.0 * profile.maxSlope) + 0.01
            val fallLimit = max(limit, profile.maxDrop)
            val first = colTop - from.topY
            val second = to.topY - colTop
            if (first > limit || first < -fallLimit || second > limit || second < -fallLimit) return IMPASSABLE
        } else {
            return IMPASSABLE
        }

        val diagonal = from.cx != to.cx && from.cz != to.cz
        var factor = 1.0 + 1.5 * min(step.toDouble(), profile.maxCellStep) / profile.maxCellStep
        // A drop that no ordinary step could make is only taken when there is no other way down.
        if (profile.maxDrop > 0.0 && -rise > profile.maxCellStep) factor += DROP_COST * -rise
        if (to.waterDepth >= DEEP_WATER) factor *= 3.0 else if (to.waterDepth == 1) factor *= 1.5
        if (to.foliage) factor *= if (profile.crushesFoliage) 1.5 else 4.0
        return (if (diagonal) 1.4142 else 1.0) * factor
    }

    // ================================================================================================== spawning

    /**
     * The y a boss of [profile] should be placed at in the block column ([x], [z]): the first free block above the ground,
     * or the water surface for a vehicle that floats. Null when the chunk is not loaded.
     */
    fun spawnY(level: Level, x: Int, z: Int, profile: GroundProfile): Double? {
        if (!readColumn(level, x, z)) return null
        return if (profile.floats) colTop.toDouble() else colFloor.toDouble()
    }

    /**
     * Whether a boss whose hull is [halfWidth] wide each way and [height] tall can be placed with its feet at ([x], [y],
     * [z]): the chunks are loaded, no lava or hazard (reported as [SpawnVerdict.LAVA]) is in or just under the box, no fluid
     * unless the vehicle floats, and nothing collides.
     */
    fun checkSpawn(level: Level, x: Double, y: Double, z: Double, halfWidth: Double, height: Double, profile: GroundProfile): SpawnVerdict {
        val box = AABB(x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth)
        val minX = Mth.floor(box.minX)
        val maxX = Mth.floor(box.maxX)
        val minZ = Mth.floor(box.minZ)
        val maxZ = Mth.floor(box.maxZ)
        if (!level.hasChunk(minX shr 4, minZ shr 4) || !level.hasChunk(maxX shr 4, maxZ shr 4) ||
            !level.hasChunk(minX shr 4, maxZ shr 4) || !level.hasChunk(maxX shr 4, minZ shr 4)
        ) return SpawnVerdict.UNLOADED

        var water = false
        val pos = BlockPos.MutableBlockPos()
        for (bx in minX..maxX) for (bz in minZ..maxZ) for (by in (Mth.floor(box.minY) - 1)..Mth.floor(box.maxY)) {
            pos.set(bx, by, bz)
            val state = level.getBlockState(pos)
            val fluid = state.fluidState
            if (fluid.`is`(FluidTags.LAVA) || isHazard(state)) return SpawnVerdict.LAVA
            if (!fluid.isEmpty && by >= Mth.floor(box.minY)) water = true
        }
        if (water && !profile.floats) return SpawnVerdict.FLUID
        return if (level.noCollision(box)) SpawnVerdict.OK else SpawnVerdict.OBSTRUCTED
    }

    // ================================================================================================== water exit

    /**
     * The centre of the nearest dry cell a vehicle in water at [from] can drive onto: passable, no water, and no higher
     * than the vehicle can climb above the waterline. Searches rings of cells out to [maxCells]; null when there is none.
     */
    fun nearestBank(level: Level, from: Vec3, profile: GroundProfile, maxCells: Int = 12): Vec3? {
        val cx = cellOf(from.x, profile)
        val cz = cellOf(from.z, profile)
        for (radius in 1..maxCells) {
            var best: Vec3? = null
            var bestDistance = Double.MAX_VALUE
            for (dx in -radius..radius) for (dz in -radius..radius) {
                if (max(abs(dx), abs(dz)) != radius) continue
                val cell = sample(level, cx + dx, cz + dz, profile)
                if (!passable(cell, profile) || cell.waterDepth > 0 || cell.foliage) continue
                if (cell.floorY > from.y + profile.upStep + 1.0) continue
                val x = centerOf(cell.cx, profile)
                val z = centerOf(cell.cz, profile)
                val distance = (x - from.x) * (x - from.x) + (z - from.z) * (z - from.z)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = Vec3(x, cell.floorY.toDouble(), z)
                }
            }
            if (best != null) return best
        }
        return null
    }

    // ================================================================================================== columns

    /**
     * Reads the column ([x], [z]) into the scratch fields: the ground below any fluid, logs and leaves on top of it.
     * Returns false for an unloaded chunk or a void column.
     */
    private fun readColumn(level: Level, x: Int, z: Int): Boolean {
        val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return false
        var y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15)
        val minY = level.minBuildHeight
        if (y < minY) return false
        colCanopy = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x and 15, z and 15) > y

        val pos = BlockPos.MutableBlockPos()
        var depth = 0
        var foliage = false
        var hazard = false
        var peeled = 0
        while (y > minY && peeled < MAX_PEEL) {
            pos.set(x, y, z)
            val state = chunk.getBlockState(pos)
            val fluid = state.fluidState
            val tree = state.`is`(BlockTags.LOGS) || state.`is`(BlockTags.LEAVES)
            if (fluid.isEmpty && !tree) break
            if (fluid.`is`(FluidTags.LAVA)) hazard = true
            if (!fluid.isEmpty) depth++ else foliage = true
            y--
            peeled++
        }
        pos.set(x, y, z)
        if (isHazard(chunk.getBlockState(pos))) hazard = true
        // The block at feet height can be fire, which has no collision and so is invisible to the heightmap.
        pos.set(x, y + 1, z)
        if (isHazard(chunk.getBlockState(pos))) hazard = true

        colFloor = y + 1
        colDepth = depth
        colTop = if (depth >= DEEP_WATER) colFloor + depth else colFloor
        colFoliage = foliage
        colHazard = hazard
        return true
    }

    /** Fire, magma, powder snow, cactus or SBW dragon's teeth: blocks a hull must not drive onto. */
    fun isHazard(state: BlockState): Boolean =
        state.`is`(BlockTags.FIRE) || state.`is`(Blocks.MAGMA_BLOCK) || state.`is`(Blocks.POWDER_SNOW) ||
            state.`is`(Blocks.CACTUS) || state.block is DragonTeethBlock
}
