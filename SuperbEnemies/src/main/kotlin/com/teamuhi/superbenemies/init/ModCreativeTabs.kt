package com.teamuhi.superbenemies.init

import com.teamuhi.superbenemies.SuperbEnemies
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.RegistryObject

object ModCreativeTabs {
    val REGISTRY: DeferredRegister<CreativeModeTab> =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SuperbEnemies.MOD_ID)

    @JvmField
    val MAIN: RegistryObject<CreativeModeTab> = REGISTRY.register("main") {
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.${SuperbEnemies.MOD_ID}"))
            .icon { ItemStack(ModItems.A10_BOSS_SPAWN_EGG.get()) }
            .displayItems { _, output ->
                output.accept(ModItems.A10_BOSS_SPAWN_EGG.get())
                output.accept(ModItems.AC130_BOSS_SPAWN_EGG.get())
                output.accept(ModItems.LAV25_BOSS_SPAWN_EGG.get())
                output.accept(ModItems.M1A2_BOSS_SPAWN_EGG.get())
                output.accept(ModItems.BRADLEY_BOSS_SPAWN_EGG.get())
                output.accept(ModItems.ZTZ99A_BOSS_SPAWN_EGG.get())
                output.accept(ModItems.PLAYER_DUMMY_SPAWN_EGG.get())
                output.accept(ModItems.DUMMY_SOLDIER_SPAWN_EGG.get())
                output.accept(ModItems.JAVELIN_SOLDIER_SPAWN_EGG.get())
                output.accept(ModItems.RPG_SOLDIER_SPAWN_EGG.get())
                output.accept(ModItems.DRONE_SPAWN_EGG.get())
            }
            .build()
    }
}
