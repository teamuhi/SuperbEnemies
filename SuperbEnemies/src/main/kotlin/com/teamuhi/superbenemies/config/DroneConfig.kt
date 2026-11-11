package com.teamuhi.superbenemies.config

import net.minecraftforge.common.ForgeConfigSpec

/**
 * Common config of the drone, written to `config/superbenemies-drone-common.toml`.
 *
 * Every option is read live (the drone and its goals re-check them while they tick), so changing one affects drones that
 * are already flying. Section names carry a `drone` prefix so the in-game config screen's header keys stay unique across
 * all the mod's files.
 */
object DroneConfig {
    val SPEC: ForgeConfigSpec

    // [drone]
    val MAX_HEALTH: ForgeConfigSpec.IntValue
    val ARMOR: ForgeConfigSpec.DoubleValue
    val ARMOR_TOUGHNESS: ForgeConfigSpec.DoubleValue
    val INVULNERABLE: ForgeConfigSpec.BooleanValue
    val SHOW_LABEL: ForgeConfigSpec.BooleanValue
    val SHOW_DEATH_MESSAGE: ForgeConfigSpec.BooleanValue
    val SHOW_COUNTER: ForgeConfigSpec.BooleanValue
    val ENGINE_VOLUME: ForgeConfigSpec.IntValue

    // [drone_movement]
    val FLY_SPEED: ForgeConfigSpec.DoubleValue
    val AGILITY: ForgeConfigSpec.DoubleValue
    val EVASIVE: ForgeConfigSpec.BooleanValue
    val FAN_OUT: ForgeConfigSpec.BooleanValue
    val JINK_SECONDS: ForgeConfigSpec.IntValue
    val DODGE_PROJECTILES: ForgeConfigSpec.BooleanValue
    val DODGE_CHANCE: ForgeConfigSpec.IntValue

    // [drone_targeting]
    val PLAYER_WEIGHT: ForgeConfigSpec.IntValue
    val TANK_WEIGHT: ForgeConfigSpec.IntValue
    val TANK_DRONE_WEIGHT: ForgeConfigSpec.IntValue
    val DETECTION_RANGE: ForgeConfigSpec.IntValue
    val CHASE_RANGE: ForgeConfigSpec.IntValue
    val CHASE_CREATIVE: ForgeConfigSpec.BooleanValue
    val REQUIRE_LINE_OF_SIGHT: ForgeConfigSpec.BooleanValue

    // [drone_shell]
    val EXPLOSION_DAMAGE: ForgeConfigSpec.DoubleValue
    val TANK_EXPLOSION_DAMAGE: ForgeConfigSpec.DoubleValue
    val EXPLOSION_RADIUS: ForgeConfigSpec.DoubleValue
    val DETONATE_DISTANCE: ForgeConfigSpec.DoubleValue
    val ARM_SECONDS: ForgeConfigSpec.IntValue
    val DESTROY_BLOCKS: ForgeConfigSpec.BooleanValue

    // [drone_alert]
    val ALERT_DRONES: ForgeConfigSpec.BooleanValue
    val ALERT_RADIUS: ForgeConfigSpec.IntValue
    val ALERT_SECONDS: ForgeConfigSpec.IntValue

    /** Integer options shown as a 0 to 5 slider in the in-game config screen. */
    val SLIDERS: Map<ForgeConfigSpec.ConfigValue<*>, IntRange>

    /** Every option in file order (grouped by section); the in-game config screen is built from this. */
    val ALL: List<ForgeConfigSpec.ConfigValue<*>>

    init {
        val b = ForgeConfigSpec.Builder()

        b.comment(
            "Drone: a small, fast, fragile drone carrying a mortar shell. It chases players, tells the other drones where",
            "its target is and detonates next to it"
        ).push("drone")

        MAX_HEALTH = b
            .comment(
                "Health of every drone. Applied to existing drones too (health above the new maximum is clamped).",
                "Capped at 1024, the vanilla limit of the max health attribute.",
                "Read live."
            )
            .defineInRange("maxHealth", 6, 1, 1024)

        ARMOR = b
            .comment("Armor points of every drone (20 is a full set of vanilla armor). Read live.")
            .defineInRange("armor", 0.0, 0.0, 30.0)

        ARMOR_TOUGHNESS = b
            .comment("Armor toughness of every drone (a full set of netherite armor gives 12). Read live.")
            .defineInRange("armorToughness", 0.0, 0.0, 20.0)

        INVULNERABLE = b
            .comment(
                "Drones take no damage. Hits still flash and make the hurt sound, and the drone still dashes.",
                "/kill and the void still remove a drone. Read live."
            )
            .define("invulnerable", false)

        SHOW_LABEL = b
            .comment(
                "Show Superb Warfare's vehicle label (name, health bar and distance) above a drone while you look at it.",
                "It follows Superb Warfare's own vehicle info display options. Read live."
            )
            .define("showLabel", true)

        SHOW_DEATH_MESSAGE = b
            .comment("Announce in chat when a drone is shot down, e.g. \"Drone was shot by Steve\". Read live.")
            .define("showDeathMessage", false)

        SHOW_COUNTER = b
            .comment("Show the number of loaded drones on the client hotbar. Read live.")
            .define("showCounter", false)

        ENGINE_VOLUME = b
            .comment(
                "Volume of the drone's engine buzz (Superb Warfare's drone engine loop) in percent: 100 is the default,",
                "200 is twice as loud, 0 turns it off. It gets louder and higher-pitched with speed. Client side, read live",
                "for drones that are already buzzing; a drone that was silent starts buzzing when it next enters view."
            )
            .defineInRange("engineVolume", 100, 0, 200)

        b.pop().push("drone_movement")

        FLY_SPEED = b
            .comment("Top speed of the drone in blocks per second (a sprinting player runs about 5.6). Read live.")
            .defineInRange("flySpeed", 14.0, 1.0, 40.0)

        AGILITY = b
            .comment(
                "How fast the drone speeds up, brakes and changes direction, as a multiplier (1.0 is the default,",
                "higher turns on a dime, lower drifts). Read live."
            )
            .defineInRange("agility", 1.0, 0.1, 4.0)

        EVASIVE = b
            .comment(
                "The drone weaves from side to side on its way to the target, changes its weave at random and dashes",
                "away when it is hit. With this off it flies straight at the target. Read live."
            )
            .define("evasive", true)

        FAN_OUT = b
            .comment(
                "A swarm does not blow up in one cluster: each drone flies up to its target, strafes round it and then dives",
                "in from a random side (left, right, front, back or above), preferring a side no other drone of the swarm",
                "has taken, and drones keep apart from each other. With this off every drone flies straight at the target.",
                "Read live."
            )
            .define("fanOut", true)

        JINK_SECONDS = b
            .comment("EVASIVE: average seconds between two sudden changes of the weave. Read live.")
            .defineInRange("jinkSeconds", 2, 1, 10)

        DODGE_PROJECTILES = b
            .comment(
                "The drone sees bullets, shells and rockets coming at it and sidesteps them. It cannot dodge what hits",
                "before it can react (hitscan, very close shots). Read live."
            )
            .define("dodgeProjectiles", true)

        DODGE_CHANCE = b
            .comment("DODGE PROJECTILES: percent chance that the drone notices a shot in time and sidesteps it (0-100). Read live.")
            .defineInRange("dodgeChance", 70, 0, 100)

        b.pop().push("drone_targeting")

        PLAYER_WEIGHT = b
            .comment(
                "How much the drone wants to attack players, 0 to 5 like the bosses' target weights: 0 means it never",
                "chases or blows up next to a player (and never reports one to the other drones), 5 counts a player as five",
                "times closer than a weight-1 target. Read live."
            )
            .defineInRange("playerWeight", 5, 0, MAX_TARGET_WEIGHT)

        TANK_WEIGHT = b
            .comment(
                "How much the drone wants to attack tanks, 0 to 5: any manned armoured vehicle and the tank bosses (LAV-25,",
                "M1A2, Bradley, ZTZ-99A, unless crippled). 0 means it ignores them. It prefers whatever has the lowest",
                "distance divided by weight, so a far tank with a high weight can beat a near player. Read live."
            )
            .defineInRange("tankWeight", 0, 0, MAX_TARGET_WEIGHT)

        TANK_DRONE_WEIGHT = b
            .comment(
                "The other way round: how much the tank bosses (LAV-25, M1A2, Bradley, ZTZ-99A) want to shoot drones, 0 to 5:",
                "0 means they ignore drones, 5 counts a drone as five times closer. The A-10 and AC-130 never shoot",
                "drones. Read live."
            )
            .defineInRange("tankDroneWeight", 4, 0, MAX_TARGET_WEIGHT)

        DETECTION_RANGE = b
            .comment(
                "Detection range: farthest distance (blocks) at which a drone notices a new target (and reports it",
                "to the other drones). Read live."
            )
            .defineInRange("detectionRange", 64, 8, 256)

        CHASE_RANGE = b
            .comment(
                "Farthest distance (blocks) a drone keeps following a target it has already noticed before it gives up.",
                "Never less than detectionRange. Read live."
            )
            .defineInRange("chaseRange", 96, 8, 512)

        CHASE_CREATIVE = b
            .comment(
                "The drone also chases (and blows up next to) creative-mode players. Bosses ignore creative players.",
                "Spectators are never chased. Read live."
            )
            .define("chaseCreative", true)

        REQUIRE_LINE_OF_SIGHT = b
            .comment(
                "The drone only notices and reports a target it has a clear line of sight to (glass, leaves and the like",
                "do not block it). Once a target is reported the other drones keep the report for alertSeconds. Read live."
            )
            .define("requireLineOfSight", true)

        b.pop().push("drone_shell")

        EXPLOSION_DAMAGE = b
            .comment("Damage of the mortar shell the drone carries (Superb Warfare's own mortar shell does 100). Read live.")
            .defineInRange("explosionDamage", 60.0, 0.0, 100000.0)

        TANK_EXPLOSION_DAMAGE = b
            .comment(
                "Damage of the same blast against vehicles (tanks and the tank bosses, any other Superb Warfare vehicle in",
                "range), with the same falloff by distance. explosionDamage still applies to everything else. Read live."
            )
            .defineInRange("tankExplosionDamage", 60.0, 0.0, 100000.0)

        EXPLOSION_RADIUS = b
            .comment("Blast radius (blocks) of the shell (Superb Warfare's own mortar shell has 8). Read live.")
            .defineInRange("explosionRadius", 5.0, 0.0, 64.0)

        DETONATE_DISTANCE = b
            .comment("The drone blows up when it is this close (blocks) to its target's hitbox. Read live.")
            .defineInRange("detonateDistance", 3.0, 0.5, 16.0)

        ARM_SECONDS = b
            .comment(
                "The shell is safe for this many seconds after the drone appears, so a drone spawned next to a player",
                "does not blow up at once. Read live."
            )
            .defineInRange("armSeconds", 2, 0, 60)

        DESTROY_BLOCKS = b
            .comment("The blast breaks blocks (it also needs Superb Warfare's own explosion block damage on). Read live.")
            .define("destroyBlocks", false)

        b.pop().push("drone_alert")

        ALERT_DRONES = b
            .comment(
                "While a drone sees its target (a player or a tank) the other drones are told where it is: they chase",
                "it from any distance and through cover, whatever their detectionRange and line of sight say. Their own",
                "weights still apply (a drone never goes for what it has weight 0 for). Read live."
            )
            .define("alertDrones", true)

        ALERT_RADIUS = b
            .comment(
                "Only drones within this many blocks (horizontally) of the drone that saw the target are told. 0 = every",
                "drone in the dimension. Read live."
            )
            .defineInRange("alertRadius", 0, 0, 2048)

        ALERT_SECONDS = b
            .comment("How long (seconds) the other drones keep the report after the drone last saw the target. Read live.")
            .defineInRange("alertSeconds", 15, 1, 300)

        b.pop()
        SPEC = b.build()

        SLIDERS = listOf(PLAYER_WEIGHT, TANK_WEIGHT, TANK_DRONE_WEIGHT).associateWith { 0..MAX_TARGET_WEIGHT }
        ALL = listOf(
            MAX_HEALTH, ARMOR, ARMOR_TOUGHNESS, INVULNERABLE, SHOW_LABEL, SHOW_DEATH_MESSAGE, SHOW_COUNTER, ENGINE_VOLUME,
            FLY_SPEED, AGILITY, EVASIVE, FAN_OUT, JINK_SECONDS, DODGE_PROJECTILES, DODGE_CHANCE,
            PLAYER_WEIGHT, TANK_WEIGHT, TANK_DRONE_WEIGHT, DETECTION_RANGE, CHASE_RANGE, CHASE_CREATIVE, REQUIRE_LINE_OF_SIGHT,
            EXPLOSION_DAMAGE, TANK_EXPLOSION_DAMAGE, EXPLOSION_RADIUS, DETONATE_DISTANCE, ARM_SECONDS, DESTROY_BLOCKS,
            ALERT_DRONES, ALERT_RADIUS, ALERT_SECONDS
        )
    }
}
