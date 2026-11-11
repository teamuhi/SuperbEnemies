package com.teamuhi.superbenemies.entity.dummy.ai

import com.teamuhi.superbenemies.entity.boss.common.ai.SkyExposure
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The dummy runs for cover whenever [HumanoidDummyEntity.wantsCover] is true, in any movement mode. For the player dummy
 * that is: `hideFromBosses` is on and a live, uncrippled boss is within [DummyAi.BOSS_RANGE] blocks. The dummy soldier
 * also wants cover for a while after a hit, or when it is out of missiles.
 *
 * Cover is the nearest standable spot with a roof: at least one block that stops the bosses seeing down
 * ([SkyExposure.blocksSight]: stone, slabs, doors; not glass, leaves or fences) somewhere above the dummy's head, the
 * same test the boss' DYNAMIC targeting uses, so a spot that hides the dummy from the goal also hides it from the boss.
 * The search only runs when a boss shows up or the current cover is lost (never every tick):
 *  - the columns around the dummy are walked nearest first (a pre-sorted disc of [SEARCH_RADIUS] blocks), each resolved
 *    to the first standable spot near the dummy's height that has a roof,
 *  - the search stops after [MAX_CANDIDATES] spots, which are then ordered by distance,
 *  - tried nearest first: a candidate with no path, or that was not reached in time, falls through to the next.
 * Chunks are read with `getChunkNow`, so the search never loads or generates terrain.
 *
 * Phases: SEEK (sprint to the chosen spot) -> COVER (stand still, crouch now and then, the look goal keeps the boss in
 * sight; searches again if the roof is blown away) -> RETURN (once cover was not wanted for [HumanoidDummyEntity.coverMemoryTicks] ticks,
 * walk back to the spawn point and hand over to the movement mode). With no cover at all the dummy falls back to
 * strafing for a while ([HumanoidDummyEntity.forcedStrafeUntil]), so it is never a sitting duck.
 */
class DummyHideGoal(private val dummy: HumanoidDummyEntity) : Goal() {

    private enum class Phase { SEEK, COVER, RETURN }

    private var phase = Phase.SEEK
    private var finished = false

    private var cover: BlockPos? = null
    private val candidates = ArrayDeque<BlockPos>()
    private var needsSearch = true
    private var moveDeadline = 0

    private var lastThreatTick = 0
    private var nextScanTick = 0
    private var crouchTicks = 0
    private var returnDeadline = 0

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.JUMP)
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun canUse(): Boolean {
        if (!dummy.coverEnabled() || dummy.isPassenger || !dummy.isAlive) {
            dummy.thought.think("hide.canUse") { if (!dummy.coverEnabled()) "no: hiding is switched off" else "no: unable to hide" }
            return false
        }
        val now = dummy.tickCount
        if (now < dummy.hideRetryUntil || now < nextScanTick) return false
        nextScanTick = now + scanInterval()
        val wants = dummy.wantsCover()
        dummy.thought.think("hide.canUse") { if (wants) "yes: wants cover" else "no: no reason to hide" }
        return wants
    }

    override fun canContinueToUse(): Boolean {
        val continues = !finished && dummy.coverEnabled() && !dummy.isPassenger && dummy.isAlive
        if (!continues) {
            dummy.thought.think("hide.canContinueToUse") {
                "no: " + if (finished) "hiding finished" else if (!dummy.coverEnabled()) "hiding switched off" else "unable to hide"
            }
        }
        return continues
    }

    override fun start() {
        dummy.thought.event("hide.start") { "running for cover" }
        val now = dummy.tickCount
        phase = Phase.SEEK
        finished = false
        cover = null
        candidates.clear()
        needsSearch = true
        lastThreatTick = now
        nextScanTick = now + scanInterval()
        crouchTicks = 0
    }

    override fun stop() {
        dummy.thought.event("hide.stop") { "hide goal over (phase $phase)" }
        dummy.navigation.stop()
        dummy.isSprinting = false
        dummy.setCrouched(false)
        cover = null
        candidates.clear()
    }

    override fun tick() {
        val now = dummy.tickCount

        if (now >= nextScanTick) {
            nextScanTick = now + scanInterval()
            if (dummy.wantsCover()) {
                lastThreatTick = now
                if (phase == Phase.RETURN) enterSeek()
            }
        }
        if (phase != Phase.RETURN && now - lastThreatTick > dummy.coverMemoryTicks) enterReturn()

        when (phase) {
            Phase.SEEK -> tickSeek()
            Phase.COVER -> tickCover()
            Phase.RETURN -> tickReturn()
        }
    }

    /**
     * How often [HumanoidDummyEntity.wantsCover] is asked. A dummy with no memory (the soldier: it must walk back the
     * moment its cover time ends) asks every tick, which is cheap for it; the player dummy scans the bosses only every
     * [SCAN_INTERVAL] ticks.
     */
    private fun scanInterval(): Int = if (dummy.coverMemoryTicks <= 0) 1 else SCAN_INTERVAL

    // ================================================================================================== phases

    private fun enterSeek() {
        dummy.thought.event("hide.phase") { "$phase -> SEEK (looking for cover)" }
        phase = Phase.SEEK
        cover = null
        candidates.clear()
        needsSearch = true
        dummy.setCrouched(false)
        crouchTicks = 0
    }

    private fun enterReturn() {
        dummy.thought.event("hide.phase") { "$phase -> RETURN (no reason to hide any more, walking home)" }
        phase = Phase.RETURN
        dummy.navigation.stop()
        dummy.isSprinting = false
        dummy.setCrouched(false)
        crouchTicks = 0
        returnDeadline = dummy.tickCount + RETURN_TIMEOUT_TICKS
    }

    private fun tickSeek() {
        val target = cover ?: nextCover()
        if (target == null) {
            // Nothing within reach is roofed: strafe instead of standing in the open, and try again later.
            dummy.thought.event("hide.tickSeek") { "no cover within reach, falling back to strafing" }
            dummy.hideRetryUntil = dummy.tickCount + NO_COVER_RETRY_TICKS
            dummy.forcedStrafeUntil = dummy.tickCount + NO_COVER_RETRY_TICKS
            finished = true
            return
        }
        if (cover == null) dummy.thought.event("hide.tickSeek") { "cover spot chosen at ${target.toShortString()}" }
        cover = target

        val dx = target.x + 0.5 - dummy.x
        val dz = target.z + 0.5 - dummy.z
        if (hypot(dx, dz) < ARRIVE_DISTANCE && abs(target.y - dummy.y) < ARRIVE_HEIGHT) {
            dummy.thought.event("hide.phase") { "SEEK -> COVER (reached the cover spot)" }
            phase = Phase.COVER
            dummy.navigation.stop()
            dummy.isSprinting = false
            return
        }

        dummy.isSprinting = true
        dummy.thought.think("hide.tickSeek") { "sprinting to cover" }
        if (!dummy.navigation.isInProgress) {
            if (!DummyAi.pathfinds.tryTake(dummy.level())) return // path budget used up this tick: wait one tick
            // moveTo returns false when there is no path; the next tick then picks the next candidate.
            if (!dummy.navigation.moveTo(target.x + 0.5, target.y.toDouble(), target.z + 0.5, 1.0)) {
                dummy.thought.event("hide.tickSeek") { "no path to ${target.toShortString()}, trying the next spot" }
                cover = null
            } else {
                moveDeadline = dummy.tickCount + MOVE_TIMEOUT_TICKS
            }
        } else if (dummy.tickCount > moveDeadline) {
            dummy.thought.event("hide.tickSeek") { "too slow to reach ${target.toShortString()}, giving up on it" }
            cover = null // too slow (stuck on something): give up on this spot
            dummy.navigation.stop()
        }
        if (cover != null && dummy.navigation.isDone && hypot(dx, dz) >= ARRIVE_DISTANCE) {
            dummy.thought.event("hide.tickSeek") { "path ended short of ${target.toShortString()}, trying the next spot" }
            cover = null // path ended short
        }
    }

    private fun tickCover() {
        val here = cover ?: return enterSeek()
        dummy.navigation.stop()

        // Pushed out of the spot (knockback): walk back into it.
        if (hypot(here.x + 0.5 - dummy.x, here.z + 0.5 - dummy.z) > DRIFT_DISTANCE) {
            dummy.thought.event("hide.phase") { "COVER -> SEEK (pushed out of the cover spot)" }
            phase = Phase.SEEK
            return
        }
        // The roof is gone (blown up): look for new cover.
        if (dummy.tickCount % COVER_CHECK_INTERVAL == 0 && !isCovered(dummy.blockPosition())) {
            dummy.thought.event("hide.tickCover") { "the roof is gone, looking for new cover" }
            enterSeek()
            return
        }
        dummy.thought.think("hide.tickCover") { if (crouchTicks > 0) "in cover, crouching" else "in cover, standing" }

        if (crouchTicks > 0) {
            if (--crouchTicks == 0) dummy.setCrouched(false)
        } else if (dummy.getRandom().nextInt(COVER_CROUCH_CHANCE) == 0) {
            crouchTicks = COVER_CROUCH_MIN_TICKS + dummy.getRandom().nextInt(COVER_CROUCH_EXTRA_TICKS)
            dummy.setCrouched(true)
        }
    }

    private fun tickReturn() {
        val home = dummy.anchorCenter()
        if (home == null || dummy.tickCount > returnDeadline ||
            hypot(home.x - dummy.x, home.z - dummy.z) < HOME_DISTANCE
        ) {
            dummy.thought.event("hide.tickReturn") {
                if (home == null) "no anchor to return to, done" else if (dummy.tickCount > returnDeadline) "walk home timed out, done" else "back home, done"
            }
            finished = true
            return
        }
        dummy.thought.think("hide.tickReturn") { "walking back to the anchor" }
        if (dummy.navigation.isDone) {
            if (!DummyAi.pathfinds.tryTake(dummy.level())) return
            if (!dummy.navigation.moveTo(home.x, home.y, home.z, 1.0)) {
                dummy.thought.event("hide.tickReturn") { "no path home, done" }
                finished = true
            }
        }
    }

    // ================================================================================================== cover search

    /** The next untried cover spot; runs the search first when the list is empty and a search is due. */
    private fun nextCover(): BlockPos? {
        if (candidates.isEmpty() && needsSearch) {
            needsSearch = false
            val here = dummy.blockPosition()
            // Already under a roof when the boss appears: no need to go anywhere.
            if (isCovered(here)) return here
            search()
        }
        return candidates.removeFirstOrNull()
    }

    private fun search() {
        candidates.clear()
        val level = dummy.level()
        val origin = dummy.blockPosition()
        val cursor = BlockPos.MutableBlockPos()
        val found = ArrayList<BlockPos>(MAX_CANDIDATES)

        var chunkX = Int.MIN_VALUE
        var chunkZ = Int.MIN_VALUE
        var chunk: LevelChunk? = null
        var i = 0
        while (i < OFFSETS.size && found.size < MAX_CANDIDATES) {
            val x = origin.x + OFFSETS[i]
            val z = origin.z + OFFSETS[i + 1]
            i += 2
            if (x shr 4 != chunkX || z shr 4 != chunkZ) {
                chunkX = x shr 4
                chunkZ = z shr 4
                chunk = level.chunkSource.getChunkNow(chunkX, chunkZ)
            }
            val loaded = chunk ?: continue
            val y = coveredY(loaded, cursor, x, origin.y, z) ?: continue
            found.add(BlockPos(x, y, z))
        }
        found.sortBy { origin.distSqr(it) }
        candidates.addAll(found)
    }

    /** True when [pos] has a block above its head that hides it from above (read-only, never loads chunks). */
    private fun isCovered(pos: BlockPos): Boolean {
        val chunk = dummy.level().chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) ?: return false
        return hasRoof(chunk, BlockPos.MutableBlockPos(), pos.x, pos.y, pos.z, firstFreeY(chunk, pos.x, pos.z))
    }

    /** The y of the first free block above the highest block of the column (the `WORLD_SURFACE` height). */
    private fun firstFreeY(chunk: LevelChunk, x: Int, z: Int): Int =
        chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x and 15, z and 15) + 1

    /**
     * The y of the spot in column ([x], [z]) near [aroundY] that is standable and roofed, or null. The lowest roof block
     * is at y + 2, so no spot above `surface - ROOF_CLEARANCE` can qualify: that bounds the scan, which makes open
     * ground (the common case) almost free.
     */
    private fun coveredY(chunk: LevelChunk, cursor: BlockPos.MutableBlockPos, x: Int, aroundY: Int, z: Int): Int? {
        val surface = firstFreeY(chunk, x, z)
        val y = standingY(chunk, cursor, x, aroundY, z, surface - ROOF_CLEARANCE) ?: return null
        return if (hasRoof(chunk, cursor, x, y, z, surface)) y else null
    }

    /** True when a block that [SkyExposure.blocksSight] lies in the column above the head of a dummy standing at [y]. */
    private fun hasRoof(chunk: LevelChunk, cursor: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int, surface: Int): Boolean {
        for (above in y + ROOF_CLEARANCE - 1 until surface) {
            cursor.set(x, above, z)
            if (SkyExposure.blocksSight(chunk, cursor, chunk.getBlockState(cursor))) return true
        }
        return false
    }

    /**
     * The y of the first spot in column ([x], [z]) near [aroundY] with a solid, dry floor and two free blocks above it,
     * scanning down from [SEARCH_UP] blocks above [aroundY] (or [maxY] if lower) to [SEARCH_DOWN] below. Null when there is none.
     */
    private fun standingY(
        chunk: LevelChunk, cursor: BlockPos.MutableBlockPos, x: Int, aroundY: Int, z: Int, maxY: Int
    ): Int? {
        val level = dummy.level()
        val top = minOf(aroundY + SEARCH_UP, maxY, level.maxBuildHeight - 3)
        val bottom = maxOf(aroundY - SEARCH_DOWN, level.minBuildHeight + 1)
        for (y in top downTo bottom) {
            val floor = chunk.getBlockState(cursor.set(x, y - 1, z))
            if (floor.getCollisionShape(chunk, cursor).isEmpty || !floor.fluidState.isEmpty) continue
            val feet = chunk.getBlockState(cursor.set(x, y, z))
            if (!feet.getCollisionShape(chunk, cursor).isEmpty || !feet.fluidState.isEmpty) return null
            val head = chunk.getBlockState(cursor.set(x, y + 1, z))
            if (!head.getCollisionShape(chunk, cursor).isEmpty || !head.fluidState.isEmpty) return null
            return y
        }
        return null
    }

    private companion object {
        /** A boss within range is looked for this often (ticks). */
        const val SCAN_INTERVAL = 20

        const val SEARCH_RADIUS = 24
        const val SEARCH_UP = 4
        const val SEARCH_DOWN = 6
        const val MAX_CANDIDATES = 8

        /** The lowest roof block is this far above the feet: the body is two blocks tall, so the block over the head is y + 2. */
        const val ROOF_CLEARANCE = 3

        /** (dx, dz) pairs of every column within [SEARCH_RADIUS], nearest first, flattened: dx0, dz0, dx1, dz1, ... */
        val OFFSETS: IntArray = buildOffsets()

        private fun buildOffsets(): IntArray {
            val discs = ArrayList<IntArray>()
            for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) {
                for (dz in -SEARCH_RADIUS..SEARCH_RADIUS) {
                    if (dx * dx + dz * dz <= SEARCH_RADIUS * SEARCH_RADIUS) discs.add(intArrayOf(dx, dz))
                }
            }
            discs.sortBy { it[0] * it[0] + it[1] * it[1] }
            val flat = IntArray(discs.size * 2)
            discs.forEachIndexed { index, pair ->
                flat[index * 2] = pair[0]
                flat[index * 2 + 1] = pair[1]
            }
            return flat
        }

        const val ARRIVE_DISTANCE = 1.2
        const val ARRIVE_HEIGHT = 1.5
        const val DRIFT_DISTANCE = 2.5
        const val MOVE_TIMEOUT_TICKS = 200
        const val NO_COVER_RETRY_TICKS = 100

        const val COVER_CHECK_INTERVAL = 20
        const val COVER_CROUCH_CHANCE = 60
        const val COVER_CROUCH_MIN_TICKS = 20
        const val COVER_CROUCH_EXTRA_TICKS = 41

        const val HOME_DISTANCE = 1.5
        const val RETURN_TIMEOUT_TICKS = 400
    }
}
