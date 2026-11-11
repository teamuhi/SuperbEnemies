package com.teamuhi.superbenemies.entity.boss.common

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.entity.boss.common.air.BossAircraft
import com.teamuhi.superbenemies.entity.boss.common.ground.GroundProfile
import com.teamuhi.superbenemies.entity.boss.common.ground.GroundTerrain
import com.teamuhi.superbenemies.entity.boss.common.ground.SpawnVerdict
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.registries.ForgeRegistries
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A boss that has withdrawn to rearm and is waiting to come back (see [BossRespawns]). Stored in the world so it
 * survives a restart; [dueTick] is a `level.gameTime`.
 */
class PendingBoss(
    val typeId: ResourceLocation,
    /** Health as a fraction of the maximum when it left, so the replacement keeps it whatever the config says now. */
    val healthFraction: Float,
    /** Where the fight was: the replacement appears around here (or around the nearest player if that is unloaded). */
    val anchor: Vec3,
    val home: Vec3,
    val dueTick: Long,
    /** Lang key of the "begins hunting" notice shown when it is back. */
    val huntingKey: String,
    val clearanceHalfWidth: Double,
    /** True for a boss that drives on the ground: the replacement is put on the surface, not launched into the air. */
    val ground: Boolean = false
) {
    fun save(): CompoundTag = CompoundTag().apply {
        putString(TAG_TYPE, typeId.toString())
        putFloat(TAG_HEALTH, healthFraction)
        putDouble(TAG_ANCHOR_X, anchor.x)
        putDouble(TAG_ANCHOR_Y, anchor.y)
        putDouble(TAG_ANCHOR_Z, anchor.z)
        putDouble(TAG_HOME_X, home.x)
        putDouble(TAG_HOME_Y, home.y)
        putDouble(TAG_HOME_Z, home.z)
        putLong(TAG_DUE, dueTick)
        putString(TAG_HUNTING, huntingKey)
        putDouble(TAG_CLEARANCE, clearanceHalfWidth)
        putBoolean(TAG_GROUND, ground)
    }

    companion object {
        private const val TAG_TYPE = "Type"
        private const val TAG_HEALTH = "Health"
        private const val TAG_ANCHOR_X = "AnchorX"
        private const val TAG_ANCHOR_Y = "AnchorY"
        private const val TAG_ANCHOR_Z = "AnchorZ"
        private const val TAG_HOME_X = "HomeX"
        private const val TAG_HOME_Y = "HomeY"
        private const val TAG_HOME_Z = "HomeZ"
        private const val TAG_DUE = "Due"
        private const val TAG_HUNTING = "Hunting"
        private const val TAG_CLEARANCE = "Clearance"
        private const val TAG_GROUND = "Ground"

        fun load(tag: CompoundTag): PendingBoss? {
            val typeId = ResourceLocation.tryParse(tag.getString(TAG_TYPE)) ?: return null
            return PendingBoss(
                typeId,
                tag.getFloat(TAG_HEALTH),
                Vec3(tag.getDouble(TAG_ANCHOR_X), tag.getDouble(TAG_ANCHOR_Y), tag.getDouble(TAG_ANCHOR_Z)),
                Vec3(tag.getDouble(TAG_HOME_X), tag.getDouble(TAG_HOME_Y), tag.getDouble(TAG_HOME_Z)),
                tag.getLong(TAG_DUE),
                tag.getString(TAG_HUNTING),
                tag.getDouble(TAG_CLEARANCE),
                // Saves from before the ground bosses have no such key: those entries are aircraft.
                tag.contains(TAG_GROUND) && tag.getBoolean(TAG_GROUND)
            )
        }
    }
}

/** The withdrawn bosses of one dimension, saved with it. */
class BossRespawnData : SavedData() {
    val pending = ArrayList<PendingBoss>()

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for (boss in pending) list.add(boss.save())
        tag.put(TAG_PENDING, list)
        return tag
    }

    companion object {
        const val NAME = "superbenemies_boss_respawns"
        private const val TAG_PENDING = "Pending"

        fun load(tag: CompoundTag): BossRespawnData {
            val data = BossRespawnData()
            val list = tag.getList(TAG_PENDING, Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) PendingBoss.load(list.getCompound(i))?.let { data.pending.add(it) }
            return data
        }
    }
}

/**
 * A boss that runs out of ammunition does not loiter somewhere far away eating chunk loading and entity ticks: once it
 * is out of sight it is removed ([withdraw]) and a replacement with the same health and a full load appears around the
 * fight after `resupplySeconds` ([tick]). A plane comes back in the air; a ground boss ([BossVehicle.groundBoss]) on the
 * surface, on a spot its [GroundProfile] can stand on.
 *
 * The replacement waits (the entry stays saved) while nobody is online, the difficulty is peaceful, or no free spot
 * with loaded chunks is found yet.
 */
object BossRespawns {

    fun register(bus: IEventBus) {
        bus.addListener<TickEvent.LevelTickEvent> { event ->
            val level = event.level
            if (event.phase == TickEvent.Phase.END && level is ServerLevel && level.gameTime % CHECK_INTERVAL == 0L) {
                tick(level)
            }
        }
    }

    private fun data(level: ServerLevel): BossRespawnData =
        level.dataStorage.computeIfAbsent(BossRespawnData::load, ::BossRespawnData, BossRespawnData.NAME)

    /** True while a (non-spectator) player is within [range] blocks of [boss]: it is still in sight, so it keeps flying. */
    fun playerWithin(boss: Entity, range: Double): Boolean {
        val level = boss.level() as? ServerLevel ?: return false
        val rangeSqr = range * range
        return level.players().any { !it.isSpectator && it.distanceToSqr(boss) <= rangeSqr }
    }

    /**
     * Removes [boss] and queues its replacement [delayTicks] from now. The replacement keeps the health fraction (never
     * below the cripple threshold, so it is not born crashing). [anchor] is where the fight was, [home] the boss's
     * home point.
     */
    fun <T> withdraw(
        boss: T,
        huntingKey: String,
        anchor: Vec3,
        home: Vec3,
        delayTicks: Long,
        clearanceHalfWidth: Double
    ) where T : VehicleEntity, T : BossVehicle {
        val level = boss.level() as? ServerLevel ?: return
        val typeId = ForgeRegistries.ENTITY_TYPES.getKey(boss.type) ?: return
        val fraction = boss.healthFraction().coerceIn(MIN_HEALTH_FRACTION, 1f)
        val data = data(level)
        data.pending.add(
            PendingBoss(
                typeId, fraction, anchor, home, level.gameTime + delayTicks, huntingKey, clearanceHalfWidth, boss.groundBoss
            )
        )
        data.setDirty()
        boss.thought.event("respawns.withdraw") {
            "withdrawn to rearm at ${(fraction * 100).toInt()}% health: replacement due in ${delayTicks / 20}s around ${anchor.x.toInt()}, ${anchor.z.toInt()}"
        }
        boss.discard()
    }

    private fun tick(level: ServerLevel) {
        val data = data(level)
        if (data.pending.isEmpty() || level.difficulty == Difficulty.PEACEFUL) return
        val players = level.players().filter { !it.isSpectator }
        if (players.isEmpty()) return

        val now = level.gameTime
        val iterator = data.pending.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            if (now < pending.dueTick) continue
            if (respawn(level, pending, players.map { it.position() })) {
                iterator.remove()
                data.setDirty()
            }
        }
    }

    /** @return true when the entry is finished (spawned, or it can never spawn); false to try again later. */
    private fun respawn(level: ServerLevel, pending: PendingBoss, players: List<Vec3>): Boolean {
        // The entity type registry falls back to a pig for unknown ids, so ask first (a removed mod's boss is dropped).
        if (!ForgeRegistries.ENTITY_TYPES.containsKey(pending.typeId)) return true
        val type = ForgeRegistries.ENTITY_TYPES.getValue(pending.typeId) ?: return true
        val boss = type.create(level) ?: return true
        // The entry and the entity must agree on the kind: a type that changed under an old save is dropped.
        if (boss !is BossVehicle || boss.groundBoss != pending.ground || (!pending.ground && boss !is BossAircraft)) {
            boss.discard()
            return true
        }

        val nearest = players.minBy { it.distanceToSqr(pending.anchor) }
        val spot = (if (pending.ground) findGroundSpot(level, type.height.toDouble(), type.width.toDouble(), boss.groundProfile(), pending, nearest)
        else findSpot(level, type.height.toDouble(), pending, nearest)) ?: run {
            boss.discard()
            return false
        }
        val yaw = (Mth.RAD_TO_DEG * atan2(-(pending.anchor.x - spot.x), pending.anchor.z - spot.z)).toFloat()
        boss.moveTo(spot.x, spot.y, spot.z, yaw, 0f)
        boss.restoreHealthFraction(pending.healthFraction)
        if (boss is BossAircraft) boss.prepareLaunch(pending.home) else boss.prepareGround(pending.home)
        boss.rememberFight(pending.anchor)
        if (!level.addFreshEntity(boss)) return false

        boss.thought.event("respawns.respawn") {
            "replacement spawned at ${spot.x.toInt()}, ${spot.y.toInt()}, ${spot.z.toInt()} with ${(pending.healthFraction * 100).toInt()}% health, hunting around ${pending.anchor.x.toInt()}, ${pending.anchor.z.toInt()}"
        }
        boss.announce(pending.huntingKey, ResupplyNotices.HUNTING_STYLE, pending.anchor)
        return true
    }

    /**
     * A free spot on a ring around the fight (or, when that is not loaded, around the nearest player), in loaded
     * chunks, inside the world border, high enough above the terrain and clear of blocks.
     */
    private fun findSpot(level: ServerLevel, height: Double, pending: PendingBoss, nearestPlayer: Vec3): Vec3? {
        val half = pending.clearanceHalfWidth
        val boxHeight = max(height + 2.0, MIN_CLEARANCE_HEIGHT)
        for (center in listOf(pending.anchor, nearestPlayer)) {
            repeat(ATTEMPTS) {
                val angle = level.random.nextDouble() * Math.PI * 2.0
                val radius = RING_MIN + level.random.nextDouble() * (RING_MAX - RING_MIN)
                val x = center.x + cos(angle) * radius
                val z = center.z + sin(angle) * radius
                val column = BlockPos.containing(x, center.y, z)
                if (!level.hasChunkAt(column) || !level.worldBorder.isWithinBounds(x, z, BORDER_MARGIN)) return@repeat

                val terrainY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.x, column.z)
                val spawnY = max(terrainY.toDouble(), center.y) + SPAWN_ALTITUDE
                if (spawnY + HEADROOM > level.maxBuildHeight) return@repeat
                val box = AABB(x - half, spawnY - 2.0, z - half, x + half, spawnY - 2.0 + boxHeight, z + half)
                if (level.noCollision(box)) return Vec3(x, spawnY, z)
            }
        }
        return null
    }

    /**
     * The ground counterpart of [findSpot]: a spot on a ring around the fight (or the nearest player), in loaded chunks and
     * inside the world border, on dry (or, for a floating boss, watery) ground the [profile] can drive on, with room for
     * the hull ([GroundTerrain.checkSpawn]). The height is the surface there; there is no altitude.
     */
    private fun findGroundSpot(
        level: ServerLevel, height: Double, width: Double, profile: GroundProfile, pending: PendingBoss, nearestPlayer: Vec3
    ): Vec3? {
        if (level.dimensionType().hasCeiling()) return null
        val half = max(pending.clearanceHalfWidth, width / 2.0)
        for (center in listOf(pending.anchor, nearestPlayer)) {
            repeat(ATTEMPTS * 2) {
                val angle = level.random.nextDouble() * Math.PI * 2.0
                val radius = GROUND_RING_MIN + level.random.nextDouble() * (GROUND_RING_MAX - GROUND_RING_MIN)
                val x = center.x + cos(angle) * radius
                val z = center.z + sin(angle) * radius
                if (!level.worldBorder.isWithinBounds(x, z, GROUND_BORDER_MARGIN)) return@repeat

                val bx = Mth.floor(x)
                val bz = Mth.floor(z)
                val cell = GroundTerrain.sample(level, GroundTerrain.cellOf(x, profile), GroundTerrain.cellOf(z, profile), profile)
                if (!GroundTerrain.passable(cell, profile) || cell.foliage) return@repeat
                val surfaceY = (GroundTerrain.spawnY(level, bx, bz, profile) ?: return@repeat) + GROUND_LIFT
                val verdict = GroundTerrain.checkSpawn(level, x, surfaceY, z, half, height + GROUND_HEADROOM, profile)
                if (verdict == SpawnVerdict.OK) return Vec3(x, surfaceY, z)
            }
        }
        return null
    }

    private const val CHECK_INTERVAL = 20L
    private const val MIN_HEALTH_FRACTION = 0.11f
    private const val ATTEMPTS = 8
    private const val RING_MIN = 120.0
    private const val RING_MAX = 180.0
    private const val SPAWN_ALTITUDE = 60.0
    private const val HEADROOM = 40.0
    private const val MIN_CLEARANCE_HEIGHT = 7.0
    private const val BORDER_MARGIN = 64.0
    private const val GROUND_RING_MIN = 80.0
    private const val GROUND_RING_MAX = 140.0
    private const val GROUND_BORDER_MARGIN = 16.0
    private const val GROUND_LIFT = 0.1
    private const val GROUND_HEADROOM = 0.5
}
