package com.teamuhi.superbenemies.entity.crate

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import com.teamuhi.superbenemies.entity.boss.common.air.BossAircraft
import com.teamuhi.superbenemies.item.SpawnMode
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.Difficulty
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.Vec3
import net.minecraftforge.registries.ForgeRegistries

/**
 * A Superb Warfare supply crate that opens and lets a boss out. The boss eggs put one of these where the boss is about to
 * appear: it plays Superb Warfare's crate opening animation (the renderer reuses the crate model and animation), and
 * [OPEN_TICKS] ticks later it is gone and the boss stands (or, for a plane, flies) where the egg worked out that it should.
 *
 * It is an entity and not Superb Warfare's crate block so that it never has to find room for a block (water, snow, slabs,
 * a plane's spawn spot high above a column) and so the boss gets its `prepareLaunch` / `prepareGround` set-up, which the
 * block's own opening code does not know about. It cannot be hit, pushed or collided with.
 *
 * Only the server decides anything. The client just draws the animation from the moment it starts tracking the crate.
 * Everything needed to finish the job is saved, so a crate that is unloaded or survives a restart still opens; one that
 * has lost its data (a corrupted save) discards itself.
 */
class BossCrateEntity(type: EntityType<*>, level: Level) : Entity(type, level) {

    private var boss: ResourceLocation? = null
    private var mode = SpawnMode.AIR
    private var spawn = Vec3.ZERO
    private var home = Vec3.ZERO

    /** The egg that made this crate, handed back if the boss cannot be put into the world after all. */
    private var refund: ResourceLocation? = null
    private var age = 0

    init {
        noPhysics = true
        blocksBuilding = false
    }

    /** Fills in what the crate has to do when it opens. Called once by the egg right after creating the crate. */
    fun configure(boss: ResourceLocation, mode: SpawnMode, spawn: Vec3, home: Vec3, refund: ResourceLocation?) {
        this.boss = boss
        this.mode = mode
        this.spawn = spawn
        this.home = home
        this.refund = refund
    }

    override fun tick() {
        super.tick()
        val server = level() as? ServerLevel ?: return
        if (boss == null) {
            discard()
            return
        }
        age++
        if (age == EXPLOSION_TICK) explode(server)
        if (age >= OPEN_TICKS) {
            // The crate goes first, whatever happens: a failed release must never leave a crate that retries forever.
            discard()
            if (!release(server)) giveBackEgg(server)
        }
    }

    /** The bang of Superb Warfare's own crate, just before the lid is gone. */
    private fun explode(level: ServerLevel) {
        level.sendParticles(ParticleTypes.EXPLOSION, x, y + 1.0, z, 40, 1.5, 1.5, 1.5, 1.0)
        level.playSound(
            null, blockPosition(), SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS,
            4f, (1f + (level.random.nextFloat() - level.random.nextFloat()) * 0.2f) * 0.7f
        )
    }

    /** Puts the boss into the world where the egg planned it. False when that is no longer possible. */
    private fun release(level: ServerLevel): Boolean {
        // The difficulty may have been switched while the lid was opening; bosses do not belong in Peaceful.
        if (level.difficulty == Difficulty.PEACEFUL) return false
        val type = boss?.let { ForgeRegistries.ENTITY_TYPES.getValue(it) } ?: return false
        val entity = type.create(level) as? VehicleEntity ?: return false

        when (mode) {
            SpawnMode.AIR -> {
                if (entity !is BossAircraft) return discarded(entity)
                entity.moveTo(spawn.x, spawn.y, spawn.z, yRot, 0f)
                entity.prepareLaunch(home)
            }

            SpawnMode.GROUND -> {
                if (entity !is BossVehicle || !entity.groundBoss) return discarded(entity)
                entity.moveTo(spawn.x, spawn.y, spawn.z, Mth.wrapDegrees(yRot), 0f)
                entity.prepareGround(home)
            }
        }
        if (!level.addFreshEntity(entity)) return false
        level.gameEvent(null, GameEvent.ENTITY_PLACE, entity.position())
        return true
    }

    /** Throws away a boss that was created but is of the wrong kind for the mode; always false (the release failed). */
    private fun discarded(entity: Entity): Boolean {
        entity.discard()
        return false
    }

    private fun giveBackEgg(level: ServerLevel) {
        val item = refund?.let { ForgeRegistries.ITEMS.getValue(it) } ?: return
        spawnAtLocation(ItemStack(item))
        level.playSound(null, blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.5f, 1f)
    }

    override fun defineSynchedData() {}

    override fun readAdditionalSaveData(tag: CompoundTag) {
        boss = if (tag.contains(TAG_BOSS)) ResourceLocation.tryParse(tag.getString(TAG_BOSS)) else null
        mode = SpawnMode.values().firstOrNull { it.name == tag.getString(TAG_MODE) } ?: SpawnMode.AIR
        spawn = Vec3(tag.getDouble("SpawnX"), tag.getDouble("SpawnY"), tag.getDouble("SpawnZ"))
        home = Vec3(tag.getDouble("HomeX"), tag.getDouble("HomeY"), tag.getDouble("HomeZ"))
        refund = if (tag.contains(TAG_REFUND)) ResourceLocation.tryParse(tag.getString(TAG_REFUND)) else null
        age = tag.getInt(TAG_AGE)
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        boss?.let { tag.putString(TAG_BOSS, it.toString()) }
        tag.putString(TAG_MODE, mode.name)
        tag.putDouble("SpawnX", spawn.x)
        tag.putDouble("SpawnY", spawn.y)
        tag.putDouble("SpawnZ", spawn.z)
        tag.putDouble("HomeX", home.x)
        tag.putDouble("HomeY", home.y)
        tag.putDouble("HomeZ", home.z)
        refund?.let { tag.putString(TAG_REFUND, it.toString()) }
        tag.putInt(TAG_AGE, age)
    }

    // The crate is scenery for a second: nothing can hit it, push it or stand on it.
    override fun isPickable(): Boolean = false
    override fun canBeCollidedWith(): Boolean = false
    override fun isPushedByFluid(): Boolean = false
    override fun isInvulnerableTo(source: DamageSource): Boolean = true
    override fun isNoGravity(): Boolean = true

    companion object {
        /** Ticks from the start until the boss is out: Superb Warfare's crate takes the same 20 ticks (1 second). */
        const val OPEN_TICKS = 20

        /** The tick of the bang (Superb Warfare's crate blows at tick 18). */
        const val EXPLOSION_TICK = 18

        private const val TAG_BOSS = "Boss"
        private const val TAG_MODE = "Mode"
        private const val TAG_REFUND = "Egg"
        private const val TAG_AGE = "Age"
    }
}
