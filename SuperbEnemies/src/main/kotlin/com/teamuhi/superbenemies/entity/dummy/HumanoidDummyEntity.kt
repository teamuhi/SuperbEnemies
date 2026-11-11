package com.teamuhi.superbenemies.entity.dummy

import com.teamuhi.superbenemies.debug.AiKind
import com.teamuhi.superbenemies.debug.AiThought
import com.teamuhi.superbenemies.debug.runningGoalName
import com.teamuhi.superbenemies.entity.dummy.ai.DummyAi
import com.teamuhi.superbenemies.entity.dummy.ai.DummyHideGoal
import com.teamuhi.superbenemies.entity.dummy.ai.DummyRoamGoal
import com.teamuhi.superbenemies.entity.dummy.ai.DummyStrafeGoal
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.MoveControl
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/**
 * The shared body of the player-looking mobs (the player dummy and the dummy soldier): a [PathfinderMob] with vanilla
 * navigation, jumping and strafing, drawn with the vanilla player model (see `HumanoidDummyRenderer`).
 *
 * Everything a subclass can tune comes from its [settings], which read a config file live. The shared goals
 * ([DummyHideGoal], [DummyStrafeGoal], [DummyRoamGoal], `DummyLookGoal`) work on this class, so any subclass only has to
 * register the ones it wants and add its own.
 *
 * The entity never despawns, drops nothing and gives no XP. The spawn point is remembered as the [anchor], which is
 * where it strafes around, where its roaming is leashed to and where it walks back to. Sneak + right-click with empty
 * hands picks it up again and hands back [pickupItem].
 */
abstract class HumanoidDummyEntity(type: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(type, level) {

    /** Which AI Thinking filter this kind of dummy prints under. */
    abstract val aiKind: AiKind

    /** Console voice for the AI Thinking debug option; the state shown is the goal that is running. */
    val thought: AiThought by lazy { AiThought(this, aiKind) { runningGoalName(this) } }

    /** Where this kind of dummy reads its health, armor, boss targeting and movement options from. */
    abstract val settings: DummySettings

    /** What sneak + right-click with empty hands returns to the player (the spawn egg). */
    abstract fun pickupItem(): ItemStack

    /**
     * True while [DummyHideGoal] should start (or keep) running for cover. The player dummy hides while a boss is around
     * (with `hideFromBosses`); the soldier also hides after a shot or when it is out of ammo.
     */
    open fun wantsCover(): Boolean = false

    /**
     * Whether hiding is switched on at all. When this turns false mid-hide the hide goal stops at once instead of
     * waiting out [coverMemoryTicks]. Read live.
     */
    open fun coverEnabled(): Boolean = true

    /**
     * What the look goal turns the dummy to face: the nearest boss, crippled or not, by default. A subclass that only cares
     * about some bosses (the anti-tank soldiers watch the tanks) overrides it. Null = nobody: the look goal then falls back
     * to the nearest player.
     */
    open fun lookThreat(): Entity? = DummyAi.nearestBoss(this, includeCrippled = true)

    /** Extra fields for the AI Thinking heartbeat line (the launcher soldiers add their ammo). */
    protected open fun heartbeatExtra(): String = ""

    /** The hide goal keeps hiding this many ticks after [wantsCover] went false, then walks back to the anchor. */
    open val coverMemoryTicks: Int get() = 100

    /** How far (degrees) up or down the look goal may tilt the head. */
    open val maxLookPitch: Float get() = 60f

    /** The block it was spawned on (set on spawn, or on the first tick for `/summon`). Strafing and hiding return here. */
    var anchor: BlockPos? = null
        private set

    /** Until this tick the strafe goal runs even in STATIC mode: the hide goal found no cover and "falls back to strafing". */
    var forcedStrafeUntil = 0

    /** Until this tick the hide goal does not start again after it failed to find any cover. */
    var hideRetryUntil = 0

    /** True while [DummyRoamGoal] runs; the look goal then traces the walking path instead of staring at the threat. */
    var roaming = false

    private val dummyMove = DummyMoveControl(this)

    // --- health tag / invulnerability bookkeeping (server side, not saved) ---
    private var lastHit = 0f
    private var lastFlashTick = -FLASH_INTERVAL
    private var tagShown = false
    private var shownHealth = -1f
    private var shownMax = -1f
    private var shownHit = -1f
    private var attributesSynced = false

    init {
        moveControl = dummyMove
        setPersistenceRequired()
    }

    // ================================================================================================== movement API

    /**
     * Strafes for one tick. [forward] and [left] are unit-length local inputs (left is positive); [speed] scales the
     * player walk speed (1.0 = walking; sprinting adds vanilla's +30% through the sprint flag, not through this).
     * Vanilla `MoveControl.strafe` resets the speed to a quarter, hence the custom control. Must be re-issued every tick.
     */
    fun strafeAt(forward: Float, left: Float, speed: Float) = dummyMove.strafeAt(forward, left, speed)

    /** Requests one jump; vanilla performs it on the next tick if the dummy is on the ground. */
    fun hop() = getJumpControl().jump()

    /** Crouch pose plus sneak flag: the vanilla humanoid model reads `isCrouching()` and draws the sneak pose. */
    fun setCrouched(crouched: Boolean) {
        if (crouched == (pose == Pose.CROUCHING)) return
        pose = if (crouched) Pose.CROUCHING else Pose.STANDING
        isShiftKeyDown = crouched
    }

    /** Horizontal distance to the anchor centre, or null without an anchor / when the anchor's chunk is not loaded. */
    fun distanceToAnchor(): Double? {
        val a = anchor ?: return null
        if (!level().isLoaded(a)) return null
        return hypot(x - (a.x + 0.5), z - (a.z + 0.5))
    }

    /** The anchor's block centre, or null (see [distanceToAnchor]). */
    fun anchorCenter(): Vec3? {
        val a = anchor ?: return null
        return if (level().isLoaded(a)) Vec3.atBottomCenterOf(a) else null
    }

    // ================================================================================================== spawning / saving

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forge's spawn event still routes spawns through this method
    override fun finalizeSpawn(
        level: ServerLevelAccessor, difficulty: DifficultyInstance, reason: MobSpawnType,
        spawnData: SpawnGroupData?, tag: CompoundTag?
    ): SpawnGroupData? {
        anchor = blockPosition()
        syncAttributes()
        return super.finalizeSpawn(level, difficulty, reason, spawnData, tag)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        anchor?.let { tag.putIntArray(TAG_ANCHOR, intArrayOf(it.x, it.y, it.z)) }
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        val saved = tag.getIntArray(TAG_ANCHOR)
        anchor = if (saved.size == 3) BlockPos(saved[0], saved[1], saved[2]) else null
        // A stored custom name is the old health tag: remember that we own it so it is cleared when the tag is turned off.
        tagShown = hasCustomName()
    }

    // ================================================================================================== per-tick upkeep

    override fun customServerAiStep() {
        super.customServerAiStep()
        thought.heartbeat {
            "goal=${runningGoalName(this)} threat=${lookThreat()?.name?.string ?: "none"} hp=${(health / maxHealth * 100f).toInt()}% " +
                "pos=${blockPosition().toShortString()} ${heartbeatExtra()}".trimEnd()
        }
        if (anchor == null) anchor = blockPosition() // /summon: no finalizeSpawn
        keepAnchorReachable()
        if (!attributesSynced || tickCount % ATTRIBUTE_SYNC_INTERVAL == 0) syncAttributes()
        if (settings.invulnerable() && isOnFire) clearFire()
        refreshTag()
    }

    /** Teleported or carried far from its spawn point: the old anchor is meaningless, so adopt the current position. */
    private fun keepAnchorReachable() {
        val a = anchor ?: return
        val dx = x - (a.x + 0.5)
        val dz = z - (a.z + 0.5)
        if (dx * dx + dz * dz > MAX_ANCHOR_DISTANCE * MAX_ANCHOR_DISTANCE || abs(y - a.y) > MAX_ANCHOR_HEIGHT) {
            anchor = blockPosition()
            thought.event("keepAnchorReachable") { "moved far from the anchor, new anchor at ${anchor?.toShortString()}" }
        }
    }

    /**
     * Applies maximum health, armor and armor toughness from [settings]. Raising the maximum tops up an entity that was
     * full; lowering it clamps the current health. Runs on spawn and every [ATTRIBUTE_SYNC_INTERVAL] ticks, so editing the
     * config affects standing entities.
     */
    private fun syncAttributes() {
        attributesSynced = true
        setBase(Attributes.ARMOR, settings.armor())
        setBase(Attributes.ARMOR_TOUGHNESS, settings.armorToughness())
        val attribute = getAttribute(Attributes.MAX_HEALTH) ?: return
        val wanted = settings.maxHealth().toDouble()
        if (attribute.baseValue == wanted) return
        val wasFull = health >= maxHealth - FULL_EPSILON
        attribute.baseValue = wanted
        if (wasFull || health > maxHealth) health = maxHealth
    }

    private fun setBase(attribute: Attribute, value: Double) {
        val instance = getAttribute(attribute) ?: return
        if (instance.baseValue != value) instance.baseValue = value
    }

    // ================================================================================================== health tag

    /** Rewrites the name tag only when health, the maximum or the last hit changed (or the option was toggled). */
    private fun refreshTag() {
        if (!settings.showHealthTag()) {
            if (tagShown) {
                customName = null
                isCustomNameVisible = false
                tagShown = false
                shownHealth = -1f
            }
            return
        }
        val current = health
        val max = maxHealth
        if (tagShown && current == shownHealth && max == shownMax && lastHit == shownHit) return
        shownHealth = current
        shownMax = max
        shownHit = lastHit
        tagShown = true
        customName = buildTag(current, max, lastHit)
        isCustomNameVisible = true
    }

    private fun buildTag(current: Float, max: Float, hit: Float): Component {
        val tag = Component.translatable(type.descriptionId)
            .append(Component.literal(" ❤ ").withStyle(ChatFormatting.RED))
            .append(Component.literal("${format(current)} / ${format(max)}").withStyle(ChatFormatting.WHITE))
        if (hit > 0f) tag.append(Component.literal(" (-${format(hit)})").withStyle(ChatFormatting.GRAY))
        return tag
    }

    /** "20" for whole numbers, "18.5" otherwise. */
    private fun format(value: Float): String =
        if (value == floor(value)) value.toInt().toString() else String.format(Locale.ROOT, "%.1f", value)

    // ================================================================================================== damage

    /**
     * With `invulnerable` on the dummy takes nothing but still reacts: the hurt flash and sound are broadcast (at most
     * once per [FLASH_INTERVAL] ticks so constant boss fire does not flood the network) and the hit shows on the tag.
     * Damage tagged `bypasses_invulnerability` (`/kill`, the void) still goes through.
     */
    override fun hurt(source: DamageSource, amount: Float): Boolean {
        if (level().isClientSide || !isAlive) return super.hurt(source, amount)
        if (settings.invulnerable() && !source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            if (amount > 0f) lastHit = amount
            thought.think("hurt") { "under fire from ${source.msgId} while invulnerable, not hurt" }
            if (tickCount - lastFlashTick >= FLASH_INTERVAL) {
                lastFlashTick = tickCount
                level().broadcastDamageEvent(this, source)
            }
            return false
        }
        val before = health
        val result = super.hurt(source, amount)
        val dealt = before - health
        if (result && dealt > 0f) {
            lastHit = dealt
            thought.event("hurt") { "took ${"%.1f".format(dealt)} damage from ${source.msgId}, ${(health / maxHealth * 100f).toInt()}% health left" }
        }
        return result
    }

    /**
     * Announces the kill in chat when `showDeathMessage` is on. The death message names the victim by its display name,
     * which is the health tag while that is shown, so the tag is lifted for the moment the message is built.
     */
    override fun die(source: DamageSource) {
        val firstDeath = !isRemoved && !dead
        thought.event("die") { "killed by ${source.msgId}" }
        super.die(source)
        val server = level().server ?: return
        if (!firstDeath || !settings.showDeathMessage()) return
        val tag = customName
        customName = null
        val message = combatTracker.deathMessage
        customName = tag
        server.playerList.broadcastSystemMessage(message, false)
    }

    // ================================================================================================== interaction / loot

    /** Sneak + right-click with empty hands removes the dummy and returns [pickupItem] (not in creative). */
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (player.isSecondaryUseActive && player.mainHandItem.isEmpty && player.offhandItem.isEmpty) {
            val serverLevel = level() as? ServerLevel ?: return InteractionResult.sidedSuccess(true)
            if (!player.isCreative) {
                val egg = pickupItem()
                if (!player.addItem(egg)) player.drop(egg, false)
            }
            serverLevel.sendParticles(ParticleTypes.POOF, x, y + bbHeight / 2.0, z, 8, 0.25, 0.4, 0.25, 0.02)
            discard()
            return InteractionResult.sidedSuccess(false)
        }
        return super.mobInteract(player, hand)
    }

    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false
    override fun canBeLeashed(player: Player): Boolean = false
    override fun shouldDropLoot(): Boolean = false
    override fun shouldDropExperience(): Boolean = false

    // ================================================================================================== move control

    /** Vanilla's [MoveControl.strafe] fixes the speed at a quarter of the attribute; this one takes the speed. */
    private class DummyMoveControl(mob: Mob) : MoveControl(mob) {
        fun strafeAt(forward: Float, left: Float, speed: Float) {
            strafe(forward, left)
            speedModifier = speed.toDouble()
        }
    }

    companion object {
        private const val TAG_ANCHOR = "Anchor"
        private const val ATTRIBUTE_SYNC_INTERVAL = 20
        private const val FULL_EPSILON = 0.01f

        /** Minimum ticks between two broadcast hurt flashes (the client's own hurt animation lasts 10 ticks). */
        private const val FLASH_INTERVAL = 10

        /** Farther than this from the anchor (blocks) the anchor is replaced by the current position. */
        private const val MAX_ANCHOR_DISTANCE = 64.0
        private const val MAX_ANCHOR_HEIGHT = 32.0

        /**
         * Vanilla player walk speed (0.1), 20 health, no armor and a 32 block follow range. The settings overwrite health
         * and armor as soon as the entity spawns.
         */
        fun createDummyAttributes(): AttributeSupplier.Builder =
            createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.1)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 0.0)
                .add(Attributes.ARMOR_TOUGHNESS, 0.0)
    }
}
