package com.teamuhi.superbenemies.config

import net.minecraftforge.common.ForgeConfigSpec

/** What kind of thing a boss may pick as its target. Each category has its own weight option (see [TargetWeights]). */
enum class TargetCategory {
    /** Survival / adventure players, and player dummies / dummy soldiers while their `bossesTarget...` option is on. */
    PLAYER,

    /** Non-hostile mobs: animals, villagers, golems and so on. */
    MOB,

    /** Mobs that implement [net.minecraft.world.entity.monster.Enemy]: zombies, skeletons, creepers... */
    HOSTILE_MOB,

    /** EXPERIMENTAL. Manned Superb Warfare airplanes, helicopters and airships, and the plane bosses. */
    PLANE,

    /** EXPERIMENTAL. Manned Superb Warfare tanks, APCs and anti-air vehicles, and the tank bosses. */
    TANK
}

/** Highest value of a target weight option; the config screen shows them as a 0 to this slider. */
const val MAX_TARGET_WEIGHT = 5

/**
 * The five target weight options of one boss (`playerWeight`, `mobWeight`, `hostileMobWeight`, `planeWeight`, `tankWeight`), read live.
 * 0 means the category is never attacked. With random targeting the weight is the relative draw chance; otherwise the
 * boss compares `distance / weight`, so a weight-5 target counts as five times closer than a weight-1 one.
 */
class TargetWeights(
    val players: ForgeConfigSpec.IntValue,
    val mobs: ForgeConfigSpec.IntValue,
    val hostileMobs: ForgeConfigSpec.IntValue,
    val planes: ForgeConfigSpec.IntValue,
    val tanks: ForgeConfigSpec.IntValue
) {
    /** The weight of [category]; a null category (something that is no target at all) weighs 0. */
    fun of(category: TargetCategory?): Int = when (category) {
        TargetCategory.PLAYER -> players.get()
        TargetCategory.MOB -> mobs.get()
        TargetCategory.HOSTILE_MOB -> hostileMobs.get()
        TargetCategory.PLANE -> planes.get()
        TargetCategory.TANK -> tanks.get()
        null -> 0
    }

    val allowsPlayers: Boolean get() = players.get() > 0

    /** True when either kind of mob (non-hostile or hostile) may be attacked. */
    val allowsMobs: Boolean get() = mobs.get() > 0 || hostileMobs.get() > 0

    val allowsPlanes: Boolean get() = planes.get() > 0

    val allowsTanks: Boolean get() = tanks.get() > 0

    /** True when either kind of vehicle (aircraft or armour) may be attacked, i.e. the vehicle tracker is worth scanning. */
    val allowsVehicles: Boolean get() = allowsPlanes || allowsTanks
}
