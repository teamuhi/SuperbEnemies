package com.teamuhi.superbenemies.client

import com.mojang.blaze3d.platform.InputConstants
import com.teamuhi.superbenemies.SuperbEnemies
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterKeyMappingsEvent
import net.minecraftforge.client.settings.KeyConflictContext
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.lwjgl.glfw.GLFW

/**
 * Hotkey that opens the config editor without going through the Mods screen (default K, rebindable under Controls). The
 * same key closes the editor again, see [BossConfigScreen.keyPressed]. Like the Mods screen button it edits the local copy
 * of the config: on a dedicated server the server's own file is what counts.
 */
object ConfigHotkey {

    val KEY = KeyMapping(
        "key.superbenemies.config", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K,
        "key.categories.superbenemies"
    )
}

/** Registers [ConfigHotkey.KEY] so it shows up under Controls. */
@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object ConfigHotkeyRegistration {
    @SubscribeEvent
    fun registerKeys(event: RegisterKeyMappingsEvent) {
        event.register(ConfigHotkey.KEY)
    }
}

/** Opens the editor on a key press while playing with no screen open. */
@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = [Dist.CLIENT])
object ConfigHotkeyListener {
    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val minecraft = Minecraft.getInstance()
        // Always drain the presses, so one made while a screen was open does not open the editor later.
        var pressed = false
        while (ConfigHotkey.KEY.consumeClick()) pressed = true
        if (pressed && minecraft.screen == null && minecraft.level != null) {
            minecraft.setScreen(ClientSetup.createConfigScreen(null))
        }
    }
}
