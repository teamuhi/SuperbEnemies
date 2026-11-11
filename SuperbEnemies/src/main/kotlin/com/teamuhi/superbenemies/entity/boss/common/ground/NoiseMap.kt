package com.teamuhi.superbenemies.entity.boss.common.ground

import com.atsuishio.superbwarfare.api.event.ShootEvent
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.config.TargetCategory
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedBosses
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedVehicles
import com.teamuhi.superbenemies.entity.boss.common.ai.categoryOf
import com.teamuhi.superbenemies.entity.boss.common.ai.isAirType
import com.teamuhi.superbenemies.entity.boss.common.ai.isArmourType
import com.teamuhi.superbenemies.entity.boss.common.ai.isTeammate
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.VanillaGameEvent
import net.minecraftforge.event.level.ExplosionEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.IEventBus
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One thing a ground boss can hear.
 *
 * @param pos where it happened
 * @param radius nominal loudness: the distance in blocks from which a boss with the reference hearing range still hears it
 * @param tick the `gameTime` it was made at
 * @param sourceId entity id of what made it (-1 when unknown)
 * @param rootId entity id of the vehicle that source rides, or the source itself (-1 when unknown); a boss ignores its
 *        own noise through either id, which also covers its hidden crew
 * @param categoryA target category of the source (null: not a target kind)
 * @param categoryB target category of the vehicle's rider, or of the source's vehicle (null: none)
 * @param anonymous true for a noise nobody can be blamed for (an explosion or a shot with no known source): it is heard
 *        whatever the target weights say. A known source is heard only when one of its categories has a weight above 0.
 * @param kind what made it; for [NoiseKind.CANNON] and [NoiseKind.MACHINE_GUN] [radius] is the real audible distance in blocks
 *        (Superb Warfare's sound radius of the weapon), scaled by the listener's hearing percent for that kind instead of its `hearingRange`
 */
class Noise(
    val pos: Vec3,
    val radius: Double,
    val tick: Long,
    val sourceId: Int,
    val rootId: Int,
    val categoryA: TargetCategory?,
    val categoryB: TargetCategory?,
    val anonymous: Boolean,
    val kind: NoiseKind = NoiseKind.OTHER
)

/** The noises a tank can pinpoint: the fire of a vehicle's guns. Everything else is [OTHER] and only makes the tank investigate. */
enum class NoiseKind {
    /** A vehicle gun that fires shells, missiles or rockets. */
    CANNON,

    /** A vehicle gun that fires bullets. */
    MACHINE_GUN,
    OTHER
}

/**
 * Hearing for the ground bosses: a short-lived record of the noise in each dimension that the bosses poll, so events
 * never loop over bosses and a boss that is not listening costs nothing.
 *
 * Sources (all server side, registered once by [register]):
 *  - [ShootEvent.Post]: hand guns [GUNSHOT_RADIUS]. A gun fired from a vehicle is as loud as Superb Warfare makes it: the
 *    audible distance of the weapon is its `SoundRadius` times [SBW_BLOCKS_PER_SOUND_RADIUS] (the same radius its own
 *    sounds are sent to players with), [VEHICLE_GUN_RADIUS] for a weapon without one. Those noises are [NoiseKind.CANNON]
 *    (the weapon fires anything but plain bullets) or [NoiseKind.MACHINE_GUN], which a tank pinpoints (see [pinpoint]).
 *    (The SBW build this mod compiles against has no silencer API, so suppressed guns are as loud.)
 *  - [ExplosionEvent.Detonate] (SBW's `CustomExplosion` fires it too): [EXPLOSION_RADIUS].
 *  - [VanillaGameEvent]: footsteps (vanilla emits none for sneaking players), block break / place, projectile landing and
 *    damage, [GAME_EVENT_RADIUS] each ([STEP_RADIUS] for steps). Events without a causing entity are dropped.
 *  - Moving vehicles, polled every [VEHICLE_POLL_INTERVAL] ticks (SBW vehicles emit no engine game event): armour
 *    [TANK_RADIUS], aircraft [AIR_RADIUS], other vehicles [OTHER_VEHICLE_RADIUS].
 *
 * Nothing is recorded in a dimension without a living ground boss. Repeated noise from one source is merged into its
 * previous entry, so a stream of footsteps cannot push every other noise out of the buffer. Entries expire after
 * [MAX_AGE_TICKS].
 *
 * A boss asks [heard]. Server thread only.
 */
object NoiseMap {

    /** Hearing range (blocks) at which a [Noise.radius] counts at face value; a boss's `hearingRange` scales it. */
    const val REFERENCE_HEARING_RANGE = 128.0

    const val GUNSHOT_RADIUS = 96.0
    const val VEHICLE_GUN_RADIUS = 160.0

    /** Superb Warfare plays a gun's far sound to players within 16 blocks per point of the weapon's `SoundRadius` (`SoundTool.playDistantSound`). */
    const val SBW_BLOCKS_PER_SOUND_RADIUS = 16.0

    /** The projectile of a plain bullet; a vehicle gun that fires anything else is a cannon. */
    private const val BULLET_PROJECTILE = "superbwarfare:projectile"

    /** The most a heard shooter is placed off, in blocks, however far away it is. */
    private const val MAX_PINPOINT_ERROR = 40.0
    const val EXPLOSION_RADIUS = 128.0
    const val GAME_EVENT_RADIUS = 24.0
    const val STEP_RADIUS = 16.0
    const val TANK_RADIUS = 64.0
    const val AIR_RADIUS = 160.0
    const val OTHER_VEHICLE_RADIUS = 48.0

    /** Ticks an entry stays audible. */
    const val MAX_AGE_TICKS = 100L

    private const val CAPACITY = 96
    private const val MERGE_WINDOW = 8
    private const val MERGE_TICKS = 20L
    private const val VEHICLE_POLL_INTERVAL = 10L
    private const val MOVING_SPEED_SQR = 0.15 * 0.15

    private class Ring {
        val items = arrayOfNulls<Noise>(CAPACITY)
        var next = 0
    }

    private val rings = HashMap<ResourceKey<Level>, Ring>()

    fun register(bus: IEventBus) {
        bus.addListener<ShootEvent.Post> { event -> onShot(event) }
        bus.addListener<ExplosionEvent.Detonate> { event ->
            val level = event.level as? ServerLevel ?: return@addListener
            val explosion = event.explosion
            val source = explosion.indirectSourceEntity ?: explosion.directSourceEntity
            record(level, explosion.position, EXPLOSION_RADIUS, source, anonymousIfUnknown = true)
        }
        bus.addListener<VanillaGameEvent> { event -> onGameEvent(event) }
        bus.addListener<TickEvent.LevelTickEvent> { event ->
            val level = event.level
            if (event.phase == TickEvent.Phase.END && level is ServerLevel && level.gameTime % VEHICLE_POLL_INTERVAL == 0L) {
                pollVehicles(level)
            }
        }
        bus.addListener<ServerStoppedEvent> { rings.clear() }
    }

    // ================================================================================================== query

    /**
     * The loudest fresh noise [boss] hears, or null. A noise counts when it is at most [MAX_AGE_TICKS] old (and not older
     * than [sinceTick], a `gameTime`, so a boss that already investigated one can ignore it afterwards), did not come
     * from the boss itself, its crew or a teammate boss, and its source is something the boss attacks: a category with a weight above 0
     * in the weights of [settings], or an anonymous noise. The distance it carries is its radius scaled by
     * `hearingRange` / [REFERENCE_HEARING_RANGE]; for the fire of a vehicle's guns it is the weapon's audible distance times
     * `cannonHearingPercent` or `machineGunHearingPercent` (0 = not heard at all). The winner has the largest margin (carried
     * distance minus actual distance), the newer one on a tie.
     */
    fun heard(boss: Entity, settings: TankSettings, sinceTick: Long = Long.MIN_VALUE): Noise? {
        val level = boss.level()
        val ring = rings[level.dimension()] ?: return null
        val now = level.gameTime
        val weights = settings.weights
        val scale = settings.hearingRange.get() / REFERENCE_HEARING_RANGE
        val cannonScale = settings.cannonHearingPercent.get() / 100.0
        val machineGunScale = settings.machineGunHearingPercent.get() / 100.0
        val here = boss.position()
        var best: Noise? = null
        var bestMargin = 0.0
        for (noise in ring.items) {
            if (noise == null) continue
            if (now - noise.tick > MAX_AGE_TICKS || noise.tick < sinceTick || noise.tick > now) continue
            if (noise.sourceId == boss.id || noise.rootId == boss.id) continue
            if (noise.rootId != -1 && isTeammateNoise(level, boss, noise)) continue
            if (!noise.anonymous && weights.of(noise.categoryA) <= 0 && weights.of(noise.categoryB) <= 0) continue
            val carried = noise.radius * when (noise.kind) {
                NoiseKind.CANNON -> cannonScale
                NoiseKind.MACHINE_GUN -> machineGunScale
                NoiseKind.OTHER -> scale
            }
            if (carried <= 0.0) continue
            val dx = noise.pos.x - here.x
            val dy = noise.pos.y - here.y
            val dz = noise.pos.z - here.z
            val margin = carried - sqrt(dx * dx + dy * dy + dz * dz)
            if (margin <= 0.0) continue
            if (best == null || margin > bestMargin || (margin == bestMargin && noise.tick > best.tick)) {
                best = noise
                bestMargin = margin
            }
        }
        return best
    }

    /** True when [noise] came from a boss (or its crew) that [boss] lists as a teammate: not worth investigating. */
    private fun isTeammateNoise(level: Level, boss: Entity, noise: Noise): Boolean {
        val source = level.getEntity(noise.rootId) ?: return false
        return isTeammate(boss, source)
    }

    // ================================================================================================== sources

    private fun onShot(event: ShootEvent.Post) {
        val parameters = event.parameters
        val shooter = event.shooter
        val vehicle = parameters.ammoSupplier as? VehicleEntity ?: shooter?.rootVehicle as? VehicleEntity
        if (vehicle == null) {
            record(event.level, parameters.shootPosition, GUNSHOT_RADIUS, shooter, anonymousIfUnknown = true)
            return
        }
        val data = event.data
        val audible = data.get(GunProp.SOUND_RADIUS) * SBW_BLOCKS_PER_SOUND_RADIUS
        val kind = if (data.get(GunProp.PROJECTILE).itemId == BULLET_PROJECTILE) NoiseKind.MACHINE_GUN else NoiseKind.CANNON
        record(
            event.level, parameters.shootPosition, if (audible > 0.0) audible else VEHICLE_GUN_RADIUS, shooter ?: vehicle,
            anonymousIfUnknown = true, kind = kind
        )
    }

    /**
     * Where a tank places the shooter of the [noise] it heard: the real spot of the shooter (when still around, else where it
     * fired from) moved by a random horizontal error of [scatterPercent] percent of the [distance] to it, at most [MAX_PINPOINT_ERROR]
     * blocks. 0 percent is exact.
     */
    fun pinpoint(level: ServerLevel, noise: Noise, distance: Double, scatterPercent: Int, random: RandomSource): Vec3 {
        val origin = level.getEntity(noise.rootId)?.takeIf { it.isAlive }?.position() ?: noise.pos
        val error = (distance * scatterPercent / 100.0).coerceIn(0.0, MAX_PINPOINT_ERROR)
        if (error <= 0.0) return origin
        val angle = random.nextDouble() * Math.PI * 2.0
        val radius = error * sqrt(random.nextDouble())
        return Vec3(origin.x + cos(angle) * radius, origin.y, origin.z + sin(angle) * radius)
    }

    private fun onGameEvent(event: VanillaGameEvent) {
        val level = event.level as? ServerLevel ?: return
        val cause = event.cause ?: return
        val radius = when (event.vanillaEvent) {
            GameEvent.STEP -> STEP_RADIUS
            GameEvent.BLOCK_DESTROY, GameEvent.BLOCK_PLACE, GameEvent.PROJECTILE_LAND, GameEvent.ENTITY_DAMAGE -> GAME_EVENT_RADIUS
            else -> return
        }
        record(level, event.eventPosition, radius, cause, anonymousIfUnknown = false)
    }

    private fun pollVehicles(level: ServerLevel) {
        if (!hasListener(level)) return
        val now = level.gameTime
        for (entity in TrackedVehicles.all(level)) {
            val vehicle = entity as? VehicleEntity ?: continue
            if (vehicle.isWreck || vehicle.passengers.isEmpty()) continue
            if (vehicle.deltaMovement.horizontalDistanceSqr() < MOVING_SPEED_SQR) continue
            val type = vehicle.vehicleType
            val radius = when {
                isArmourType(type) -> TANK_RADIUS
                isAirType(type) -> AIR_RADIUS
                else -> OTHER_VEHICLE_RADIUS
            }
            val rider = vehicle.passengers.firstOrNull { it !is BossCrewEntity }
            add(
                level,
                Noise(vehicle.position(), radius, now, vehicle.id, vehicle.id, categoryOf(vehicle), rider?.let { categoryOf(it) }, false)
            )
        }
    }

    /** True when some ground boss lives in [level]: otherwise there is nobody to hear and nothing is recorded. */
    private fun hasListener(level: ServerLevel): Boolean {
        for (entity in TrackedBosses.all(level)) if (entity is BossVehicle && entity.groundBoss) return true
        return false
    }

    /** Records a noise of nominal [radius] at [pos] made by [source] (which may be null: see [anonymousIfUnknown]). */
    private fun record(
        level: ServerLevel, pos: Vec3, radius: Double, source: Entity?, anonymousIfUnknown: Boolean, kind: NoiseKind = NoiseKind.OTHER
    ) {
        if (!hasListener(level)) return
        if (source == null) {
            if (anonymousIfUnknown) add(level, Noise(pos, radius, level.gameTime, -1, -1, null, null, true, kind))
            return
        }
        val root = source.rootVehicle
        val own = if (source is BossCrewEntity) null else categoryOf(source)
        val other = if (root !== source) categoryOf(root) else null
        add(level, Noise(pos, radius, level.gameTime, source.id, root.id, own, other, false, kind))
    }

    /** Puts [noise] in its dimension's ring; it replaces a recent entry of the same source instead of adding one. */
    private fun add(level: ServerLevel, noise: Noise) {
        val ring = rings.getOrPut(level.dimension()) { Ring() }
        if (noise.sourceId != -1) {
            for (back in 1..MERGE_WINDOW) {
                val index = Math.floorMod(ring.next - back, CAPACITY)
                val old = ring.items[index] ?: break
                if (old.sourceId == noise.sourceId && noise.tick - old.tick <= MERGE_TICKS &&
                    max(old.radius, noise.radius) == noise.radius
                ) {
                    ring.items[index] = noise
                    return
                }
            }
        }
        ring.items[ring.next] = noise
        ring.next = (ring.next + 1) % CAPACITY
    }
}
