package com.teamuhi.superbenemies.client

import com.atsuishio.superbwarfare.client.renderer.entity.A10Renderer
import com.atsuishio.superbwarfare.client.renderer.entity.Ac130hRenderer
import com.atsuishio.superbwarfare.client.renderer.entity.BradleyRenderer
import com.atsuishio.superbwarfare.client.renderer.entity.Lav25Renderer
import com.atsuishio.superbwarfare.client.renderer.entity.M1A2Renderer
import com.atsuishio.superbwarfare.client.renderer.entity.Ztz99aRenderer
import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.Ac130Config
import com.teamuhi.superbenemies.config.AtSoldierConfig
import com.teamuhi.superbenemies.config.BossConfig
import com.teamuhi.superbenemies.config.BossKind
import com.teamuhi.superbenemies.config.mirroredToggles
import com.teamuhi.superbenemies.config.DebugConfig
import com.teamuhi.superbenemies.config.DroneConfig
import com.teamuhi.superbenemies.config.PlayerConfig
import com.teamuhi.superbenemies.config.DummyConfig
import com.teamuhi.superbenemies.config.BradleyConfig
import com.teamuhi.superbenemies.config.Lav25Config
import com.teamuhi.superbenemies.config.M1A2Config
import com.teamuhi.superbenemies.config.Ztz99aConfig
import com.teamuhi.superbenemies.config.SoldierConfig
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.entity.soldier.AtSoldierEntity
import com.teamuhi.superbenemies.entity.soldier.DummySoldierEntity
import com.teamuhi.superbenemies.init.ModEntities
import com.teamuhi.superbenemies.init.ModItems
import net.minecraft.client.Minecraft
import net.minecraft.client.color.item.ItemColor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.entity.NoopRenderer
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.ConfigScreenHandler
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.client.event.RegisterColorHandlersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.common.Mod
import java.util.function.BiFunction

/**
 * Client-only registrations. The bosses reuse Superb Warfare's own A-10 and AC-130H renderers unchanged, so they look
 * and animate exactly like the player-flown aircraft (flaps, propellers, landing gear).
 */
@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object ClientSetup {

    /** Adds the Config button for this mod on the Mods screen. Called from the mod constructor on the client only. */
    fun registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory::class.java) {
            ConfigScreenHandler.ConfigScreenFactory(BiFunction<Minecraft, Screen, Screen> { _, parent ->
                createConfigScreen(parent)
            })
        }
    }

    /** The config editor, shared by the Mods screen button and the [ConfigHotkey]. [parent] is null when opened in-game. */
    fun createConfigScreen(parent: Screen?): Screen =
                BossConfigScreen(
                    parent,
                    listOf(
                        ConfigGroup(group("aircraft"), listOf(
                        ConfigPage(
                            tab("a10"), BossConfig.SPEC, BossConfig.ALL,
                            requires = mapOf(
                                BossConfig.FLARE_MISS_CHANCE to BossConfig.DEFENSIVE_FLARES,
                                BossConfig.DEFAULT_FLARE_CHANCE to BossConfig.DEFENSIVE_FLARES,
                                BossConfig.BARREL_ROLL_FLARE_CHANCE to BossConfig.DEFENSIVE_FLARES,
                                BossConfig.CLIMB_FLARE_CHANCE to BossConfig.DEFENSIVE_FLARES,
                                BossConfig.SIDE_FLARES_CHANCE to BossConfig.DEFENSIVE_FLARES,
                                BossConfig.CANNON_DAMAGE_MULT to BossConfig.CANNON_ENABLED,
                                BossConfig.ROCKET_DAMAGE_MULT to BossConfig.ROCKETS_ENABLED,
                                BossConfig.BOMB_DAMAGE_MULT to BossConfig.BOMBS_ENABLED,
                                BossConfig.MISSILE_DAMAGE_MULT to BossConfig.MISSILES_ENABLED,
                                BossConfig.CANNON_ROUNDS to BossConfig.CANNON_ENABLED,
                                BossConfig.ROCKET_COUNT to BossConfig.ROCKETS_ENABLED,
                                BossConfig.BOMB_COUNT to BossConfig.BOMBS_ENABLED,
                                BossConfig.MISSILE_COUNT to BossConfig.MISSILES_ENABLED,
                                BossConfig.GUN_RUN_CHANCE to BossConfig.CANNON_ENABLED,
                                BossConfig.GUN_RUN_BOMB_CHANCE to BossConfig.CANNON_ENABLED,
                                BossConfig.ROCKET_RUN_CHANCE to BossConfig.ROCKETS_ENABLED,
                                BossConfig.BOMB_RUN_CHANCE to BossConfig.BOMBS_ENABLED,
                                BossConfig.NUKE_RUN_CHANCE to BossConfig.NUKE_ENABLED,
                                BossConfig.MISSILE_ORBIT_CHANCE to BossConfig.MISSILES_ENABLED,
                                BossConfig.ROCKET_MISSILE_RUN_CHANCE to BossConfig.ROCKETS_ENABLED,
                                BossConfig.DOUBLE_GUN_RUN_CHANCE to BossConfig.CANNON_ENABLED,
                                BossConfig.CANNON_ROCKET_STALL_CHANCE to BossConfig.CANNON_ENABLED,
                                BossConfig.ROCKET_BOMB_STALL_CHANCE to BossConfig.ROCKETS_ENABLED,
                                BossConfig.FLARE_COUNT to BossConfig.LIMITED_AMMO,
                                BossConfig.RESUPPLY_SECONDS to BossConfig.LIMITED_AMMO,
                                BossConfig.IDLE_RESUPPLY_SECONDS to BossConfig.LIMITED_AMMO,
                                BossConfig.IDLE_RESUPPLY_CHANCE to BossConfig.LIMITED_AMMO,
                                BossConfig.UNSTUCK_SECONDS to BossConfig.UNSTUCK_TELEPORT
                            ),
                            disabledWhenEnabled = mapOf(
                                BossConfig.AGGRO_RANGE to BossConfig.GLOBAL_AGGRO
                            ),
                            sliders = BossConfig.SLIDERS
                        ),
                        ConfigPage(
                            tab("ac130"), Ac130Config.SPEC, Ac130Config.ALL,
                            requires = mapOf(
                                Ac130Config.FLARE_MISS_CHANCE to Ac130Config.DEFENSIVE_FLARES,
                                Ac130Config.CANNON_SIMULTANEOUS to Ac130Config.CANNON_ENABLED,
                                Ac130Config.BOFORS_SIMULTANEOUS to Ac130Config.BOFORS_ENABLED,
                                Ac130Config.HOWITZER_SIMULTANEOUS to Ac130Config.HOWITZER_ENABLED,
                                Ac130Config.CANNON_DAMAGE_MULT to Ac130Config.CANNON_ENABLED,
                                Ac130Config.BOFORS_DAMAGE_MULT to Ac130Config.BOFORS_ENABLED,
                                Ac130Config.HOWITZER_DAMAGE_MULT to Ac130Config.HOWITZER_ENABLED,
                                Ac130Config.CANNON_ROUNDS to Ac130Config.CANNON_ENABLED,
                                Ac130Config.BOFORS_ROUNDS to Ac130Config.BOFORS_ENABLED,
                                Ac130Config.HOWITZER_ROUNDS to Ac130Config.HOWITZER_ENABLED,
                                Ac130Config.SPLIT_FIRE_CHANCE to Ac130Config.CANNON_ENABLED,
                                Ac130Config.FOCUS_FIRE_CHANCE to Ac130Config.CANNON_ENABLED,
                                Ac130Config.FLARE_COUNT to Ac130Config.LIMITED_AMMO,
                                Ac130Config.RESUPPLY_SECONDS to Ac130Config.LIMITED_AMMO,
                                Ac130Config.IDLE_RESUPPLY_SECONDS to Ac130Config.LIMITED_AMMO,
                                Ac130Config.IDLE_RESUPPLY_CHANCE to Ac130Config.LIMITED_AMMO,
                                Ac130Config.UNSTUCK_SECONDS to Ac130Config.UNSTUCK_TELEPORT
                            ),
                            disabledWhenEnabled = mapOf(
                                Ac130Config.AGGRO_RANGE to Ac130Config.GLOBAL_AGGRO
                            ),
                            sliders = Ac130Config.SLIDERS
                        ),
                        )),
                        ConfigGroup(group("armour"), listOf(
                        ConfigPage(
                            tab("lav25"), Lav25Config.SPEC, Lav25Config.ALL,
                            requires = mapOf(
                                Lav25Config.CANNON_DAMAGE_MULT to Lav25Config.CANNON_ENABLED,
                                Lav25Config.CANNON_SHELL to Lav25Config.CANNON_ENABLED,
                                Lav25Config.CANNON_ROUNDS to Lav25Config.CANNON_ENABLED,
                                Lav25Config.MACHINE_GUN_DAMAGE_MULT to Lav25Config.MACHINE_GUN_ENABLED,
                                Lav25Config.MACHINE_GUN_ROUNDS to Lav25Config.MACHINE_GUN_ENABLED,
                                Lav25Config.MISSILE_DAMAGE_MULT to Lav25Config.MISSILE_ENABLED,
                                Lav25Config.MISSILE_COOLDOWN_SECONDS to Lav25Config.MISSILE_ENABLED,
                                Lav25Config.MISSILE_ROUNDS to Lav25Config.MISSILE_ENABLED,
                                Lav25Config.SMOKE_CHARGES to Lav25Config.LIMITED_AMMO,
                                Lav25Config.RESUPPLY_SECONDS to Lav25Config.LIMITED_AMMO,
                                Lav25Config.SMOKE_WHEN_HIT_CHANCE to Lav25Config.SMOKE_ENABLED,
                                Lav25Config.SMOKE_AGAINST_MISSILES to Lav25Config.SMOKE_ENABLED,
                                Lav25Config.SMOKE_AGAINST_AIR to Lav25Config.SMOKE_ENABLED,
                                Lav25Config.FLANK_CHANCE to Lav25Config.FLANKING_ENABLED,
                                Lav25Config.RETURN_FIRE_SECONDS to Lav25Config.RETURN_FIRE_ENABLED,
                                Lav25Config.FORMATION_SPACING to Lav25Config.FORMATION_ENABLED,
                                Lav25Config.FORMATION_HALT_SECONDS to Lav25Config.FORMATION_ENABLED,
                                Lav25Config.IDLE_RESUPPLY_SECONDS to Lav25Config.LIMITED_AMMO,
                                Lav25Config.IDLE_RESUPPLY_CHANCE to Lav25Config.LIMITED_AMMO
                            ),
                            disabledWhenEnabled = mapOf(
                                Lav25Config.AGGRO_RANGE to Lav25Config.GLOBAL_AGGRO
                            ),
                            sliders = Lav25Config.SLIDERS
                        ),
                        ConfigPage(
                            tab("m1a2"), M1A2Config.SPEC, M1A2Config.ALL,
                            requires = mapOf(
                                M1A2Config.CANNON_DAMAGE_MULT to M1A2Config.CANNON_ENABLED,
                                M1A2Config.CANNON_SHELL to M1A2Config.CANNON_ENABLED,
                                M1A2Config.CANNON_ROUNDS to M1A2Config.CANNON_ENABLED,
                                M1A2Config.MACHINE_GUN_DAMAGE_MULT to M1A2Config.MACHINE_GUN_ENABLED,
                                M1A2Config.MACHINE_GUN_ROUNDS to M1A2Config.MACHINE_GUN_ENABLED,
                                M1A2Config.HEAVY_MG_DAMAGE_MULT to M1A2Config.HEAVY_MG_ENABLED,
                                M1A2Config.HEAVY_MG_ROUNDS to M1A2Config.HEAVY_MG_ENABLED,
                                M1A2Config.SMOKE_CHARGES to M1A2Config.LIMITED_AMMO,
                                M1A2Config.RESUPPLY_SECONDS to M1A2Config.LIMITED_AMMO,
                                M1A2Config.SMOKE_WHEN_HIT_CHANCE to M1A2Config.SMOKE_ENABLED,
                                M1A2Config.SMOKE_AGAINST_MISSILES to M1A2Config.SMOKE_ENABLED,
                                M1A2Config.SMOKE_AGAINST_AIR to M1A2Config.SMOKE_ENABLED,
                                M1A2Config.FLANK_CHANCE to M1A2Config.FLANKING_ENABLED,
                                M1A2Config.RETURN_FIRE_SECONDS to M1A2Config.RETURN_FIRE_ENABLED,
                                M1A2Config.FORMATION_SPACING to M1A2Config.FORMATION_ENABLED,
                                M1A2Config.FORMATION_HALT_SECONDS to M1A2Config.FORMATION_ENABLED,
                                M1A2Config.IDLE_RESUPPLY_SECONDS to M1A2Config.LIMITED_AMMO,
                                M1A2Config.IDLE_RESUPPLY_CHANCE to M1A2Config.LIMITED_AMMO
                            ),
                            disabledWhenEnabled = mapOf(
                                M1A2Config.AGGRO_RANGE to M1A2Config.GLOBAL_AGGRO
                            ),
                            sliders = M1A2Config.SLIDERS
                        ),
                        ConfigPage(
                            tab("bradley"), BradleyConfig.SPEC, BradleyConfig.ALL,
                            requires = mapOf(
                                BradleyConfig.CANNON_DAMAGE_MULT to BradleyConfig.CANNON_ENABLED,
                                BradleyConfig.CANNON_SHELL to BradleyConfig.CANNON_ENABLED,
                                BradleyConfig.CANNON_ROUNDS to BradleyConfig.CANNON_ENABLED,
                                BradleyConfig.MACHINE_GUN_DAMAGE_MULT to BradleyConfig.MACHINE_GUN_ENABLED,
                                BradleyConfig.MACHINE_GUN_ROUNDS to BradleyConfig.MACHINE_GUN_ENABLED,
                                BradleyConfig.MISSILE_DAMAGE_MULT to BradleyConfig.MISSILE_ENABLED,
                                BradleyConfig.MISSILE_COOLDOWN_SECONDS to BradleyConfig.MISSILE_ENABLED,
                                BradleyConfig.MISSILE_ROUNDS to BradleyConfig.MISSILE_ENABLED,
                                BradleyConfig.SMOKE_CHARGES to BradleyConfig.LIMITED_AMMO,
                                BradleyConfig.RESUPPLY_SECONDS to BradleyConfig.LIMITED_AMMO,
                                BradleyConfig.SMOKE_WHEN_HIT_CHANCE to BradleyConfig.SMOKE_ENABLED,
                                BradleyConfig.SMOKE_AGAINST_MISSILES to BradleyConfig.SMOKE_ENABLED,
                                BradleyConfig.SMOKE_AGAINST_AIR to BradleyConfig.SMOKE_ENABLED,
                                BradleyConfig.FLANK_CHANCE to BradleyConfig.FLANKING_ENABLED,
                                BradleyConfig.RETURN_FIRE_SECONDS to BradleyConfig.RETURN_FIRE_ENABLED,
                                BradleyConfig.FORMATION_SPACING to BradleyConfig.FORMATION_ENABLED,
                                BradleyConfig.FORMATION_HALT_SECONDS to BradleyConfig.FORMATION_ENABLED,
                                BradleyConfig.IDLE_RESUPPLY_SECONDS to BradleyConfig.LIMITED_AMMO,
                                BradleyConfig.IDLE_RESUPPLY_CHANCE to BradleyConfig.LIMITED_AMMO
                            ),
                            disabledWhenEnabled = mapOf(
                                BradleyConfig.AGGRO_RANGE to BradleyConfig.GLOBAL_AGGRO
                            ),
                            sliders = BradleyConfig.SLIDERS
                        ),
                        ConfigPage(
                            tab("ztz99a"), Ztz99aConfig.SPEC, Ztz99aConfig.ALL,
                            requires = mapOf(
                                Ztz99aConfig.CANNON_DAMAGE_MULT to Ztz99aConfig.CANNON_ENABLED,
                                Ztz99aConfig.CANNON_SHELL to Ztz99aConfig.CANNON_ENABLED,
                                Ztz99aConfig.CANNON_ROUNDS to Ztz99aConfig.CANNON_ENABLED,
                                Ztz99aConfig.MACHINE_GUN_DAMAGE_MULT to Ztz99aConfig.MACHINE_GUN_ENABLED,
                                Ztz99aConfig.MACHINE_GUN_ROUNDS to Ztz99aConfig.MACHINE_GUN_ENABLED,
                                Ztz99aConfig.HEAVY_MG_DAMAGE_MULT to Ztz99aConfig.HEAVY_MG_ENABLED,
                                Ztz99aConfig.HEAVY_MG_ROUNDS to Ztz99aConfig.HEAVY_MG_ENABLED,
                                Ztz99aConfig.SMOKE_CHARGES to Ztz99aConfig.LIMITED_AMMO,
                                Ztz99aConfig.RESUPPLY_SECONDS to Ztz99aConfig.LIMITED_AMMO,
                                Ztz99aConfig.SMOKE_WHEN_HIT_CHANCE to Ztz99aConfig.SMOKE_ENABLED,
                                Ztz99aConfig.SMOKE_AGAINST_MISSILES to Ztz99aConfig.SMOKE_ENABLED,
                                Ztz99aConfig.SMOKE_AGAINST_AIR to Ztz99aConfig.SMOKE_ENABLED,
                                Ztz99aConfig.FLANK_CHANCE to Ztz99aConfig.FLANKING_ENABLED,
                                Ztz99aConfig.RETURN_FIRE_SECONDS to Ztz99aConfig.RETURN_FIRE_ENABLED,
                                Ztz99aConfig.FORMATION_SPACING to Ztz99aConfig.FORMATION_ENABLED,
                                Ztz99aConfig.FORMATION_HALT_SECONDS to Ztz99aConfig.FORMATION_ENABLED,
                                Ztz99aConfig.IDLE_RESUPPLY_SECONDS to Ztz99aConfig.LIMITED_AMMO,
                                Ztz99aConfig.IDLE_RESUPPLY_CHANCE to Ztz99aConfig.LIMITED_AMMO
                            ),
                            disabledWhenEnabled = mapOf(
                                Ztz99aConfig.AGGRO_RANGE to Ztz99aConfig.GLOBAL_AGGRO
                            ),
                            sliders = Ztz99aConfig.SLIDERS
                        ),
                        )),
                        ConfigGroup(group("infantry"), listOf(
                        ConfigPage(tab("dummy"), DummyConfig.SPEC, DummyConfig.ALL),
                        ConfigPage(
                            tab("soldier"), SoldierConfig.SPEC, SoldierConfig.ALL,
                            requires = mapOf(
                                SoldierConfig.RELOAD_TICKS to SoldierConfig.UNLIMITED_AMMO,
                                SoldierConfig.RESUPPLY_SECONDS to SoldierConfig.UNLIMITED_AMMO
                            )
                        ),
                        ConfigPage(
                            tab("at_soldier"), AtSoldierConfig.SPEC, AtSoldierConfig.ALL,
                            disabledWhenEnabled = mapOf(
                                AtSoldierConfig.ROCKETS to AtSoldierConfig.UNLIMITED_AMMO,
                                AtSoldierConfig.RESUPPLY_SECONDS to AtSoldierConfig.UNLIMITED_AMMO
                            )
                        ),
                        )),
                        ConfigGroup(group("drones"), listOf(
                        ConfigPage(
                            tab("drone"), DroneConfig.SPEC, DroneConfig.ALL,
                            requires = mapOf(
                                DroneConfig.ALERT_RADIUS to DroneConfig.ALERT_DRONES,
                                DroneConfig.ALERT_SECONDS to DroneConfig.ALERT_DRONES,
                                DroneConfig.JINK_SECONDS to DroneConfig.EVASIVE,
                                DroneConfig.DODGE_CHANCE to DroneConfig.DODGE_PROJECTILES
                            ),
                            sliders = DroneConfig.SLIDERS
                        )
                        )),
                        ConfigGroup(group("player"), listOf(
                        ConfigPage(tab("spotted"), PlayerConfig.SPEC, PlayerConfig.ALL)
                        )),
                        ConfigGroup(group("debug"), listOf(
                        ConfigPage(
                            tab("ai_thinking"), DebugConfig.SPEC, DebugConfig.ALL,
                            requires = (DebugConfig.FILTERS + DebugConfig.HEARTBEAT_SECONDS + DebugConfig.SHOW_ON_SCREEN + DebugConfig.SCREEN_RANGE + DebugConfig.SHOW_VISION)
                                .associateWith { DebugConfig.AI_THINKING } +
                                listOf(DebugConfig.SCREEN_X, DebugConfig.SCREEN_Y, DebugConfig.SCREEN_SCALE, DebugConfig.SCREEN_MAX_WIDTH)
                                    .associateWith { DebugConfig.SHOW_ON_SCREEN },
                            sliders = DebugConfig.SLIDERS
                        )
                        ))
                    ),
                    mirroredToggles(mapOf(
                        BossKind.A10 to BossConfig.TEAMMATES,
                        BossKind.AC130 to Ac130Config.TEAMMATES,
                        BossKind.LAV25 to Lav25Config.TEAMMATES,
                        BossKind.M1A2 to M1A2Config.TEAMMATES,
                        BossKind.BRADLEY to BradleyConfig.TEAMMATES,
                        BossKind.ZTZ99A to Ztz99aConfig.TEAMMATES
                    ))
                )

    /** Tab title of a config page. */
    private fun tab(id: String): Component = Component.translatable("screen.superbenemies.config.tab.$id")

    /** Title of a group of config tabs. */
    private fun group(id: String): Component = Component.translatable("screen.superbenemies.config.group.$id")

    @SubscribeEvent
    fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerEntityRenderer(ModEntities.A10_WARTHOG_BOSS.get(), ::A10Renderer)
        event.registerEntityRenderer(ModEntities.AC130_ANGEL_OF_DEATH.get(), ::Ac130hRenderer)
        event.registerEntityRenderer(ModEntities.LAV25_BOSS.get(), ::Lav25Renderer)
        event.registerEntityRenderer(ModEntities.M1A2_BOSS.get(), ::M1A2Renderer)
        event.registerEntityRenderer(ModEntities.BRADLEY_BOSS.get(), ::BradleyRenderer)
        event.registerEntityRenderer(ModEntities.ZTZ99A_BOSS.get(), ::Ztz99aRenderer)
        event.registerEntityRenderer(ModEntities.PLAYER_DUMMY.get(), ::PlayerDummyRenderer)
        event.registerEntityRenderer(ModEntities.DUMMY_SOLDIER.get()) { context ->
            SoldierRenderer<DummySoldierEntity>(context, SoldierRenderer.DUMMY_SKIN)
        }
        event.registerEntityRenderer(ModEntities.JAVELIN_SOLDIER.get()) { context ->
            SoldierRenderer<AtSoldierEntity>(context, SoldierRenderer.AT_SKIN)
        }
        event.registerEntityRenderer(ModEntities.RPG_SOLDIER.get()) { context ->
            SoldierRenderer<AtSoldierEntity>(context, SoldierRenderer.AT_SKIN)
        }
        event.registerEntityRenderer(ModEntities.DRONE.get(), ::DroneRenderer)
        event.registerEntityRenderer(ModEntities.BOSS_CRATE.get(), ::BossCrateRenderer)
        // The crew is invisible and sits inside the aircraft; it needs a renderer but draws nothing.
        event.registerEntityRenderer(ModEntities.BOSS_PILOT.get()) { context ->
            NoopRenderer<BossCrewEntity>(context)
        }
    }

    /** The soldier renderer caches whether the optional custom skin exists; a resource reload may add or remove it. */
    @SubscribeEvent
    fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(ResourceManagerReloadListener { SoldierRenderer.invalidateSkinCache() })
    }

    /** The vanilla-style eggs use the spawn-egg model, which is tinted per layer. The boss crates and the drone egg are not tinted. */
    @SubscribeEvent
    fun registerItemColors(event: RegisterColorHandlersEvent.Item) {
        event.register(
            ItemColor { _, tintIndex ->
                if (tintIndex == 0) ModItems.DUMMY_EGG_BASE_COLOR else ModItems.DUMMY_EGG_SPOT_COLOR
            },
            ModItems.PLAYER_DUMMY_SPAWN_EGG.get()
        )
        event.register(
            ItemColor { _, tintIndex ->
                if (tintIndex == 0) ModItems.SOLDIER_EGG_BASE_COLOR else ModItems.SOLDIER_EGG_SPOT_COLOR
            },
            ModItems.DUMMY_SOLDIER_SPAWN_EGG.get()
        )
        event.register(
            ItemColor { _, tintIndex ->
                if (tintIndex == 0) ModItems.JAVELIN_EGG_BASE_COLOR else ModItems.JAVELIN_EGG_SPOT_COLOR
            },
            ModItems.JAVELIN_SOLDIER_SPAWN_EGG.get()
        )
        event.register(
            ItemColor { _, tintIndex ->
                if (tintIndex == 0) ModItems.RPG_EGG_BASE_COLOR else ModItems.RPG_EGG_SPOT_COLOR
            },
            ModItems.RPG_SOLDIER_SPAWN_EGG.get()
        )
    }
}
