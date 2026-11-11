package com.teamuhi.superbenemies.client

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.AtSoldierConfig
import com.teamuhi.superbenemies.config.DroneConfig
import com.teamuhi.superbenemies.config.DummyConfig
import com.teamuhi.superbenemies.config.SoldierConfig
import com.teamuhi.superbenemies.entity.drone.DroneEntity
import com.teamuhi.superbenemies.entity.dummy.PlayerDummyEntity
import com.teamuhi.superbenemies.entity.soldier.AtSoldierEntity
import com.teamuhi.superbenemies.entity.soldier.DummySoldierEntity
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

/** The `showCounter` lines above the hotbar: how many player dummies, dummy soldiers, AT soldiers and drones are loaded. */
@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = [Dist.CLIENT])
object DummyCounterOverlay {

    @SubscribeEvent
    fun render(event: RenderGuiOverlayEvent.Post) {
        if (event.overlay != VanillaGuiOverlay.HOTBAR.type()) return
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val level = minecraft.level ?: return
        val showPlayers = DummyConfig.SHOW_COUNTER.get()
        val showSoldiers = SoldierConfig.SHOW_COUNTER.get()
        val showAtSoldiers = AtSoldierConfig.SHOW_COUNTER.get()
        val showDrones = DroneConfig.SHOW_COUNTER.get()
        if (player.isSpectator || (!showPlayers && !showSoldiers && !showAtSoldiers && !showDrones)) return

        val entities = level.entitiesForRendering()
        val lines = ArrayList<Component>(4)
        if (showPlayers) {
            lines += Component.translatable("hud.superbenemies.player_dummies", entities.count { it is PlayerDummyEntity && it.isAlive })
        }
        if (showSoldiers) {
            lines += Component.translatable("hud.superbenemies.dummy_soldiers", entities.count { it is DummySoldierEntity && it.isAlive })
        }
        if (showAtSoldiers) {
            lines += Component.translatable("hud.superbenemies.at_soldiers", entities.count { it is AtSoldierEntity && it.isAlive })
        }
        if (showDrones) {
            lines += Component.translatable("hud.superbenemies.drones", entities.count { it is DroneEntity && it.isAlive })
        }

        val y = minecraft.window.guiScaledHeight - 49
        for ((index, line) in lines.withIndex()) {
            event.guiGraphics.drawString(minecraft.font, line, 4, y - index * 10, 0xFFFFFF, true)
        }
    }
}
