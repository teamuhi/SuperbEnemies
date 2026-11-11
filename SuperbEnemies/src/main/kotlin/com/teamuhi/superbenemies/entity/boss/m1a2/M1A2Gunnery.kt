package com.teamuhi.superbenemies.entity.boss.m1a2

import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossEntity
import com.teamuhi.superbenemies.entity.boss.common.ground.TankGunnery
import com.teamuhi.superbenemies.entity.boss.common.ground.TankWeapon
import com.teamuhi.superbenemies.entity.boss.common.ground.WeaponKind

/**
 * The M1A2's guns: the 120mm cannon (AP / HE, one shell per reload), the coaxial machine gun, and the commander's .50 cal
 * on the weapon station, which turns on its own: it shoots aircraft first, then the nearest visible soft target (so it
 * works while the cannon deals with armour), then a soft main target. The cannon reaches 300 blocks, the machine guns far
 * less (coaxial 45, heavy 100). The shared [TankGunnery] does everything. The ZTZ-99A uses these guns too, with its own
 * (smaller) gun depression.
 *
 * @param depression how far below the horizon (degrees) the cannon and the coaxial gun can be laid
 */
class M1A2Gunnery(boss: TankBossEntity, settings: TankSettings, depression: Double = DEFAULT_DEPRESSION) :
    TankGunnery(boss, settings, weaponsFor(depression)) {

    companion object {
        /** The M1A2's 120mm gun depresses 9 degrees; the ZTZ-99A passes 6. */
        const val DEFAULT_DEPRESSION = 9.0

        private fun weaponsFor(depression: Double) = listOf(
            TankWeapon(
                "Cannon", WeaponKind.CANNON, seat = 0, fireAngle = 2.0, maxRange = 300.0, maxElevation = 20.0,
                maxDepression = depression, interval = 10, maxPierce = 6
            ),
            TankWeapon(
                "MachineGun", WeaponKind.COAX, seat = 0, fireAngle = 4.0, maxRange = 45.0, maxElevation = 20.0,
                maxDepression = depression, burstMin = 20, burstMax = 50, pauseMin = 10, pauseMax = 25
            ),
            TankWeapon(
                "PassengerMachineGun", WeaponKind.HEAVY, seat = 1, fireAngle = 4.0, maxRange = 100.0, maxElevation = 66.0,
                burstMin = 30, burstMax = 60, pauseMin = 10, pauseMax = 30
            )
        )
    }
}
