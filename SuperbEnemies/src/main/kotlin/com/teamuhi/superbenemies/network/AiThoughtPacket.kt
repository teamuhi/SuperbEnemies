package com.teamuhi.superbenemies.network

import com.teamuhi.superbenemies.client.AiThinkingOverlay
import net.minecraft.network.FriendlyByteBuf
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.network.NetworkEvent
import java.util.function.Supplier

/**
 * One AI thinking line, server to client, shown by [AiThinkingOverlay]. The fields are the parts of the console line
 * `[AI][label #entityId|state] channel: text`.
 */
class AiThoughtPacket(
    val label: String,
    val entityId: Int,
    val state: String,
    val channel: String,
    val text: String
) {
    fun encode(buf: FriendlyByteBuf) {
        buf.writeUtf(label, MAX_LABEL)
        buf.writeVarInt(entityId)
        buf.writeUtf(state, MAX_STATE)
        buf.writeUtf(channel, MAX_CHANNEL)
        buf.writeUtf(text, MAX_TEXT)
    }

    fun handle(context: Supplier<NetworkEvent.Context>) {
        val ctx = context.get()
        ctx.enqueueWork {
            // The overlay class is client only; it is touched from this lambda alone, which runs on the client dist.
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable { AiThinkingOverlay.add(label, entityId, state, channel, text) } }
        }
        ctx.packetHandled = true
    }

    companion object {
        const val MAX_LABEL = 32
        const val MAX_STATE = 64
        const val MAX_CHANNEL = 64

        /** Longer texts are cut on the server before sending. */
        const val MAX_TEXT = 256

        fun decode(buf: FriendlyByteBuf): AiThoughtPacket = AiThoughtPacket(
            buf.readUtf(MAX_LABEL), buf.readVarInt(), buf.readUtf(MAX_STATE), buf.readUtf(MAX_CHANNEL), buf.readUtf(MAX_TEXT)
        )
    }
}
