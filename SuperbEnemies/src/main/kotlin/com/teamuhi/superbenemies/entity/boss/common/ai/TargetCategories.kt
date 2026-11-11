package com.teamuhi.superbenemies.entity.boss.common.ai

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.config.TargetCategory
import com.teamuhi.superbenemies.entity.drone.DroneEntity
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Enemy

/**
 * Which [TargetCategory] [entity] falls into, or null when it is not a target at all. It only classifies: range, line of
 * sight, game mode, crew and the like are still checked by the caller.
 *
 * A vehicle is split by its SBW vehicle type: airplane, helicopter and airship are PLANE; tank, APC and anti-air are TANK;
 * everything else (cars, boats, artillery, turrets...) is not a target (null).
 *
 * The dummy test comes before the mob test because the dummies are [Mob]s; a dummy whose `bossesTarget...` option is off
 * falls through to the mob rules (a dummy is not an [Enemy], so it then counts as a plain MOB).
 *
 * The drone is no target in any category: the A-10 and AC-130 ignore it, and the tank bosses weigh it with their own
 * `DroneConfig.TANK_DRONE_WEIGHT` (see `TankTargeting`), not with the mob weights.
 */
fun categoryOf(entity: Entity): TargetCategory? = when {
    entity is ServerPlayer -> TargetCategory.PLAYER
    entity is HumanoidDummyEntity && entity.settings.targetedByBosses() -> TargetCategory.PLAYER
    entity is DroneEntity -> null
    entity is VehicleEntity -> vehicleCategory(entity)
    entity is Mob -> if (entity is Enemy) TargetCategory.HOSTILE_MOB else TargetCategory.MOB
    else -> null
}

private fun vehicleCategory(vehicle: VehicleEntity): TargetCategory? {
    val type = vehicle.vehicleType
    return when {
        isAirType(type) -> TargetCategory.PLANE
        isArmourType(type) -> TargetCategory.TANK
        else -> null
    }
}
