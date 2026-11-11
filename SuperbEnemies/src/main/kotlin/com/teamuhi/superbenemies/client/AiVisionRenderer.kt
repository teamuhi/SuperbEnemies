package com.teamuhi.superbenemies.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.DebugConfig
import com.teamuhi.superbenemies.debug.VisionCone
import com.teamuhi.superbenemies.debug.VisionState
import com.teamuhi.superbenemies.network.AiVisionPacket
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The in-world half of the boss vision debug (see `AiVision`): for every boss that sent an `AiVisionPacket` it draws
 *
 *  - a **sight line** from the boss to what it aims at: green = the target is seen, yellow = out of sight but still sensed
 *    (the sight-loss grace), red = hidden, aiming at the last known spot (marked with a small cross);
 *  - for tanks, the **sight cone**: the two edge lines and a 12-segment arc at the turret's height;
 *  - a **label** at the end of the line, "<boss> -> <target> [<category>]" (plus "(sensing)" / "(last known)"), or a grey
 *    "<boss>: no target" above a boss with nothing to shoot at.
 *
 * Drawn after the translucent blocks, camera-relative, through walls (the lines are the point: they show what the boss
 * cannot see through), so a red line behind a wall is still visible. The colours are saturated, the label sits on a
 * translucent black plate, so they read on a bright desert and a dark cave alike. Entries are keyed by boss id, glide to
 * each new packet over [GLIDE_MS], follow the live boss entity when it is loaded, fade out and expire [EXPIRE_MS] after the
 * last packet (so switching `showVision` off clears the world within that time). Nothing is drawn with the GUI hidden (F1) or
 * with the client's own `showVision` off; everything is cleared on logout. Client thread only.
 */
@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = [Dist.CLIENT])
object AiVisionRenderer {
    /** Milliseconds after its last packet that a boss's drawing is dropped (the server sends every 5 ticks = 250 ms). */
    private const val EXPIRE_MS = 600L

    /** The last milliseconds before expiry over which the drawing fades out. */
    private const val FADE_MS = 200f

    /** Milliseconds a point takes to glide to its new position after a packet. */
    private const val GLIDE_MS = 250f

    private const val COLOR_SEEN = 0x22E04A
    private const val COLOR_SENSING = 0xFFD600
    private const val COLOR_GHOST = 0xFF2A2A
    private const val COLOR_CONE = 0x33CCFF
    private const val COLOR_NONE = 0xC8C8C8

    private const val CROSS_SIZE = 0.6
    private const val ARC_SEGMENTS = 12

    /** Label scale at 10 blocks (the size of a name tag); farther labels grow with the distance so they keep their size on screen. */
    private const val LABEL_SCALE_PER_BLOCK = 0.0025f
    private const val LABEL_MIN_DISTANCE = 10.0
    private const val LABEL_PLATE = 0x90 shl 24
    private const val FULL_BRIGHT = 0xF000F0

    /** How far above the line's end or the boss's eye the label floats, in blocks. */
    private const val LABEL_LIFT_TARGET = 0.9
    private const val LABEL_LIFT_BOSS = 2.5

    private class Entry(
        val label: String,
        val state: VisionState,
        val eyeOffset: Vec3,
        val cone: VisionCone?,
        val targetName: String,
        val category: String,
        val fromEye: Vec3,
        val toEye: Vec3,
        val fromAim: Vec3?,
        val toAim: Vec3?,
        val stamp: Long
    ) {
        /** Fraction of the glide from the previous packet's point to this one's that has passed at [now]. */
        fun progress(now: Long): Float = ((now - stamp) / GLIDE_MS).coerceIn(0f, 1f)
    }

    private val entries = HashMap<Int, Entry>()

    /** Stores a packet (called from the network handler, client thread). */
    fun update(packet: AiVisionPacket) {
        val now = Util.getMillis()
        val previous = entries[packet.entityId]
        // Glide from where the old entry is drawn right now, so a late or early packet does not make the line jump.
        val fromEye = previous?.let { lerp(it.fromEye, it.toEye, it.progress(now)) } ?: packet.eye
        val fromAim = if (previous?.toAim != null && packet.aim != null) {
            lerp(previous.fromAim ?: previous.toAim, previous.toAim, previous.progress(now))
        } else {
            packet.aim
        }
        entries[packet.entityId] = Entry(
            packet.label, packet.state, packet.eyeOffset, packet.cone, packet.targetName, packet.category,
            fromEye, packet.eye, fromAim, packet.aim, now
        )
    }

    @SubscribeEvent
    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        if (entries.isEmpty()) return
        val now = Util.getMillis()
        entries.values.removeIf { now - it.stamp >= EXPIRE_MS }
        if (entries.isEmpty()) return

        val minecraft = Minecraft.getInstance()
        if (minecraft.options.hideGui || !DebugConfig.SHOW_VISION.get()) return
        val level = minecraft.level ?: return

        val camera = event.camera
        val cam = camera.position
        val pose = event.poseStack
        val buffers = minecraft.renderBuffers().bufferSource()
        val lines = buffers.getBuffer(RenderType.lines())
        val labels = ArrayList<Label>(entries.size)

        for ((bossId, entry) in entries) {
            val fade = ((EXPIRE_MS - (now - entry.stamp)) / FADE_MS).coerceIn(0f, 1f)
            // The live boss entity gives a smooth eye; the glided packet position is the fallback (boss not loaded here).
            val boss = level.getEntity(bossId)
            val eye = if (boss != null) boss.getPosition(event.partialTick).add(entry.eyeOffset)
            else lerp(entry.fromEye, entry.toEye, entry.progress(now))
            val aim = entry.toAim?.let { lerp(entry.fromAim ?: it, it, entry.progress(now)) }
            val color = colorOf(entry.state)

            if (aim != null) {
                line(lines, pose.last(), cam, eye, aim, color, fade)
                if (entry.state == VisionState.GHOST) cross(lines, pose.last(), cam, aim, color, fade)
            }
            entry.cone?.let { cone(lines, pose.last(), cam, eye, it, fade) }
            labels += labelOf(entry, eye, aim, color, fade)
        }

        // Through walls: a hidden target's red line is exactly the one that runs behind a block.
        RenderSystem.disableDepthTest()
        buffers.endBatch(RenderType.lines())
        RenderSystem.enableDepthTest()

        val font = minecraft.font
        for (label in labels) drawLabel(font, pose, buffers, camera.rotation(), cam, label)
        buffers.endBatch()
    }

    @SubscribeEvent
    fun onLogout(event: ClientPlayerNetworkEvent.LoggingOut) {
        entries.clear()
    }

    // ================================================================================================== lines

    private fun colorOf(state: VisionState): Int = when (state) {
        VisionState.SEEN -> COLOR_SEEN
        VisionState.SENSING -> COLOR_SENSING
        VisionState.GHOST -> COLOR_GHOST
        VisionState.NONE -> COLOR_NONE
    }

    /** One line segment from [a] to [b] (world positions), camera-relative; the lines vertex format needs the direction as its normal. */
    private fun line(consumer: VertexConsumer, pose: PoseStack.Pose, cam: Vec3, a: Vec3, b: Vec3, rgb: Int, fade: Float) {
        val ax = (a.x - cam.x).toFloat()
        val ay = (a.y - cam.y).toFloat()
        val az = (a.z - cam.z).toFloat()
        val bx = (b.x - cam.x).toFloat()
        val by = (b.y - cam.y).toFloat()
        val bz = (b.z - cam.z).toFloat()
        var nx = bx - ax
        var ny = by - ay
        var nz = bz - az
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length < 1.0e-4f || !length.isFinite()) return
        nx /= length
        ny /= length
        nz /= length
        val red = rgb shr 16 and 0xFF
        val green = rgb shr 8 and 0xFF
        val blue = rgb and 0xFF
        val alpha = (255 * fade).toInt()
        consumer.vertex(pose.pose(), ax, ay, az).color(red, green, blue, alpha).normal(pose.normal(), nx, ny, nz).endVertex()
        consumer.vertex(pose.pose(), bx, by, bz).color(red, green, blue, alpha).normal(pose.normal(), nx, ny, nz).endVertex()
    }

    /** A small three-axis cross at [at]: the last known spot. */
    private fun cross(consumer: VertexConsumer, pose: PoseStack.Pose, cam: Vec3, at: Vec3, rgb: Int, fade: Float) {
        line(consumer, pose, cam, at.add(-CROSS_SIZE, 0.0, 0.0), at.add(CROSS_SIZE, 0.0, 0.0), rgb, fade)
        line(consumer, pose, cam, at.add(0.0, -CROSS_SIZE, 0.0), at.add(0.0, CROSS_SIZE, 0.0), rgb, fade)
        line(consumer, pose, cam, at.add(0.0, 0.0, -CROSS_SIZE), at.add(0.0, 0.0, CROSS_SIZE), rgb, fade)
    }

    /** The sight cone at the height of [eye]: both edges and an arc through the middle of the range. */
    private fun cone(consumer: VertexConsumer, pose: PoseStack.Pose, cam: Vec3, eye: Vec3, cone: VisionCone, fade: Float) {
        val half = Math.toRadians(cone.halfAngleDeg.toDouble())
        val range = cone.range.toDouble()
        var previous: Vec3? = null
        for (i in 0..ARC_SEGMENTS) {
            val angle = -half + 2.0 * half * i / ARC_SEGMENTS
            val rim = rim(eye, cone, angle, range)
            if (i == 0 || i == ARC_SEGMENTS) line(consumer, pose, cam, eye, rim, COLOR_CONE, fade)
            previous?.let { line(consumer, pose, cam, it, rim, COLOR_CONE, fade) }
            previous = rim
        }
    }

    /** The point [range] blocks from [eye] along the cone's direction turned by [angle] radians about the vertical axis. */
    private fun rim(eye: Vec3, cone: VisionCone, angle: Double, range: Double): Vec3 {
        val c = cos(angle)
        val s = sin(angle)
        val dx = cone.dirX * c - cone.dirZ * s
        val dz = cone.dirX * s + cone.dirZ * c
        return Vec3(eye.x + dx * range, eye.y, eye.z + dz * range)
    }

    // ================================================================================================== labels

    private class Label(val text: String, val at: Vec3, val rgb: Int, val fade: Float)

    private fun labelOf(entry: Entry, eye: Vec3, aim: Vec3?, rgb: Int, fade: Float): Label {
        if (aim == null || entry.state == VisionState.NONE) {
            val text = if (entry.targetName.isEmpty()) "${entry.label}: no target" else "${entry.label} -> ${entry.targetName}"
            return Label(text, eye.add(0.0, LABEL_LIFT_BOSS, 0.0), rgb, fade)
        }
        val suffix = when (entry.state) {
            VisionState.SENSING -> " (sensing)"
            VisionState.GHOST -> " (last known)"
            else -> ""
        }
        val category = if (entry.category.isEmpty()) "" else " [${entry.category}]"
        val target = entry.targetName.ifEmpty { "target" }
        return Label("${entry.label} -> $target$category$suffix", aim.add(0.0, LABEL_LIFT_TARGET, 0.0), rgb, fade)
    }

    /**
     * A billboard label: turned to face the camera and scaled with the distance so it keeps its size on screen, drawn
     * see-through (the whole point is to read it behind walls) on a translucent black plate for contrast.
     */
    private fun drawLabel(
        font: Font, pose: PoseStack, buffers: net.minecraft.client.renderer.MultiBufferSource,
        rotation: org.joml.Quaternionf, cam: Vec3, label: Label
    ) {
        val alpha = (255 * label.fade).toInt()
        if (alpha < 8) return
        val dx = label.at.x - cam.x
        val dy = label.at.y - cam.y
        val dz = label.at.z - cam.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(LABEL_MIN_DISTANCE)
        val scale = LABEL_SCALE_PER_BLOCK * distance.toFloat()
        val plate = (((LABEL_PLATE ushr 24) * label.fade).toInt() shl 24)

        pose.pushPose()
        pose.translate(dx, dy, dz)
        pose.mulPose(rotation)
        pose.scale(-scale, -scale, scale)
        font.drawInBatch(
            label.text, -font.width(label.text) / 2f, -(font.lineHeight + 2f), (alpha shl 24) or label.rgb, false,
            pose.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH, plate, FULL_BRIGHT
        )
        pose.popPose()
    }

    private fun lerp(from: Vec3, to: Vec3, t: Float): Vec3 = from.lerp(to, t.toDouble())
}
