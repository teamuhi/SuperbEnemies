package com.teamuhi.superbenemies.client

import com.atsuishio.superbwarfare.resource.model.BlockModelReloadListener
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.geom.EntityModelSet
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack

/**
 * Draws the boss eggs as Superb Warfare's closed crate (its own item renderer does the same for its container item), in
 * the inventory, the hand and on the ground. The item models (`builtin/entity`, with Superb Warfare's container transforms)
 * hand the drawing over to this. Nothing is drawn while Superb Warfare's model is not loaded.
 */
class BossCrateItemRenderer(dispatcher: BlockEntityRenderDispatcher, modelSet: EntityModelSet) :
    BlockEntityWithoutLevelRenderer(dispatcher, modelSet) {

    private var instance = createInstance()

    override fun renderByItem(
        stack: ItemStack, context: ItemDisplayContext, poseStack: PoseStack,
        buffer: MultiBufferSource, packedLight: Int, packedOverlay: Int
    ) {
        val model = (instance ?: createInstance().also { instance = it }) ?: return
        poseStack.pushPose()
        poseStack.translate(0.5f, 0.5f, 0.5f)
        model.resetPose()
        model.renderToBuffer(poseStack, buffer.getBuffer(RenderType.entityCutout(TEXTURE)), packedLight, packedOverlay)
        poseStack.popPose()
    }

    private fun createInstance() = BlockModelReloadListener.getModel(MODEL)?.createInstance()

    private companion object {
        val TEXTURE = ResourceLocation("superbwarfare", "textures/bedrock/block/container.png")
        val MODEL = ResourceLocation("superbwarfare", "models/bedrock/block/container.geo.json")
    }
}
