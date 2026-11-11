package com.teamuhi.superbenemies.entity.soldier

import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.DifficultyInstance
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.SpawnGroupData
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.ServerLevelAccessor

/**
 * What the soldiers that carry a one-shot launcher have in common (the Igla soldier and the anti-tank soldiers): a synced
 * [aiming] flag the renderer raises the arms with, the launcher item in the main hand (so the soldier looks armed and the
 * renderer draws it), and the [ammo]: one round in the launcher, a reserve, a reload timer and the resupply after a while
 * without any. What the soldier shoots, and when, is up to the subclass and its goals.
 *
 * All timers use [tickCount] (reset by a reload of the chunk); only the ammo counts and the resupply countdown are saved.
 */
abstract class LauncherSoldierEntity(type: EntityType<out PathfinderMob>, level: Level) : HumanoidDummyEntity(type, level) {

    /** The launcher shown in the main hand; never dropped. */
    abstract val launcherItem: Item

    /** The ammo options of this soldier, read live. */
    abstract val ammoSettings: AmmoSettings

    /** Loaded round, reserve, reload and resupply. */
    val ammo = LauncherAmmo({ ammoSettings }, { thought })

    /** True while the soldier has the launcher raised. Synced; written only by the server through [aim]. */
    val aiming: Boolean get() = entityData.get(DATA_AIMING)

    private var launcherChecked = false

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(DATA_AIMING, false)
    }

    // ================================================================================================== launcher state

    /** Raises or lowers the launcher. Server only; the renderer reads [aiming]. */
    fun aim(value: Boolean) {
        if (level().isClientSide || aiming == value) return
        entityData.set(DATA_AIMING, value)
    }

    /** True while a round this soldier fired is still flying and counts against its reload (only the Igla guides one). */
    protected open fun roundInFlight(): Boolean = false

    /** Runs every server tick just before the ammo is ticked: the subclass's own watchdog. */
    protected open fun tickWeapon(now: Int) {}

    // ================================================================================================== spawning / saving

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forge's spawn event still routes spawns through this method
    override fun finalizeSpawn(
        level: ServerLevelAccessor, difficulty: DifficultyInstance, reason: MobSpawnType,
        spawnData: SpawnGroupData?, tag: CompoundTag?
    ): SpawnGroupData? {
        val data = super.finalizeSpawn(level, difficulty, reason, spawnData, tag)
        ensureLauncher()
        if (!ammo.ready) ammo.init()
        return data
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        ammo.save(tag, tickCount)
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        ammo.load(tag)
    }

    /** The launcher in the main hand (for the renderer, and so the soldier looks armed); never dropped. */
    private fun ensureLauncher() {
        launcherChecked = true
        val launcher = launcherItem
        if (getItemBySlot(EquipmentSlot.MAINHAND).`is`(launcher)) return
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack(launcher))
        setDropChance(EquipmentSlot.MAINHAND, 0f)
    }

    // ================================================================================================== per-tick upkeep

    override fun customServerAiStep() {
        super.customServerAiStep()
        val now = tickCount
        if (!launcherChecked || now % LAUNCHER_CHECK_INTERVAL == 0) ensureLauncher() // also covers /summon
        tickWeapon(now)
        ammo.tick(now, roundInFlight())
    }

    // ================================================================================================== death

    override fun die(source: DamageSource) {
        super.die(source)
        if (!level().isClientSide) aim(false)
    }

    override fun heartbeatExtra(): String = "ammo=${ammo.loaded}+${ammo.reserve}${if (aiming) " launcher raised" else ""}"

    private companion object {
        val DATA_AIMING: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(LauncherSoldierEntity::class.java, EntityDataSerializers.BOOLEAN)

        const val LAUNCHER_CHECK_INTERVAL = 100
    }
}
