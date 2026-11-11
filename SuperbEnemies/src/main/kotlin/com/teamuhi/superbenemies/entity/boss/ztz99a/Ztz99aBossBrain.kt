package com.teamuhi.superbenemies.entity.boss.ztz99a

import com.teamuhi.superbenemies.config.Ztz99aConfig
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossBrain
import com.teamuhi.superbenemies.entity.boss.m1a2.M1A2Gunnery

/**
 * The ZTZ-99A's brain: the M1A2's. Tracks (they turn on the spot), a hull that sinks in water and flattens trees, a main gun
 * that fights from chosen firing spots and a commander on the heavy machine gun. All the behaviour is in [TankBossBrain].
 */
class Ztz99aBossBrain(boss: Ztz99aBossEntity) : TankBossBrain(
    boss, Ztz99aConfig.SETTINGS, Ztz99aBossEntity.PROFILE, pivots = true, gunnery = M1A2Gunnery(boss, Ztz99aConfig.SETTINGS, depression = 6.0)
) {
    /** The main gun peeks from cover for one round at a time (shoot and scoot from chosen firing spots when no cover is near). */
    override val snipes: Boolean get() = true

    /** The heavy machine gun looks for its own targets over a short distance; the cannon is the long arm. */
    override val stationRange: Double get() = 50.0
}
