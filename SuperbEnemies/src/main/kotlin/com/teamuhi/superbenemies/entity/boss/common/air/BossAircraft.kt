package com.teamuhi.superbenemies.entity.boss.common.air

import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import net.minecraft.world.phys.Vec3

/**
 * A [BossVehicle] that flies: the A-10 and the AC-130. The planes add the flare load and the launch into the air. The
 * dummy and soldier code (cover, Igla) is written against this type, so it only ever reacts to planes, never to a tank.
 */
interface BossAircraft : BossVehicle {
    override val groundBoss: Boolean get() = false

    /** Configured flare bursts in a finite load. */
    fun flareAmmoLoad(): Int

    /** Called by the spawn egg before the entity is added: sets the home point and puts the aircraft in the air. */
    fun prepareLaunch(home: Vec3)
}
