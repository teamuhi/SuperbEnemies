package com.teamuhi.superbenemies.network

import com.teamuhi.superbenemies.client.AiVisionRenderer
import com.teamuhi.superbenemies.debug.VisionCone
import com.teamuhi.superbenemies.debug.VisionState
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.network.NetworkEvent
import java.util.function.Supplier

/**
 * What one boss sees, server to client, drawn in the world by [AiVisionRenderer] (see `AiVision`).
 *
 * Layout (in this order): boss entity id (varint), boss label (utf), state (byte, the [VisionState] ordinal), eye position
 * (3 doubles), eye offset from the boss's own position (3 floats; the client follows the live boss entity with it, so the
 * line does not lag behind a fast plane), has-aim flag (boolean) and the aim point (3 doubles) when set, target entity id
 * (varint, -1 = none), target name (utf), target category (utf, empty = none or no category), has-cone flag (boolean) and
 * the cone (dirX, dirZ, half angle, range as 4 floats) when set.
 */
class AiVisionPacket(
    val entityId: Int,
    val label: String,
    val state: VisionState,
    val eye: Vec3,
    val eyeOffset: Vec3,
    val aim: Vec3?,
    val targetId: Int,
    val targetName: String,
    val category: String,
    val cone: VisionCone?
) {
    fun encode(buf: FriendlyByteBuf) {
        buf.writeVarInt(entityId)
        buf.writeUtf(label, MAX_LABEL)
        buf.writeByte(state.ordinal)
        buf.writeDouble(eye.x).writeDouble(eye.y).writeDouble(eye.z)
        buf.writeFloat(eyeOffset.x.toFloat()).writeFloat(eyeOffset.y.toFloat()).writeFloat(eyeOffset.z.toFloat())
        buf.writeBoolean(aim != null)
        if (aim != null) buf.writeDouble(aim.x).writeDouble(aim.y).writeDouble(aim.z)
        buf.writeVarInt(targetId + 1)
        buf.writeUtf(targetName, MAX_NAME)
        buf.writeUtf(category, MAX_CATEGORY)
        buf.writeBoolean(cone != null)
        if (cone != null) buf.writeFloat(cone.dirX).writeFloat(cone.dirZ).writeFloat(cone.halfAngleDeg).writeFloat(cone.range)
    }

    fun handle(context: Supplier<NetworkEvent.Context>) {
        val ctx = context.get()
        ctx.enqueueWork {
            // The renderer class is client only; it is touched from this lambda alone, which runs on the client dist.
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable { AiVisionRenderer.update(this) } }
        }
        ctx.packetHandled = true
    }

    companion object {
        const val MAX_LABEL = 32
        const val MAX_NAME = 48
        const val MAX_CATEGORY = 16

        fun decode(buf: FriendlyByteBuf): AiVisionPacket {
            val entityId = buf.readVarInt()
            val label = buf.readUtf(MAX_LABEL)
            val states = VisionState.entries
            val state = states[buf.readByte().toInt().coerceIn(0, states.size - 1)]
            val eye = Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
            val offset = Vec3(buf.readFloat().toDouble(), buf.readFloat().toDouble(), buf.readFloat().toDouble())
            val aim = if (buf.readBoolean()) Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) else null
            val targetId = buf.readVarInt() - 1
            val name = buf.readUtf(MAX_NAME)
            val category = buf.readUtf(MAX_CATEGORY)
            val cone = if (buf.readBoolean()) VisionCone(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()) else null
            return AiVisionPacket(entityId, label, state, eye, offset, aim, targetId, name, category, cone)
        }
    }
}
