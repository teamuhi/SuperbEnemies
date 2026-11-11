package com.teamuhi.superbenemies.entity.boss.common.ai

import com.atsuishio.superbwarfare.entity.projectile.SmokeDecoyEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Every Superb Warfare smoke decoy (tank smoke launchers, smoke grenades, mortar smoke); read by [SmokeScreen]. */
val TrackedSmoke = EntityTracker { it is SmokeDecoyEntity }

/**
 * Smoke as cover. The SBW smoke decoys (`SmokeDecoyEntity`, 400 ticks long, lit [SmokeDecoyEntity.igniteTime] ticks after
 * launch) hide whatever is inside or behind them from the bosses that track their target by sight (the `DYNAMIC`
 * targeting method; `STATIC` never asks, see [LastKnownLocation]). SBW itself only breaks missile locks near smoke, so the bosses
 * need their own rule.
 *
 * A cloud is a sphere of [SMOKE_RADIUS] blocks around the decoy once it is lit and until it burns out. Reads the
 * [TrackedSmoke] list, so it costs nothing while no smoke exists and a few distance tests while it does.
 */
object SmokeScreen {

    /** Radius of a cloud for sight lines, in blocks (the visible puff spreads about this far). */
    const val SMOKE_RADIUS = 5.0

    /** An entity this close to a cloud's centre counts as standing in it. */
    const val INSIDE_RADIUS = 4.0

    /** True once [smoke] has lit and has not yet burned out (a decoy still flying does not hide anything). */
    fun isBurning(smoke: Entity): Boolean {
        val decoy = smoke as? SmokeDecoyEntity ?: return false
        return smoke.tickCount >= decoy.igniteTime && smoke.tickCount <= decoy.life
    }

    /**
     * True when the straight line [from] to [to] passes through a burning cloud: the segment comes within [SMOKE_RADIUS]
     * of a cloud's centre. Clouds outside the segment's bounding box are skipped before any maths. Clouds for which
     * [ignore] returns true are not counted (a tank's sight ignores the smoke its own side launched).
     */
    fun blocksLine(level: Level, from: Vec3, to: Vec3, ignore: ((Entity) -> Boolean)? = null): Boolean {
        val smokes = TrackedSmoke.all(level)
        if (smokes.isEmpty()) return false

        val minX = min(from.x, to.x) - SMOKE_RADIUS
        val maxX = max(from.x, to.x) + SMOKE_RADIUS
        val minY = min(from.y, to.y) - SMOKE_RADIUS
        val maxY = max(from.y, to.y) + SMOKE_RADIUS
        val minZ = min(from.z, to.z) - SMOKE_RADIUS
        val maxZ = max(from.z, to.z) + SMOKE_RADIUS

        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val lengthSqr = dx * dx + dy * dy + dz * dz
        for (smoke in smokes) {
            if (!isBurning(smoke) || (ignore != null && ignore(smoke))) continue
            if (smoke.x < minX || smoke.x > maxX || smoke.y < minY || smoke.y > maxY || smoke.z < minZ || smoke.z > maxZ) continue
            // Closest point of the segment to the centre; a zero length segment is just the point itself.
            val t = if (lengthSqr < 1.0e-9) 0.0
            else (((smoke.x - from.x) * dx + (smoke.y - from.y) * dy + (smoke.z - from.z) * dz) / lengthSqr).coerceIn(0.0, 1.0)
            val ox = from.x + dx * t - smoke.x
            val oy = from.y + dy * t - smoke.y
            val oz = from.z + dz * t - smoke.z
            if (ox * ox + oy * oy + oz * oz < SMOKE_RADIUS * SMOKE_RADIUS) return true
        }
        return false
    }

    /** True when [entity] (its bounding box centre) stands within [INSIDE_RADIUS] of a burning cloud's centre. */
    fun inSmoke(entity: Entity): Boolean {
        val smokes = TrackedSmoke.all(entity.level())
        if (smokes.isEmpty()) return false
        val center = entity.boundingBox.center
        for (smoke in smokes) {
            if (!isBurning(smoke)) continue
            if (abs(smoke.x - center.x) > INSIDE_RADIUS || abs(smoke.y - center.y) > INSIDE_RADIUS ||
                abs(smoke.z - center.z) > INSIDE_RADIUS
            ) continue
            val dx = smoke.x - center.x
            val dy = smoke.y - center.y
            val dz = smoke.z - center.z
            if (dx * dx + dy * dy + dz * dz <= INSIDE_RADIUS * INSIDE_RADIUS) return true
        }
        return false
    }
}
