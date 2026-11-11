package com.teamuhi.superbenemies.entity.boss.bradley

import com.teamuhi.superbenemies.config.BossKind
import com.teamuhi.superbenemies.config.BradleyConfig
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.ground.GroundProfile
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossBrain
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossEntity
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level

/**
 * The M2 Bradley boss: Superb Warfare's tracked, amphibious fighting vehicle (25mm cannon that elevates to 60 degrees,
 * coaxial machine gun, two wire-guided missiles) driven and gunned by one hidden crew member. A better LAV-25: tracks that
 * pivot in place, a higher step, a longer reach and a second missile. Everything it does is in [TankBossEntity] and
 * [BradleyBossBrain]; this class names its config and driving profile. The registry id `bradley_boss` is also the name of
 * its vehicle data and asset files (`sbw/vehicles/bradley_boss.json`), because Superb Warfare resolves vehicle data by
 * entity type id.
 */
class BradleyBossEntity(type: EntityType<BradleyBossEntity>, level: Level) : TankBossEntity(type, level) {

    override val settings: TankSettings
        get() = BradleyConfig.SETTINGS
    override val bossKind: BossKind
        get() = BossKind.BRADLEY
    override val bossKey: String
        get() = "bradley_boss"
    override val crewSeats: Int
        get() = 1
    override val clearanceHalfWidth: Double
        get() = CLEARANCE_HALF_WIDTH
    override val barColor: BossEvent.BossBarColor
        get() = BossEvent.BossBarColor.WHITE
    override val trailBack: Double
        get() = 3.0
    override val trailUp: Double
        get() = 2.0

    override fun groundProfile(): GroundProfile = PROFILE

    override fun createBrain(): TankBossBrain = BradleyBossBrain(this)

    companion object {
        /** Climbs 2.25 blocks, floats, does not flatten trees. */
        val PROFILE = GroundProfile(2.25, floats = true, crushesFoliage = false, halfWidth = 1.85, halfLength = 3.3)

        /** Half the 3.6 block hull plus a margin. */
        const val CLEARANCE_HALF_WIDTH = 2.1
    }
}
