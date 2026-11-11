package com.teamuhi.superbenemies.entity.soldier

import com.atsuishio.superbwarfare.entity.projectile.IglaMissileEntity
import com.atsuishio.superbwarfare.init.ModSounds
import com.teamuhi.superbenemies.config.SoldierAmmo
import com.teamuhi.superbenemies.config.SoldierConfig
import com.teamuhi.superbenemies.config.SoldierSettings
import com.teamuhi.superbenemies.debug.AiKind
import com.teamuhi.superbenemies.entity.dummy.DummySettings
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import com.teamuhi.superbenemies.entity.dummy.ai.DummyAi
import com.teamuhi.superbenemies.entity.dummy.ai.DummyHideGoal
import com.teamuhi.superbenemies.entity.dummy.ai.DummyLookGoal
import com.teamuhi.superbenemies.entity.dummy.ai.DummyRoamGoal
import com.teamuhi.superbenemies.entity.soldier.ai.SoldierIglaGoal
import com.teamuhi.superbenemies.init.ModItems
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Entity.RemovalReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import org.joml.Vector3d
import com.atsuishio.superbwarfare.init.ModItems as SbwItems

/**
 * A player-looking soldier with an Igla 9K38 MANPADS that shoots down the boss aircraft.
 *
 * The body, movement, health tag and so on come from [HumanoidDummyEntity], the launcher (synced [aiming] flag, held item,
 * ammo with its reload and resupply) from [LauncherSoldierEntity]; this class adds the bookkeeping of the one missile in
 * flight. The decisions (when to aim, lock and fire) live in [SoldierIglaGoal].
 *
 * Superb Warfare's Igla only keeps steering while its *shooter is a player who is zooming*; with a mob as the owner it
 * would home forever. So the soldier enforces that rule itself: its missile is set lost the moment it stops aiming, dies
 * or is unloaded. When the missile disappears the soldier decides whether it hit (its last position was inside the
 * target's hitbox, inflated by the blast radius) and, per the config, runs for cover.
 */
class DummySoldierEntity(type: EntityType<out PathfinderMob>, level: Level) : LauncherSoldierEntity(type, level) {

    override val settings: DummySettings get() = SoldierSettings

    override val aiKind: AiKind get() = AiKind.DUMMY_SOLDIER

    override val launcherItem: Item get() = SbwItems.IGLA_9K38.get()
    override val ammoSettings: AmmoSettings get() = SoldierAmmo

    override val coverMemoryTicks: Int get() = 0 // walk back as soon as the cover time is over
    override val maxLookPitch: Float get() = 89f // the launcher points almost straight up

    override fun coverEnabled(): Boolean = SoldierConfig.MOVEMENT.get() != com.teamuhi.superbenemies.config.SoldierMovement.ROAM

    /** The missile this soldier fired that is still flying and guided, or null. */
    var activeMissile: IglaMissileEntity? = null
        private set

    /** What [activeMissile] was fired at (to judge hit or miss when it is gone). */
    private var missileTarget: Entity? = null
    private var missileRadius = 0.0

    /** Until this tick the soldier wants to be in cover (after a hit, or a miss with `coverAfterMiss`). */
    private var coverUntil = 0

    // wantsCover() runs every tick (coverMemoryTicks is 0), so the boss lookup behind it is cached.
    private var bossNear = false
    private var nextBossCheck = 0

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, SoldierIglaGoal(this))
        goalSelector.addGoal(2, DummyHideGoal(this))
        goalSelector.addGoal(3, DummyRoamGoal(this))
        goalSelector.addGoal(4, DummyLookGoal(this))
    }

    override fun pickupItem(): ItemStack = ItemStack(ModItems.DUMMY_SOLDIER_SPAWN_EGG.get())

    // ================================================================================================== launcher state

    /** True when a missile is loaded or can still be loaded (unlimited ammo, reserve), or one is in flight. */
    fun hasAnyMissile(): Boolean = ammo.hasAny(activeMissile != null)

    override fun roundInFlight(): Boolean = activeMissile != null

    /**
     * Fires the loaded missile at [target]: copies Superb Warfare's `IglaItem.shootBullet` (right-shoulder muzzle, +0.3
     * on the direction's y, cloud puff behind the tube) and sets the missile to home on [target]. Returns false when
     * nothing was fired (no missile loaded, one already flying, or the level refused the entity).
     */
    fun launchMissile(target: Entity): Boolean {
        val serverLevel = level() as? ServerLevel ?: return false
        if (ammo.loaded <= 0 || activeMissile != null || !isAlive) return false

        val radius = SoldierConfig.EXPLOSION_RADIUS.get()
        val missile = IglaMissileEntity(
            this, serverLevel,
            SoldierConfig.MISSILE_DAMAGE.get().toFloat(),
            SoldierConfig.EXPLOSION_DAMAGE.get().toFloat(),
            radius.toFloat()
        )
        val shoulderYaw = (yRot + 360f + 90f) % 360f
        val muzzle = Vector3d(0.0, -0.2, 0.15)
            .rotateZ((-xRot * Mth.DEG_TO_RAD).toDouble())
            .rotateY((-shoulderYaw * Mth.DEG_TO_RAD).toDouble())
        missile.setPos(x + muzzle.x, eyeY + muzzle.y, z + muzzle.z)
        missile.setLife(PROJECTILE_LIFE)
        missile.setGuideType(0)
        missile.setTargetUuid(target.stringUUID)
        val look = lookAngle
        missile.shoot(look.x, look.y + 0.3, look.z, MUZZLE_VELOCITY, INACCURACY)
        if (!serverLevel.addFreshEntity(missile)) return false

        ammo.consume()
        thought.event("launchMissile") { "Igla away at ${target.name.string}, ${ammo.reserve} in reserve" }
        activeMissile = missile
        missileTarget = target
        missileRadius = radius

        serverLevel.playSound(null, x, y, z, ModSounds.IGLA_FIRE_3P.get(), SoundSource.PLAYERS, FIRE_VOLUME, 1f)
        serverLevel.sendParticles(
            ParticleTypes.CLOUD,
            x + 1.8 * look.x, y + bbHeight - 0.1 + 1.8 * look.y, z + 1.8 * look.z,
            30, 0.4, 0.4, 0.4, 0.005
        )
        return true
    }

    /**
     * Ends the guidance of the flying missile (it falls and explodes on its own) and forgets it. Called when the soldier
     * stops aiming, dies or is removed. The missile is then counted as a miss only by [tickMissile], never here.
     */
    fun releaseMissile() {
        val missile = activeMissile ?: return
        if (!missile.isRemoved) missile.setLost(true)
        activeMissile = null
        missileTarget = null
    }

    // ================================================================================================== cover

    override fun wantsCover(): Boolean {
        val now = tickCount
        if (now < coverUntil) {
            thought.think("wantsCover") { "yes: taking cover after a shot" }
            return true
        }
        if (!SoldierConfig.HIDE_WHEN_OUT_OF_AMMO.get() || hasAnyMissile()) {
            thought.think("wantsCover") { "no: has a missile or hiding when empty is off" }
            return false
        }
        if (now >= nextBossCheck) {
            nextBossCheck = now + BOSS_CHECK_INTERVAL
            bossNear = DummyAi.nearestBoss(this, false) != null
        }
        thought.think("wantsCover") { if (bossNear) "yes: out of missiles with a boss near" else "no: out of missiles but no boss near" }
        return bossNear
    }

    // ================================================================================================== per-tick upkeep

    override fun tickWeapon(now: Int) = tickMissile(now)

    /**
     * Missile watchdog. A flying missile is set lost (and forgotten) as soon as the soldier stops aiming. A missile that
     * is gone is judged: a hit when its last position was inside the target's hitbox inflated by the blast radius plus
     * [HIT_MARGIN]. A hit starts the cover time with `coverAfterHit`, a miss only with `coverAfterMiss`.
     */
    private fun tickMissile(now: Int) {
        val missile = activeMissile ?: return
        val lost = !missile.isRemoved && !aiming
        if (!missile.isRemoved && !lost) return

        val target = missileTarget
        val hit = !lost && target != null &&
            target.boundingBox.inflate(missileRadius + HIT_MARGIN).contains(missile.x, missile.y, missile.z)
        if (lost) missile.setLost(true)
        activeMissile = null
        missileTarget = null

        val cover = if (hit) SoldierConfig.COVER_AFTER_HIT.get() else SoldierConfig.COVER_AFTER_MISS.get()
        if (cover) coverUntil = now + SoldierConfig.COVER_SECONDS.get() * 20
        thought.event("tickMissile") {
            "missile ${if (lost) "dropped (launcher lowered)" else if (hit) "hit ${target?.name?.string}" else "missed ${target?.name?.string}"}" +
                if (cover) ", taking cover for ${SoldierConfig.COVER_SECONDS.get()} s" else ""
        }
    }

    // ================================================================================================== death / removal

    override fun die(source: DamageSource) {
        super.die(source)
        releaseMissile()
    }

    /** Also covers chunk unloads and `discard()` (sneak pickup): the missile must not keep homing without its shooter. */
    override fun remove(reason: RemovalReason) {
        releaseMissile()
        super.remove(reason)
    }

    companion object {
        /** The Igla's `ProjectileLife` and the `Velocity` of its json. */
        private const val PROJECTILE_LIFE = 300
        private const val MUZZLE_VELOCITY = 3f

        /** Spread of the launch (degrees-ish); the missile homes in afterwards, so it is small. */
        private const val INACCURACY = 1f
        private const val FIRE_VOLUME = 4f

        /** A missile that vanished this far (blocks) beyond the blast radius from the target's hitbox still counts as a hit. */
        private const val HIT_MARGIN = 2.0

        private const val BOSS_CHECK_INTERVAL = 20

        /** Same stats as the player dummy (walk speed 0.1, 20 health, 32 follow range); armor comes from the config. */
        fun createAttributes(): AttributeSupplier.Builder = HumanoidDummyEntity.createDummyAttributes()
    }
}
