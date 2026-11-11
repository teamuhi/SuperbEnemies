package com.teamuhi.superbenemies.entity.boss.common

import com.teamuhi.superbenemies.config.BossKind
import com.teamuhi.superbenemies.config.TeammateSettings
import com.teamuhi.superbenemies.debug.AiThought
import com.teamuhi.superbenemies.entity.boss.common.ground.GroundProfile
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * What the shared boss code ([BossLifecycle], [BossRespawns], [BossCrewEntity], the `common.ai` helpers and the spawn
 * egg) needs to know about any boss vehicle, flying or driving. Implemented by every boss (the A-10, the AC-130, the
 * tanks) through [BossAircraft] or directly, so the shared pieces never refer to a concrete boss class or to a boss's own
 * config object. The dummy and soldier code deliberately stays on [BossAircraft]: they only deal with planes.
 */
interface BossVehicle {
    /** True once health has dropped to the crash threshold: the controls are released and the wreck burns down. */
    val crippled: Boolean

    /**
     * True for a boss that drives on the ground. Such a boss is never launched ([BossAircraft.prepareLaunch]); the spawn
     * egg and [BossRespawns] place it on the surface and call [prepareGround] instead. A crippled ground boss is
     * wrecked at once (it is already on the ground), so [BossLifecycle] goes straight to the explosion and payout.
     */
    val groundBoss: Boolean

    /**
     * What a [groundBoss] can drive over (climb height, floats, crushes foliage). Read by the spawn egg and [BossRespawns]
     * to place it, and by its brain for route finding. Aircraft keep the default, which nothing reads.
     */
    fun groundProfile(): GroundProfile = GroundProfile.DEFAULT

    /** Which of the six boss vehicles this is: the row other bosses look up in their Teammates table. */
    val bossKind: BossKind

    /**
     * This boss's console voice for the AI Thinking debug option (see [AiThought]); one per boss. Every AI helper that
     * holds the boss reports through it.
     */
    val thought: AiThought

    /** This boss's Teammates table (config, read live): the boss types it never targets and, for tanks, may form up with. */
    fun teammates(): TeammateSettings

    /** Remaining health as a fraction of the maximum, 0..1. */
    fun healthFraction(): Float

    /** True when [entity] is one of this boss's hidden crew members (pilot, gunner, driver). */
    fun isCrew(entity: Entity?): Boolean

    /** Same as [isCrew] for a string UUID, e.g. the target UUID of a homing missile. Null is never crew. */
    fun isCrewUuid(uuid: String?): Boolean

    /**
     * Whether the boss reacts to a homing missile with its countermeasure (config, read live): flares for the planes,
     * smoke for the tanks. The name is historical; [ThreatMonitor] drives the SBW decoy dispenser either way.
     */
    fun defensiveFlares(): Boolean

    /** Chance (0-1) that the boss fails to notice an incoming homing missile (config, read live). */
    fun flareMissChance(): Double

    /** Whether the finite-ammo option is on (config, read live): the boss withdraws to rearm when it runs dry. */
    fun limitedAmmo(): Boolean

    /**
     * Called by the spawn egg and [BossRespawns] before a [groundBoss] is added to the level: sets its home point (the
     * centre of its patrol). Aircraft keep the default, which does nothing; they get [BossAircraft.prepareLaunch].
     */
    fun prepareGround(home: Vec3) {}

    /**
     * Called by [BossRespawns] on a replacement boss before it is added to the level: [anchor] is where the fight was.
     * A plane remembers it as the spot to circle until it finds a target (instead of its original spawn point). The
     * default does nothing; kept in memory only, so a reloaded boss falls back to its home point.
     */
    fun rememberFight(anchor: Vec3) {}

    /** True when the boss took damage within the last [ticks] ticks. */
    fun recentlyDamaged(ticks: Int): Boolean

    /**
     * Makes this (new) boss start at [fraction] of its maximum health instead of full: the replacement of a boss that
     * withdrew to rearm (see [BossRespawns]). Call before the entity is added to the level.
     */
    fun restoreHealthFraction(fraction: Float)

    /** Chat message to the players around the boss, and around [alsoNear] when given. */
    fun announce(translationKey: String, style: Style, alsoNear: Vec3? = null)
}
