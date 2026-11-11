package com.teamuhi.superbenemies.client

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.entity.drone.DroneEntity
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

/** Starts the looping engine buzz ([DroneBuzzSound]) of every drone that enters the client level. */
@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = [Dist.CLIENT])
object DroneBuzz {

    @SubscribeEvent
    fun onEntityJoin(event: EntityJoinLevelEvent) {
        if (!event.level.isClientSide) return
        val drone = event.entity as? DroneEntity ?: return
        onDroneJoin(drone)
    }
}
