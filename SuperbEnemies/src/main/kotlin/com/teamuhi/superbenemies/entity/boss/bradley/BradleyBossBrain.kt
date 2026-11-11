package com.teamuhi.superbenemies.entity.boss.bradley

import com.teamuhi.superbenemies.config.BradleyConfig
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossBrain
import com.teamuhi.superbenemies.entity.boss.lav25.Lav25Gunnery

/**
 * The Bradley's brain: tracks (they turn on the spot), a hull that floats, a 25mm gun and wire missiles. All the behaviour
 * is in [TankBossBrain]; the guns are the LAV-25's with a longer reach and a gun that elevates to 60 degrees.
 */
class BradleyBossBrain(boss: BradleyBossEntity) : TankBossBrain(
    boss, BradleyConfig.SETTINGS, BradleyBossEntity.PROFILE, pivots = true,
    gunnery = Lav25Gunnery(boss, BradleyConfig.SETTINGS, missileRange = 200.0, cannonRange = 170.0, elevation = 60.0)
) {
    /** Circles its target on the engage ring, rolling on between legs, and sometimes rocks in and out of hull-down cover. */
    override val strafes: Boolean get() = true
}
