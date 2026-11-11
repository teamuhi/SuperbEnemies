package com.teamuhi.superbenemies.init

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.entity.boss.a10.A10WarthogBossEntity
import com.teamuhi.superbenemies.entity.boss.ac130.Ac130BossEntity
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.entity.boss.bradley.BradleyBossEntity
import com.teamuhi.superbenemies.entity.boss.lav25.Lav25BossEntity
import com.teamuhi.superbenemies.entity.boss.m1a2.M1A2BossEntity
import com.teamuhi.superbenemies.entity.boss.ztz99a.Ztz99aBossEntity
import com.teamuhi.superbenemies.entity.crate.BossCrateEntity
import com.teamuhi.superbenemies.entity.drone.DroneEntity
import com.teamuhi.superbenemies.entity.dummy.PlayerDummyEntity
import com.teamuhi.superbenemies.entity.soldier.AtSoldierEntity
import com.teamuhi.superbenemies.entity.soldier.AtWeapon
import com.teamuhi.superbenemies.entity.soldier.DummySoldierEntity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MobCategory
import net.minecraftforge.event.entity.EntityAttributeCreationEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.registries.DeferredRegister
import net.minecraftforge.registries.ForgeRegistries
import net.minecraftforge.registries.RegistryObject

@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
object ModEntities {
    val REGISTRY: DeferredRegister<EntityType<*>> =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, SuperbEnemies.MOD_ID)

    /**
     * Same builder settings as Superb Warfare's own vehicles (tracking range 512, update interval 1, fire immune)
     * and the same hitbox as its A-10. The registry id must match the data and asset files
     * `sbw/vehicles/a10_warthog_boss.json`, because Superb Warfare resolves vehicle data by entity type id.
     */
    @JvmField
    val A10_WARTHOG_BOSS: RegistryObject<EntityType<A10WarthogBossEntity>> =
        REGISTRY.register("a10_warthog_boss") {
            EntityType.Builder.of(::A10WarthogBossEntity, MobCategory.MISC)
                .sized(3.375f, 2.625f)
                .setTrackingRange(512)
                .setUpdateInterval(1)
                .fireImmune()
                .build("a10_warthog_boss")
        }

    /**
     * Same builder settings again, with the AC-130's hitbox. The registry id must match the data and asset files
     * `sbw/vehicles/ac130_angel_of_death.json`, because Superb Warfare resolves vehicle data by entity type id.
     */
    @JvmField
    val AC130_ANGEL_OF_DEATH: RegistryObject<EntityType<Ac130BossEntity>> =
        REGISTRY.register("ac130_angel_of_death") {
            EntityType.Builder.of(::Ac130BossEntity, MobCategory.MISC)
                .sized(12.5f, 6.8125f)
                .setTrackingRange(512)
                .setUpdateInterval(1)
                .fireImmune()
                .build("ac130_angel_of_death")
        }

    /**
     * The LAV-25 boss, with the hitbox of Superb Warfare's own LAV-25 and the same builder settings. The registry id must
     * match the data and asset files `sbw/vehicles/lav25_boss.json`, because Superb Warfare resolves vehicle data by
     * entity type id.
     */
    @JvmField
    val LAV25_BOSS: RegistryObject<EntityType<Lav25BossEntity>> =
        REGISTRY.register("lav25_boss") {
            EntityType.Builder.of(::Lav25BossEntity, MobCategory.MISC)
                .sized(2.8f, 2.35f)
                .setTrackingRange(512)
                .setUpdateInterval(1)
                .fireImmune()
                .build("lav25_boss")
        }

    /** The M1A2 boss, with the hitbox of Superb Warfare's own M1A2 (data and asset files `sbw/vehicles/m1a2_boss.json`). */
    @JvmField
    val M1A2_BOSS: RegistryObject<EntityType<M1A2BossEntity>> =
        REGISTRY.register("m1a2_boss") {
            EntityType.Builder.of(::M1A2BossEntity, MobCategory.MISC)
                .sized(4.62f, 2.0f)
                .setTrackingRange(512)
                .setUpdateInterval(1)
                .fireImmune()
                .build("m1a2_boss")
        }

    /** The M2 Bradley boss, with the hitbox of Superb Warfare's own Bradley (data and asset files `sbw/vehicles/bradley_boss.json`). */
    @JvmField
    val BRADLEY_BOSS: RegistryObject<EntityType<BradleyBossEntity>> =
        REGISTRY.register("bradley_boss") {
            EntityType.Builder.of(::BradleyBossEntity, MobCategory.MISC)
                .sized(3.6f, 2.3f)
                .setTrackingRange(512)
                .setUpdateInterval(1)
                .fireImmune()
                .build("bradley_boss")
        }

    /** The ZTZ-99A boss, with the hitbox of Superb Warfare's own ZTZ-99A (data and asset files `sbw/vehicles/ztz99a_boss.json`). */
    @JvmField
    val ZTZ99A_BOSS: RegistryObject<EntityType<Ztz99aBossEntity>> =
        REGISTRY.register("ztz99a_boss") {
            EntityType.Builder.of(::Ztz99aBossEntity, MobCategory.MISC)
                .sized(4.62f, 2.2f)
                .setTrackingRange(512)
                .setUpdateInterval(1)
                .fireImmune()
                .build("ztz99a_boss")
        }

    /**
     * Hidden crew member (pilot, gunner, driver or commander) of any boss. Not saved with the world (the boss re-creates it) and not
     * summonable. The registry id and field name are kept from when it was the A-10's pilot only.
     */
    @JvmField
    val BOSS_PILOT: RegistryObject<EntityType<BossCrewEntity>> =
        REGISTRY.register("boss_pilot") {
            EntityType.Builder.of(::BossCrewEntity, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .setTrackingRange(8)
                .noSave()
                .noSummon()
                .fireImmune()
                .build("boss_pilot")
        }

    /**
     * The player dummy: a player-sized, player-looking test target (see [PlayerDummyEntity]). MISC so it never counts
     * towards or gets removed by the mob caps; tracked from 64 blocks so it is visible at the range the bosses fight at.
     */
    @JvmField
    val PLAYER_DUMMY: RegistryObject<EntityType<PlayerDummyEntity>> =
        REGISTRY.register("player_dummy") {
            EntityType.Builder.of(::PlayerDummyEntity, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .setTrackingRange(64)
                .build("player_dummy")
        }

    /**
     * The Dummy Soldier: a player-shaped soldier carrying an Igla MANPADS that hunts the boss aircraft (see
     * [DummySoldierEntity]). Same category, hitbox and tracking range as the player dummy.
     */
    @JvmField
    val DUMMY_SOLDIER: RegistryObject<EntityType<DummySoldierEntity>> =
        REGISTRY.register("dummy_soldier") {
            EntityType.Builder.of(::DummySoldierEntity, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .setTrackingRange(64)
                .build("dummy_soldier")
        }

    /**
     * The Javelin Soldier: a player-shaped anti-tank soldier that locks and fires a Javelin at the tank bosses (see
     * [AtSoldierEntity]). Same category, hitbox and tracking range as the dummy soldier.
     */
    @JvmField
    val JAVELIN_SOLDIER: RegistryObject<EntityType<AtSoldierEntity>> =
        REGISTRY.register("javelin_soldier") {
            EntityType.Builder.of({ type, level -> AtSoldierEntity(type, level, AtWeapon.JAVELIN) }, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .setTrackingRange(64)
                .build("javelin_soldier")
        }

    /** The RPG Soldier: the same anti-tank soldier with an RPG-7 that works out the arc of its rocket. */
    @JvmField
    val RPG_SOLDIER: RegistryObject<EntityType<AtSoldierEntity>> =
        REGISTRY.register("rpg_soldier") {
            EntityType.Builder.of({ type, level -> AtSoldierEntity(type, level, AtWeapon.RPG) }, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .setTrackingRange(64)
                .build("rpg_soldier")
        }

    /**
     * The drone: small like Superb Warfare's own drone. MISC so it never counts towards or gets removed by the mob caps;
     * tracked from 128 blocks because it flies ahead of the player it shadows.
     */
    /** The crate a boss egg sets down; it plays Superb Warfare's crate opening animation and then releases the boss. */
    @JvmField
    val BOSS_CRATE: RegistryObject<EntityType<BossCrateEntity>> =
        REGISTRY.register("boss_crate") {
            EntityType.Builder.of(::BossCrateEntity, MobCategory.MISC)
                .sized(1.0f, 1.0f)
                .clientTrackingRange(10)
                .updateInterval(20)
                .fireImmune()
                .build("boss_crate")
        }

    @JvmField
    val DRONE: RegistryObject<EntityType<DroneEntity>> =
        REGISTRY.register("drone") {
            EntityType.Builder.of(::DroneEntity, MobCategory.MISC)
                .sized(0.9f, 0.4f)
                .setTrackingRange(128)
                .setUpdateInterval(1)
                .build("drone")
        }

    @SubscribeEvent
    fun registerAttributes(event: EntityAttributeCreationEvent) {
        event.put(BOSS_PILOT.get(), Mob.createMobAttributes().build())
        event.put(PLAYER_DUMMY.get(), PlayerDummyEntity.createAttributes().build())
        event.put(DUMMY_SOLDIER.get(), DummySoldierEntity.createAttributes().build())
        event.put(JAVELIN_SOLDIER.get(), AtSoldierEntity.createAttributes().build())
        event.put(RPG_SOLDIER.get(), AtSoldierEntity.createAttributes().build())
        event.put(DRONE.get(), DroneEntity.createAttributes().build())
    }
}
