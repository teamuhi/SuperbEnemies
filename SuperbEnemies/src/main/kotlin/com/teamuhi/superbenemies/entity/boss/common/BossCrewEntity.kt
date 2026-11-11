package com.teamuhi.superbenemies.entity.boss.common

import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.Level

/**
 * A hidden crew member (pilot or gunner) of a boss aircraft.
 *
 * Superb Warfare's aircraft engine only accepts control input while the vehicle has a passenger (without one it
 * clears all inputs and lets the nose drop), and projectiles need a living shooter. This entity fills both roles.
 * It is invisible, silent, invulnerable, has no AI and is never saved: the boss re-creates it whenever it is missing.
 * The boss gives it its own name (set right after creation) so that kill messages for the boss's weapons read sensibly.
 */
class BossCrewEntity(type: EntityType<BossCrewEntity>, level: Level) : Mob(type, level) {

    init {
        isNoAi = true
        isSilent = true
        setPersistenceRequired()
        isCustomNameVisible = false
    }

    override fun tick() {
        super.tick()
        if (!level().isClientSide && tickCount > ORPHAN_GRACE_TICKS) {
            val mount = vehicle
            if (mount !is BossVehicle || mount.isRemoved) discard()
        }
    }

    /**
     * Crew never hold a target: Superb Warfare auto-fires the weapons of any Mob passenger that has one, and the
     * boss's own fire control is the only thing that may shoot.
     */
    override fun setTarget(target: LivingEntity?) {
        super.setTarget(null)
    }

    override fun isInvisible(): Boolean = true
    override fun isInvulnerableTo(source: DamageSource): Boolean = true
    override fun hurt(source: DamageSource, amount: Float): Boolean = false
    override fun isPickable(): Boolean = false
    override fun isPushable(): Boolean = false
    override fun canBeCollidedWith(): Boolean = false
    override fun isAttackable(): Boolean = false
    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false
    override fun checkDespawn() {}

    /** Never drops anything, even if something manages to kill it. */
    override fun shouldDropLoot(): Boolean = false
    override fun shouldDropExperience(): Boolean = false

    private companion object {
        /** Ticks to wait before an unmounted crew member discards itself, so the boss has time to seat it. */
        const val ORPHAN_GRACE_TICKS = 10
    }
}
