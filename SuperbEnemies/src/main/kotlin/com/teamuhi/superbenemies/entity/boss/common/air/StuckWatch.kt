package com.teamuhi.superbenemies.entity.boss.common.air

import com.teamuhi.superbenemies.debug.AiThought
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Notices an aircraft boss that has stayed on one spot (wedged in terrain or a building: a healthy plane never hangs in
 * place) and finds open air to put it back in. One per brain; the brain decides what to do with the answer.
 *
 * [update] is called every tick the boss is alive and not crippled: it remembers where the boss was and since when, and
 * reports true once the boss has not left a [RADIUS] block ball around that spot for the given time. [findSpot] then
 * looks for a free place above the terrain, straight up first and then on rings around the boss.
 */
class StuckWatch(private val boss: Entity) {

    private val thought: AiThought? get() = (boss as? BossVehicle)?.thought

    private var anchor: Vec3? = null
    private var anchorTick = 0

    /**
     * @param seconds how long the boss may stay inside [RADIUS] blocks of one spot
     * @return true once it has (the watch starts over, so a failed rescue is retried after another [seconds])
     */
    fun update(seconds: Int): Boolean {
        val now = boss.tickCount
        val position = boss.position()
        val from = anchor
        if (from == null || position.distanceToSqr(from) > RADIUS * RADIUS || now < anchorTick) {
            anchor = position
            anchorTick = now
            return false
        }
        if (now - anchorTick < seconds * 20) return false
        reset()
        return true
    }

    /**
     * [update] and, once it fires, [findSpot] in one call: the open air to put the boss in, or null while it is not stuck
     * (or nothing is free: the watch has started over, so the rescue is tried again after another [seconds]).
     */
    fun rescueSpot(level: ServerLevel, seconds: Int): Vec3? = if (update(seconds)) findSpot(level) else null

    /** Forgets the spot (the watch is off, the boss changed place by itself or was just moved). */
    fun reset() {
        anchor = null
    }

    /**
     * A point where the boss's hitbox fits, in a loaded chunk and inside the world border, high above the terrain: the
     * column the boss is in first, then rings of growing radius. Null when nothing is free (try again later).
     */
    fun findSpot(level: ServerLevel): Vec3? {
        val box = boss.boundingBox.inflate(CLEARANCE_MARGIN)
        val ceiling = level.maxBuildHeight - CEILING_MARGIN
        for (radius in RINGS) {
            val steps = if (radius == 0.0) 1 else RING_STEPS
            for (i in 0 until steps) {
                val angle = Math.PI * 2.0 * i / steps
                val x = boss.x + cos(angle) * radius
                val z = boss.z + sin(angle) * radius
                val column = BlockPos.containing(x, boss.y, z)
                if (!level.hasChunkAt(column) || !level.worldBorder.isWithinBounds(x, z, BORDER_MARGIN)) continue
                val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.x, column.z)
                val y = min(max(ground + LIFT, boss.y + MIN_RISE), ceiling.toDouble())
                if (y < ground + MIN_GROUND_CLEARANCE) continue
                val moved = box.move(x - boss.x, y - boss.y, z - boss.z)
                if (level.noCollision(moved)) {
                    thought?.event("stuck.findSpot") { "rescue spot found on ring ${radius.toInt()}: $x, $y, $z" }
                    return Vec3(x, y, z)
                }
            }
        }
        thought?.event("stuck.findSpot") { "no free rescue spot, trying again after the next window" }
        return null
    }

    private companion object {
        /** The boss counts as staying put while it stays inside a ball of this radius (blocks). */
        const val RADIUS = 6.0

        /** Blocks above the terrain (or above the stuck position, whichever is higher) the rescue puts the boss. */
        const val LIFT = 60.0
        const val MIN_RISE = 20.0
        const val MIN_GROUND_CLEARANCE = 20.0
        const val CLEARANCE_MARGIN = 2.0
        const val CEILING_MARGIN = 20
        const val BORDER_MARGIN = 16.0
        const val RING_STEPS = 8
        val RINGS = doubleArrayOf(0.0, 48.0, 96.0, 160.0)
    }
}
