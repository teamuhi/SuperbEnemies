package com.teamuhi.superbenemies.client

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.DebugConfig
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

/**
 * The on-screen AI log: a small box with the last few AI thinking lines sent by the server (see `AiThoughtPacket`). Each
 * line lives [LIFETIME_MS] and fades out over its last [FADE_MS]. Drawn once per frame after the hotbar overlay, which also
 * fires in spectator mode.
 *
 * Placement and size come from the client-side debug options, read every frame: `screenX` / `screenY` place the box in
 * percent of the free screen space (0 = top left corner, 100 = flush right / bottom), `screenScale` is the text size in
 * percent and `screenMaxWidth` the widest the box may get. The box is measured first and then placed, so it stays fully on
 * screen at any window size or scale: its width is capped to the screen, and when it would be taller than the screen only
 * the newest lines that fit are drawn. The colours (white, gold and grey text on a translucent black box, with a text
 * shadow) read on bright and dark scenes alike.
 */
@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = [Dist.CLIENT])
object AiThinkingOverlay {
    private const val MAX_LINES = 8
    private const val LIFETIME_MS = 8000L
    private const val FADE_MS = 1000L

    /** Gap between the box and the screen edge, in GUI pixels. */
    private const val MARGIN = 4f
    private const val PADDING = 2

    private const val COLOR_LABEL = 0xFFD966
    private const val COLOR_DIM = 0xA0A0A0
    private const val COLOR_TEXT = 0xFFFFFF
    private const val BACKGROUND_ALPHA = 0x80

    /** The font renderer draws text with an alpha below 4 fully opaque, so a line that faded this far is not drawn at all. */
    private const val MIN_TEXT_ALPHA = 8

    /** The narrowest the text area may get, so a tiny `screenMaxWidth` at a large scale still shows something. */
    private const val MIN_CONTENT_WIDTH = 24

    private const val ELLIPSIS = "…"

    private class Line(val head: String, val state: String, val channel: String, val text: String, val time: Long)

    /** A line cut to the box width: its four parts, the text alpha byte and the width they use together. */
    private class Row(val head: String, val state: String, val channel: String, val text: String, val alpha: Int, val width: Int)

    private val lines = ArrayDeque<Line>(MAX_LINES + 1)

    /** Appends a line stamped with the current time; the oldest line is dropped past [MAX_LINES]. Client thread only. */
    fun add(label: String, entityId: Int, state: String, channel: String, text: String) {
        lines.addLast(
            Line(
                "$label #$entityId",
                if (state.isEmpty()) "" else "|$state",
                if (channel.isEmpty()) "" else "$channel:",
                text.replace('\n', ' ').replace('\r', ' '),
                Util.getMillis()
            )
        )
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @SubscribeEvent
    fun render(event: RenderGuiOverlayEvent.Post) {
        if (event.overlay != VanillaGuiOverlay.HOTBAR.type()) return
        if (lines.isEmpty()) return
        val now = Util.getMillis()
        while (lines.isNotEmpty() && now - lines.first().time >= LIFETIME_MS) lines.removeFirst()
        if (lines.isEmpty()) return

        val minecraft = Minecraft.getInstance()
        if (minecraft.options.hideGui || minecraft.options.renderDebug) return

        val font = minecraft.font
        val graphics = event.guiGraphics
        val screenW = minecraft.window.guiScaledWidth.toFloat()
        val screenH = minecraft.window.guiScaledHeight.toFloat()
        val scale = DebugConfig.SCREEN_SCALE.get().coerceAtLeast(1) / 100f
        val lineHeight = font.lineHeight + 1

        // The box, in screen pixels, may use the configured width but never more than the screen minus the margins; its
        // content is that over the scale (the text is drawn scaled).
        val boxLimitW = minOf(DebugConfig.SCREEN_MAX_WIDTH.get().toFloat(), screenW - 2 * MARGIN)
        val maxWidth = maxOf((boxLimitW / scale).toInt() - PADDING * 2, MIN_CONTENT_WIDTH)
        // Only the newest lines that fit the screen height are shown.
        val maxRows = (((screenH - 2 * MARGIN) / scale).toInt() - PADDING * 2).div(lineHeight).coerceAtLeast(1)
        val shown = if (lines.size > maxRows) lines.drop(lines.size - maxRows) else lines

        // Fit every line first so the box is only as wide as the longest one.
        val rows = ArrayList<Row>(shown.size)
        var width = 0
        var maxAlpha = 0f
        for (line in shown) {
            val alpha = alphaOf(now - line.time)
            val row = fit(font, line, maxWidth, alpha)
            rows += row
            width = maxOf(width, row.width)
            maxAlpha = maxOf(maxAlpha, alpha)
        }
        val background = (BACKGROUND_ALPHA * maxAlpha).toInt() shl 24

        // Place the measured box: 0 % = against the top left margin, 100 % = against the bottom right one. Never negative,
        // so a box bigger than the free space sits at the margin instead of leaving the screen.
        val boxW = (width + PADDING * 2) * scale
        val boxH = (rows.size * lineHeight + PADDING * 2) * scale
        val boxX = MARGIN + maxOf(0f, screenW - 2 * MARGIN - boxW) * DebugConfig.SCREEN_X.get().coerceIn(0, 100) / 100f
        val boxY = MARGIN + maxOf(0f, screenH - 2 * MARGIN - boxH) * DebugConfig.SCREEN_Y.get().coerceIn(0, 100) / 100f

        graphics.pose().pushPose()
        graphics.pose().translate(boxX, boxY, 0f)
        graphics.pose().scale(scale, scale, 1f)
        if (background != 0) {
            graphics.fill(0, 0, width + PADDING * 2, rows.size * lineHeight + PADDING * 2, background)
        }
        var y = PADDING
        for (row in rows) {
            drawRow(graphics, font, row, PADDING, y)
            y += lineHeight
        }
        graphics.pose().popPose()
    }

    @SubscribeEvent
    fun onLogout(event: ClientPlayerNetworkEvent.LoggingOut) {
        lines.clear()
    }

    private fun alphaOf(age: Long): Float {
        val remaining = LIFETIME_MS - age
        return if (remaining >= FADE_MS) 1f else (remaining.toFloat() / FADE_MS).coerceIn(0f, 1f)
    }

    /** Cuts the parts of [line] to [maxWidth] in order; the first part that does not fit ends with an ellipsis and the rest are dropped. */
    private fun fit(font: Font, line: Line, maxWidth: Int, alpha: Float): Row {
        var room = maxWidth
        var used = 0
        fun take(part: String, gap: Boolean): String {
            if (part.isEmpty() || room <= 0) return ""
            val shown = if (gap) " $part" else part
            val cut = if (font.width(shown) <= room) shown else font.plainSubstrByWidth(shown, room - font.width(ELLIPSIS)) + ELLIPSIS
            val w = font.width(cut)
            room -= w
            used += w
            return cut
        }
        val head = take(line.head, false)
        val state = take(line.state, false)
        val channel = take(line.channel, true)
        val text = take(line.text, true)
        return Row(head, state, channel, text, (alpha * 255f).toInt(), used)
    }

    private fun drawRow(graphics: GuiGraphics, font: Font, row: Row, x: Int, y: Int) {
        if (row.alpha < MIN_TEXT_ALPHA) return
        val alphaBits = row.alpha shl 24
        var cursor = x
        cursor = drawPart(graphics, font, row.head, cursor, y, COLOR_LABEL or alphaBits)
        cursor = drawPart(graphics, font, row.state, cursor, y, COLOR_DIM or alphaBits)
        cursor = drawPart(graphics, font, row.channel, cursor, y, COLOR_DIM or alphaBits)
        drawPart(graphics, font, row.text, cursor, y, COLOR_TEXT or alphaBits)
    }

    private fun drawPart(graphics: GuiGraphics, font: Font, part: String, x: Int, y: Int, color: Int): Int {
        if (part.isEmpty()) return x
        graphics.drawString(font, part, x, y, color, true)
        return x + font.width(part)
    }
}
