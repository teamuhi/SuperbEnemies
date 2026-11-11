package com.teamuhi.superbenemies.entity.soldier

import com.atsuishio.superbwarfare.entity.projectile.JavelinMissileEntity
import com.atsuishio.superbwarfare.entity.projectile.RpgRocketStandardEntity
import com.atsuishio.superbwarfare.entity.projectile.RpgRocketTBGEntity
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile
import com.atsuishio.superbwarfare.init.ModSounds
import com.teamuhi.superbenemies.config.AtSoldierConfig
import com.teamuhi.superbenemies.config.AtSoldierSettings
import com.teamuhi.superbenemies.config.JavelinAmmo
import com.teamuhi.superbenemies.config.RpgAmmo
import com.teamuhi.superbenemies.config.RpgRocket
import com.teamuhi.superbenemies.debug.AiKind
import com.teamuhi.superbenemies.entity.dummy.DummySettings
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import com.teamuhi.superbenemies.entity.dummy.ai.DummyAi
import com.teamuhi.superbenemies.entity.dummy.ai.DummyLookGoal
import com.teamuhi.superbenemies.entity.dummy.ai.DummyRoamGoal
import com.teamuhi.superbenemies.entity.soldier.ai.AtSoldierAttackGoal
import com.teamuhi.superbenemies.entity.soldier.ai.SoldierRepositionGoal
import com.teamuhi.superbenemies.init.ModItems
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import com.atsuishio.superbwarfare.init.ModEntities as SbwEntities
import com.atsuishio.superbwarfare.init.ModItems as SbwItems

/** The launcher an [AtSoldierEntity] carries. */
enum class AtWeapon {
    /** The Javelin: locks a tank like the Igla soldier locks a plane, but is fire-and-forget (the soldier does not guide it). */
    JAVELIN,

    /** The RPG-7: unguided, so the soldier works out the arc of the rocket and leads a moving tank. */
    RPG
}

/**
 * A player-looking anti-tank soldier that fires at the tank bosses and nothing else: a Javelin soldier or an RPG soldier
 * (one entity class, two registered types, told apart by [weapon]).
 *
 * The body, movement, health tag and so on come from [HumanoidDummyEntity], the launcher (synced `aiming` flag, held item,
 * ammo) from [LauncherSoldierEntity]; this class adds the two launches ([launchJavelin], [launchRocket]) and the
 * shoot-and-scoot state. The decisions live in [AtSoldierAttackGoal] (react, raise, lock or aim, fire) and
 * [SoldierRepositionGoal] (the move to a new spot after a shot, and the retreat when out of ammo).
 *
 * Nothing fired by this soldier needs looking after: the Javelin finds its tank by itself and a rocket flies on its own, so
 * there is no missile bookkeeping on death or removal and the ammo does not wait for a round in flight.
 */
class AtSoldierEntity(type: EntityType<out PathfinderMob>, level: Level, val weapon: AtWeapon) : LauncherSoldierEntity(type, level) {

    override val settings: DummySettings get() = AtSoldierSettings

    override val aiKind: AiKind get() = AiKind.AT_SOLDIER

    override val launcherItem: Item
        get() = when (weapon) {
            AtWeapon.JAVELIN -> SbwItems.JAVELIN.get()
            AtWeapon.RPG -> SbwItems.RPG.get()
        }

    override val ammoSettings: AmmoSettings
        get() = when (weapon) {
            AtWeapon.JAVELIN -> JavelinAmmo
            AtWeapon.RPG -> RpgAmmo
        }

    /**
     * True from a shot (with `repositionAfterShot`), a rocket that keeps being blocked, or a tank that came too close until
     * [SoldierRepositionGoal] has moved the soldier. The attack goal does not start meanwhile. Expires by itself after
     * [PENDING_TIMEOUT] ticks, so a soldier that cannot move is not stuck without a weapon.
     */
    var repositionPending = false
        set(value) {
            field = value
            pendingSince = tickCount
        }

    /** Where the turret of the tank this soldier last engaged was, for the reposition to move sideways to it. Null = none. */
    var lastThreat: Vec3? = null

    private var pendingSince = 0

    // isRetreating() is asked every tick by the goals, so the tank lookup behind it is cached.
    private var tankNear = false
    private var nextTankCheck = 0

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, AtSoldierAttackGoal(this))
        goalSelector.addGoal(2, SoldierRepositionGoal(this))
        goalSelector.addGoal(3, DummyRoamGoal(this))
        goalSelector.addGoal(4, DummyLookGoal(this))
    }

    override fun pickupItem(): ItemStack = ItemStack(
        when (weapon) {
            AtWeapon.JAVELIN -> ModItems.JAVELIN_SOLDIER_SPAWN_EGG.get()
            AtWeapon.RPG -> ModItems.RPG_SOLDIER_SPAWN_EGG.get()
        }
    )

    /** The anti-tank soldiers only watch the tanks. */
    override fun lookThreat(): Entity? = DummyAi.nearestTank(this, includeCrippled = true)

    // ================================================================================================== state

    /**
     * True while the soldier has no round left and cannot get one soon (`retreatWhenOutOfAmmo`, nothing loaded or in the
     * reserve, no unlimited ammo) and a live tank is within [DummyAi.BOSS_RANGE] blocks: it walks away out of sight.
     */
    fun isRetreating(): Boolean {
        if (!AtSoldierConfig.RETREAT_WHEN_OUT_OF_AMMO.get() || ammo.hasAny(false)) return false
        val now = tickCount
        if (now >= nextTankCheck) {
            nextTankCheck = now + TANK_CHECK_INTERVAL
            tankNear = DummyAi.nearestTank(this, includeCrippled = false) != null
        }
        return tankNear
    }

    override fun tickWeapon(now: Int) {
        if (repositionPending && now - pendingSince > PENDING_TIMEOUT) repositionPending = false
    }

    // ================================================================================================== launches

    /**
     * Fires the loaded Javelin at [tank]: copies Superb Warfare's `JavelinItem.shootBullet` (right-shoulder muzzle, +0.3 on
     * the direction's y, cloud puff behind the tube) and gives the missile the tank's UUID, so it homes in by itself in the
     * Javelin's direct or top attack mode (`javelinTopAttack`). The missile does not depend on its shooter aiming, so the
     * soldier is free to move at once. Returns false when nothing was fired (not a Javelin soldier, nothing loaded, or the
     * level refused the entity).
     */
    fun launchJavelin(tank: Entity): Boolean {
        val serverLevel = level() as? ServerLevel ?: return false
        if (weapon != AtWeapon.JAVELIN || ammo.loaded <= 0 || !isAlive) return false

        val missile = JavelinMissileEntity(
            this, serverLevel,
            AtSoldierConfig.JAVELIN_DAMAGE.get().toFloat(),
            AtSoldierConfig.JAVELIN_EXPLOSION_DAMAGE.get().toFloat(),
            AtSoldierConfig.JAVELIN_EXPLOSION_RADIUS.get().toFloat(),
            0, null
        )
        val shoulderYaw = (yRot + 360f + 90f) % 360f
        val muzzle = Vector3d(0.0, -0.2, 0.15)
            .rotateZ((-xRot * Mth.DEG_TO_RAD).toDouble())
            .rotateY((-shoulderYaw * Mth.DEG_TO_RAD).toDouble())
        missile.setPos(x + muzzle.x, eyeY + muzzle.y, z + muzzle.z)
        missile.setLife(JAVELIN_LIFE)
        missile.setTargetUuid(tank.stringUUID)
        missile.setAttackMode(AtSoldierConfig.JAVELIN_TOP_ATTACK.get())
        val look = lookAngle
        missile.shoot(look.x, look.y + 0.3, look.z, JAVELIN_VELOCITY, JAVELIN_INACCURACY)
        if (!serverLevel.addFreshEntity(missile)) return false

        ammo.consume()
        serverLevel.playSound(null, x, y, z, ModSounds.JAVELIN_FIRE_3P.get(), SoundSource.PLAYERS, FIRE_VOLUME, 1f)
        puff(serverLevel, look)
        return true
    }

    /**
     * Fires the loaded RPG rocket along [direction] (a unit vector, normally the solution of `RocketBallistics`). Like
     * Superb Warfare's RPG it leaves from the eyes; it is given the selected rocket's damage (times
     * `rpgDamageMultiplier`), gravity and life, and the soldier as its owner. Returns false when nothing was fired (not an
     * RPG soldier, nothing loaded, or the level refused the entity).
     */
    fun launchRocket(direction: Vec3): Boolean {
        val serverLevel = level() as? ServerLevel ?: return false
        if (weapon != AtWeapon.RPG || ammo.loaded <= 0 || !isAlive) return false

        val kind = AtSoldierConfig.RPG_ROCKET.get()
        val multiplier = AtSoldierConfig.RPG_DAMAGE_MULTIPLIER.get()
        val damage = (kind.damage * multiplier).toFloat()
        val explosionDamage = (kind.explosionDamage * multiplier).toFloat()
        val radius = kind.explosionRadius.toFloat()
        val rocket: FastThrowableProjectile = when (kind) {
            RpgRocket.STANDARD -> RpgRocketStandardEntity(
                SbwEntities.RPG_ROCKET_STANDARD.get(), x, eyeY, z, serverLevel, damage, explosionDamage, radius
            )
            RpgRocket.TBG -> RpgRocketTBGEntity(
                SbwEntities.RPG_ROCKET_TBG.get(), x, eyeY, z, serverLevel, damage, explosionDamage, radius
            )
        }
        rocket.owner = this
        rocket.setLife(ROCKET_LIFE)
        rocket.setCustomGravity(kind.gravity)
        rocket.setVelocity(kind.velocity)
        rocket.shoot(direction.x, direction.y, direction.z, kind.velocity, AtSoldierConfig.RPG_SPREAD.get().toFloat())
        if (!serverLevel.addFreshEntity(rocket)) return false

        ammo.consume()
        serverLevel.playSound(null, x, y, z, ModSounds.RPG_FIRE_3P.get(), SoundSource.PLAYERS, FIRE_VOLUME, 1f)
        puff(serverLevel, lookAngle)
        return true
    }

    /** The cloud behind the tube that `JavelinItem` and `RpgItem` send. */
    private fun puff(serverLevel: ServerLevel, look: Vec3) {
        serverLevel.sendParticles(
            ParticleTypes.CLOUD,
            x + 1.8 * look.x, y + bbHeight - 0.1 + 1.8 * look.y, z + 1.8 * look.z,
            30, 0.4, 0.4, 0.4, 0.005
        )
    }

    companion object {
        /** The Javelin's `ProjectileLife` and the `Velocity` of its json. */
        private const val JAVELIN_LIFE = 300
        private const val JAVELIN_VELOCITY = 3f

        /** Spread of the Javelin launch (degrees-ish); the missile homes in afterwards, so it is small. */
        private const val JAVELIN_INACCURACY = 1f

        /** The RPG's `ProjectileLife`. */
        private const val ROCKET_LIFE = 100

        private const val FIRE_VOLUME = 4f

        private const val TANK_CHECK_INTERVAL = 20

        /** A reposition that has not been carried out after this many ticks (30 seconds) is dropped. */
        private const val PENDING_TIMEOUT = 600

        /** Same stats as the player dummy (walk speed 0.1, 20 health, 32 follow range); armor comes from the config. */
        fun createAttributes() = HumanoidDummyEntity.createDummyAttributes()
    }
}
