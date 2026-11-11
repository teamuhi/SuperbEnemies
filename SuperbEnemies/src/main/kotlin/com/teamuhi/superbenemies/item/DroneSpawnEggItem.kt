package com.teamuhi.superbenemies.item

import com.teamuhi.superbenemies.client.DroneEggItemRenderer
import com.teamuhi.superbenemies.entity.drone.DroneEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.world.entity.EntityType
import net.minecraftforge.client.extensions.common.IClientItemExtensions
import net.minecraftforge.common.ForgeSpawnEggItem
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * The vanilla-style spawn egg of the drone (works on blocks and in dispensers), drawn as the drone itself instead of the
 * tinted egg: see [DroneEggItemRenderer]. The colours the egg constructor wants are never shown.
 */
class DroneSpawnEggItem(type: Supplier<out EntityType<DroneEntity>>, baseColor: Int, spotColor: Int, properties: Properties) :
    ForgeSpawnEggItem(type, baseColor, spotColor, properties) {

    override fun initializeClient(consumer: Consumer<IClientItemExtensions>) {
        super.initializeClient(consumer)
        consumer.accept(object : IClientItemExtensions {
            private var renderer: BlockEntityWithoutLevelRenderer? = null

            override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer {
                val minecraft = Minecraft.getInstance()
                return renderer ?: DroneEggItemRenderer(minecraft.blockEntityRenderDispatcher, minecraft.entityModels)
                    .also { renderer = it }
            }
        })
    }
}
