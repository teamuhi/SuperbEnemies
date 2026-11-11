package com.teamuhi.superbenemies.entity.boss.common

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.ChatFormatting
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.Vec3

/**
 * The per-boss values [BossLifecycle] needs. Config-backed values are suppliers so they stay live.
 *
 * @param lootTable loot table rolled once when the boss is defeated
 * @param titleKey translation key of the boss bar title
 * @param crippledMessageKey broadcast when the boss starts to crash
 * @param defeatedMessageKey broadcast when the boss is defeated
 * @param xpReward experience dropped on defeat
 * @param bossBarRange players within this many blocks see the bar (and the broadcasts reach 1.5 times as far)
 * @param globalAggro when true every player in the dimension sees the bar
 * @param trailBack how far behind the entity's origin the crash fire trail starts, in blocks
 * @param trailUp how far above the entity's origin the crash fire trail starts, in blocks
 * @param canCripple false for a boss that fights to the last point of health (the tanks): there is no crippled phase at 10%,
 *        the boss bar empties at 0, and the loot / XP are paid out as soon as Superb Warfare declares the wreck
 */
class BossLifecycleSettings(
    val lootTable: ResourceLocation,
    val titleKey: String,
    val crippledMessageKey: String,
    val defeatedMessageKey: String,
    val barColor: BossEvent.BossBarColor,
    val xpReward: () -> Int,
    val bossBarRange: () -> Int,
    val globalAggro: () -> Boolean,
    val trailBack: Double = 5.0,
    val trailUp: Double = 1.8,
    val canCripple: Boolean = true
)

/**
 * Everything a boss aircraft does between "healthy" and "paid out" that does not depend on which aircraft it is:
 *
 *  - Boss bar: full at full health, empty exactly at 10% (where SBW's aircraft engine switches to its crash branch).
 *  - Crippled state: at 10% health the controls are released, a fire trail is emitted, the wreck is declared on impact
 *    (or after a short burn-down in the air) so SBW's own explosion path runs, and a plane that never lands is
 *    detonated after [CRIPPLED_TIMEOUT] ticks.
 *  - Defeat: once the wreck has exploded the loot table and XP are paid out exactly once, with a `remove()` fallback if
 *    the wreck disappears before that (void, `/kill`, other mods).
 *
 * One instance per boss entity. The entity forwards its tick, the seen-by-player hooks, `remove` and the save / load
 * hooks to it; all calls happen on the server.
 */
class BossLifecycle<T>(
    private val boss: T,
    private val settings: BossLifecycleSettings
) where T : VehicleEntity, T : BossVehicle {

    /**
     * True once health has dropped to 10% of the maximum. From then on the plane is lost: the brain releases the
     * controls, SBW's aircraft engine runs its crash branch, and [tickWounded] finishes the job.
     */
    var crippled = false
        private set

    /** True once the loot / XP has been paid out; the brain stops and the wreck is discarded shortly after. */
    var defeated = false
        private set

    private var crippledTicks = 0
    private var defeatTick = 0

    private val bossEvent = ServerBossEvent(
        Component.translatable(settings.titleKey),
        settings.barColor,
        BossEvent.BossBarOverlay.PROGRESS
    )

    // ================================================================================================== tick

    /** Switches to the crippled state once health reaches the threshold (never when [BossLifecycleSettings.canCripple] is false). Run before the AI so it sees the flag. */
    fun checkCripple() {
        if (settings.canCripple && !crippled && boss.healthFraction() <= CRIPPLE_FRACTION) enterCrippled()
    }

    /** Crash handling while crippled, otherwise a light smoke trail in the final phase. Run after the AI. */
    fun tickWounded() {
        if (crippled) tickCrippled()
        else if (boss.healthFraction() < PHASE_3_FRACTION && boss.tickCount % 3 == 0) spawnTrail(2)
    }

    /** Boss bar update plus the defeat check; run after `super.baseTick()`. */
    fun tickAfter() {
        updateBossBar()
        checkDefeat()
    }

    private fun checkDefeat() {
        // A boss that cannot be crippled is beaten the moment its wreck is declared; the others wait for the crash explosion.
        if (!defeated && boss.isWreck && (boss.sympatheticDetonated || !settings.canCripple)) {
            onDefeated()
            defeatTick = boss.tickCount
        }
        // Give the queued crash explosion a few ticks to go off before the wreck disappears; a wreck that stays a wreck (a tank) burns for a while first.
        val delay = if (settings.canCripple) DISCARD_DELAY_TICKS else WRECK_LINGER_TICKS
        if (defeated && boss.tickCount - defeatTick >= delay) {
            boss.thought.event("lifecycle.checkDefeat") { "wreck removed after the payout" }
            boss.discard()
        }
    }

    // ================================================================================================== boss bar

    private fun updateBossBar() {
        val max = boss.getMaxHealth()
        val floor = if (settings.canCripple) max * CRIPPLE_FRACTION else 0f
        // Full at full health, empty exactly when the crash branch starts (at 0 for a boss that cannot be crippled).
        bossEvent.progress = if (max > floor) ((boss.health - floor) / (max - floor)).coerceIn(0f, 1f) else 0f

        if ((boss.tickCount + boss.id) % BAR_SYNC_INTERVAL == 0 && !defeated) {
            for (player in (boss.level() as ServerLevel).players()) {
                if (isInBarRange(player)) bossEvent.addPlayer(player) else bossEvent.removePlayer(player)
            }
        }
    }

    /**
     * Hides the boss bar from everyone while true (e.g. the boss has withdrawn to rearm). The player list and progress
     * keep updating underneath, so the bar comes back exactly as it would have been. Defeat still clears it for good.
     */
    var barHidden: Boolean
        get() = !bossEvent.isVisible
        set(value) {
            bossEvent.isVisible = !value
        }

    private fun isInBarRange(player: ServerPlayer): Boolean {
        if (isUnderground(player)) return false
        if (settings.globalAggro()) return true
        val range = settings.bossBarRange().toDouble()
        return player.distanceToSqr(boss) <= range * range
    }

    /**
     * Deep below the surface (caves, mines): more than [UNDERGROUND_DEPTH] blocks under the highest block above them,
     * so a roof or a treetop does not count. Dimensions with a ceiling (the Nether) have no surface to measure, and an
     * unloaded chunk counts as above ground; neither loads a chunk.
     */
    private fun isUnderground(player: ServerPlayer): Boolean {
        val level = player.level()
        if (level.dimensionType().hasCeiling()) return false
        val x = Mth.floor(player.x)
        val z = Mth.floor(player.z)
        val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return false
        val surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15)
        return player.y < surface - UNDERGROUND_DEPTH
    }

    /** Forward from the entity's `startSeenByPlayer` (after calling super). */
    fun startSeenBy(player: ServerPlayer) {
        if (!defeated && isInBarRange(player)) bossEvent.addPlayer(player)
    }

    /** Forward from the entity's `stopSeenByPlayer` (after calling super). */
    fun stopSeenBy(player: ServerPlayer) {
        bossEvent.removePlayer(player)
    }

    // ================================================================================================== defeat

    private fun enterCrippled() {
        crippled = true
        crippledTicks = 0
        bossEvent.progress = 0f
        boss.thought.event("lifecycle.enterCrippled") { "crippled at ${(boss.healthFraction() * 100).toInt()}% health: controls released, crash trail on" }
        broadcast(settings.crippledMessageKey, ChatFormatting.RED)
    }

    /**
     * Crash handling. SBW's aircraft engine already switches to its uncontrollable crash branch at 10% health; this
     * adds the fire trail, makes sure the wreck is declared (on impact, or after a short burn-down in the air) so SBW's
     * own explosion path runs, and detonates the plane in the air if it somehow never lands.
     */
    private fun tickCrippled() {
        crippledTicks++
        // Every other tick with double the count: same density, half the packets.
        if (crippledTicks % 2 == 0) spawnTrail(12)

        if (!boss.isWreck) {
            if (boss.onGround() || boss.isInFluidType) {
                boss.thought.event("lifecycle.tickCrippled") { "crash: touched ${if (boss.onGround()) "the ground" else "water"}, declaring the wreck" }
                boss.health = 0f
            } else if (crippledTicks % CRIPPLED_DRAIN_INTERVAL == 0) {
                boss.thought.think("lifecycle.tickCrippled") { "crashing, burning down in the air" }
                boss.health = boss.health - boss.getMaxHealth() * CRIPPLED_DRAIN
            }
        }
        if (crippledTicks > CRIPPLED_TIMEOUT) forceDetonation()
    }

    /** Detonates the wreck in the air if it never reached the ground (stuck, or the timeout elapsed). */
    private fun forceDetonation() {
        if (boss.sympatheticDetonated) return
        boss.thought.event("lifecycle.forceDetonation") { "crash timed out in the air: detonating the wreck" }
        if (!boss.isWreck) {
            boss.health = 0f
            boss.isWreck = true
        }
        boss.sympatheticDetonated = true
        boss.vehicleExplosion(boss.computed().destroyInfo)
    }

    /** Drops the loot table and experience once, then removes the boss bar. */
    private fun onDefeated() {
        if (defeated) return
        defeated = true
        val level = boss.level() as? ServerLevel ?: return

        val server = level.server
        val table = server.lootData.getLootTable(settings.lootTable)
        val params = LootParams.Builder(level)
            .withParameter(LootContextParams.THIS_ENTITY, boss)
            .withParameter(LootContextParams.ORIGIN, boss.position())
            .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic())
            .create(LootContextParamSets.ENTITY)
        for (stack in table.getRandomItems(params)) boss.spawnAtLocation(stack)

        val xp = settings.xpReward()
        if (xp > 0) ExperienceOrb.award(level, boss.position(), xp)
        boss.thought.event("lifecycle.onDefeated") { "defeated: loot table ${settings.lootTable} rolled, $xp xp paid out" }

        broadcast(settings.defeatedMessageKey, ChatFormatting.GOLD)
        bossEvent.progress = 0f
        bossEvent.removeAllPlayers()
    }

    private fun broadcast(translationKey: String, format: ChatFormatting) =
        broadcast(translationKey, Style.EMPTY.applyFormat(format))

    /**
     * Sends [translationKey] in [style] to every player within 1.5 times the boss bar range of the boss, or of
     * [alsoNear] when given (a boss far away, e.g. rearming, still reaches the players at the fight).
     */
    fun broadcast(translationKey: String, style: Style, alsoNear: Vec3? = null) {
        val level = boss.level() as? ServerLevel ?: return
        val rangeSqr = (settings.bossBarRange() * 1.5).let { it * it }
        val message = Component.translatable(translationKey).withStyle(style)
        for (player in level.players()) {
            val near = player.distanceToSqr(boss) <= rangeSqr ||
                    (alsoNear != null && player.distanceToSqr(alsoNear) <= rangeSqr)
            if (near) player.sendSystemMessage(message)
        }
    }

    /** Fire and smoke trail, visible from far away (the default particle limiter would cut it at 32 blocks). */
    fun spawnTrail(count: Int) {
        val level = boss.level() as? ServerLevel ?: return
        val tail = boss.position().add(boss.getViewVector(1f).scale(-settings.trailBack)).add(0.0, settings.trailUp, 0.0)
        for (player in level.players()) {
            if (player.distanceToSqr(tail) > TRAIL_RANGE_SQR) continue
            level.sendParticles(player, ParticleTypes.FLAME, true, tail.x, tail.y, tail.z, count, 0.5, 0.3, 0.5, 0.02)
            level.sendParticles(
                player, ParticleTypes.LARGE_SMOKE, true, tail.x, tail.y, tail.z, count, 0.6, 0.4, 0.6, 0.01
            )
        }
    }

    // ================================================================================================== removal

    /**
     * Forward from the entity's `remove` (server side only, before calling super). Last-resort payout if the wreck is
     * removed (void, `/kill`, other mods) before the normal defeat path ran, then the boss bar is cleared.
     */
    fun onRemove(reason: Entity.RemovalReason) {
        val down = crippled || (!settings.canCripple && boss.isWreck)
        if (!defeated && down && (reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED)) {
            boss.thought.event("lifecycle.onRemove") { "removed before the payout ($reason): paying out now" }
            onDefeated()
        }
        bossEvent.removeAllPlayers()
    }

    // ================================================================================================== persistence

    fun save(tag: CompoundTag) {
        tag.putBoolean(TAG_DEFEATED, defeated)
        tag.putBoolean(TAG_CRIPPLED, crippled)
    }

    fun load(tag: CompoundTag) {
        defeated = tag.getBoolean(TAG_DEFEATED)
        // A tank saved while crippled by an older version fights on.
        crippled = settings.canCripple && tag.getBoolean(TAG_CRIPPLED)
    }

    companion object {
        // Identical to the tag names the A-10 used before this class existed, so existing worlds still load.
        const val TAG_DEFEATED = "BossDefeated"
        const val TAG_CRIPPLED = "BossCrippled"

        const val CRIPPLE_FRACTION = 0.1f
        const val PHASE_3_FRACTION = 0.3f
        private const val CRIPPLED_DRAIN = 0.01f
        private const val CRIPPLED_DRAIN_INTERVAL = 10
        private const val CRIPPLED_TIMEOUT = 1200
        private const val BAR_SYNC_INTERVAL = 20
        private const val UNDERGROUND_DEPTH = 8
        private const val DISCARD_DELAY_TICKS = 5

        /** Ticks the wreck of a boss that cannot be crippled stays after the payout (the longest a crippled tank used to linger). */
        private const val WRECK_LINGER_TICKS = 1200
        private const val TRAIL_RANGE_SQR = 300.0 * 300.0
    }
}
