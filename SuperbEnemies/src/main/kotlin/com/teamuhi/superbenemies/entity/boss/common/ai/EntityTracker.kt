package com.teamuhi.superbenemies.entity.boss.common.ai

import com.atsuishio.superbwarfare.entity.projectile.ITrackableProjectile
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.IEventBus
import kotlin.math.abs

/**
 * Live list of every server-side entity that satisfies [accepts], kept by the entity join / leave events.
 *
 * The AI used to ask the level for all entities in a huge box every few ticks. The things it cares about are rare
 * (guided missiles, boss aircraft, player dummies), so each kind gets one tracker and the AI reads that instead: the
 * cost is proportional to how many exist and is zero while there are none. The list is only touched on the server thread.
 *
 * [accepts] is evaluated once, on join, so it must depend on the entity's class only, not on mutable state.
 */
class EntityTracker(private val accepts: (Entity) -> Boolean) {
    private val tracked = ArrayList<Entity>()
    private val nearby = ArrayList<Entity>()
    private val inLevel = ArrayList<Entity>()

    fun register(bus: IEventBus) {
        bus.addListener<EntityJoinLevelEvent> { event ->
            val entity = event.entity
            if (!event.level.isClientSide && accepts(entity) && !tracked.contains(entity)) tracked.add(entity)
        }
        bus.addListener<EntityLeaveLevelEvent> { event ->
            if (!event.level.isClientSide && accepts(event.entity)) tracked.remove(event.entity)
        }
        bus.addListener<ServerStoppedEvent> {
            tracked.clear()
            nearby.clear()
            inLevel.clear()
        }
    }

    /**
     * Live tracked entities in [level] within [radius] blocks of [center] along every axis (a cube, like an AABB inflate).
     * The returned list is reused by the next call: consume it immediately and do not keep it.
     */
    fun near(level: Level, center: Vec3, radius: Double): List<Entity> {
        nearby.clear()
        val iterator = tracked.iterator()
        while (iterator.hasNext()) {
            val entity = iterator.next()
            if (entity.isRemoved) {
                iterator.remove() // missed leave event (cancelled join, other mod): do not leak it
                continue
            }
            if (entity.level() !== level || !entity.isAlive) continue
            if (abs(entity.x - center.x) > radius || abs(entity.y - center.y) > radius || abs(entity.z - center.z) > radius) continue
            nearby.add(entity)
        }
        return nearby
    }

    /**
     * Every live tracked entity in [level]. Reused list like [near] (a separate buffer, so a [near] result is not
     * clobbered by this): consume it immediately and do not keep it.
     */
    fun all(level: Level): List<Entity> {
        inLevel.clear()
        val iterator = tracked.iterator()
        while (iterator.hasNext()) {
            val entity = iterator.next()
            if (entity.isRemoved) {
                iterator.remove()
                continue
            }
            if (entity.level() === level && entity.isAlive) inLevel.add(entity)
        }
        return inLevel
    }
}

/** Every guided projectile (anything implementing SBW's [ITrackableProjectile]); read by [ThreatMonitor]. */
val TrackedProjectiles = EntityTracker { it is ITrackableProjectile }

/** Every boss vehicle (planes and ground bosses). The player dummy and the soldier cast to BossAircraft, so they only react to planes. */
val TrackedBosses = EntityTracker { it is BossVehicle }
