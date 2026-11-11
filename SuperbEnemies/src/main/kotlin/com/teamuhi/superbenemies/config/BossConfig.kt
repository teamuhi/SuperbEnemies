package com.teamuhi.superbenemies.config

import net.minecraftforge.common.ForgeConfigSpec

/**
 * Common config, written to `config/superbenemies-common.toml`.
 *
 * Health, damage multipliers and block damage are applied to a boss once, when it is first created
 * (they are stored in the entity data afterwards). Everything else is read live by the AI.
 */
object BossConfig {
    val SPEC: ForgeConfigSpec

    val MAX_HEALTH: ForgeConfigSpec.IntValue
    val CRUISE_HEIGHT: ForgeConfigSpec.IntValue
    val AGGRO_RANGE: ForgeConfigSpec.IntValue
    val GLOBAL_AGGRO: ForgeConfigSpec.BooleanValue
    val TARGETING_METHOD: ForgeConfigSpec.EnumValue<TargetingMethod>
    val SIGHT_LOSS_GRACE_SECONDS: ForgeConfigSpec.IntValue
    val PLAYER_WEIGHT: ForgeConfigSpec.IntValue
    val MOB_WEIGHT: ForgeConfigSpec.IntValue
    val HOSTILE_MOB_WEIGHT: ForgeConfigSpec.IntValue
    val PLANE_WEIGHT: ForgeConfigSpec.IntValue
    val TANK_WEIGHT: ForgeConfigSpec.IntValue

    /** The five weight options as one object, for classifying a target by [TargetCategory]. */
    val WEIGHTS: TargetWeights
    val MOB_SCAN_RANGE: ForgeConfigSpec.IntValue
    val RANDOM_TARGETING: ForgeConfigSpec.BooleanValue
    val ORBIT_RADIUS: ForgeConfigSpec.IntValue

    val CANNON_DAMAGE_MULT: ForgeConfigSpec.DoubleValue
    val ROCKET_DAMAGE_MULT: ForgeConfigSpec.DoubleValue
    val BOMB_DAMAGE_MULT: ForgeConfigSpec.DoubleValue
    val MISSILE_DAMAGE_MULT: ForgeConfigSpec.DoubleValue

    val CANNON_ENABLED: ForgeConfigSpec.BooleanValue
    val ROCKETS_ENABLED: ForgeConfigSpec.BooleanValue
    val BOMBS_ENABLED: ForgeConfigSpec.BooleanValue
    val MISSILES_ENABLED: ForgeConfigSpec.BooleanValue

    val GUN_RUN_CHANCE: ForgeConfigSpec.IntValue
    val GUN_RUN_BOMB_CHANCE: ForgeConfigSpec.IntValue
    val ROCKET_RUN_CHANCE: ForgeConfigSpec.IntValue
    val BOMB_RUN_CHANCE: ForgeConfigSpec.IntValue
    val NUKE_ENABLED: ForgeConfigSpec.BooleanValue
    val NUKE_RUN_CHANCE: ForgeConfigSpec.IntValue
    val MISSILE_ORBIT_CHANCE: ForgeConfigSpec.IntValue
    val ROCKET_MISSILE_RUN_CHANCE: ForgeConfigSpec.IntValue
    val DOUBLE_GUN_RUN_CHANCE: ForgeConfigSpec.IntValue
    val CANNON_ROCKET_STALL_CHANCE: ForgeConfigSpec.IntValue
    val ROCKET_BOMB_STALL_CHANCE: ForgeConfigSpec.IntValue

    val LIMITED_AMMO: ForgeConfigSpec.BooleanValue
    val CANNON_ROUNDS: ForgeConfigSpec.IntValue
    val ROCKET_COUNT: ForgeConfigSpec.IntValue
    val BOMB_COUNT: ForgeConfigSpec.IntValue
    val NUKE_COUNT: ForgeConfigSpec.IntValue
    val MISSILE_COUNT: ForgeConfigSpec.IntValue
    val FLARE_COUNT: ForgeConfigSpec.IntValue
    val RESUPPLY_SECONDS: ForgeConfigSpec.IntValue
    val IDLE_RESUPPLY_SECONDS: ForgeConfigSpec.IntValue
    val IDLE_RESUPPLY_CHANCE: ForgeConfigSpec.IntValue

    val DEFENSIVE_FLARES: ForgeConfigSpec.BooleanValue
    val FLARE_MISS_CHANCE: ForgeConfigSpec.IntValue
    val DEFAULT_FLARE_CHANCE: ForgeConfigSpec.IntValue
    val BARREL_ROLL_FLARE_CHANCE: ForgeConfigSpec.IntValue
    val CLIMB_FLARE_CHANCE: ForgeConfigSpec.IntValue
    val SIDE_FLARES_CHANCE: ForgeConfigSpec.IntValue
    val TAUNT_ENABLED: ForgeConfigSpec.BooleanValue
    val LAST_KNOWN_STRIKE_SECONDS: ForgeConfigSpec.IntValue
    val IGNORE_BLOCK_COLLISIONS: ForgeConfigSpec.BooleanValue
    val COLLISION_DAMAGE: ForgeConfigSpec.BooleanValue
    val UNSTUCK_TELEPORT: ForgeConfigSpec.BooleanValue
    val UNSTUCK_SECONDS: ForgeConfigSpec.IntValue
    val BOSS_BAR_RANGE: ForgeConfigSpec.IntValue
    val XP_REWARD: ForgeConfigSpec.IntValue
    val BLOCK_DAMAGE: ForgeConfigSpec.BooleanValue

    /** Which boss types this boss counts as teammates (never targeted). */
    val TEAMMATES: TeammateSettings

    /** Every option in file order (grouped by section); the in-game config screen is built from this. */
    val ALL: List<ForgeConfigSpec.ConfigValue<*>>

    /** Options the config screen draws as sliders, with their range: the four target weights. */
    val SLIDERS: Map<ForgeConfigSpec.ConfigValue<*>, IntRange>

    init {
        val b = ForgeConfigSpec.Builder()

        b.comment("A-10 Warthog boss: The Plane That Burns the Sky").push("a10_boss")

        MAX_HEALTH = b
            .comment("Boss health. The boss bar empties at 10% of this, when the plane starts to crash. Applied when the boss is first created.")
            .defineInRange("maxHealth", 2000, 100, 1_000_000)

        CRUISE_HEIGHT = b
            .comment("Cruise altitude in blocks above the target player.")
            .defineInRange("cruiseHeightAboveTarget", 40, 20, 400)

        AGGRO_RANGE = b
            .comment("Range (blocks) in which the boss picks a player as target. Unused if globalAggro is on.")
            .defineInRange("aggroRange", 256, 32, 1024)

        GLOBAL_AGGRO = b
            .comment("Hunt the nearest player anywhere in the dimension and show the boss bar to everyone.")
            .define("globalAggro", true)

        TARGETING_METHOD = b
            .comment(
                "How well the boss keeps track of its target. STATIC: it always knows exactly where the target is.",
                "DYNAMIC: a target with a cover block above it is hidden and the boss attacks the last position it saw it at;",
                "once the target is under open sky again it is tracked live. Blocks in the superbenemies:boss_see_through",
                "block tag (glass, leaves, fences, carpets...) and blocks without collision (water, plants) are not cover.",
                "With DYNAMIC the boss prefers any exposed target and only keeps attacking a hidden one when there is none."
            )
            .defineEnum("targetingMethod", TargetingMethod.DYNAMIC)

        SIGHT_LOSS_GRACE_SECONDS = b
            .comment(
                "DYNAMIC targeting only. How many seconds the boss keeps tracking a target's real position after it breaks",
                "line of sight, as a general sense of where it went, before it finally loses sight and falls back to the last",
                "spot it saw it at. A target that was hidden when first chosen gets no grace. 0 = off: sight is lost at once.",
                "Everything that counts from losing sight (last-known strikes, searching, giving up) starts after this time."
            )
            .defineInRange("sightLossGraceSeconds", 5, 0, 30)

        PLAYER_WEIGHT = b
            .comment(
                "How much the boss wants to attack players (and player dummies that bosses target), from 0 to 5. 0 = never.",
                "With randomTargeting the weight is the relative chance of being drawn; otherwise a weight-5 target",
                "counts as 5x closer than a weight-1 target."
            )
            .defineInRange("playerWeight", 5, 0, MAX_TARGET_WEIGHT)

        MOB_WEIGHT = b
            .comment(
                "Same for non-hostile mobs: animals, villagers, golems. 0 = never.",
                "Mobs, hostile or not, are only engaged under open sky. Player dummies follow bossesTargetDummy and count as players."
            )
            .defineInRange("mobWeight", 0, 0, MAX_TARGET_WEIGHT)

        HOSTILE_MOB_WEIGHT = b
            .comment("Same for hostile mobs (monsters). 0 = never.")
            .defineInRange("hostileMobWeight", 0, 0, MAX_TARGET_WEIGHT)

        PLANE_WEIGHT = b
            .comment(
                "EXPERIMENTAL. Same for manned airplanes, helicopters and airships and for other plane bosses. 0 = never.",
                "The normal attack patterns are reused, so fast movers are often missed."
            )
            .defineInRange("planeWeight", 0, 0, MAX_TARGET_WEIGHT)

        TANK_WEIGHT = b
            .comment(
                "EXPERIMENTAL. Same for manned tanks, APCs and anti-air vehicles (and the tank bosses). 0 = never.",
                "Under DYNAMIC targeting a tank hidden in smoke is attacked at the spot where it was last seen."
            )
            .defineInRange("tankWeight", 0, 0, MAX_TARGET_WEIGHT)

        WEIGHTS = TargetWeights(PLAYER_WEIGHT, MOB_WEIGHT, HOSTILE_MOB_WEIGHT, PLANE_WEIGHT, TANK_WEIGHT)

        MOB_SCAN_RANGE = b
            .comment("Range (blocks) in which mobs are considered as targets, whatever globalAggro says. Only used when mobWeight or hostileMobWeight is above 0.")
            .defineInRange("mobScanRange", 128, 32, 256)

        RANDOM_TARGETING = b
            .comment(
                "Pick a random target at the start of every attack, drawn by the target weights (playerWeight, mobWeight...).",
                "Never swapped mid-dive or bomb run."
            )
            .define("randomTargeting", true)

        ORBIT_RADIUS = b
            .comment("Radius (blocks) of the missile orbit and of idling over its home.")
            .defineInRange("orbitRadius", 90, 40, 400)

        b.pop().push("damage")

        CANNON_DAMAGE_MULT = b
            .comment("Damage multiplier for the GAU-8 cannon. Applied when the boss is first created.")
            .defineInRange("cannonDamageMultiplier", 1.0, 0.0, 100.0)
        ROCKET_DAMAGE_MULT = b
            .comment("Damage multiplier for 70mm rockets.")
            .defineInRange("rocketDamageMultiplier", 1.0, 0.0, 100.0)
        BOMB_DAMAGE_MULT = b
            .comment("Damage multiplier for Mk 82 bombs.")
            .defineInRange("bombDamageMultiplier", 1.0, 0.0, 100.0)
        MISSILE_DAMAGE_MULT = b
            .comment("Damage multiplier for AGM-65 missiles.")
            .defineInRange("missileDamageMultiplier", 1.0, 0.0, 100.0)

        BLOCK_DAMAGE = b
            .comment("Whether weapons and the crash explosion break blocks. Applied when the boss is first created.")
            .define("blockDamage", true)

        b.pop().push("weapons")

        CANNON_ENABLED = b
            .comment("Whether the boss may use the GAU-8 cannon. Off removes both gun runs. With every weapon off it only flies and circles.")
            .define("cannonEnabled", true)
        ROCKETS_ENABLED = b
            .comment("Whether the boss may use 70mm rockets. Off removes the rocket run.")
            .define("rocketsEnabled", true)
        BOMBS_ENABLED = b
            .comment("Whether the boss may drop Mk 82 bombs. Off removes the bomb run and the gun run with bombs.")
            .define("bombsEnabled", true)
        MISSILES_ENABLED = b
            .comment("Whether the boss may fire AGM-65 missiles. Off removes the missile orbit.")
            .define("missilesEnabled", true)

        GUN_RUN_CHANCE = b
            .comment(
                "Weight (percent) of plain gun runs. Attack chances are relative weights among attacks whose weapons",
                "are enabled and have ammo, so they need not add up to 100. 0 = never. Needs cannonEnabled."
            )
            .defineInRange("gunRunChance", 25, 0, 100)
        GUN_RUN_BOMB_CHANCE = b
            .comment("Weight (percent) of gun runs with bombs: strafe, then drop bombs up close. Needs cannonEnabled and bombsEnabled.")
            .defineInRange("gunRunWithBombsChance", 25, 0, 100)
        ROCKET_RUN_CHANCE = b
            .comment("Weight (percent) of 70mm rocket runs. Needs rocketsEnabled.")
            .defineInRange("rocketRunChance", 20, 0, 100)
        BOMB_RUN_CHANCE = b
            .comment("Weight (percent) of straight Mk 82 bomb runs. Needs bombsEnabled.")
            .defineInRange("bombRunChance", 20, 0, 100)
        NUKE_ENABLED = b
            .comment("Whether the boss may use the single Mk 84 bomb variant. Needs bombsEnabled.")
            .define("nukeEnabled", true)
        NUKE_RUN_CHANCE = b
            .comment("Weight (percent) of single-bomb Mk 84 nuke runs. Needs nukeEnabled and bombsEnabled.")
            .defineInRange("nukeRunChance", 5, 0, 100)
        MISSILE_ORBIT_CHANCE = b
            .comment("Weight (percent) of AGM-65 missile orbits. Needs missilesEnabled.")
            .defineInRange("missileOrbitChance", 10, 0, 100)
        ROCKET_MISSILE_RUN_CHANCE = b
            .comment("Weight (percent) of rocket runs that also launch missiles. Needs rocketsEnabled and missilesEnabled.")
            .defineInRange("rocketMissileRunChance", 10, 0, 100)
        DOUBLE_GUN_RUN_CHANCE = b
            .comment("Weight (percent) of double gun runs: a gun run, a climbing stall turn, then a second run. Needs cannonEnabled.")
            .defineInRange("doubleGunRunChance", 10, 0, 100)
        CANNON_ROCKET_STALL_CHANCE = b
            .comment("Weight (percent) of cannon-to-rocket runs: a gun run, a climbing stall turn, then a rocket run. Needs cannonEnabled and rocketsEnabled.")
            .defineInRange("cannonRocketStallChance", 10, 0, 100)
        ROCKET_BOMB_STALL_CHANCE = b
            .comment("Weight (percent) of rocket-to-bomb runs: a rocket run, a climbing stall turn, then a bomb pass. Needs rocketsEnabled and bombsEnabled.")
            .defineInRange("rocketBombStallChance", 10, 0, 100)

        b.pop().push("ammo")

        LIMITED_AMMO = b
            .comment("Finite ammo. When spent, the boss flies away to resupply, then returns with a full load.")
            .define("limitedAmmo", false)
        CANNON_ROUNDS = b
            .comment("Cannon rounds per load (a gun run burst is about 40-70).")
            .defineInRange("cannonRounds", 600, 1, 100_000)
        ROCKET_COUNT = b
            .comment("70mm rockets per load.")
            .defineInRange("rockets", 56, 1, 10_000)
        BOMB_COUNT = b
            .comment("Mk 82 bombs per load (a bomb run drops 3).")
            .defineInRange("bombs", 9, 1, 1_000)
        NUKE_COUNT = b
            .comment("Mk 84 bombs per load (a nuke run drops 1).")
            .defineInRange("nukes", 1, 1, 100)
        MISSILE_COUNT = b
            .comment("AGM-65 missiles per load.")
            .defineInRange("missiles", 8, 1, 1_000)
        FLARE_COUNT = b
            .comment("Flare bursts per load when limitedAmmo is on.")
            .defineInRange("flares", 24, 1, 1_000)
        RESUPPLY_SECONDS = b
            .comment("Seconds a withdrawn boss stays gone before its replacement (same health, full load) appears near the fight. The flight away is extra.")
            .defineInRange("resupplySeconds", 40, 0, 3_600)
        IDLE_RESUPPLY_SECONDS = b
            .comment(
                "Seconds without a target after which a boss with some ammo or flares spent may leave to rearm",
                "(needs limitedAmmo). 0 = never; a full load never triggers it."
            )
            .defineInRange("idleResupplySeconds", 120, 0, 3_600)
        IDLE_RESUPPLY_CHANCE = b
            .comment("Chance (percent) rolled every 10 seconds once idleResupplySeconds has passed, to go and rearm.")
            .defineInRange("idleResupplyChance", 25, 0, 100)

        b.pop().push("behaviour")

        DEFENSIVE_FLARES = b
            .comment("Whether the boss releases flares against homing missiles. Off by default so missiles can hit.")
            .define("defensiveFlares", false)

        FLARE_MISS_CHANCE = b
            .comment("With defensiveFlares: chance (percent) of missing an incoming missile and releasing no flares.")
            .defineInRange("flareMissChance", 10, 0, 100)

        DEFAULT_FLARE_CHANCE = b
            .comment("Relative chance (percent) of releasing flares without an evasive maneuver. Disabled when defensiveFlares is off.")
            .defineInRange("defaultFlareChance", 25, 0, 100)

        BARREL_ROLL_FLARE_CHANCE = b
            .comment("Relative chance (percent) of using a barrel roll while releasing flares. Disabled when defensiveFlares is off.")
            .defineInRange("barrelRollFlareChance", 25, 0, 100)

        CLIMB_FLARE_CHANCE = b
            .comment("Relative chance (percent) of climbing while releasing flares. Disabled when defensiveFlares is off.")
            .defineInRange("climbFlareChance", 25, 0, 100)

        SIDE_FLARES_CHANCE = b
            .comment("Relative chance (percent) of yawing and pitching sideways while releasing flares. Disabled when defensiveFlares is off.")
            .defineInRange("sideFlaresChance", 25, 0, 100)

        TAUNT_ENABLED = b
            .comment("Whether the boss may taunt: a low flyby or barrel roll near the player, dropping flares. Harmless.")
            .define("tauntEnabled", true)

        LAST_KNOWN_STRIKE_SECONDS = b
            .comment(
                "Seconds the boss keeps attacking the spot where it lost its target (out of range or no longer valid)",
                "before it goes back to circling. A new target ends it early. 0 = off."
            )
            .defineInRange("lastKnownStrikeSeconds", 20, 0, 300)

        IGNORE_BLOCK_COLLISIONS = b
            .comment(
                "Fly straight through terrain and buildings with no collision damage. A crippled boss collides",
                "normally again so its wreck still crashes."
            )
            .define("ignoreBlockCollisions", false)

        COLLISION_DAMAGE = b
            .comment(
                "Take damage when hitting terrain or buildings. Off: the plane still collides and bounces but takes no",
                "crash damage. A crippled plane always takes it."
            )
            .define("collisionDamage", true)

        UNSTUCK_TELEPORT = b
            .comment(
                "Teleport the plane up into open air when it has stayed within a few blocks of one spot for",
                "unstuckSeconds (wedged in terrain or a building). It keeps its heading and flies on."
            )
            .define("unstuckTeleport", true)

        UNSTUCK_SECONDS = b
            .comment("Seconds a plane may stay on one spot before unstuckTeleport moves it.")
            .defineInRange("unstuckSeconds", 15, 5, 300)

        BOSS_BAR_RANGE = b
            .comment("Players within this many blocks see the boss bar.")
            .defineInRange("bossBarRange", 300, 32, 1024)

        XP_REWARD = b
            .comment("Experience dropped when the boss is defeated.")
            .defineInRange("xpReward", 500, 0, 100_000)

        b.pop()

        TEAMMATES = defineTeammates(b, "a10", BossKind.A10)

        b.pop()
        SPEC = b.build()

        SLIDERS = listOf<ForgeConfigSpec.ConfigValue<*>>(PLAYER_WEIGHT, MOB_WEIGHT, HOSTILE_MOB_WEIGHT, PLANE_WEIGHT, TANK_WEIGHT)
            .associateWith { 0..MAX_TARGET_WEIGHT } + (SIGHT_LOSS_GRACE_SECONDS to 0..30)

        ALL = listOf(
            MAX_HEALTH, CRUISE_HEIGHT, AGGRO_RANGE, GLOBAL_AGGRO, TARGETING_METHOD, SIGHT_LOSS_GRACE_SECONDS, PLAYER_WEIGHT, MOB_WEIGHT, HOSTILE_MOB_WEIGHT, PLANE_WEIGHT, TANK_WEIGHT,
            MOB_SCAN_RANGE, RANDOM_TARGETING, ORBIT_RADIUS,
            CANNON_DAMAGE_MULT, ROCKET_DAMAGE_MULT, BOMB_DAMAGE_MULT, MISSILE_DAMAGE_MULT, BLOCK_DAMAGE,
            CANNON_ENABLED, ROCKETS_ENABLED, BOMBS_ENABLED, MISSILES_ENABLED,
            GUN_RUN_CHANCE, GUN_RUN_BOMB_CHANCE, ROCKET_RUN_CHANCE, BOMB_RUN_CHANCE, NUKE_ENABLED, NUKE_RUN_CHANCE, MISSILE_ORBIT_CHANCE,
            ROCKET_MISSILE_RUN_CHANCE, DOUBLE_GUN_RUN_CHANCE, CANNON_ROCKET_STALL_CHANCE, ROCKET_BOMB_STALL_CHANCE,
            LIMITED_AMMO, CANNON_ROUNDS, ROCKET_COUNT, BOMB_COUNT, NUKE_COUNT, MISSILE_COUNT, FLARE_COUNT, RESUPPLY_SECONDS,
            IDLE_RESUPPLY_SECONDS, IDLE_RESUPPLY_CHANCE,
            DEFENSIVE_FLARES, FLARE_MISS_CHANCE, DEFAULT_FLARE_CHANCE, BARREL_ROLL_FLARE_CHANCE, CLIMB_FLARE_CHANCE,
            SIDE_FLARES_CHANCE, TAUNT_ENABLED, LAST_KNOWN_STRIKE_SECONDS, IGNORE_BLOCK_COLLISIONS, COLLISION_DAMAGE, UNSTUCK_TELEPORT, UNSTUCK_SECONDS, BOSS_BAR_RANGE, XP_REWARD
        ) + TEAMMATES.toggles.values
    }
}
