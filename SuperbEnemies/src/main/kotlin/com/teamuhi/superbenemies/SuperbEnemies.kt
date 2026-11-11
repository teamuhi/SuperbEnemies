package com.teamuhi.superbenemies

import com.teamuhi.superbenemies.client.ClientSetup
import com.teamuhi.superbenemies.config.Ac130Config
import com.teamuhi.superbenemies.config.AtSoldierConfig
import com.teamuhi.superbenemies.config.BossConfig
import com.teamuhi.superbenemies.config.DebugConfig
import com.teamuhi.superbenemies.config.DroneConfig
import com.teamuhi.superbenemies.config.DummyConfig
import com.teamuhi.superbenemies.config.PlayerConfig
import com.teamuhi.superbenemies.config.BradleyConfig
import com.teamuhi.superbenemies.config.Lav25Config
import com.teamuhi.superbenemies.config.M1A2Config
import com.teamuhi.superbenemies.config.Ztz99aConfig
import com.teamuhi.superbenemies.config.SoldierConfig
import com.teamuhi.superbenemies.entity.boss.common.ground.NoiseMap
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedBosses
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedProjectiles
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedSmoke
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedVehicles
import com.teamuhi.superbenemies.entity.boss.common.BossRespawns
import com.teamuhi.superbenemies.entity.boss.common.ground.TankAlerts
import com.teamuhi.superbenemies.entity.boss.common.ground.TankFormations
import com.teamuhi.superbenemies.entity.drone.DroneBlast
import com.teamuhi.superbenemies.entity.drone.TrackedDrones
import com.teamuhi.superbenemies.entity.dummy.TrackedDummies
import com.teamuhi.superbenemies.init.ModCreativeTabs
import com.teamuhi.superbenemies.init.ModEntities
import com.teamuhi.superbenemies.init.ModItems
import com.teamuhi.superbenemies.network.ModNetwork
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.DistExecutor
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.config.ModConfig
import org.apache.logging.log4j.LogManager
import thedarkcolour.kotlinforforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.forge.MOD_BUS

/**
 * Mod entry point. Superb Warfare is a mandatory dependency (declared in mods.toml with `ordering = "AFTER"`),
 * so every SBW class referenced from this mod is already loaded when the registries below fire.
 */
@Mod(SuperbEnemies.MOD_ID)
object SuperbEnemies {
    const val MOD_ID = "superbenemies"

    @JvmField
    val LOGGER = LogManager.getLogger(MOD_ID)

    init {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BossConfig.SPEC)
        // One file per boss so each can be tuned (and reset) independently.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Ac130Config.SPEC, "superbenemies-ac130-common.toml")
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Lav25Config.SPEC, "superbenemies-lav25-common.toml")
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, M1A2Config.SPEC, "superbenemies-m1a2-common.toml")
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BradleyConfig.SPEC, "superbenemies-bradley-common.toml")
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Ztz99aConfig.SPEC, "superbenemies-ztz99a-common.toml")
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, DummyConfig.SPEC, "superbenemies-dummy-common.toml")
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SoldierConfig.SPEC, "superbenemies-soldier-common.toml")
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, AtSoldierConfig.SPEC, "superbenemies-at-soldier-common.toml")
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, DroneConfig.SPEC, "superbenemies-drone-common.toml")
        // The AI thinking console output (master switch, heartbeat, per-AI filters).
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, DebugConfig.SPEC, "superbenemies-debug-common.toml")
        // What the player sees (the spotted icon): only this game's screen, so a client config.
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, PlayerConfig.SPEC, "superbenemies-player-client.toml")
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT) { Runnable { ClientSetup.registerConfigScreen() } }

        // The channel that carries the AI thinking lines to the on-screen log.
        ModNetwork.register()

        val bus = MOD_BUS
        ModEntities.REGISTRY.register(bus)
        ModItems.REGISTRY.register(bus)
        ModCreativeTabs.REGISTRY.register(bus)

        // Entity trackers the AI reads instead of scanning levels (guided missiles, boss vehicles, vehicles, smoke, player dummies).
        TrackedProjectiles.register(FORGE_BUS)
        TrackedBosses.register(FORGE_BUS)
        TrackedVehicles.register(FORGE_BUS)
        TrackedSmoke.register(FORGE_BUS)
        TrackedDummies.register(FORGE_BUS)
        // The drones that tell each other where a target is (see DroneEntity.alert).
        TrackedDrones.register(FORGE_BUS)
        // The drone's blast hurts vehicles with its own damage value (see DroneBlast).
        DroneBlast.register(FORGE_BUS)
        // What the ground bosses hear (shots, explosions, footsteps, moving vehicles).
        NoiseMap.register(FORGE_BUS)
        // Groups of teammate tanks driving in formation (see TankFormations).
        TankFormations.register(FORGE_BUS)
        // Calls of tanks under attack, heard by their teammates (see TankAlerts).
        TankAlerts.register(FORGE_BUS)
        // Bosses that withdrew to rearm come back through this (see BossRespawns).
        BossRespawns.register(FORGE_BUS)
    }
}
