package com.teamuhi.superbenemies.network

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.DebugConfig
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import java.util.Optional

/**
 * The mod's network channel. Its messages are both clientbound debug data: the [AiThoughtPacket] behind the on-screen AI
 * log (id 0) and the [AiVisionPacket] behind the in-world vision lines (id 1).
 */
object ModNetwork {
    private const val PROTOCOL = "1"

    /** Most overlay packets (thoughts and vision together) sent in one game tick, across all AIs and players; the rest only reach the console. */
    private const val MAX_PER_TICK = 40

    private val CHANNEL: SimpleChannel = NetworkRegistry.newSimpleChannel(
        ResourceLocation(SuperbEnemies.MOD_ID, "main"),
        { PROTOCOL }, { it == PROTOCOL }, { it == PROTOCOL }
    )

    /** The game tick [sentThisTick] counts for. */
    private var countedTick = Long.MIN_VALUE
    private var sentThisTick = 0

    /** Registers the messages. Called from the mod constructor. */
    fun register() {
        CHANNEL.registerMessage(
            0, AiThoughtPacket::class.java,
            { packet, buf -> packet.encode(buf) },
            AiThoughtPacket::decode,
            { packet, context -> packet.handle(context) },
            Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        )
        CHANNEL.registerMessage(
            1, AiVisionPacket::class.java,
            { packet, buf -> packet.encode(buf) },
            AiVisionPacket::decode,
            { packet, context -> packet.handle(context) },
            Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        )
    }

    /**
     * Sends a thinking line of [owner] to every player within `screenRange` blocks of it in its own level, as long as the
     * per-tick cap allows. The packet is only built when some player is in range. Server thread only.
     */
    fun sendThought(owner: Entity, label: String, state: String, channel: String, text: String) {
        broadcast(owner) {
            AiThoughtPacket(
                label.take(AiThoughtPacket.MAX_LABEL), owner.id, state.take(AiThoughtPacket.MAX_STATE),
                channel.take(AiThoughtPacket.MAX_CHANNEL), text.take(AiThoughtPacket.MAX_TEXT)
            )
        }
    }

    /**
     * Sends the vision data of [owner] (see `AiVision`) to every player within `screenRange` blocks of it in its own level,
     * under the same per-tick cap as the thoughts. [build] only runs when some player is in range and the cap still has
     * room. Server thread only.
     */
    fun sendVision(owner: Entity, build: () -> AiVisionPacket) {
        broadcast(owner, build)
    }

    /** Sends the packet [build] makes (built at most once) to every player in range of [owner], within the per-tick cap. */
    private inline fun broadcast(owner: Entity, build: () -> Any) {
        val level = owner.level() as? ServerLevel ?: return
        val players = level.players()
        if (players.isEmpty()) return
        val tick = level.gameTime
        if (tick != countedTick) {
            countedTick = tick
            sentThisTick = 0
        }
        val range = DebugConfig.SCREEN_RANGE.get().toDouble()
        val rangeSq = range * range
        var packet: Any? = null
        for (player in players) {
            if (sentThisTick >= MAX_PER_TICK) return
            if (player.distanceToSqr(owner) > rangeSq) continue
            if (packet == null) packet = build()
            CHANNEL.send(PacketDistributor.PLAYER.with { player }, packet)
            sentThisTick++
        }
    }
}
