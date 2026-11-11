package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath.horizontalDistance
import com.teamuhi.superbenemies.entity.boss.common.ai.SmokeScreen
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedSmoke
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * Smoke as a clue: a smoke cloud tells a tank that another tank is about (a smoke launcher goes off in a fight or on a
 * break away), so a tank with nothing to do goes and scouts the place. [nextCloud] finds the nearest fresh cloud (lit, and not
 * the tank's own) within `smokeScoutRange` that the tank has a clear line to over the blocks (the smoke itself does not hide
 * the cloud); each cloud is scouted once. The brain drives there like to a noise, then sweeps the area ([SearchPattern]).
 * Clouds of the tank's teammates count too: they went up in a fight. One instance per brain.
 */
class SmokeScout(
    private val boss: TankBossEntity,
    private val settings: TankSettings
) {

    /** Entity ids of the clouds already looked at; the ones that burned out are dropped on each scan. */
    private val handled = HashSet<Int>()

    /**
     * The place of the nearest cloud this tank has not scouted yet, or null (none, out of range, no clear line, `smokeScoutRange`
     * 0). The cloud found is marked as scouted: the next call looks for another one.
     */
    fun nextCloud(level: ServerLevel): Vec3? {
        val range = settings.smokeScoutRange.get().toDouble()
        val clouds = TrackedSmoke.all(level)
        if (clouds.isEmpty()) {
            handled.clear()
            return null
        }
        if (range <= 0.0) return null
        handled.retainAll(clouds.mapTo(HashSet()) { it.id })

        val eye = TankSight.eyeOf(boss)
        val position = boss.position()
        var best: Entity? = null
        var bestDistance = Double.MAX_VALUE
        for (cloud in clouds) {
            if (cloud.id in handled || cloud.tickCount < MIN_AGE || !SmokeScreen.isBurning(cloud)) continue
            // Its own smoke tells the tank nothing new.
            if (TankSight.smokeOwner(cloud) === boss) {
                handled.add(cloud.id)
                continue
            }
            val distance = horizontalDistance(position, cloud.position())
            if (distance > range || distance >= bestDistance) continue
            // The plume stands well above the ground: the line is run to a point over the cloud's centre.
            if (TankSight.solidBlocks(level, eye, Vec3(cloud.x, cloud.y + PLUME_HEIGHT, cloud.z), 0) != 0) continue
            best = cloud
            bestDistance = distance
        }
        val found = best ?: return null
        handled.add(found.id)
        boss.thought.event("smokeScout.nextCloud") { "found a smoke cloud ${bestDistance.toInt()} blocks away to scout" }
        return found.position()
    }

    private companion object {
        /** A cloud is looked at once it is this many ticks old: the one that tank bosses launch is noted as theirs by then. */
        const val MIN_AGE = 2

        /** Blocks above a cloud's centre that the line to it ends at. */
        const val PLUME_HEIGHT = 6.0
    }
}
