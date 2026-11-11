package com.teamuhi.superbenemies.entity.boss.ztz99a

import com.atsuishio.superbwarfare.tools.ParticleTool
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.config.BossKind
import com.teamuhi.superbenemies.config.Ztz99aConfig
import com.teamuhi.superbenemies.entity.boss.common.ground.GroundProfile
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossBrain
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossEntity
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * The ZTZ-99A boss: Superb Warfare's tracked main battle tank (125mm cannon, coaxial machine gun, heavy machine gun on the
 * weapon station) driven and gunned by two hidden crew members, the driver / gunner in seat 0 and the commander in seat 1.
 * It behaves like the M1A2 boss and shares its guns and brain logic.
 *
 * Superb Warfare's own `Ztz99aEntity` is final, so this extends the plain vehicle and repeats its small overrides (the
 * cannon's muzzle blast and the slightly lowered resting barrel). Its registry id `ztz99a_boss` is also the name of its
 * vehicle data and asset files (`sbw/vehicles/ztz99a_boss.json`), because Superb Warfare resolves vehicle data by entity
 * type id.
 */
class Ztz99aBossEntity(type: EntityType<Ztz99aBossEntity>, level: Level) : TankBossEntity(type, level) {

    override var turretXRot = -3f
    override var turretXRotO = -3f

    override val settings: TankSettings
        get() = Ztz99aConfig.SETTINGS
    override val bossKind: BossKind
        get() = BossKind.ZTZ99A
    override val bossKey: String
        get() = "ztz99a_boss"
    override val crewSeats: Int
        get() = 2
    override val clearanceHalfWidth: Double
        get() = CLEARANCE_HALF_WIDTH
    override val barColor: BossEvent.BossBarColor
        get() = BossEvent.BossBarColor.RED
    override val trailBack: Double
        get() = 3.0
    override val trailUp: Double
        get() = 2.0

    override fun groundProfile(): GroundProfile = PROFILE

    override fun createBrain(): TankBossBrain = Ztz99aBossBrain(this)

    /** The cannon's muzzle blast, as `Ztz99aEntity` does it (that class fires by seat; the boss fires by weapon name). */
    override fun vehicleShoot(living: LivingEntity?, weaponName: String, uuid: UUID?, targetPos: Vec3?) {
        val level = level()
        if (level is ServerLevel && weaponName == "Cannon" && !isWreck) {
            ParticleTool.spawnBigCannonMuzzleParticles(getShootVec(weaponName, 1f), getShootPos(weaponName, 1f), level, this)
        }
        super.vehicleShoot(living, weaponName, uuid, targetPos)
    }

    companion object {
        /** Climbs 2.25 blocks, sinks in deep water, flattens trees and soft blocks. */
        val PROFILE = GroundProfile(2.25, floats = false, crushesFoliage = true, halfWidth = 2.35, halfLength = 5.0)

        /** Half the 4.62 block hull plus a margin. */
        const val CLEARANCE_HALF_WIDTH = 2.6
    }
}
