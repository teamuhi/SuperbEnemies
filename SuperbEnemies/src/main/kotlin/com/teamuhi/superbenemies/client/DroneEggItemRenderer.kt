package com.teamuhi.superbenemies.client

import com.mojang.blaze3d.vertex.PoseStack
import com.teamuhi.superbenemies.entity.drone.DroneEntity
import com.teamuhi.superbenemies.init.ModEntities
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.EntityModelSet
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * Draws the drone egg as the drone itself: the same Superb Warfare drone model and texture that [DroneRenderer] uses in the
 * world (spinning rotors included), by handing a client-only [DroneEntity] to the entity renderer. The item model
 * (`builtin/entity`) hands the drawing over to this. Nothing is drawn without a client level (no drone can exist then).
 */
class DroneEggItemRenderer(dispatcher: BlockEntityRenderDispatcher, modelSet: EntityModelSet) :
    BlockEntityWithoutLevelRenderer(dispatcher, modelSet) {

    /** The entity that is drawn; it is never added to a world and is rebuilt when the client level changes. */
    private var drone: DroneEntity? = null

    override fun renderByItem(
        stack: ItemStack, context: ItemDisplayContext, poseStack: PoseStack,
        buffer: MultiBufferSource, packedLight: Int, packedOverlay: Int
    ) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        var shown = drone
        if (shown == null || shown.level() !== level) {
            shown = ModEntities.DRONE.get().create(level) ?: return
            drone = shown
        }

        val dispatcher = minecraft.entityRenderDispatcher
        // The shadow disc would be drawn flat on the item's plane.
        dispatcher.setRenderShadow(false)
        poseStack.pushPose()
        poseStack.translate(0.5, 0.35, 0.5)
        poseStack.scale(SCALE, SCALE, SCALE)
        dispatcher.render(shown, 0.0, 0.0, 0.0, 0f, 1f, poseStack, buffer, packedLight)
        poseStack.popPose()
        dispatcher.setRenderShadow(true) // the vanilla default; the dispatcher has no getter to restore from
    }

    private companion object {
        /** The drone is 0.9 blocks wide; this fills most of the item's one block cell. */
        const val SCALE = 0.9f
    }
}
