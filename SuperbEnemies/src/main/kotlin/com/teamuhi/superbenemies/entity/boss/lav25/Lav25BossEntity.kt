package com.teamuhi.superbenemies.entity.boss.lav25

import com.teamuhi.superbenemies.config.BossKind
import com.teamuhi.superbenemies.config.Lav25Config
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.ground.GroundProfile
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossBrain
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossEntity
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level

/**
 * The LAV-25 boss: Superb Warfare's wheeled, amphibious APC (25mm cannon, coaxial machine gun, wire-guided missile) driven
 * and gunned by one hidden crew member. Everything it does is in [TankBossEntity] and [Lav25BossBrain]; this class names
 * its config and driving profile. The registry id `lav25_boss` is also the name of its vehicle data and asset files
 * (`sbw/vehicles/lav25_boss.json`), because Superb Warfare resolves vehicle data by entity type id.
 */
class Lav25BossEntity(type: EntityType<Lav25BossEntity>, level: Level) : TankBossEntity(type, level) {

    override val settings: TankSettings
        get() = Lav25Config.SETTINGS
    override val bossKind: BossKind
        get() = BossKind.LAV25
    override val bossKey: String
        get() = "lav25_boss"
    override val crewSeats: Int
        get() = 1
    override val clearanceHalfWidth: Double
        get() = CLEARANCE_HALF_WIDTH
    override val barColor: BossEvent.BossBarColor
        get() = BossEvent.BossBarColor.YELLOW
    override val trailBack: Double
        get() = 2.5
    override val trailUp: Double
        get() = 2.0

    override fun groundProfile(): GroundProfile = PROFILE

    override fun createBrain(): TankBossBrain = Lav25BossBrain(this)

    companion object {
        /** Climbs 1.5 blocks, floats, does not flatten trees. */
        val PROFILE = GroundProfile(1.5, floats = true, crushesFoliage = false, halfWidth = 1.4, halfLength = 3.5)

        /** Half the 2.8 block hull plus a margin. */
        const val CLEARANCE_HALF_WIDTH = 1.7
    }
}
