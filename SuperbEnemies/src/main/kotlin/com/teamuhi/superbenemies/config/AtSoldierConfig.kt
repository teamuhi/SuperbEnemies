package com.teamuhi.superbenemies.config

import com.teamuhi.superbenemies.entity.dummy.DummySettings
import com.teamuhi.superbenemies.entity.soldier.AmmoSettings
import com.teamuhi.superbenemies.entity.soldier.AtWeapon
import net.minecraftforge.common.ForgeConfigSpec

/**
 * The rocket the RPG soldier fires. The numbers are Superb Warfare's own: the TBG is the RPG's default round, the standard
 * (PG-7 HEAT) round is its json override (`Damage 450`, `Velocity 6`, `Gravity 0.015`). [velocity] is the launch speed in
 * blocks per tick and [gravity] the downward pull per tick, which the ballistics solver needs.
 */
enum class RpgRocket(
    val damage: Double,
    val explosionDamage: Double,
    val explosionRadius: Double,
    val velocity: Float,
    val gravity: Float
) {
    STANDARD(450.0, 40.0, 5.0, 6f, 0.015f),
    TBG(300.0, 150.0, 11.0, 4f, 0.03f)
}

/**
 * Common config of the anti-tank soldiers (the Javelin soldier and the RPG soldier), written to
 * `config/superbenemies-at-soldier-common.toml`. One file serves both variants: the options that only make sense for one
 * of them carry its name.
 *
 * Every option is read live (the soldiers and their goals re-check them while they tick), so changing one affects
 * soldiers that are already standing in the world. Section names carry an `at_soldier` prefix so the in-game config
 * screen's header keys stay unique across all the mod's files. A minimum range above its maximum, or a minimum
 * reposition distance above the maximum, is not rejected: the accessors ([minRange], [maxRange], [repositionMin],
 * [repositionMax]) coerce it when it is read.
 */
object AtSoldierConfig {
    val SPEC: ForgeConfigSpec

    // [at_soldier]
    val MAX_HEALTH: ForgeConfigSpec.IntValue
    val ARMOR: ForgeConfigSpec.DoubleValue
    val ARMOR_TOUGHNESS: ForgeConfigSpec.DoubleValue
    val INVULNERABLE: ForgeConfigSpec.BooleanValue
    val SHOW_HEALTH_TAG: ForgeConfigSpec.BooleanValue
    val SHOW_DEATH_MESSAGE: ForgeConfigSpec.BooleanValue
    val SHOW_COUNTER: ForgeConfigSpec.BooleanValue
    val BOSSES_TARGET_SOLDIER: ForgeConfigSpec.BooleanValue

    // [at_soldier_ammo]
    val UNLIMITED_AMMO: ForgeConfigSpec.BooleanValue
    val ROCKETS: ForgeConfigSpec.IntValue
    val RESUPPLY_SECONDS: ForgeConfigSpec.IntValue
    val JAVELIN_RELOAD_TICKS: ForgeConfigSpec.IntValue
    val RPG_RELOAD_TICKS: ForgeConfigSpec.IntValue
    val JAVELIN_DAMAGE: ForgeConfigSpec.DoubleValue
    val JAVELIN_EXPLOSION_DAMAGE: ForgeConfigSpec.DoubleValue
    val JAVELIN_EXPLOSION_RADIUS: ForgeConfigSpec.DoubleValue
    val RPG_ROCKET: ForgeConfigSpec.EnumValue<RpgRocket>
    val RPG_DAMAGE_MULTIPLIER: ForgeConfigSpec.DoubleValue

    // [at_soldier_behaviour]
    val MOVEMENT: ForgeConfigSpec.EnumValue<SoldierMovement>
    val ROAM_DISTANCE: ForgeConfigSpec.IntValue
    val ROAM_PAUSE_SECONDS: ForgeConfigSpec.IntValue
    val JUMPING: ForgeConfigSpec.BooleanValue
    val SPRINTING: ForgeConfigSpec.BooleanValue
    val JAVELIN_RANGE: ForgeConfigSpec.IntValue
    val JAVELIN_MIN_RANGE: ForgeConfigSpec.IntValue
    val JAVELIN_TOP_ATTACK: ForgeConfigSpec.BooleanValue
    val JAVELIN_LOCK_TICKS: ForgeConfigSpec.IntValue
    val RPG_RANGE: ForgeConfigSpec.IntValue
    val RPG_MIN_RANGE: ForgeConfigSpec.IntValue
    val RPG_AIM_TICKS: ForgeConfigSpec.IntValue
    val RPG_SPREAD: ForgeConfigSpec.DoubleValue
    val RPG_LEAD_TARGET: ForgeConfigSpec.BooleanValue
    val AIM_TURN_SPEED: ForgeConfigSpec.DoubleValue
    val REACTION_TICKS: ForgeConfigSpec.IntValue
    val REPOSITION_AFTER_SHOT: ForgeConfigSpec.BooleanValue
    val REPOSITION_MIN_DISTANCE: ForgeConfigSpec.IntValue
    val REPOSITION_MAX_DISTANCE: ForgeConfigSpec.IntValue
    val RETREAT_WHEN_OUT_OF_AMMO: ForgeConfigSpec.BooleanValue

    /** Every option in file order (grouped by section); the in-game config screen is built from this. */
    val ALL: List<ForgeConfigSpec.ConfigValue<*>>

    init {
        val b = ForgeConfigSpec.Builder()

        b.comment("Anti-tank soldiers: player-looking soldiers that fire a Javelin or an RPG-7 at the tank bosses and nothing else")
            .push("at_soldier")

        MAX_HEALTH = b
            .comment(
                "Health of every anti-tank soldier. Applied to existing soldiers too (health above the new maximum is clamped).",
                "Capped at 1024, the vanilla limit of the max health attribute.",
                "Read live."
            )
            .defineInRange("maxHealth", 20, 1, 1024)

        ARMOR = b
            .comment("Armor points of every anti-tank soldier (20 is a full set of vanilla armor). Read live.")
            .defineInRange("armor", 10.0, 0.0, 30.0)

        ARMOR_TOUGHNESS = b
            .comment("Armor toughness of every anti-tank soldier (a full set of netherite armor gives 12). Read live.")
            .defineInRange("armorToughness", 2.0, 0.0, 20.0)

        INVULNERABLE = b
            .comment(
                "Soldiers take no damage. Hits still flash and make the hurt sound, and the health tag still shows them.",
                "/kill and the void still remove a soldier. Read live."
            )
            .define("invulnerable", false)

        SHOW_HEALTH_TAG = b
            .comment(
                "Show a name tag above the soldier with its health and the damage of the last hit it took,",
                "e.g. \"Javelin Soldier 18.5 / 20 (-4.5)\". Read live."
            )
            .define("showHealthTag", true)

        SHOW_DEATH_MESSAGE = b
            .comment(
                "Announce in chat when a soldier is killed, e.g. \"Javelin Soldier was shot by M1A2 Abrams\". Read live."
            )
            .define("showDeathMessage", true)

        SHOW_COUNTER = b
            .comment("Show the number of loaded anti-tank soldiers (both kinds) on the client hotbar. Read live.")
            .define("showCounter", false)

        BOSSES_TARGET_SOLDIER = b
            .comment(
                "The bosses hunt anti-tank soldiers like survival players. With this off the A-10 ignores them and the AC-130",
                "treats them as ordinary mobs. Read live."
            )
            .define("bossesTargetSoldier", true)

        b.pop().push("at_soldier_ammo")

        UNLIMITED_AMMO = b
            .comment("The soldier never runs out of rounds: every reload is free. Read live.")
            .define("unlimitedAmmo", false)

        ROCKETS = b
            .comment(
                "Rounds a soldier carries, including the one in the launcher (Javelin missiles or RPG rockets). A soldier fires",
                "one, then reloads from the rest. Lowering this clamps soldiers that carry more. Read live."
            )
            .defineInRange("rockets", 3, 1, 64)

        RESUPPLY_SECONDS = b
            .comment(
                "Seconds after the last round is gone until the soldier is resupplied with a full load.",
                "0 = never: an empty soldier stays empty. Read live."
            )
            .defineInRange("resupplySeconds", 60, 0, 3600)

        JAVELIN_RELOAD_TICKS = b
            .comment("Ticks (20 = 1 second) the Javelin soldier needs to load the next missile. The Javelin itself takes 80. Read live.")
            .defineInRange("javelinReloadTicks", 80, 20, 400)

        RPG_RELOAD_TICKS = b
            .comment("Ticks (20 = 1 second) the RPG soldier needs to load the next rocket. The RPG itself takes 98. Read live.")
            .defineInRange("rpgReloadTicks", 98, 20, 400)

        JAVELIN_DAMAGE = b
            .comment("Direct-hit damage of the Javelin missile (the Javelin's own value is 650; a top attack hits 25 percent harder). Read live.")
            .defineInRange("javelinDamage", 650.0, 0.0, 100000.0)

        JAVELIN_EXPLOSION_DAMAGE = b
            .comment("Splash damage of the Javelin missile (the Javelin's own value is 120). Read live.")
            .defineInRange("javelinExplosionDamage", 120.0, 0.0, 100000.0)

        JAVELIN_EXPLOSION_RADIUS = b
            .comment("Blast radius (blocks) of the Javelin missile (the Javelin's own value is 9). Read live.")
            .defineInRange("javelinExplosionRadius", 9.0, 0.0, 64.0)

        RPG_ROCKET = b
            .comment(
                "The rocket the RPG soldier fires. STANDARD is the PG-7 HEAT round (damage 450, blast 40 over 5 blocks, fast and",
                "flat); TBG is the thermobaric round (damage 300, blast 150 over 11 blocks, slower with a steeper arc). The soldier",
                "aims for the arc of whichever is selected. Read live."
            )
            .defineEnum("rpgRocket", RpgRocket.STANDARD)

        RPG_DAMAGE_MULTIPLIER = b
            .comment("Multiplier on the direct-hit and splash damage of the selected rocket. Read live.")
            .defineInRange("rpgDamageMultiplier", 1.0, 0.0, 100.0)

        b.pop().push("at_soldier_behaviour")

        MOVEMENT = b
            .comment(
                "STATIC (stands still, only turns to face the nearest tank or player) or ROAM (walks to random nearby spots one",
                "after another). Whenever the soldier aims at a tank it stands still, whatever this is. Read live."
            )
            .defineEnum("movement", SoldierMovement.STATIC)

        ROAM_DISTANCE = b
            .comment(
                "ROAM: how far (blocks) each new spot may be from where the soldier stands. The soldier is also kept within",
                "48 blocks of its spawn point. Read live."
            )
            .defineInRange("roamDistance", 12, 4, 32)

        ROAM_PAUSE_SECONDS = b
            .comment("ROAM: at each spot the soldier pauses for a random time between 0 and this many seconds. Read live.")
            .defineInRange("roamPauseSeconds", 2, 0, 30)

        JUMPING = b
            .comment("ROAM: the soldier jumps now and then. Read live.")
            .define("jumping", true)

        SPRINTING = b
            .comment("The soldier sprints when it repositions after a shot or retreats, and on some ROAM walks. Read live.")
            .define("sprinting", true)

        JAVELIN_RANGE = b
            .comment(
                "Farthest distance (blocks) at which the Javelin soldier picks a tank as its target. The Javelin's own seek range",
                "is 256. The tank must also be in line of sight and out of smoke. Read live."
            )
            .defineInRange("javelinRange", 200, 32, 256)

        JAVELIN_MIN_RANGE = b
            .comment(
                "Closest distance (blocks) at which the Javelin soldier fires; a tank nearer than this is left alone and the",
                "soldier backs off. In top attack mode the missile only climbs when the tank is more than 30 blocks away, closer it",
                "dives at once. A value above javelinRange is treated as javelinRange. Read live."
            )
            .defineInRange("javelinMinRange", 32, 0, 128)

        JAVELIN_TOP_ATTACK = b
            .comment(
                "Fire in the Javelin's top attack mode: the missile climbs, then dives onto the roof of the tank for 25 percent",
                "more damage. Off = direct attack, flying straight at the tank. Read live."
            )
            .define("javelinTopAttack", true)

        JAVELIN_LOCK_TICKS = b
            .comment(
                "Ticks (20 = 1 second) the Javelin soldier must keep a tank inside its 10 degree cone, in sight and out of smoke,",
                "before the lock is made and it fires. The Javelin's own value is 20. Losing the cone restarts the count. Read live."
            )
            .defineInRange("javelinLockTicks", 40, 10, 200)

        RPG_RANGE = b
            .comment(
                "Farthest distance (blocks) at which the RPG soldier picks a tank as its target. A rocket falls off and flies",
                "fast, so this is much shorter than the Javelin's. Read live."
            )
            .defineInRange("rpgRange", 96, 16, 160)

        RPG_MIN_RANGE = b
            .comment(
                "Closest distance (blocks) at which the RPG soldier fires; a tank nearer than this is left alone and the soldier",
                "backs off. A value above rpgRange is treated as rpgRange. Read live."
            )
            .defineInRange("rpgMinRange", 10, 0, 80)

        RPG_AIM_TICKS = b
            .comment(
                "Ticks (20 = 1 second) the RPG soldier must hold its aim within 1.5 degrees of the computed firing solution",
                "before it fires. Read live."
            )
            .defineInRange("rpgAimTicks", 20, 0, 100)

        RPG_SPREAD = b
            .comment(
                "Random spread of the rocket's launch, in the units of Superb Warfare's Spread (the RPG's own is 5). 0 = a perfect",
                "shot along the computed arc. Read live."
            )
            .defineInRange("rpgSpread", 1.0, 0.0, 10.0)

        RPG_LEAD_TARGET = b
            .comment("The RPG soldier leads a moving tank: it aims where the tank will be when the rocket arrives. Read live.")
            .define("rpgLeadTarget", true)

        AIM_TURN_SPEED = b
            .comment("How fast (degrees per tick) the soldier swings its body, head and launcher onto a tank. Read live.")
            .defineInRange("aimTurnSpeed", 10.0, 1.0, 45.0)

        REACTION_TICKS = b
            .comment("Ticks the soldier needs to react to a tank before it raises the launcher. 0 = instantly. Read live.")
            .defineInRange("reactionTicks", 10, 0, 100)

        REPOSITION_AFTER_SHOT = b
            .comment(
                "Shoot and scoot: right after a shot the soldier moves to a new spot, preferably sideways to the tank or out of its",
                "sight, before it engages again. A rocket that cannot be fired because something is in the way makes it look for",
                "a new firing spot as well. Read live."
            )
            .define("repositionAfterShot", true)

        REPOSITION_MIN_DISTANCE = b
            .comment(
                "Shortest move (blocks) of a reposition or retreat. A value above repositionMaxDistance is treated as",
                "repositionMaxDistance. Read live."
            )
            .defineInRange("repositionMinDistance", 8, 2, 48)

        REPOSITION_MAX_DISTANCE = b
            .comment("Longest move (blocks) of a reposition or retreat. The soldier is kept within 48 blocks of its spawn point. Read live.")
            .defineInRange("repositionMaxDistance", 18, 4, 48)

        RETREAT_WHEN_OUT_OF_AMMO = b
            .comment(
                "A soldier without any round left walks away from the nearest tank, out of its sight, while a tank is within 400",
                "blocks, until it is resupplied (see resupplySeconds). Read live."
            )
            .define("retreatWhenOutOfAmmo", true)

        b.pop()
        SPEC = b.build()

        ALL = listOf(
            MAX_HEALTH, ARMOR, ARMOR_TOUGHNESS, INVULNERABLE, SHOW_HEALTH_TAG, SHOW_DEATH_MESSAGE, SHOW_COUNTER, BOSSES_TARGET_SOLDIER,
            UNLIMITED_AMMO, ROCKETS, RESUPPLY_SECONDS, JAVELIN_RELOAD_TICKS, RPG_RELOAD_TICKS,
            JAVELIN_DAMAGE, JAVELIN_EXPLOSION_DAMAGE, JAVELIN_EXPLOSION_RADIUS, RPG_ROCKET, RPG_DAMAGE_MULTIPLIER,
            MOVEMENT, ROAM_DISTANCE, ROAM_PAUSE_SECONDS, JUMPING, SPRINTING,
            JAVELIN_RANGE, JAVELIN_MIN_RANGE, JAVELIN_TOP_ATTACK, JAVELIN_LOCK_TICKS,
            RPG_RANGE, RPG_MIN_RANGE, RPG_AIM_TICKS, RPG_SPREAD, RPG_LEAD_TARGET,
            AIM_TURN_SPEED, REACTION_TICKS, REPOSITION_AFTER_SHOT, REPOSITION_MIN_DISTANCE, REPOSITION_MAX_DISTANCE,
            RETREAT_WHEN_OUT_OF_AMMO
        )
    }

    /** The farthest distance (blocks) at which [weapon]'s soldier picks a tank. */
    fun maxRange(weapon: AtWeapon): Double = when (weapon) {
        AtWeapon.JAVELIN -> JAVELIN_RANGE.get()
        AtWeapon.RPG -> RPG_RANGE.get()
    }.toDouble()

    /** The closest distance (blocks) at which [weapon]'s soldier fires; never above [maxRange]. */
    fun minRange(weapon: AtWeapon): Double = when (weapon) {
        AtWeapon.JAVELIN -> JAVELIN_MIN_RANGE.get()
        AtWeapon.RPG -> RPG_MIN_RANGE.get()
    }.toDouble().coerceAtMost(maxRange(weapon))

    /** The longest move (blocks) of a reposition or retreat. */
    fun repositionMax(): Double = REPOSITION_MAX_DISTANCE.get().toDouble()

    /** The shortest move (blocks) of a reposition or retreat; never above [repositionMax]. */
    fun repositionMin(): Double = REPOSITION_MIN_DISTANCE.get().toDouble().coerceAtMost(repositionMax())
}

/** The anti-tank soldiers' settings: [AtSoldierConfig], read live. They have no strafe or crouch options. */
object AtSoldierSettings : DummySettings {
    override fun maxHealth(): Int = AtSoldierConfig.MAX_HEALTH.get()
    override fun armor(): Double = AtSoldierConfig.ARMOR.get()
    override fun armorToughness(): Double = AtSoldierConfig.ARMOR_TOUGHNESS.get()
    override fun invulnerable(): Boolean = AtSoldierConfig.INVULNERABLE.get()
    override fun showHealthTag(): Boolean = AtSoldierConfig.SHOW_HEALTH_TAG.get()
    override fun showDeathMessage(): Boolean = AtSoldierConfig.SHOW_DEATH_MESSAGE.get()
    override fun targetedByBosses(): Boolean = AtSoldierConfig.BOSSES_TARGET_SOLDIER.get()
    override fun movement(): DummyMovement = when (AtSoldierConfig.MOVEMENT.get()) {
        SoldierMovement.STATIC -> DummyMovement.STATIC
        SoldierMovement.ROAM -> DummyMovement.ROAM
    }
    override fun strafeRadius(): Int = 0
    override fun roamDistance(): Int = AtSoldierConfig.ROAM_DISTANCE.get()
    override fun roamPauseSeconds(): Int = AtSoldierConfig.ROAM_PAUSE_SECONDS.get()
    override fun jumping(): Boolean = AtSoldierConfig.JUMPING.get()
    override fun sprinting(): Boolean = AtSoldierConfig.SPRINTING.get()
    override fun crouching(): Boolean = false
}

/** The Javelin soldier's ammo options: [AtSoldierConfig], read live. */
object JavelinAmmo : AmmoSettings {
    override fun unlimited(): Boolean = AtSoldierConfig.UNLIMITED_AMMO.get()
    override fun capacity(): Int = AtSoldierConfig.ROCKETS.get()
    override fun reloadTicks(): Int = AtSoldierConfig.JAVELIN_RELOAD_TICKS.get()
    override fun resupplySeconds(): Int = AtSoldierConfig.RESUPPLY_SECONDS.get()
}

/** The RPG soldier's ammo options: [AtSoldierConfig], read live. */
object RpgAmmo : AmmoSettings {
    override fun unlimited(): Boolean = AtSoldierConfig.UNLIMITED_AMMO.get()
    override fun capacity(): Int = AtSoldierConfig.ROCKETS.get()
    override fun reloadTicks(): Int = AtSoldierConfig.RPG_RELOAD_TICKS.get()
    override fun resupplySeconds(): Int = AtSoldierConfig.RESUPPLY_SECONDS.get()
}
