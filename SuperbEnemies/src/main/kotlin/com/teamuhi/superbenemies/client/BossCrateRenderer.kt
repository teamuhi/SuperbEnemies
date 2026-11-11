package com.teamuhi.superbenemies.client

import com.atsuishio.superbwarfare.client.animation.AnimationPlayType
import com.atsuishio.superbwarfare.resource.model.BlockModelReloadListener
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.BedrockAnimation
import com.maydaymemory.mae.basic.ArrayPoseBuilder
import com.maydaymemory.mae.basic.DummyPose
import com.maydaymemory.mae.basic.ZYXBoneTransformFactory
import com.maydaymemory.mae.blend.EulerAdditiveBlender
import com.maydaymemory.mae.blend.SimpleEulerAdditiveBlender
import com.maydaymemory.mae.control.runner.AnimationContext
import com.maydaymemory.mae.control.runner.AnimationRunner
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import com.teamuhi.superbenemies.entity.crate.BossCrateEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import java.util.WeakHashMap

/**
 * Draws a [BossCrateEntity] with Superb Warfare's crate model, texture and "open" animation, the same way its crate block
 * is drawn (the sounds the animation carries are played too), so the boss egg's crate looks exactly like the real one
 * being opened with a crowbar.
 *
 * Every crate keeps its own animation runner, started when the crate is first drawn and held on its last frame. If the
 * model or the animation is not loaded (Superb Warfare's resources failed, or the reload has not finished) nothing is drawn.
 */
class BossCrateRenderer(context: EntityRendererProvider.Context) : EntityRenderer<BossCrateEntity>(context) {

    private val runners = WeakHashMap<BossCrateEntity, AnimationRunner>()

    /** One model instance is enough: its pose is set again for every crate that is drawn. */
    private var instance = createInstance()

    override fun getTextureLocation(entity: BossCrateEntity): ResourceLocation = TEXTURE

    override fun render(
        entity: BossCrateEntity, entityYaw: Float, partialTick: Float,
        poseStack: PoseStack, buffer: MultiBufferSource, packedLight: Int
    ) {
        val model = (instance ?: createInstance().also { instance = it }) ?: return
        val runner = runners[entity] ?: startRunner() ?: return
        runners.putIfAbsent(entity, runner)

        // Same maths as Superb Warfare's crate block, on whose facing the boss is later turned: 0 is north, the boss yaw is 180 off.
        poseStack.pushPose()
        poseStack.mulPose(Axis.YP.rotationDegrees(180f - entityYaw))

        runner.tick()
        playSounds(entity, runner)
        model.applyPose(BLENDER.blend(model.bindPose, runner.evaluate() ?: DummyPose.INSTANCE))
        model.renderToBuffer(
            poseStack, buffer.getBuffer(RenderType.entityTranslucent(TEXTURE)), packedLight, OverlayTexture.NO_OVERLAY
        )
        poseStack.popPose()
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight)
    }

    /** Null while Superb Warfare's models are not loaded yet; the renderer is built before the first resource load. */
    private fun createInstance() = BlockModelReloadListener.getModel(MODEL)?.createInstance()

    private fun startRunner(): AnimationRunner? {
        val animation: BedrockAnimation = BlockModelReloadListener.getAnimation(ANIMATION)
            ?.firstOrNull { it.name == OPEN_ANIMATION } ?: return null
        return AnimationRunner(animation, AnimationContext(animation.specifiedEndTimeS)).also {
            it.state = AnimationPlayType.PLAY_ONCE_HOLD.state()
        }
    }

    /** The sounds the animation itself carries (the lid, the latches). */
    private fun playSounds(entity: BossCrateEntity, runner: AnimationRunner) {
        val sounds = runner.clip<ResourceLocation>(BedrockAnimation.SOUND_CHANNEL_NAME) ?: return
        val level = Minecraft.getInstance().level ?: return
        for (keyframe in sounds) {
            level.playSound(
                null, entity.x, entity.y, entity.z,
                SoundEvent.createVariableRangeEvent(keyframe.getValue()), SoundSource.BLOCKS, 1f, 1f
            )
        }
    }

    private companion object {
        val TEXTURE = ResourceLocation("superbwarfare", "textures/bedrock/block/container.png")
        val MODEL = ResourceLocation("superbwarfare", "models/bedrock/block/container.geo.json")
        val ANIMATION = ResourceLocation("superbwarfare", "animations/bedrock/block/container.animation.json")
        const val OPEN_ANIMATION = "animation.container.open"
        val BLENDER: EulerAdditiveBlender = SimpleEulerAdditiveBlender(ZYXBoneTransformFactory()) { ArrayPoseBuilder() }
    }
}
