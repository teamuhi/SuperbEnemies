package com.teamuhi.superbenemies.config

import net.minecraftforge.common.ForgeConfigSpec

/** Which shell the tank bosses' main cannon loads (`cannonShell`). AUTO picks per target, see `TankGunnery`. */
enum class CannonShell {
    /** Armour-piercing against vehicles, hidden targets and anything behind cover; high explosive against targets in the open. */
    AUTO,

    /** Always armour-piercing: pierces a few blocks, little splash. */
    AP,

    /** Always high explosive: big splash, stopped by the first block. */
    HE
}

/**
 * The config options of one weapon of a tank boss. [name] is the Superb Warfare weapon name (`Cannon`, `MachineGun`,
 * `Missile`, `PassengerMachineGun`), so the code finds the options of a weapon by the name it fires with.
 */
class WeaponSettings(
    val name: String,
    val enabled: ForgeConfigSpec.BooleanValue,
    val rounds: ForgeConfigSpec.IntValue,
    val damageMultiplier: ForgeConfigSpec.DoubleValue
)

/**
 * Every option the shared tank code reads (targeting, ammunition, smoke, flanking...), bundled so the LAV-25 and the M1A2
 * use one brain, one targeting and one gunnery class with their own config file each. Built by [Lav25Config] and
 * [M1A2Config]; all values are read live, except [maxHealth], [blockDamage] and the weapons' damage multipliers, which are
 * applied once when a boss is first created (they are stored in the entity afterwards).
 *
 * @param logName name used in debug log lines
 * @param missileCooldownSeconds LAV-25 only: seconds between two wire missiles; null for a tank without missiles
 * @param crossWater LAV-25 only: whether the amphibious hull may swim across lakes; null for a tank that cannot swim
 * @param teammates the Teammates table: boss types this tank never targets, and may form up with when they list it back
 * @param formationEnabled whether this tank joins a formation with its teammates (see `TankFormations`)
 * @param formationSpacing blocks between the tanks of a formation; the leader's value is used for the whole group
 * @param formationHaltSeconds seconds the whole formation holds still and fires on first contact before it breaks up (0 = never halts)
 * @param idleResupplySeconds with `limitedAmmo`: seconds without a target before a partly spent tank may roll to rearm (0 = off)
 * @param idleResupplyChance percent chance, rolled every few seconds of idling once `idleResupplySeconds` have passed, that the tank leaves to rearm
 * @param sightConeDegrees full width of the sight cone around the turret (0 = no cone)
 * @param sightConeRangeMultiplier how many times `sightRange` the sight reaches inside the cone
 * @param cannonHearingPercent percent of a cannon's Superb Warfare sound radius (16 blocks per point) from which the tank hears and pinpoints its fire (0 = deaf)
 * @param machineGunHearingPercent the same for machine gun fire
 * @param hearingScatterPercent how far off (percent of the distance) a heard shooter is placed (0 = exact)
 * @param shootDownMissiles whether the machine guns shoot at an incoming guided missile (alongside the smoke, see `ShootHomingMissiles`)
 * @param shootDownFailPercent percent chance that the machine guns fail to stop a missile they engage (0 = never fail, 100 = never stop one)
 * @param smokeScoutRange blocks within which a tank with no target goes to scout a fresh smoke cloud (0 = ignores smoke)
 * @param returnFireEnabled whether a tank shoots back at the estimated spot of an unseen shooter at all
 * @param returnFireSeconds seconds a tank shoots back at the estimated spot of an unseen shooter (0 = off)
 * @param useCover whether the tank scans for cover and uses it: hull-down spots for the light tanks, peek and shoot for
 * the heavy ones (see `CoverAwareness`); off keeps the older random-sample spots and shoot and scoot
 * @param pullBackDamagePercent percent of the maximum health lost within 3 seconds that sends the tank to cover (0 = never, see `PullBackRetreat`)
 * @param wallBreaching whether the tank shoots a hole through a thin wall on the straight line to its goal when the route round it
 * is a long detour or there is none (needs [blockDamage]; see `WallBreach`)
 */
class TankSettings(
    val logName: String,
    val maxHealth: ForgeConfigSpec.IntValue,
    val aggroRange: ForgeConfigSpec.IntValue,
    val globalAggro: ForgeConfigSpec.BooleanValue,
    val targetingMethod: ForgeConfigSpec.EnumValue<TargetingMethod>,
    val weights: TargetWeights,
    val mobScanRange: ForgeConfigSpec.IntValue,
    val randomTargeting: ForgeConfigSpec.BooleanValue,
    val randomTargetSeconds: ForgeConfigSpec.IntValue,
    val engageRange: ForgeConfigSpec.IntValue,
    val sightRange: ForgeConfigSpec.IntValue,
    val hearingRange: ForgeConfigSpec.IntValue,
    val cannonHearingPercent: ForgeConfigSpec.IntValue,
    val machineGunHearingPercent: ForgeConfigSpec.IntValue,
    val hearingScatterPercent: ForgeConfigSpec.IntValue,
    val smokeScoutRange: ForgeConfigSpec.IntValue,
    val searchSeconds: ForgeConfigSpec.IntValue,
    val breakDetectionSeconds: ForgeConfigSpec.IntValue,
    val decisionCommitSeconds: ForgeConfigSpec.IntValue,
    val sightLossGraceSeconds: ForgeConfigSpec.IntValue,
    val closeDetectionRange: ForgeConfigSpec.IntValue,
    val leashRange: ForgeConfigSpec.IntValue,
    val blockDamage: ForgeConfigSpec.BooleanValue,
    val weapons: List<WeaponSettings>,
    val cannonShell: ForgeConfigSpec.EnumValue<CannonShell>,
    val missileCooldownSeconds: ForgeConfigSpec.IntValue?,
    val limitedAmmo: ForgeConfigSpec.BooleanValue,
    val smokeCharges: ForgeConfigSpec.IntValue,
    val resupplySeconds: ForgeConfigSpec.IntValue,
    val smokeEnabled: ForgeConfigSpec.BooleanValue,
    val smokeWhenHitChance: ForgeConfigSpec.IntValue,
    val smokeAgainstMissiles: ForgeConfigSpec.BooleanValue,
    val smokeAgainstAir: ForgeConfigSpec.BooleanValue,
    val shootDownMissiles: ForgeConfigSpec.BooleanValue,
    val shootDownFailPercent: ForgeConfigSpec.IntValue,
    val flankingEnabled: ForgeConfigSpec.BooleanValue,
    val flankChance: ForgeConfigSpec.IntValue,
    val crossWater: ForgeConfigSpec.BooleanValue?,
    val bossBarRange: ForgeConfigSpec.IntValue,
    val xpReward: ForgeConfigSpec.IntValue,
    val teammates: TeammateSettings,
    val formationEnabled: ForgeConfigSpec.BooleanValue,
    val formationSpacing: ForgeConfigSpec.IntValue,
    val formationHaltSeconds: ForgeConfigSpec.IntValue,
    val idleResupplySeconds: ForgeConfigSpec.IntValue,
    val idleResupplyChance: ForgeConfigSpec.IntValue,
    val sightConeDegrees: ForgeConfigSpec.IntValue,
    val sightConeRangeMultiplier: ForgeConfigSpec.DoubleValue,
    val returnFireEnabled: ForgeConfigSpec.BooleanValue,
    val returnFireSeconds: ForgeConfigSpec.IntValue,
    val useCover: ForgeConfigSpec.BooleanValue,
    val pullBackDamagePercent: ForgeConfigSpec.IntValue,
    val wallBreaching: ForgeConfigSpec.BooleanValue
) {
    /** The options of the weapon fired as [name], or null when this tank has no such weapon. */
    fun weapon(name: String): WeaponSettings? = weapons.firstOrNull { it.name == name }
}
