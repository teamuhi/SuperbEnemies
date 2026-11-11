package com.teamuhi.superbenemies.init

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.item.BossSpawnEggItem
import com.teamuhi.superbenemies.item.DroneSpawnEggItem
import com.teamuhi.superbenemies.item.SpawnMode
import net.minecraft.world.item.Item
import net.minecraftforge.common.ForgeSpawnEggItem
import net.minecraft.world.item.Rarity
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

object ModItems {
    val REGISTRY: DeferredRegister<Item> = DeferredRegister.create(ForgeRegistries.ITEMS, SuperbEnemies.MOD_ID)

    @JvmField
    val A10_BOSS_SPAWN_EGG: RegistryObject<BossSpawnEggItem> =
        REGISTRY.register("a10_boss_spawn_egg") {
            BossSpawnEggItem(
                Item.Properties().stacksTo(16).rarity(Rarity.EPIC),
                ModEntities.A10_WARTHOG_BOSS,
                A10_CLEARANCE_HALF_WIDTH
            )
        }

    @JvmField
    val AC130_BOSS_SPAWN_EGG: RegistryObject<BossSpawnEggItem> =
        REGISTRY.register("ac130_boss_spawn_egg") {
            BossSpawnEggItem(
                Item.Properties().stacksTo(16).rarity(Rarity.EPIC),
                ModEntities.AC130_ANGEL_OF_DEATH,
                AC130_CLEARANCE_HALF_WIDTH
            )
        }

    /** Puts a LAV-25 boss on the clicked surface (on water too: the hull floats). */
    @JvmField
    val LAV25_BOSS_SPAWN_EGG: RegistryObject<BossSpawnEggItem> =
        REGISTRY.register("lav25_boss_spawn_egg") {
            BossSpawnEggItem(
                Item.Properties().stacksTo(16).rarity(Rarity.EPIC),
                ModEntities.LAV25_BOSS,
                LAV25_CLEARANCE_HALF_WIDTH,
                SpawnMode.GROUND
            )
        }

    /** Puts an M1A2 boss on the clicked surface (dry ground only: the hull sinks). */
    @JvmField
    val M1A2_BOSS_SPAWN_EGG: RegistryObject<BossSpawnEggItem> =
        REGISTRY.register("m1a2_boss_spawn_egg") {
            BossSpawnEggItem(
                Item.Properties().stacksTo(16).rarity(Rarity.EPIC),
                ModEntities.M1A2_BOSS,
                M1A2_CLEARANCE_HALF_WIDTH,
                SpawnMode.GROUND
            )
        }

    /** Puts an M2 Bradley boss on the clicked surface (on water too: the hull floats). */
    @JvmField
    val BRADLEY_BOSS_SPAWN_EGG: RegistryObject<BossSpawnEggItem> =
        REGISTRY.register("bradley_boss_spawn_egg") {
            BossSpawnEggItem(
                Item.Properties().stacksTo(16).rarity(Rarity.EPIC),
                ModEntities.BRADLEY_BOSS,
                BRADLEY_CLEARANCE_HALF_WIDTH,
                SpawnMode.GROUND
            )
        }

    /** Puts a ZTZ-99A boss on the clicked surface (dry ground only: the hull sinks). */
    @JvmField
    val ZTZ99A_BOSS_SPAWN_EGG: RegistryObject<BossSpawnEggItem> =
        REGISTRY.register("ztz99a_boss_spawn_egg") {
            BossSpawnEggItem(
                Item.Properties().stacksTo(16).rarity(Rarity.EPIC),
                ModEntities.ZTZ99A_BOSS,
                ZTZ99A_CLEARANCE_HALF_WIDTH,
                SpawnMode.GROUND
            )
        }

    /** A vanilla-style spawn egg (works on blocks and in dispensers) for the player dummy. */
    @JvmField
    val PLAYER_DUMMY_SPAWN_EGG: RegistryObject<ForgeSpawnEggItem> =
        REGISTRY.register("player_dummy_spawn_egg") {
            ForgeSpawnEggItem(ModEntities.PLAYER_DUMMY, DUMMY_EGG_BASE_COLOR, DUMMY_EGG_SPOT_COLOR, Item.Properties())
        }

    /** A vanilla-style spawn egg for the Dummy Soldier. */
    @JvmField
    val DUMMY_SOLDIER_SPAWN_EGG: RegistryObject<ForgeSpawnEggItem> =
        REGISTRY.register("dummy_soldier_spawn_egg") {
            ForgeSpawnEggItem(ModEntities.DUMMY_SOLDIER, SOLDIER_EGG_BASE_COLOR, SOLDIER_EGG_SPOT_COLOR, Item.Properties())
        }

    /** A vanilla-style spawn egg for the Javelin Soldier. */
    @JvmField
    val JAVELIN_SOLDIER_SPAWN_EGG: RegistryObject<ForgeSpawnEggItem> =
        REGISTRY.register("javelin_soldier_spawn_egg") {
            ForgeSpawnEggItem(ModEntities.JAVELIN_SOLDIER, JAVELIN_EGG_BASE_COLOR, JAVELIN_EGG_SPOT_COLOR, Item.Properties())
        }

    /** A vanilla-style spawn egg for the RPG Soldier. */
    @JvmField
    val RPG_SOLDIER_SPAWN_EGG: RegistryObject<ForgeSpawnEggItem> =
        REGISTRY.register("rpg_soldier_spawn_egg") {
            ForgeSpawnEggItem(ModEntities.RPG_SOLDIER, RPG_EGG_BASE_COLOR, RPG_EGG_SPOT_COLOR, Item.Properties())
        }

    /** A vanilla-style spawn egg for the drone, drawn as the drone itself (the colours are never shown). */
    @JvmField
    val DRONE_SPAWN_EGG: RegistryObject<DroneSpawnEggItem> =
        REGISTRY.register("drone_spawn_egg") {
            DroneSpawnEggItem(ModEntities.DRONE, DRONE_EGG_BASE_COLOR, DRONE_EGG_SPOT_COLOR, Item.Properties())
        }

    /** Free airspace the spawn egg demands around the spawn point: about half the wingspan plus a margin. */
    private const val A10_CLEARANCE_HALF_WIDTH = 6.0
    private const val AC130_CLEARANCE_HALF_WIDTH = 16.0

    /** Half the hull width plus a margin: the ground eggs demand this much free ground around the spawn point. */
    private const val LAV25_CLEARANCE_HALF_WIDTH = 1.7
    private const val M1A2_CLEARANCE_HALF_WIDTH = 2.6
    private const val BRADLEY_CLEARANCE_HALF_WIDTH = 2.1
    private const val ZTZ99A_CLEARANCE_HALF_WIDTH = 2.6

    /** Skin tone and shirt blue of the default player skin; ClientSetup registers the same colours for the item tint. */
    const val DUMMY_EGG_BASE_COLOR = 0xC69C6D
    const val DUMMY_EGG_SPOT_COLOR = 0x3C44AA

    /** Olive drab and khaki for the soldier egg; ClientSetup registers the same colours for the item tint. */
    const val SOLDIER_EGG_BASE_COLOR = 0x556B2F
    const val SOLDIER_EGG_SPOT_COLOR = 0xC3B091

    /** Olive with charcoal for the Javelin soldier egg, rust brown with charcoal for the RPG soldier egg; ClientSetup registers the same colours for the item tint. */
    const val JAVELIN_EGG_BASE_COLOR = 0x4B5320
    const val JAVELIN_EGG_SPOT_COLOR = 0x2F2F2F
    const val RPG_EGG_BASE_COLOR = 0x6B4F2A
    const val RPG_EGG_SPOT_COLOR = 0x1E1E1E

    /** The drone's grey shell and its red light: what the egg constructor wants; the item is drawn as the drone, so they are never shown. */
    const val DRONE_EGG_BASE_COLOR = 0x8E949B
    const val DRONE_EGG_SPOT_COLOR = 0xD32F2F
}
