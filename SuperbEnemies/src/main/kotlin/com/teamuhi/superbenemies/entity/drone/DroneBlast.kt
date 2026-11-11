package com.teamuhi.superbenemies.entity.drone

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.CustomExplosion
import com.teamuhi.superbenemies.config.DroneConfig
import net.minecraft.world.level.Explosion
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.IEventBus
import kotlin.math.sqrt

/**
 * Gives the drone's blast its own damage against vehicles (`tankExplosionDamage`).
 *
 * Superb Warfare skips vehicles in its explosion damage loop and hurts them from an `ExplosionEvent.Detonate` handler with the
 * explosion's one damage value. For a blast set off by a [DroneEntity] this handler runs first (higher priority), takes the
 * vehicles out of the affected list and hurts them itself with the same falloff but `tankExplosionDamage`; everything else
 * still takes `explosionDamage`. When both values are equal it leaves the event alone.
 */
object DroneBlast {

    fun register(bus: IEventBus) {
        bus.addListener<ExplosionEvent.Detonate>(EventPriority.HIGH) { event -> onDetonate(event) }
    }

    private fun onDetonate(event: ExplosionEvent.Detonate) {
        val explosion = event.explosion as? CustomExplosion ?: return
        if (explosion.directSourceEntity !is DroneEntity) return
        val tankDamage = DroneConfig.TANK_EXPLOSION_DAMAGE.get()
        if (tankDamage == DroneConfig.EXPLOSION_DAMAGE.get()) return // Superb Warfare's own handling gives the same result

        val position = explosion.position
        val reach = explosion.radius * 2.0 // the blast's diameter: nothing farther away is hurt
        val iterator = event.affectedEntities.iterator()
        while (iterator.hasNext()) {
            val vehicle = iterator.next() as? VehicleEntity ?: continue
            iterator.remove()
            if (vehicle.ignoreExplosion()) continue

            val distanceRatio = sqrt(vehicle.distanceToSqr(position)) / reach
            if (distanceRatio > 1.0) continue
            val dx = vehicle.x - position.x
            val dy = vehicle.eyeY - position.y
            val dz = vehicle.z - position.z
            if (dx * dx + dy * dy + dz * dz == 0.0) continue

            val seen = if (!vehicle.enableAABB()) {
                CustomExplosion.getSeenPercentOptimized(vehicle.level(), position, vehicle)
            } else {
                Explosion.getSeenPercent(position, vehicle)
            }
            val impact = (1.0 - distanceRatio) * seen
            val damage = (impact * impact + impact) / 2.0 * tankDamage
            vehicle.hurt(explosion.damageSource, damage.toFloat())
        }
    }
}
