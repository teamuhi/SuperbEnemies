package com.teamuhi.superbenemies.entity.boss.m1a2

import com.atsuishio.superbwarfare.tools.ParticleTool
import com.teamuhi.superbenemies.config.BossKind
import com.teamuhi.superbenemies.config.M1A2Config
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.ground.GroundProfile
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossBrain
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * The M1A2 boss: Superb Warfare's tracked main battle tank (120mm cannon, coaxial machine gun, .50 cal on the weapon
 * station) driven and gunned by two hidden crew members, the driver / gunner in seat 0 and the commander in seat 1.
 *
 * Superb Warfare's own `M1A2Entity` is final, so this extends the plain vehicle and repeats its two small overrides (the
 * cannon's muzzle blast and the lower turret limit when the turret faces backwards). Its registry id `m1a2_boss` is also
 * the name of its vehicle data and asset files (`sbw/vehicles/m1a2_boss.json`), because Superb Warfare resolves vehicle
 * data by entity type id.
 */
class M1A2BossEntity(type: EntityType<M1A2BossEntity>, level: Level) : TankBossEntity(type, level) {

    override val settings: TankSettings
        get() = M1A2Config.SETTINGS
    override val bossKind: BossKind
        get() = BossKind.M1A2
    override val bossKey: String
        get() = "m1a2_boss"
    override val crewSeats: Int
        get() = 2
    override val clearanceHalfWidth: Double
        get() = CLEARANCE_HALF_WIDTH
    override val barColor: BossEvent.BossBarColor
        get() = BossEvent.BossBarColor.GREEN
    override val trailBack: Double
        get() = 3.0
    override val trailUp: Double
        get() = 2.0

    override fun groundProfile(): GroundProfile = PROFILE

    override fun createBrain(): TankBossBrain = M1A2BossBrain(this)

    /** The cannon's muzzle blast, as `M1A2Entity` does it (that class fires by seat; the boss fires by weapon name). */
    override fun vehicleShoot(living: LivingEntity?, weaponName: String, uuid: UUID?, targetPos: Vec3?) {
        val level = level()
        if (level is ServerLevel && weaponName == "Cannon" && !isWreck) {
            ParticleTool.spawnBigCannonMuzzleParticles(getShootVec(weaponName, 1f), getShootPos(weaponName, 1f), level, this)
        }
        super.vehicleShoot(living, weaponName, uuid, targetPos)
    }

    override val customTurretMinPitch: Float
        get() = if (Mth.abs(turretYRot) > 135) ((Mth.abs(turretYRot) - 135) * 0.4f).coerceAtMost(7f) else 0f

    companion object {
        /** Climbs 2.25 blocks, sinks in deep water, flattens trees and soft blocks. */
        val PROFILE = GroundProfile(2.25, floats = false, crushesFoliage = true, halfWidth = 2.35, halfLength = 4.9)

        /** Half the 4.62 block hull plus a margin. */
        const val CLEARANCE_HALF_WIDTH = 2.6
    }
}
