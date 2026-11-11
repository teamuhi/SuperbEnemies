package com.teamuhi.superbenemies.entity.boss.common.ai

import com.teamuhi.superbenemies.SuperbEnemies
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3

/**
 * Whether a target can be seen from above, which is all the `DYNAMIC` targeting method (see `TargetingMethod`) and the
 * A-10's "mobs only under open sky" rule care about.
 *
 * An entity is exposed when no block in the column from its head up to the sky [blocksSight]. A block blocks sight when
 * it has collision and is not in the [SEE_THROUGH] tag: stone, slabs, stairs, doors and trapdoors cover; air, water,
 * lava, grass, flowers, torches and cobwebs have no collision so they never do. The tag
 * (`data/superbenemies/tags/blocks/boss_see_through.json`: glass, leaves, fences, carpets, iron bars, chains,
 * scaffolding) can be extended or overridden by a datapack, for blocks from other mods too.
 *
 * Smoke ([SmokeScreen]) also hides: an entity standing in a smoke cloud is not exposed, and a sight line through one fails
 * [hasLineOfSight]. Only the `DYNAMIC` method asks these questions (see [LastKnownLocation]), so `STATIC` bosses ignore smoke.
 *
 * Chunks are read with `getChunkNow`, so this never loads or generates terrain; an unloaded column counts as not exposed.
 */
object SkyExposure {

    val SEE_THROUGH: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation(SuperbEnemies.MOD_ID, "boss_see_through"))

    /** True when [entity] can be seen from above: it is under [isUnderOpenSky] and not standing in a smoke cloud. */
    fun isExposed(entity: Entity): Boolean = !SmokeScreen.inSmoke(entity) && isUnderOpenSky(entity)

    /**
     * True when nothing covers [entity] from above (blocks only; smoke does not count). Fast path: the `WORLD_SURFACE`
     * heightmap (every non-air block) says where the highest block of the column is; at or above it nothing can cover
     * the entity. Otherwise only the blocks between its head and that height are scanned, and the first one that blocks
     * sight ends the scan.
     */
    fun isUnderOpenSky(entity: Entity): Boolean {
        val box = entity.boundingBox
        val x = Mth.floor((box.minX + box.maxX) * 0.5)
        val z = Mth.floor((box.minZ + box.maxZ) * 0.5)
        val chunk = entity.level().chunkSource.getChunkNow(x shr 4, z shr 4) ?: return false
        // ChunkAccess.getHeight is the y of the highest block, so +1 is the first free y above the column.
        val top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x and 15, z and 15) + 1
        if (box.maxY >= top) return true

        val pos = BlockPos.MutableBlockPos()
        for (y in Mth.floor(box.maxY) until top) {
            pos.set(x, y, z)
            if (blocksSight(chunk, pos, chunk.getBlockState(pos))) return false
        }
        return true
    }

    /** True when [state] at [pos] hides whatever is below it: solid enough to collide, and not in [SEE_THROUGH]. */
    fun blocksSight(level: BlockGetter, pos: BlockPos, state: BlockState): Boolean =
        !state.isAir && !state.`is`(SEE_THROUGH) && !state.getCollisionShape(level, pos).isEmpty

    /**
     * True when at least two of three rays from [source] reach [target] without crossing a solid block or a smoke cloud
     * ([SmokeScreen.blocksLine]). Checking more
     * than the hitbox centre avoids treating one narrow doorway or window aligned with the centre as full visibility
     * into a deep structure, while still allowing a target to be seen through a genuinely open side.
     */
    fun hasLineOfSight(source: Entity, target: Entity): Boolean {
        if (source.level() !== target.level()) return false
        if (SmokeScreen.blocksLine(source.level(), source.getEyePosition(1f), target.boundingBox.center)) return false
        val box = target.boundingBox
        val targetHeight = box.maxY - box.minY
        val heights = doubleArrayOf(
            box.minY + targetHeight * 0.25,
            box.minY + targetHeight * 0.5,
            box.minY + targetHeight * 0.75
        )
        var clear = 0
        for (height in heights) {
            if (rayIsClear(source, Vec3(box.center.x, height, box.center.z))) clear++
        }
        return clear >= 2
    }

    private fun rayIsClear(source: Entity, end: Vec3): Boolean {
        val level = source.level()
        val start = source.getEyePosition(1f)
        val ray = end.subtract(start)
        val length = ray.length()
        if (length < 1.0) return true

        val step = ray.scale(0.5 / length)
        var point = start.add(step)
        val samples = Mth.ceil(length * 2.0)
        val seen = HashSet<BlockPos>()
        repeat(samples - 1) {
            val pos = BlockPos.containing(point)
            if (seen.add(pos) && blocksSight(level, pos, level.getBlockState(pos))) return false
            point = point.add(step)
        }
        return true
    }
}
