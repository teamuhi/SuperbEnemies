package com.teamuhi.superbenemies.client

import com.atsuishio.superbwarfare.client.RenderHelper
import com.atsuishio.superbwarfare.client.overlay.OverlayTraceHandler
import com.atsuishio.superbwarfare.config.client.DisplayConfig
import com.atsuishio.superbwarfare.config.server.MiscConfig
import com.atsuishio.superbwarfare.event.ClientEventHandler
import com.atsuishio.superbwarfare.tools.FormatTool.format1D
import com.atsuishio.superbwarfare.tools.VectorTool.lerpGetEntityBoundingBoxCenter
import com.atsuishio.superbwarfare.tools.canBeSeen
import com.atsuishio.superbwarfare.tools.worldToScreen
import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.DroneConfig
import com.teamuhi.superbenemies.entity.drone.DroneEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import kotlin.math.max

/**
 * Superb Warfare's vehicle label (name, health bar and distance, see its `VehicleTeamOverlay`) for the drone, drawn the same
 * way: above the drone while it is the entity the camera looks at, shrinking with distance and field of view, white, with the
 * same bar. The drone is a mob, not a Superb Warfare vehicle, so Superb Warfare draws nothing for it by itself.
 *
 * Follows Superb Warfare's own "vehicle info" display option and combat HUD switch, plus the drone's `showLabel` option.
 */
@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = [Dist.CLIENT])
object DroneLabelOverlay {

    @SubscribeEvent
    fun render(event: RenderGuiOverlayEvent.Post) {
        if (event.overlay != VanillaGuiOverlay.HOTBAR.type()) return
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        if (minecraft.options.hideGui || player.isSpectator) return
        if (!DroneConfig.SHOW_LABEL.get() || !DisplayConfig.VEHICLE_INFO.get() || MiscConfig.HIDE_COMBAT_HUD.get()) return
        val drone = OverlayTraceHandler.cameraEntity as? DroneEntity ?: return
        if (!drone.isAlive) return

        val labelPos = lerpGetEntityBoundingBoxCenter(drone, event.partialTick).add(Vec3(0.0, drone.bbHeight / 2.0 + 0.5, 0.0))
        if (!labelPos.canBeSeen()) return
        val point = labelPos.worldToScreen()

        val distance = player.distanceTo(drone).toDouble()
        val graphics = event.guiGraphics
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(point.x.toFloat(), point.y.toFloat() - 12f, 0f)
        val size = ((30 / ClientEventHandler.fov) * 0.9f * max((512 - distance) / 512, 0.1).coerceIn(0.4, 1.0)).toFloat()
        pose.scale(size, size, size)

        val font = minecraft.font
        val color = -1
        val name = drone.displayName.string
        graphics.drawString(font, Component.literal(name), -font.width(name) / 2, -13, color, false)
        val range = format1D(distance, "M")
        graphics.drawString(font, Component.literal(range), -font.width(range) / 2, 7, color, false)

        // Black backing, white frame and the health bar, exactly as Superb Warfare lays them out.
        val frame = (255 shl 24) or color
        val type = RenderType.guiOverlay()
        RenderHelper.fill(graphics, type, -40.5f, -2f, 40.5f, 2f, 0f, -0x80000000)
        RenderHelper.fill(graphics, type, -41.5f, -3f, -40.5f, 3f, 0f, frame)
        RenderHelper.fill(graphics, type, -40.5f, -3f, 40.5f, -2f, 0f, frame)
        RenderHelper.fill(graphics, type, -40.5f, 2f, 40.5f, 3f, 0f, frame)
        RenderHelper.fill(graphics, type, 40.5f, -3f, 41.5f, 3f, 0f, frame)
        val fraction = (drone.health / drone.maxHealth).coerceIn(0f, 1f)
        RenderHelper.fill(graphics, type, -40f, -1.5f, -40 + 80 * fraction, 1.5f, 0f, frame)

        pose.popPose()
    }
}
