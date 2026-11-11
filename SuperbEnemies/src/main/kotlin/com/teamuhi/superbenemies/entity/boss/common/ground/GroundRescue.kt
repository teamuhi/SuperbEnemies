package com.teamuhi.superbenemies.entity.boss.common.ground

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Finds a place to put a ground boss that has driven itself into a corner it cannot reverse out of (flipped, wedged on a
 * ledge or in trunks): the nearest free, drivable spot around it. The ground counterpart of `StuckWatch.findSpot`, but
 * close by, so the tank stays where the fight is instead of vanishing until a replacement arrives.
 */
object GroundRescue {

    /**
     * A spot within [RINGS] blocks of [boss] where its hull fits on dry (or, for a floating boss, watery) ground it can
     * drive on, in loaded chunks and inside the world border. The nearest ring that has one wins; null when there is none.
     *
     * With [maxFloorY] only ground whose standing height is at most that counts: a tank stuck on a rooftop is set down on
     * the ground below it (see `ElevationDetection`), not on the roof again.
     */
    fun findSpot(level: ServerLevel, boss: Entity, halfWidth: Double, profile: GroundProfile, maxFloorY: Double? = null): Vec3? {
        val half = max(halfWidth, boss.bbWidth / 2.0)
        val height = boss.bbHeight + HEADROOM
        // Starts the ring at a varying angle so a retry after a failed rescue does not test the very same spots.
        val start = level.random.nextDouble() * Math.PI * 2.0
        for (radius in RINGS) {
            for (i in 0 until RING_STEPS) {
                val angle = start + Math.PI * 2.0 * i / RING_STEPS
                val x = boss.x + cos(angle) * radius
                val z = boss.z + sin(angle) * radius
                if (!level.worldBorder.isWithinBounds(x, z, BORDER_MARGIN)) continue

                val cell = GroundTerrain.sample(level, GroundTerrain.cellOf(x, profile), GroundTerrain.cellOf(z, profile), profile)
                if (!GroundTerrain.passable(cell, profile) || cell.foliage) continue
                val surface = GroundTerrain.spawnY(level, Math.floor(x).toInt(), Math.floor(z).toInt(), profile) ?: continue
                if (maxFloorY != null && surface > maxFloorY) continue
                val y = surface + LIFT
                if (GroundTerrain.checkSpawn(level, x, y, z, half, height, profile) == SpawnVerdict.OK) return Vec3(x, y, z)
            }
        }
        return null
    }

    private val RINGS = doubleArrayOf(8.0, 14.0, 22.0, 34.0, 48.0)
    private const val RING_STEPS = 12
    private const val LIFT = 0.1
    private const val HEADROOM = 0.5
    private const val BORDER_MARGIN = 16.0
}
