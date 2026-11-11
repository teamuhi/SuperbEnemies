package com.teamuhi.superbenemies.entity.boss.m1a2

import com.teamuhi.superbenemies.config.M1A2Config
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossBrain

/**
 * The M1A2's brain: tracks (they turn on the spot), a hull that sinks in deep water and flattens trees, a 120mm gun and a
 * commander on the .50 cal. All the behaviour is in [TankBossBrain].
 */
class M1A2BossBrain(boss: M1A2BossEntity) : TankBossBrain(
    boss, M1A2Config.SETTINGS, M1A2BossEntity.PROFILE, pivots = true, gunnery = M1A2Gunnery(boss, M1A2Config.SETTINGS)
) {
    /** The main gun peeks from cover for one round at a time (shoot and scoot from chosen firing spots when no cover is near). */
    override val snipes: Boolean get() = true

    /** The heavy machine gun looks for its own targets over a short distance; the cannon is the long arm. */
    override val stationRange: Double get() = 50.0
}
