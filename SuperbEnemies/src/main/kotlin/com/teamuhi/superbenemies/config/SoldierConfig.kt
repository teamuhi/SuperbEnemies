package com.teamuhi.superbenemies.config

import com.teamuhi.superbenemies.entity.dummy.DummySettings
import com.teamuhi.superbenemies.entity.soldier.AmmoSettings
import net.minecraftforge.common.ForgeConfigSpec

enum class SoldierMovement {
    STATIC,
    ROAM
}

/**
 * Common config of the dummy soldier, written to `config/superbenemies-soldier-common.toml`.
 *
 * Every option is read live (the soldier and its goals re-check them while they tick), so changing one affects soldiers
 * that are already standing in the world. Section names carry a `dummy_soldier` prefix so the in-game config screen's
 * header keys stay unique across all the mod's files.
 */
object SoldierConfig {
    val SPEC: ForgeConfigSpec

    // [dummy_soldier]
    val MAX_HEALTH: ForgeConfigSpec.IntValue
    val ARMOR: ForgeConfigSpec.DoubleValue
    val ARMOR_TOUGHNESS: ForgeConfigSpec.DoubleValue
    val INVULNERABLE: ForgeConfigSpec.BooleanValue
    val SHOW_HEALTH_TAG: ForgeConfigSpec.BooleanValue
    val SHOW_DEATH_MESSAGE: ForgeConfigSpec.BooleanValue
    val SHOW_COUNTER: ForgeConfigSpec.BooleanValue
    val BOSSES_TARGET_SOLDIER: ForgeConfigSpec.BooleanValue

    // [dummy_soldier_ammo]
    val UNLIMITED_AMMO: ForgeConfigSpec.BooleanValue
    val MISSILES: ForgeConfigSpec.IntValue
    val RELOAD_TICKS: ForgeConfigSpec.IntValue
    val RESUPPLY_SECONDS: ForgeConfigSpec.IntValue
    val MISSILE_DAMAGE: ForgeConfigSpec.DoubleValue
    val EXPLOSION_DAMAGE: ForgeConfigSpec.DoubleValue
    val EXPLOSION_RADIUS: ForgeConfigSpec.DoubleValue

    // [dummy_soldier_behaviour]
    val MOVEMENT: ForgeConfigSpec.EnumValue<SoldierMovement>
    val STRAFE_RADIUS: ForgeConfigSpec.IntValue
    val ROAM_DISTANCE: ForgeConfigSpec.IntValue
    val ROAM_PAUSE_SECONDS: ForgeConfigSpec.IntValue
    val STRAFE_JUMPING: ForgeConfigSpec.BooleanValue
    val STRAFE_SPRINTING: ForgeConfigSpec.BooleanValue
    val STRAFE_CROUCHING: ForgeConfigSpec.BooleanValue
    val ENGAGE_RANGE: ForgeConfigSpec.IntValue
    val MIN_ENGAGE_RANGE: ForgeConfigSpec.IntValue
    val LOCK_TIME_TICKS: ForgeConfigSpec.IntValue
    val AIM_TURN_SPEED: ForgeConfigSpec.DoubleValue
    val REACTION_TICKS: ForgeConfigSpec.IntValue
    val HOLD_FIRE_ON_FLARES: ForgeConfigSpec.BooleanValue
    val STAGGER_SHOTS: ForgeConfigSpec.BooleanValue
    val COVER_AFTER_HIT: ForgeConfigSpec.BooleanValue
    val COVER_AFTER_MISS: ForgeConfigSpec.BooleanValue
    val COVER_SECONDS: ForgeConfigSpec.IntValue
    val HIDE_WHEN_OUT_OF_AMMO: ForgeConfigSpec.BooleanValue

    /** Every option in file order (grouped by section); the in-game config screen is built from this. */
    val ALL: List<ForgeConfigSpec.ConfigValue<*>>

    init {
        val b = ForgeConfigSpec.Builder()

        b.comment("Dummy soldier: a player-looking soldier that shoots down the boss aircraft with an Igla MANPADS")
            .push("dummy_soldier")

        MAX_HEALTH = b
            .comment(
                "Health of every dummy soldier. Applied to existing soldiers too (health above the new maximum is clamped).",
                "Capped at 1024, the vanilla limit of the max health attribute.",
                "Read live."
            )
            .defineInRange("maxHealth", 20, 1, 1024)

        ARMOR = b
            .comment("Armor points of every dummy soldier (20 is a full set of vanilla armor). Read live.")
            .defineInRange("armor", 10.0, 0.0, 30.0)

        ARMOR_TOUGHNESS = b
            .comment("Armor toughness of every dummy soldier (a full set of netherite armor gives 12). Read live.")
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
                "e.g. \"Dummy Soldier 18.5 / 20 (-4.5)\". Read live."
            )
            .define("showHealthTag", true)

        SHOW_DEATH_MESSAGE = b
            .comment(
                "Announce in chat when a soldier is killed, e.g. \"Dummy Soldier was shot by A-10 Warthog\". Read live."
            )
            .define("showDeathMessage", true)

        SHOW_COUNTER = b
            .comment("Show the number of loaded dummy soldiers on the client hotbar. Read live.")
            .define("showCounter", false)

        BOSSES_TARGET_SOLDIER = b
            .comment(
                "The bosses hunt soldiers like survival players. With this off the A-10 ignores them and the AC-130 treats",
                "them as ordinary mobs. Read live."
            )
            .define("bossesTargetSoldier", true)

        b.pop().push("dummy_soldier_ammo")

        UNLIMITED_AMMO = b
            .comment("The soldier never runs out of missiles: every reload is free. Read live.")
            .define("unlimitedAmmo", false)

        MISSILES = b
            .comment(
                "Missiles a soldier carries, including the one in the launcher. A soldier fires one, then reloads from the",
                "rest. Lowering this clamps soldiers that carry more. Read live."
            )
            .defineInRange("missiles", 4, 1, 64)

        RELOAD_TICKS = b
            .comment("Ticks (20 = 1 second) it takes to load the next missile after one was fired. The Igla itself takes 88. Read live.")
            .defineInRange("reloadTicks", 88, 20, 400)

        RESUPPLY_SECONDS = b
            .comment(
                "Seconds after the last missile is gone until the soldier is resupplied with a full load.",
                "0 = never: an empty soldier stays empty. Read live."
            )
            .defineInRange("resupplySeconds", 60, 0, 3600)

        MISSILE_DAMAGE = b
            .comment("Direct-hit damage of the missile (the Igla's own value is 260). Read live.")
            .defineInRange("missileDamage", 260.0, 0.0, 100000.0)

        EXPLOSION_DAMAGE = b
            .comment("Splash damage of the missile (the Igla's own value is 90). Read live.")
            .defineInRange("explosionDamage", 90.0, 0.0, 100000.0)

        EXPLOSION_RADIUS = b
            .comment("Blast radius (blocks) of the missile (the Igla's own value is 6). Read live.")
            .defineInRange("explosionRadius", 6.0, 0.0, 64.0)

        b.pop().push("dummy_soldier_behaviour")

        MOVEMENT = b
            .comment(
                "STATIC (stands still, only turns to face the nearest boss or player) or ROAM (walks to random nearby spots",
                "one after another). Whenever the soldier aims at a boss",
                "it stands still, whatever this is. Read live."
            )
            .defineEnum("movement", SoldierMovement.STATIC)

        STRAFE_RADIUS = b
            .comment("STRAFE: how far (blocks) the soldier wanders from where it was spawned before it strafes back. Read live.")
            .defineInRange("strafeRadius", 6, 1, 32)

        ROAM_DISTANCE = b
            .comment(
                "ROAM: how far (blocks) each new spot may be from where the soldier stands. The soldier is also kept within",
                "48 blocks of its spawn point. Read live."
            )
            .defineInRange("roamDistance", 12, 4, 32)

        ROAM_PAUSE_SECONDS = b
            .comment("ROAM: at each spot the soldier pauses for a random time between 0 and this many seconds. Read live.")
            .defineInRange("roamPauseSeconds", 2, 0, 30)

        STRAFE_JUMPING = b
            .comment("ROAM: the soldier jumps now and then. Read live.")
            .define("jumping", true)

        STRAFE_SPRINTING = b
            .comment("ROAM: the soldier now and then sprints on some walks. Read live.")
            .define("sprinting", true)

        STRAFE_CROUCHING = b
            .comment("Unused by soldiers; kept for config compatibility. Read live.")
            .define("crouching", true)

        ENGAGE_RANGE = b
            .comment(
                "Farthest distance (blocks) at which a soldier picks a boss as its target. The Igla's own seek range is 384.",
                "A boss must also fly at least 16 blocks above the ground, in line of sight and out of smoke. Read live."
            )
            .defineInRange("engageRange", 300, 32, 384)

        MIN_ENGAGE_RANGE = b
            .comment("Closest distance (blocks) at which a soldier fires; a boss nearer than this is left alone. Read live.")
            .defineInRange("minEngageRange", 24, 0, 128)

        LOCK_TIME_TICKS = b
            .comment(
                "Ticks (20 = 1 second) the soldier must keep a boss inside its 20 degree cone, in sight and out of smoke,",
                "before the lock is made and it fires. The Igla's own value is 32. Losing the cone restarts the count. Read live."
            )
            .defineInRange("lockTimeTicks", 80, 5, 200)

        AIM_TURN_SPEED = b
            .comment("How fast (degrees per tick) the soldier swings its body, head and launcher onto a boss. Read live.")
            .defineInRange("aimTurnSpeed", 12.0, 1.0, 45.0)

        REACTION_TICKS = b
            .comment("Ticks the soldier needs to react to a boss before it raises the launcher. 0 = instantly. Read live.")
            .defineInRange("reactionTicks", 10, 0, 100)

        HOLD_FIRE_ON_FLARES = b
            .comment(
                "A locked soldier does not fire while flares or other decoys are burning near the boss (for up to 3 seconds),",
                "so the missile is not wasted on them. Read live."
            )
            .define("holdFireOnFlares", true)

        STAGGER_SHOTS = b
            .comment(
                "A locked soldier does not fire while another soldier's missile is already homing in on the same boss,",
                "so one burst of flares cannot spoof both missiles. Read live."
            )
            .define("staggerShots", true)

        COVER_AFTER_HIT = b
            .comment("After its missile hit the boss the soldier runs for cover under a roof or trees for coverSeconds. Read live.")
            .define("coverAfterHit", true)

        COVER_AFTER_MISS = b
            .comment("The same after a missile missed or was lost (with this off the soldier just reloads and tries again). Read live.")
            .define("coverAfterMiss", false)

        COVER_SECONDS = b
            .comment("How long (seconds) the soldier stays in cover after a shot, before it walks back and engages again. Read live.")
            .defineInRange("coverSeconds", 15, 1, 120)

        HIDE_WHEN_OUT_OF_AMMO = b
            .comment(
                "A soldier without any missile left runs for cover while a boss is within 400 blocks, until it is resupplied",
                "(see resupplySeconds). Read live."
            )
            .define("hideWhenOutOfAmmo", true)

        b.pop()
        SPEC = b.build()

        ALL = listOf(
            MAX_HEALTH, ARMOR, ARMOR_TOUGHNESS, INVULNERABLE, SHOW_HEALTH_TAG, SHOW_DEATH_MESSAGE, SHOW_COUNTER, BOSSES_TARGET_SOLDIER,
            UNLIMITED_AMMO, MISSILES, RELOAD_TICKS, RESUPPLY_SECONDS, MISSILE_DAMAGE, EXPLOSION_DAMAGE, EXPLOSION_RADIUS,
            MOVEMENT, STRAFE_RADIUS, ROAM_DISTANCE, ROAM_PAUSE_SECONDS, STRAFE_JUMPING, STRAFE_SPRINTING, STRAFE_CROUCHING,
            ENGAGE_RANGE, MIN_ENGAGE_RANGE, LOCK_TIME_TICKS, AIM_TURN_SPEED, REACTION_TICKS, HOLD_FIRE_ON_FLARES, STAGGER_SHOTS,
            COVER_AFTER_HIT, COVER_AFTER_MISS, COVER_SECONDS, HIDE_WHEN_OUT_OF_AMMO
        )
    }
}

/** The soldier's settings: [SoldierConfig], read live. */
object SoldierSettings : DummySettings {
    override fun maxHealth(): Int = SoldierConfig.MAX_HEALTH.get()
    override fun armor(): Double = SoldierConfig.ARMOR.get()
    override fun armorToughness(): Double = SoldierConfig.ARMOR_TOUGHNESS.get()
    override fun invulnerable(): Boolean = SoldierConfig.INVULNERABLE.get()
    override fun showHealthTag(): Boolean = SoldierConfig.SHOW_HEALTH_TAG.get()
    override fun showDeathMessage(): Boolean = SoldierConfig.SHOW_DEATH_MESSAGE.get()
    override fun targetedByBosses(): Boolean = SoldierConfig.BOSSES_TARGET_SOLDIER.get()
    override fun movement(): DummyMovement = when (SoldierConfig.MOVEMENT.get()) {
        SoldierMovement.STATIC -> DummyMovement.STATIC
        SoldierMovement.ROAM -> DummyMovement.ROAM
    }
    override fun strafeRadius(): Int = SoldierConfig.STRAFE_RADIUS.get()
    override fun roamDistance(): Int = SoldierConfig.ROAM_DISTANCE.get()
    override fun roamPauseSeconds(): Int = SoldierConfig.ROAM_PAUSE_SECONDS.get()
    override fun jumping(): Boolean = SoldierConfig.STRAFE_JUMPING.get()
    override fun sprinting(): Boolean = SoldierConfig.STRAFE_SPRINTING.get()
    override fun crouching(): Boolean = SoldierConfig.STRAFE_CROUCHING.get()
}

/** The Igla soldier's ammo options: [SoldierConfig], read live. */
object SoldierAmmo : AmmoSettings {
    override fun unlimited(): Boolean = SoldierConfig.UNLIMITED_AMMO.get()
    override fun capacity(): Int = SoldierConfig.MISSILES.get()
    override fun reloadTicks(): Int = SoldierConfig.RELOAD_TICKS.get()
    override fun resupplySeconds(): Int = SoldierConfig.RESUPPLY_SECONDS.get()
}
