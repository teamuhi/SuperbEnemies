package com.teamuhi.superbenemies.client

import com.atsuishio.superbwarfare.entity.projectile.MortarShellEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.teamuhi.superbenemies.entity.drone.DroneEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import software.bernie.geckolib.core.animation.AnimationState
import software.bernie.geckolib.model.GeoModel
import software.bernie.geckolib.renderer.GeoEntityRenderer
import com.atsuishio.superbwarfare.init.ModEntities as SbwEntities

/**
 * Draws the drone with Superb Warfare's own drone model and texture (the same ones its player-flown drone uses), so
 * the two look identical, and hangs the mortar shell it carries under it. The model has four rotor bones that Superb
 * Warfare spins from code; [DroneModel] does the same.
 *
 * The shell is drawn the way Superb Warfare draws a drone attachment (its `mortar_shell` entry in `sbw/drone_attachments`:
 * a real mortar shell entity, never added to the world, drawn through the entity render dispatcher at offset 0, -0.2, 0),
 * so it looks exactly like the shell on a player-flown drone. It points the way the drone faces and goes with the drone
 * when it detonates.
 */
class DroneRenderer(context: EntityRendererProvider.Context) :
    GeoEntityRenderer<DroneEntity>(context, DroneModel()) {

    /** The shell that is drawn under every drone; one is enough since it holds no per-drone state apart from its heading. */
    private var shell: MortarShellEntity? = null

    init {
        shadowRadius = SHADOW_RADIUS
    }

    override fun render(
        entity: DroneEntity, entityYaw: Float, partialTick: Float,
        poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int
    ) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight)
        renderShell(entity, partialTick, poseStack, bufferSource, packedLight)
    }

    /** No red hurt tint (nor the death tint): the overlay is the plain one, whatever `hurtTime` says. */
    override fun getPackedOverlay(animatable: DroneEntity, u: Float, partialTick: Float): Int = OverlayTexture.NO_OVERLAY

    private fun renderShell(
        drone: DroneEntity, partialTick: Float, poseStack: PoseStack, bufferSource: MultiBufferSource, packedLight: Int
    ) {
        var carried = shell
        if (carried == null || carried.level() !== drone.level()) {
            carried = MortarShellEntity(SbwEntities.MORTAR_SHELL.get(), drone.level())
            shell = carried
        }
        val heading = Mth.rotLerp(partialTick, drone.yBodyRotO, drone.yBodyRot)
        carried.yRot = heading
        carried.yRotO = heading
        carried.xRot = 0f
        carried.xRotO = 0f
        // Fast projectiles are not drawn during their first ticks; their renderer counts `syncedTick`, not `tickCount`.
        carried.tickCount = SHELL_TICK_COUNT
        carried.syncedTick = SHELL_TICK_COUNT

        poseStack.pushPose()
        poseStack.translate(0.0, SHELL_OFFSET_Y, 0.0)
        Minecraft.getInstance().entityRenderDispatcher.render(carried, 0.0, 0.0, 0.0, heading, partialTick, poseStack, bufferSource, packedLight)
        poseStack.popPose()
    }

    private companion object {
        const val SHADOW_RADIUS = 0.3f

        /** Superb Warfare's own values for a mortar shell on a drone (`TickCount` 2, `Offset` 0, -0.2, 0). */
        const val SHELL_TICK_COUNT = 2
        const val SHELL_OFFSET_Y = -0.2
    }
}

class DroneModel : GeoModel<DroneEntity>() {

    override fun getModelResource(animatable: DroneEntity): ResourceLocation = MODEL

    override fun getTextureResource(animatable: DroneEntity): ResourceLocation = TEXTURE

    override fun getAnimationResource(animatable: DroneEntity): ResourceLocation = ANIMATION

    /** Spins the four rotors: Superb Warfare's own formula (`millis / 12` on the Y rotation), the same sense for all four. */
    override fun setCustomAnimations(animatable: DroneEntity, instanceId: Long, animationState: AnimationState<DroneEntity>?) {
        super.setCustomAnimations(animatable, instanceId, animationState)
        val angle = (System.currentTimeMillis() % SPIN_PERIOD_MILLIS).toFloat() / SPIN_DIVISOR
        for (rotor in ROTORS) getBone(rotor).ifPresent { it.rotY = angle }
    }

    private companion object {
        val MODEL = ResourceLocation("superbwarfare", "geo/drone.geo.json")
        val TEXTURE = ResourceLocation("superbwarfare", "textures/entity/drone.png")
        val ANIMATION = ResourceLocation("superbwarfare", "animations/drone.animation.json")

        val ROTORS = listOf("wingFL", "wingFR", "wingBL", "wingBR")
        const val SPIN_PERIOD_MILLIS = 36_000_000L
        const val SPIN_DIVISOR = 12f
    }
}
