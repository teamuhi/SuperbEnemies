package com.teamuhi.superbenemies.entity.boss.common.ai

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity

/**
 * Every Superb Warfare vehicle. The class is all a tracker can test (see [EntityTracker]); whether one is an airplane, a
 * helicopter or an armoured vehicle is data-driven, so [isEnemyPlane] and [isEnemyTank] filter on that at query time.
 */
val TrackedVehicles = EntityTracker { it is VehicleEntity }

/** The vehicle kinds that count as aircraft (the PLANE target category). */
fun isAirType(type: VehicleType?): Boolean =
    type == VehicleType.AIRPLANE || type == VehicleType.HELICOPTER || type == VehicleType.AIRSHIP

/** The vehicle kinds that count as armour (the TANK target category). */
fun isArmourType(type: VehicleType?): Boolean =
    type == VehicleType.TANK || type == VehicleType.APC || type == VehicleType.AA

/**
 * Whether [entity] is an aircraft the bosses may hunt when `planeWeight` is above 0 (experimental): an SBW airplane,
 * helicopter or airship other than [boss] itself, in the same level, not a wreck and not a crippled boss, that is manned.
 * Another boss counts as manned (it has a hidden crew); any other plane needs a passenger who is not a creative /
 * spectator player, so parked empty planes are ignored. Range is not checked here.
 */
fun isEnemyPlane(boss: Entity, entity: Entity): Boolean {
    if (entity !is VehicleEntity || !isAirType(entity.vehicleType)) return false
    return isMannedHostile(boss, entity)
}

/**
 * Same as [isEnemyPlane] for armour: an SBW tank, APC or anti-air vehicle (the TANK target category) other than [boss]
 * itself, manned (or a non-crippled boss), not a wreck. Two tank bosses target each other through this unless they list
 * each other as teammates (see [isTeammate]). Range is not checked here.
 */
fun isEnemyTank(boss: Entity, entity: Entity): Boolean {
    if (entity !is VehicleEntity || !isArmourType(entity.vehicleType)) return false
    return isMannedHostile(boss, entity)
}

/**
 * Whether [other] (or the vehicle it rides) is a boss that [boss] lists as a teammate in its Teammates table. Looks only at
 * [boss]'s own table, so the relation can be one-sided; a boss is never its own teammate. False when either is no boss.
 * Targeting, retaliation, hearing and the tanks' fire control all go through this.
 */
fun isTeammate(boss: Entity, other: Entity): Boolean {
    val me = boss as? BossVehicle ?: return false
    val them = other.rootVehicle as? BossVehicle ?: return false
    return them !== me && me.teammates().allows(them.bossKind)
}

private fun isMannedHostile(boss: Entity, vehicle: VehicleEntity): Boolean {
    if (vehicle === boss) return false
    if (isTeammate(boss, vehicle)) return false
    if (!vehicle.isAlive || vehicle.isRemoved || vehicle.level() !== boss.level()) return false
    if (vehicle.isWreck) return false
    if (vehicle is BossVehicle) return !vehicle.crippled
    return vehicle.passengers.any { !(it is ServerPlayer && (it.isCreative || it.isSpectator)) }
}

/**
 * True when [entity] itself or one of its passengers, however deeply nested (a rider of a rider, a gunner in a second seat),
 * has the string UUID [uuid]. A boss's last attacker is the pilot, not the plane, so the "attacker first" rule needs this
 * to find the plane the attacker is flying. Null never matches.
 */
fun riddenBy(entity: Entity, uuid: String?): Boolean {
    if (uuid.isNullOrEmpty()) return false
    return entity.stringUUID == uuid || entity.indirectPassengers.any { it.stringUUID == uuid }
}

/**
 * One candidate per vehicle: a player riding a vehicle is listed both as a PLAYER and as the vehicle (and a mob in a
 * boat as the mob and the boat), but a boss attacks the root vehicle either way, so the group is collapsed to the member
 * with the highest [weightOf] (a tie keeps the root vehicle itself, then the earlier one), which is also the weight the
 * choice then uses. The order of the first appearance of each root is kept. An unmounted candidate is its own root.
 */
fun dedupeByRootVehicle(candidates: List<Entity>, weightOf: (Entity) -> Int): MutableList<Entity> {
    if (candidates.size < 2) return ArrayList(candidates)
    val best = LinkedHashMap<Entity, Entity>()
    for (candidate in candidates) {
        val root = candidate.rootVehicle
        val current = best[root]
        if (current == null) {
            best[root] = candidate
            continue
        }
        val weight = weightOf(candidate)
        val currentWeight = weightOf(current)
        if (weight > currentWeight || (weight == currentWeight && candidate === root && current !== root)) {
            best[root] = candidate
        }
    }
    return ArrayList(best.values)
}
