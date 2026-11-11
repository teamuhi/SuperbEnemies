package com.teamuhi.superbenemies.config

import net.minecraftforge.common.ForgeConfigSpec

/**
 * Common config of the LAV-25 boss, written to `config/superbenemies-lav25-common.toml`.
 *
 * Same layout as [Ac130Config] / [BossConfig] but for a driving boss. Section names carry a `lav25_` prefix so the in-game
 * config screen's header keys stay unique across the files. The shared tank code reads everything through [SETTINGS].
 *
 * Health, damage multipliers and block damage are applied to a boss once, when it is first created (they are stored in the
 * entity data afterwards). Everything else is read live by the AI.
 */
object Lav25Config {
    val SPEC: ForgeConfigSpec

    val MAX_HEALTH: ForgeConfigSpec.IntValue
    val AGGRO_RANGE: ForgeConfigSpec.IntValue
    val GLOBAL_AGGRO: ForgeConfigSpec.BooleanValue
    val TARGETING_METHOD: ForgeConfigSpec.EnumValue<TargetingMethod>
    val PLAYER_WEIGHT: ForgeConfigSpec.IntValue
    val MOB_WEIGHT: ForgeConfigSpec.IntValue
    val HOSTILE_MOB_WEIGHT: ForgeConfigSpec.IntValue
    val PLANE_WEIGHT: ForgeConfigSpec.IntValue
    val TANK_WEIGHT: ForgeConfigSpec.IntValue

    /** The five weight options as one object, for classifying a target by [TargetCategory]. */
    val WEIGHTS: TargetWeights
    val MOB_SCAN_RANGE: ForgeConfigSpec.IntValue
    val RANDOM_TARGETING: ForgeConfigSpec.BooleanValue
    val RANDOM_TARGET_SECONDS: ForgeConfigSpec.IntValue
    val ENGAGE_RANGE: ForgeConfigSpec.IntValue
    val SIGHT_RANGE: ForgeConfigSpec.IntValue
    val SIGHT_CONE_DEGREES: ForgeConfigSpec.IntValue
    val SIGHT_CONE_RANGE_MULTIPLIER: ForgeConfigSpec.DoubleValue
    val HEARING_RANGE: ForgeConfigSpec.IntValue
    val CANNON_HEARING_PERCENT: ForgeConfigSpec.IntValue
    val MACHINE_GUN_HEARING_PERCENT: ForgeConfigSpec.IntValue
    val HEARING_SCATTER_PERCENT: ForgeConfigSpec.IntValue
    val SMOKE_SCOUT_RANGE: ForgeConfigSpec.IntValue
    val SEARCH_SECONDS: ForgeConfigSpec.IntValue
    val BREAK_DETECTION_SECONDS: ForgeConfigSpec.IntValue
    val DECISION_COMMIT_SECONDS: ForgeConfigSpec.IntValue
    val SIGHT_LOSS_GRACE_SECONDS: ForgeConfigSpec.IntValue
    val CLOSE_DETECTION_RANGE: ForgeConfigSpec.IntValue
    val LEASH_RANGE: ForgeConfigSpec.IntValue

    val CANNON_DAMAGE_MULT: ForgeConfigSpec.DoubleValue
    val MACHINE_GUN_DAMAGE_MULT: ForgeConfigSpec.DoubleValue
    val MISSILE_DAMAGE_MULT: ForgeConfigSpec.DoubleValue
    val BLOCK_DAMAGE: ForgeConfigSpec.BooleanValue

    val CANNON_ENABLED: ForgeConfigSpec.BooleanValue
    val MACHINE_GUN_ENABLED: ForgeConfigSpec.BooleanValue
    val MISSILE_ENABLED: ForgeConfigSpec.BooleanValue
    val CANNON_SHELL: ForgeConfigSpec.EnumValue<CannonShell>
    val MISSILE_COOLDOWN_SECONDS: ForgeConfigSpec.IntValue

    val LIMITED_AMMO: ForgeConfigSpec.BooleanValue
    val CANNON_ROUNDS: ForgeConfigSpec.IntValue
    val MACHINE_GUN_ROUNDS: ForgeConfigSpec.IntValue
    val MISSILE_ROUNDS: ForgeConfigSpec.IntValue
    val SMOKE_CHARGES: ForgeConfigSpec.IntValue
    val RESUPPLY_SECONDS: ForgeConfigSpec.IntValue
    val IDLE_RESUPPLY_SECONDS: ForgeConfigSpec.IntValue
    val IDLE_RESUPPLY_CHANCE: ForgeConfigSpec.IntValue

    val SMOKE_ENABLED: ForgeConfigSpec.BooleanValue
    val SMOKE_WHEN_HIT_CHANCE: ForgeConfigSpec.IntValue
    val SMOKE_AGAINST_MISSILES: ForgeConfigSpec.BooleanValue
    val SMOKE_AGAINST_AIR: ForgeConfigSpec.BooleanValue
    val SHOOT_DOWN_MISSILES: ForgeConfigSpec.BooleanValue
    val SHOOT_DOWN_FAIL_PERCENT: ForgeConfigSpec.IntValue
    val FLANKING_ENABLED: ForgeConfigSpec.BooleanValue
    val FLANK_CHANCE: ForgeConfigSpec.IntValue
    val RETURN_FIRE_ENABLED: ForgeConfigSpec.BooleanValue
    val RETURN_FIRE_SECONDS: ForgeConfigSpec.IntValue
    val USE_COVER: ForgeConfigSpec.BooleanValue
    val PULL_BACK_DAMAGE_PERCENT: ForgeConfigSpec.IntValue
    val WALL_BREACHING: ForgeConfigSpec.BooleanValue
    val CROSS_WATER: ForgeConfigSpec.BooleanValue
    val BOSS_BAR_RANGE: ForgeConfigSpec.IntValue
    val XP_REWARD: ForgeConfigSpec.IntValue

    /** Which boss types this boss counts as teammates: never targeted, and the tanks among them may form up with it. */
    val TEAMMATES: TeammateSettings
    val FORMATION_ENABLED: ForgeConfigSpec.BooleanValue
    val FORMATION_SPACING: ForgeConfigSpec.IntValue
    val FORMATION_HALT_SECONDS: ForgeConfigSpec.IntValue

    /** Every option the shared tank code reads, bundled (see [TankSettings]). */
    val SETTINGS: TankSettings

    /** Every option in file order (grouped by section); the in-game config screen is built from this. */
    val ALL: List<ForgeConfigSpec.ConfigValue<*>>

    /** Options the config screen draws as sliders, with their range: the five target weights. */
    val SLIDERS: Map<ForgeConfigSpec.ConfigValue<*>, IntRange>

    init {
        val b = ForgeConfigSpec.Builder()

        b.comment("LAV-25 boss: wheeled, amphibious APC with a 25mm cannon, a coaxial machine gun and a wire-guided missile").push("lav25_boss")

        MAX_HEALTH = b
            .comment("Boss health. The boss bar empties when the LAV-25 is destroyed and explodes. It fights to its last point of health.")
            .defineInRange("maxHealth", 1500, 100, 1_000_000)

        AGGRO_RANGE = b
            .comment("Range (blocks) in which the boss picks a target. Unused if globalAggro is on.")
            .defineInRange("aggroRange", 192, 32, 1024)

        GLOBAL_AGGRO = b
            .comment(
                "Hunt the nearest player anywhere in the dimension and show the boss bar to everyone.",
                "Off by default: a driving boss is slow, and it never loads chunks on the way."
            )
            .define("globalAggro", false)

        TARGETING_METHOD = b
            .comment(
                "How well the boss keeps track of its target. STATIC: inside aggroRange it always knows exactly where every",
                "target is and ignores smoke. DYNAMIC: it only sees what it has a clear line of sight to (blocks and smoke",
                "block it) within sightRange; a target that slips out of sight is searched for around the spot where it was",
                "last seen, and noises (gunfire, explosions, engines, footsteps) within hearingRange make it investigate."
            )
            .defineEnum("targetingMethod", TargetingMethod.DYNAMIC)

        PLAYER_WEIGHT = b
            .comment(
                "How much the boss wants to attack players (and player dummies that bosses target), from 0 to 5. 0 = never.",
                "With randomTargeting the weight is the relative chance of being drawn; otherwise a weight-5 target",
                "counts as 5x closer than a weight-1 target."
            )
            .defineInRange("playerWeight", 5, 0, MAX_TARGET_WEIGHT)

        MOB_WEIGHT = b
            .comment("Same for non-hostile mobs: animals, villagers, golems. 0 = never.")
            .defineInRange("mobWeight", 0, 0, MAX_TARGET_WEIGHT)

        HOSTILE_MOB_WEIGHT = b
            .comment("Same for hostile mobs (monsters). 0 = never.")
            .defineInRange("hostileMobWeight", 2, 0, MAX_TARGET_WEIGHT)

        PLANE_WEIGHT = b
            .comment(
                "Same for manned airplanes, helicopters and airships and for the plane bosses. 0 = never.",
                "Only engaged while the target is inside the elevation of the cannon (27 degrees); the 25mm gets no lead on fast flyers."
            )
            .defineInRange("planeWeight", 3, 0, MAX_TARGET_WEIGHT)

        TANK_WEIGHT = b
            .comment("Same for manned tanks, APCs and anti-air vehicles (and the other tank boss). 0 = never.")
            .defineInRange("tankWeight", 4, 0, MAX_TARGET_WEIGHT)

        WEIGHTS = TargetWeights(PLAYER_WEIGHT, MOB_WEIGHT, HOSTILE_MOB_WEIGHT, PLANE_WEIGHT, TANK_WEIGHT)

        MOB_SCAN_RANGE = b
            .comment("Range (blocks) in which mobs are considered as targets. Only used when mobWeight or hostileMobWeight is above 0.")
            .defineInRange("mobScanRange", 96, 32, 256)

        RANDOM_TARGETING = b
            .comment("Switch to a random target periodically, drawn by the target weights (playerWeight, mobWeight...).")
            .define("randomTargeting", true)

        RANDOM_TARGET_SECONDS = b
            .comment("With randomTargeting: average seconds on one target (varies by +-25%).")
            .defineInRange("randomTargetSeconds", 20, 5, 300)

        ENGAGE_RANGE = b
            .comment("Distance (blocks) the boss tries to hold from its target: it drives closer when farther, backs away when much closer.")
            .defineInRange("engageRange", 60, 15, 300)

        SIGHT_RANGE = b
            .comment("How far (blocks) the boss can see a target with DYNAMIC targeting. A clear line of sight is needed.")
            .defineInRange("sightRange", 160, 32, 512)

        SIGHT_CONE_DEGREES = b
            .comment(
                "With DYNAMIC targeting: full width (degrees) of the sight cone around the turret. Inside the cone the boss",
                "sees farther (sightConeRangeMultiplier times sightRange), so the turret sweeping round finds a distant",
                "sniper. Outside it the normal sightRange applies. 0 = no cone."
            )
            .defineInRange("sightConeDegrees", 40, 0, 180)
        SIGHT_CONE_RANGE_MULTIPLIER = b
            .comment("With DYNAMIC targeting: how many times farther than sightRange the boss sees inside the sight cone.")
            .defineInRange("sightConeRangeMultiplier", 1.5, 1.0, 4.0)

        HEARING_RANGE = b
            .comment(
                "How far (blocks) a loud noise (a gunshot, an explosion, an engine) carries to the boss with DYNAMIC targeting;",
                "quieter noises (footsteps, breaking blocks) carry a fraction of it. The boss drives there to investigate."
            )
            .defineInRange("hearingRange", 128, 16, 512)

        CANNON_HEARING_PERCENT = b
            .comment(
                "With DYNAMIC targeting: how well the boss hears the cannon fire of other tanks (shells, missiles, rockets), in percent of the",
                "Superb Warfare sound radius of the weapon (its audible distance: 16 blocks per radius point, 512 blocks for a 120mm cannon).",
                "The boss pinpoints the shooter and goes after it. 0 = deaf to cannon fire."
            )
            .defineInRange("cannonHearingPercent", 50, 0, 200)
        MACHINE_GUN_HEARING_PERCENT = b
            .comment(
                "Same for the machine gun fire of other vehicles (a coaxial machine gun has a sound radius of 12: 192 blocks at 100 percent).",
                "0 = deaf to machine gun fire."
            )
            .defineInRange("machineGunHearingPercent", 50, 0, 200)
        HEARING_SCATTER_PERCENT = b
            .comment(
                "How far off (percent of the distance) the boss places a shooter it heard, for cannon and machine gun fire. 0 = exact.",
                "A shooter 200 blocks away at 5 percent is placed within 10 blocks of where it really is."
            )
            .defineInRange("hearingScatterPercent", 5, 0, 30)
        SMOKE_SCOUT_RANGE = b
            .comment(
                "With DYNAMIC targeting: a smoke cloud (a tank's smoke launcher, a smoke grenade) tells the boss that a tank is about.",
                "With no target of its own it goes to scout the area of a fresh cloud it has a clear line to within this many blocks. 0 = ignores smoke."
            )
            .defineInRange("smokeScoutRange", 192, 0, 512)

        SEARCH_SECONDS = b
            .comment("With DYNAMIC targeting: seconds the boss sweeps the area around the spot where it lost its target before giving up.")
            .defineInRange("searchSeconds", 45, 5, 600)

        BREAK_DETECTION_SECONDS = b
            .comment(
                "With DYNAMIC targeting: seconds a target must stay out of sight before the boss loses it for good. A lost",
                "target is only detected again when it comes within closeDetectionRange or shoots the boss, even if it steps",
                "back into the open or makes noise. 0 = never: the boss searches for searchSeconds, then ignores the target for 30 seconds."
            )
            .defineInRange("breakDetectionSeconds", 8, 0, 600)

        DECISION_COMMIT_SECONDS = b
            .comment(
                "Seconds the boss sticks to a decision before it may change its mind: the way it fights (advance, back off, circle,",
                "peek, snipe, stand-off), where it goes to look at a noise, and how soon it drops between patrol, investigate and",
                "search. Urgent things (a target pressing in close, a blocked rear, a new target, a hit that sends it running) skip",
                "the wait. 0 = decides afresh every moment, the way it did before."
            )
            .defineInRange("decisionCommitSeconds", 2, 0, 10)
        SIGHT_LOSS_GRACE_SECONDS = b
            .comment(
                "Seconds after the line of sight to its target breaks in which the boss keeps tracking the target's real position",
                "(its general location) before it falls back to the last known spot. Only for a target it has seen. 0 = off: the",
                "boss goes for the last spot it saw at once."
            )
            .defineInRange("sightLossGraceSeconds", 5, 0, 30)

        CLOSE_DETECTION_RANGE = b
            .comment("With DYNAMIC targeting: a lost target this close (blocks) is detected again at once, seen or not.")
            .defineInRange("closeDetectionRange", 12, 2, 64)

        LEASH_RANGE = b
            .comment("How far (blocks) from its home point the boss wanders when it has nothing to do.")
            .defineInRange("leashRange", 256, 32, 1024)

        b.pop().push("lav25_damage")

        CANNON_DAMAGE_MULT = b
            .comment("Damage multiplier for the 25mm cannon (both shells). Applied when the boss is first created.")
            .defineInRange("cannon25mmDamageMultiplier", 1.0, 0.0, 100.0)
        MACHINE_GUN_DAMAGE_MULT = b
            .comment("Damage multiplier for the coaxial machine gun. Applied when the boss is first created.")
            .defineInRange("coaxMgDamageMultiplier", 1.0, 0.0, 100.0)
        MISSILE_DAMAGE_MULT = b
            .comment("Damage multiplier for the wire-guided missile. Applied when the boss is first created.")
            .defineInRange("wireMissileDamageMultiplier", 1.0, 0.0, 100.0)

        BLOCK_DAMAGE = b
            .comment("Whether shells and the wreck's explosion break blocks. Applied when the boss is first created.")
            .define("blockDamage", true)

        b.pop().push("lav25_weapons")

        CANNON_ENABLED = b
            .comment("Whether the boss may fire the 25mm cannon.")
            .define("cannon25mmEnabled", true)
        MACHINE_GUN_ENABLED = b
            .comment("Whether the boss may fire the coaxial machine gun (used against targets in the open within 80 blocks).")
            .define("coaxMgEnabled", true)
        MISSILE_ENABLED = b
            .comment("Whether the boss may fire the wire-guided missile (used against vehicles).")
            .define("wireMissileEnabled", true)

        CANNON_SHELL = b
            .comment(
                "Shell of the 25mm cannon. AUTO: armour-piercing against vehicles, hidden targets and targets behind cover,",
                "high explosive against targets in the open. AP always pierces up to a couple of blocks; HE always explodes",
                "on the first block. Read live."
            )
            .defineEnum("cannonShell", CannonShell.AUTO)

        MISSILE_COOLDOWN_SECONDS = b
            .comment("Minimum seconds between two wire-guided missiles. The boss steers each missile onto its target itself.")
            .defineInRange("missileCooldownSeconds", 8, 3, 600)

        b.pop().push("lav25_ammo")

        LIMITED_AMMO = b
            .comment("Finite ammo. When the guns are spent, the boss drives away to resupply, then returns with a full load.")
            .define("limitedAmmo", false)
        CANNON_ROUNDS = b
            .comment("25mm rounds per load (fires about 4 a second).")
            .defineInRange("cannon25mmRounds", 300, 1, 100_000)
        MACHINE_GUN_ROUNDS = b
            .comment("Coaxial machine gun rounds per load.")
            .defineInRange("coaxMgRounds", 800, 1, 100_000)
        MISSILE_ROUNDS = b
            .comment("Wire-guided missiles per load.")
            .defineInRange("wireMissileRounds", 4, 1, 1_000)
        SMOKE_CHARGES = b
            .comment("Smoke salvos per load when limitedAmmo is on.")
            .defineInRange("smokeCharges", 6, 1, 1_000)
        RESUPPLY_SECONDS = b
            .comment("Seconds a withdrawn boss stays gone before its replacement (same health, full load) appears near the fight. The drive away is extra.")
            .defineInRange("resupplySeconds", 40, 0, 3_600)
        IDLE_RESUPPLY_SECONDS = b
            .comment(
                "With limitedAmmo: seconds the boss patrols with no target before it may leave to rearm, if any ammo or smoke is spent.",
                "0 = it only rearms when the guns are empty."
            )
            .defineInRange("idleResupplySeconds", 120, 0, 3_600)
        IDLE_RESUPPLY_CHANCE = b
            .comment("With limitedAmmo: chance (percent) rolled every 10 seconds of idling, once idleResupplySeconds have passed, that the boss leaves to rearm.")
            .defineInRange("idleResupplyChance", 25, 0, 100)

        b.pop()

        TEAMMATES = defineTeammates(b, "lav25", BossKind.LAV25)

        FORMATION_ENABLED = b
            .comment(
                "Whether this tank moves in formation with its teammates (tanks that list each other above) nearby:",
                "a wedge on open ground, a single column on narrow ground. Read live."
            )
            .define("formationEnabled", true)
        FORMATION_SPACING = b
            .comment("Blocks between the tanks of a formation. Taken from the leader of the group.")
            .defineInRange("formationSpacing", 14, 6, 40)
        FORMATION_HALT_SECONDS = b
            .comment(
                "Seconds the whole formation (leader included) stops and fires when one of its tanks meets the enemy. After that",
                "the formation breaks up and each tank fights on its own until the group has been out of contact for 10 seconds.",
                "0 = the formation keeps its shape while fighting."
            )
            .defineInRange("formationHaltSeconds", 4, 0, 30)

        b.pop().push("lav25_behaviour")

        SMOKE_ENABLED = b
            .comment(
                "Whether the boss uses its smoke launchers: to cover a flank and to break the line of sight of whoever",
                "hunts it. Smoke only blinds bosses with DYNAMIC targeting. Off = no smoke at all."
            )
            .define("smokeEnabled", true)
        SMOKE_WHEN_HIT_CHANCE = b
            .comment("Chance (percent) to pop smoke when the boss is hit.")
            .defineInRange("smokeWhenHitChance", 25, 0, 100)
        SMOKE_AGAINST_MISSILES = b
            .comment("Whether the boss pops smoke and breaks away from an incoming guided missile.")
            .define("smokeAgainstMissiles", true)
        SMOKE_AGAINST_AIR = b
            .comment("Whether the boss pops smoke and moves on when an aircraft it cannot shoot at (too high) is attacking it.")
            .define("smokeAgainstAir", true)
        SHOOT_DOWN_MISSILES = b
            .comment(
                "Whether the boss's machine guns shoot at an incoming guided missile, together with the smoke (the missile must be noticed first).",
                "The turret and the weapon station swing onto the missile and fire; the cannon stays quiet meanwhile. Needs a machine gun that is enabled and has rounds."
            )
            .define("shootDownMissiles", true)
        SHOOT_DOWN_FAIL_PERCENT = b
            .comment(
                "Chance (percent) that the machine guns fail to stop a missile they engage: they track and fire at it, but it gets through (rolled once per missile).",
                "0 = every engaged missile is shot down, 100 = none is."
            )
            .defineInRange("shootDownFailPercent", 35, 0, 100)
        FLANKING_ENABLED = b
            .comment("Whether the boss swings around to the side of its target at speed while fighting.")
            .define("flankingEnabled", true)
        FLANK_CHANCE = b
            .comment("Chance (percent) that the boss starts a flanking manoeuvre at each roll (every 6-10 seconds of fighting).")
            .defineInRange("flankChance", 30, 0, 100)
        RETURN_FIRE_ENABLED = b
            .comment("Whether the boss shoots back at the estimated position of a shooter it cannot see. Off = returnFireSeconds is ignored.")
            .define("returnFireEnabled", true)
        RETURN_FIRE_SECONDS = b
            .comment(
                "Seconds the boss shoots back at where it estimates a shooter to be (with some scatter) when it is hit by something",
                "it cannot see. The turret swings round toward the shot, so the sight cone can then find the attacker. 0 = off."
            )
            .defineInRange("returnFireSeconds", 4, 0, 30)
        USE_COVER = b
            .comment(
                "Whether the boss scans the structures and terrain around it for cover: it picks hull-down spots (body hidden, turret still seeing the target)",
                "and hide spots beside the nearest walls and ridges, and keeps moving between them while it fights. Off = it samples random spots instead."
            )
            .define("useCover", true)
        PULL_BACK_DAMAGE_PERCENT = b
            .comment(
                "Percent of its maximum health the boss may lose within 3 seconds before it pulls back: it drives to cover (behind a wall, building or ridge;",
                "against an aircraft it cannot shoot at, a spot the aircraft cannot see, else it breaks away under smoke), holds there for a few seconds and",
                "then carries on. 0 = never pulls back."
            )
            .defineInRange("pullBackDamagePercent", 10, 0, 100)
        WALL_BREACHING = b
            .comment(
                "Whether the boss shoots a hole through a thin wall (up to 6 blocks, nothing tougher than stone or brick) on the straight line to where it is going,",
                "when the way round it is a long detour or there is none, then drives through. Only with no target in sight, and only with blockDamage on."
            )
            .define("wallBreaching", true)
        CROSS_WATER = b
            .comment("Whether the amphibious hull may swim across lakes and rivers. Off: it drives around deep water.")
            .define("crossWater", true)

        BOSS_BAR_RANGE = b
            .comment("Players within this many blocks see the boss bar.")
            .defineInRange("bossBarRange", 300, 32, 1024)

        XP_REWARD = b
            .comment("Experience dropped when the boss is defeated.")
            .defineInRange("xpReward", 600, 0, 100_000)

        b.pop()
        SPEC = b.build()

        SETTINGS = TankSettings(
            logName = "LAV-25 boss",
            maxHealth = MAX_HEALTH,
            aggroRange = AGGRO_RANGE,
            globalAggro = GLOBAL_AGGRO,
            targetingMethod = TARGETING_METHOD,
            weights = WEIGHTS,
            mobScanRange = MOB_SCAN_RANGE,
            randomTargeting = RANDOM_TARGETING,
            randomTargetSeconds = RANDOM_TARGET_SECONDS,
            engageRange = ENGAGE_RANGE,
            sightRange = SIGHT_RANGE,
            hearingRange = HEARING_RANGE,
            cannonHearingPercent = CANNON_HEARING_PERCENT,
            machineGunHearingPercent = MACHINE_GUN_HEARING_PERCENT,
            hearingScatterPercent = HEARING_SCATTER_PERCENT,
            smokeScoutRange = SMOKE_SCOUT_RANGE,
            searchSeconds = SEARCH_SECONDS,
            breakDetectionSeconds = BREAK_DETECTION_SECONDS,
            decisionCommitSeconds = DECISION_COMMIT_SECONDS,
            sightLossGraceSeconds = SIGHT_LOSS_GRACE_SECONDS,
            closeDetectionRange = CLOSE_DETECTION_RANGE,
            leashRange = LEASH_RANGE,
            blockDamage = BLOCK_DAMAGE,
            weapons = listOf(
                WeaponSettings("Cannon", CANNON_ENABLED, CANNON_ROUNDS, CANNON_DAMAGE_MULT),
                WeaponSettings("MachineGun", MACHINE_GUN_ENABLED, MACHINE_GUN_ROUNDS, MACHINE_GUN_DAMAGE_MULT),
                WeaponSettings("Missile", MISSILE_ENABLED, MISSILE_ROUNDS, MISSILE_DAMAGE_MULT)
            ),
            cannonShell = CANNON_SHELL,
            missileCooldownSeconds = MISSILE_COOLDOWN_SECONDS,
            limitedAmmo = LIMITED_AMMO,
            smokeCharges = SMOKE_CHARGES,
            resupplySeconds = RESUPPLY_SECONDS,
            smokeEnabled = SMOKE_ENABLED,
            smokeWhenHitChance = SMOKE_WHEN_HIT_CHANCE,
            smokeAgainstMissiles = SMOKE_AGAINST_MISSILES,
            smokeAgainstAir = SMOKE_AGAINST_AIR,
            shootDownMissiles = SHOOT_DOWN_MISSILES,
            shootDownFailPercent = SHOOT_DOWN_FAIL_PERCENT,
            flankingEnabled = FLANKING_ENABLED,
            flankChance = FLANK_CHANCE,
            crossWater = CROSS_WATER,
            bossBarRange = BOSS_BAR_RANGE,
            xpReward = XP_REWARD,
            teammates = TEAMMATES,
            formationEnabled = FORMATION_ENABLED,
            formationSpacing = FORMATION_SPACING,
            formationHaltSeconds = FORMATION_HALT_SECONDS,
            idleResupplySeconds = IDLE_RESUPPLY_SECONDS,
            idleResupplyChance = IDLE_RESUPPLY_CHANCE,
            sightConeDegrees = SIGHT_CONE_DEGREES,
            sightConeRangeMultiplier = SIGHT_CONE_RANGE_MULTIPLIER,
            returnFireEnabled = RETURN_FIRE_ENABLED,
            returnFireSeconds = RETURN_FIRE_SECONDS,
            useCover = USE_COVER,
            pullBackDamagePercent = PULL_BACK_DAMAGE_PERCENT,
            wallBreaching = WALL_BREACHING
        )

        SLIDERS = listOf(PLAYER_WEIGHT, MOB_WEIGHT, HOSTILE_MOB_WEIGHT, PLANE_WEIGHT, TANK_WEIGHT).associateWith { 0..MAX_TARGET_WEIGHT } +
            mapOf(DECISION_COMMIT_SECONDS to 0..10, SIGHT_LOSS_GRACE_SECONDS to 0..30)

        ALL = listOf(
            MAX_HEALTH, AGGRO_RANGE, GLOBAL_AGGRO, TARGETING_METHOD, PLAYER_WEIGHT, MOB_WEIGHT, HOSTILE_MOB_WEIGHT,
            PLANE_WEIGHT, TANK_WEIGHT, MOB_SCAN_RANGE, RANDOM_TARGETING, RANDOM_TARGET_SECONDS,
            ENGAGE_RANGE, SIGHT_RANGE, SIGHT_CONE_DEGREES, SIGHT_CONE_RANGE_MULTIPLIER, HEARING_RANGE, CANNON_HEARING_PERCENT, MACHINE_GUN_HEARING_PERCENT, HEARING_SCATTER_PERCENT, SMOKE_SCOUT_RANGE, SEARCH_SECONDS, BREAK_DETECTION_SECONDS, DECISION_COMMIT_SECONDS, SIGHT_LOSS_GRACE_SECONDS, CLOSE_DETECTION_RANGE, LEASH_RANGE,
            CANNON_DAMAGE_MULT, MACHINE_GUN_DAMAGE_MULT, MISSILE_DAMAGE_MULT, BLOCK_DAMAGE,
            CANNON_ENABLED, MACHINE_GUN_ENABLED, MISSILE_ENABLED, CANNON_SHELL, MISSILE_COOLDOWN_SECONDS,
            LIMITED_AMMO, CANNON_ROUNDS, MACHINE_GUN_ROUNDS, MISSILE_ROUNDS, SMOKE_CHARGES, RESUPPLY_SECONDS, IDLE_RESUPPLY_SECONDS, IDLE_RESUPPLY_CHANCE,
            *TEAMMATES.toggles.values.toTypedArray(), FORMATION_ENABLED, FORMATION_SPACING, FORMATION_HALT_SECONDS,
            SMOKE_ENABLED, SMOKE_WHEN_HIT_CHANCE, SMOKE_AGAINST_MISSILES, SMOKE_AGAINST_AIR, SHOOT_DOWN_MISSILES, SHOOT_DOWN_FAIL_PERCENT, FLANKING_ENABLED, FLANK_CHANCE, RETURN_FIRE_ENABLED, RETURN_FIRE_SECONDS, USE_COVER, PULL_BACK_DAMAGE_PERCENT, WALL_BREACHING,
            CROSS_WATER, BOSS_BAR_RANGE, XP_REWARD
        )
    }
}
