package com.teamuhi.superbenemies.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.PlayerConfig
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The "you are spotted" indicator, a radial detection ring like the ones in stealth games. While a tank boss has the player in
 * sight as its target (`PlayerConfig.SHOW_SPOTTED_ICON`, set on the Player page of the config screen) a thin ring is drawn
 * around the middle of the view. For every such tank a bright arc on the ring and an eye just outside it pivot round the ring
 * to the tank's bearing relative to where the camera looks: straight up when the tank is dead ahead, right when it is to the
 * right, down when it is behind. Turn toward it and the arc swings up to the top. The arc is more opaque (from faint at the edge of the range to solid beside the player) and wider the closer
 * the tank is.
 *
 * A tank publishes the entity id of the vehicle (or entity) it sees as its target in [TankBossEntity.spottedId]; the player is
 * spotted by a tank within [RANGE] blocks that lists the player's root vehicle (the player itself on foot). A tank stays
 * indicated [HOLD_TICKS] ticks after it looked away, so a flickering sight line does not blink the indicator.
 *
 * Everything is white over black, so it reads in every light and on every background. Purely visual: nothing is sent from the client.
 */
@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = [Dist.CLIENT])
object SpottedOverlay {

    private val EYE = ResourceLocation(SuperbEnemies.MOD_ID, "textures/gui/spotted_eye.png")
    private const val EYE_WIDTH = 32
    private const val EYE_HEIGHT = 20

    /** The eye is drawn at this fraction of its texture size. */
    private const val EYE_SCALE = 0.7f

    /** Tanks farther than this (blocks) do not count, whatever the server says. */
    private const val RANGE = 256.0

    /** Ticks a tank stays indicated after the last time it saw the player. */
    private const val HOLD_TICKS = 6L

    /** The ring's radius as a fraction of the smaller screen side, and how far outside the ring (pixels) the eye floats. */
    private const val RING_FRACTION = 0.15f
    private const val EYE_GAP = 16f

    /** Widths (pixels) of the ring and the arc, and how much wider the black outline under each is. */
    private const val RING_WIDTH = 1.5f
    private const val ARC_WIDTH = 3.5f
    private const val OUTLINE_EXTRA = 2f

    /** Half the angle (radians) the arc spans: [ARC_NEAR] for a tank right beside the player, [ARC_FAR] at [RANGE]. */
    private const val ARC_NEAR = 0.55f
    private const val ARC_FAR = 0.3f

    /** Opacity of an indicator for a tank at the edge of [RANGE]; it rises to 1 for a tank right beside the player. */
    private const val MIN_STRENGTH = 0.15f

    private const val STEPS = 32
    private const val TWO_PI = (Math.PI * 2.0).toFloat()

    /** One tank's indicator for this frame: its bearing from the camera (radians) and how close it is, 0 (far) to 1 (beside the player). */
    private class Mark(val bearing: Float, val closeness: Float)

    /** Entity id of every tank that has the player in sight, mapped to the `gameTime` it last did. */
    private val spotters = HashMap<Int, Long>()

    /** `gameTime` of the last scan of the tanks; the scan runs once per game tick, not once per frame. */
    private var lastScanTick = Long.MIN_VALUE

    @SubscribeEvent
    fun render(event: RenderGuiOverlayEvent.Post) {
        if (event.overlay != VanillaGuiOverlay.CROSSHAIR.type()) return
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        if (!PlayerConfig.SHOW_SPOTTED_ICON.get() || minecraft.options.hideGui || player.isSpectator) {
            spotters.clear()
            return
        }

        val now = level.gameTime
        if (now < lastScanTick) {
            // A new world or dimension restarted the clock.
            spotters.clear()
            lastScanTick = Long.MIN_VALUE
        }
        if (now != lastScanTick) {
            lastScanTick = now
            scan(minecraft, player.rootVehicle.id, now)
        }
        if (spotters.isEmpty() || !player.isAlive) return

        val graphics = event.guiGraphics
        val cx = event.window.guiScaledWidth / 2f
        val cy = event.window.guiScaledHeight / 2f
        val ring = min(event.window.guiScaledWidth, event.window.guiScaledHeight) * RING_FRACTION
        val camera = minecraft.gameRenderer.mainCamera
        val pulse = (0.9f + 0.1f * sin((now + event.partialTick) * 0.35f)).coerceIn(0f, 1f)

        val marks = ArrayList<Mark>(spotters.size)
        for (id in spotters.keys) {
            val tank = level.getEntity(id) as? TankBossEntity ?: continue
            val dx = Mth.lerp(event.partialTick.toDouble(), tank.xOld, tank.x) - camera.position.x
            val dz = Mth.lerp(event.partialTick.toDouble(), tank.zOld, tank.z) - camera.position.z
            // 0 = straight ahead, positive = to the right (Minecraft's yaw grows clockwise seen from above).
            val bearing = Math.toRadians(Mth.wrapDegrees(Math.toDegrees(atan2(-dx, dz)) - camera.yRot)).toFloat()
            val closeness = (1.0 - sqrt(dx * dx + dz * dz) / RANGE).coerceIn(0.0, 1.0).toFloat()
            marks.add(Mark(bearing, closeness))
        }
        if (marks.isEmpty()) return
        // Far tanks first, so the closest one is drawn on top.
        marks.sortBy { it.closeness }

        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }
        strip(cx, cy, ring, 0f, TWO_PI, RING_WIDTH + OUTLINE_EXTRA, 0f, 0.35f * pulse, fadeEnds = false)
        strip(cx, cy, ring, 0f, TWO_PI, RING_WIDTH, 1f, 0.45f * pulse, fadeEnds = false)
        for (mark in marks) {
            val strength = strengthOf(mark) * pulse
            val half = ARC_FAR + (ARC_NEAR - ARC_FAR) * mark.closeness
            strip(cx, cy, ring, mark.bearing - half, mark.bearing + half, ARC_WIDTH + OUTLINE_EXTRA, 0f, 0.6f * strength, fadeEnds = true)
            strip(cx, cy, ring, mark.bearing - half, mark.bearing + half, ARC_WIDTH, 1f, strength, fadeEnds = true)
        }
        for (mark in marks) {
            val reach = ring + EYE_GAP
            drawEye(graphics, cx + sin(mark.bearing) * reach, cy - cos(mark.bearing) * reach, strengthOf(mark) * pulse)
        }
        graphics.setColor(1f, 1f, 1f, 1f)
        RenderSystem.disableBlend()
    }

    private fun strengthOf(mark: Mark): Float = MIN_STRENGTH + (1f - MIN_STRENGTH) * mark.closeness

    /** Refreshes [spotters] from the tanks that name [rootId] now and drops those that have not for [HOLD_TICKS] ticks. */
    private fun scan(minecraft: Minecraft, rootId: Int, now: Long) {
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        for (entity in level.entitiesForRendering()) {
            if (entity !is TankBossEntity || !entity.isAlive || entity.spottedId != rootId) continue
            if (entity.distanceToSqr(player) <= RANGE * RANGE) spotters[entity.id] = now
        }
        spotters.values.removeIf { now - it > HOLD_TICKS }
    }

    /** The eye texture centred on ([x], [y]). */
    private fun drawEye(graphics: GuiGraphics, x: Float, y: Float, alpha: Float) {
        val pose = graphics.pose()
        pose.pushPose()
        pose.translate(x, y, 0f)
        pose.scale(EYE_SCALE, EYE_SCALE, 1f)
        graphics.setColor(1f, 1f, 1f, alpha)
        graphics.blit(EYE, -EYE_WIDTH / 2, -EYE_HEIGHT / 2, 0f, 0f, EYE_WIDTH, EYE_HEIGHT, EYE_WIDTH, EYE_HEIGHT)
        pose.popPose()
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }
    }

    /**
     * A band of the circle of [radius] around ([cx], [cy]) from angle [from] to [to] (radians, 0 = up, positive = clockwise),
     * [width] pixels wide, in grey level [shade] with alpha [alpha]; with [fadeEnds] the alpha falls to 0 at both ends.
     */
    private fun strip(
        cx: Float, cy: Float, radius: Float, from: Float, to: Float, width: Float, shade: Float, alpha: Float, fadeEnds: Boolean
    ) {
        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR)
        val inner = (radius - width / 2f).toDouble()
        val outer = (radius + width / 2f).toDouble()
        for (i in 0..STEPS) {
            val t = i / STEPS.toFloat()
            val angle = from + (to - from) * t
            val a = if (fadeEnds) alpha * sin(t * Math.PI.toFloat()) else alpha
            val sx = sin(angle).toDouble()
            val sy = -cos(angle).toDouble()
            builder.vertex(cx + sx * inner, cy + sy * inner, 0.0).color(shade, shade, shade, a).endVertex()
            builder.vertex(cx + sx * outer, cy + sy * outer, 0.0).color(shade, shade, shade, a).endVertex()
        }
        BufferUploader.drawWithShader(builder.end())
    }
}
