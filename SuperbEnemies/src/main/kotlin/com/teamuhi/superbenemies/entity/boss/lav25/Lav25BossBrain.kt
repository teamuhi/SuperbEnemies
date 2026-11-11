package com.teamuhi.superbenemies.entity.boss.lav25

import com.teamuhi.superbenemies.config.Lav25Config
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossBrain

/**
 * The LAV-25's brain: wheels (they cannot turn on the spot), a hull that floats, a 25mm gun and a wire missile. All the
 * behaviour is in [TankBossBrain].
 */
class Lav25BossBrain(boss: Lav25BossEntity) : TankBossBrain(
    boss, Lav25Config.SETTINGS, Lav25BossEntity.PROFILE, pivots = false, gunnery = Lav25Gunnery(boss, Lav25Config.SETTINGS)
) {
    /** Circles its target on the engage ring, rolling on between legs, and sometimes rocks in and out of hull-down cover. */
    override val strafes: Boolean get() = true
}
