package com.teamuhi.superbenemies.entity.boss.ac130.ai

import com.teamuhi.superbenemies.config.Ac130Config
import com.teamuhi.superbenemies.entity.boss.ac130.Ac130BossEntity
import com.teamuhi.superbenemies.entity.boss.ac130.Ac130Gun
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.Vec3
import java.util.EnumMap
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * The AC-130's three side guns: aiming, firing cadence and ammunition.
 *
 * **Aiming.** Superb Warfare's seat-based `vehicleShoot` fires along the gunner's own look direction (the seats use
 * `Directions: Passenger`) and only clamps a *player's* mouse input to the seat window, so for a mob gunner this class
 * does both: it works out where the gun must point (muzzle-to-target with ballistic lead), converts that to a yaw and
 * depression relative to the seat frame, clamps it to [Ac130Gun]'s window (the same maths as SBW's `clampRotation`) and
 * writes the result onto the crew member. The seat frame is `VehicleEntity.getTransformDirection`, which already
 * contains the plane's yaw, pitch, roll and the seat's side-facing orientation.
 *
 * The *wanted world direction* is stored and re-clamped against the seat frame every time [applyAim] runs. That
 * matters because the aircraft moves between `brain.tick()` (before `baseTick`) and the deferred shot SBW queues:
 * `positionRider` also rotates the gunner with the vehicle, so [applyAim] is called again after `baseTick`.
 *
 * **Firing** is a barrage, not a sniper: there is no line-of-sight test (shells go through roofs and trees the way
 * they go through air, blowing them apart with `blockDamage`). A gun fires when the clamped aim is within [FIRE_ANGLE]
 * degrees of the wanted direction, or, when the target has slipped out of the seat window, as long as the clamped
 * line still lands within [barrageRadius] blocks of it (suppressive fire while the orbit catches up). It also needs
 * the gun enabled in the config, ammo left, `GunData.canShoot`, a range below [MAX_RANGE] and its turn (see
 * [allowedGuns]). The weapon name overloads of `vehicleShoot` are never used: they ignore "Passenger" and would
 * fire along the nose.
 *
 * All state is per instance.
 */
class Ac130Gunnery(private val boss: Ac130BossEntity) {

    private class GunState {
        /** True while the gun has a live aim point; false = idle at the neutral aim. */
        var hasAim = false
        var wantYaw = 0f
        var wantPitch = 0f

        /** Seat-relative angles of the wanted direction before clamping, and the seat frame's own pitch (diagnostics). */
        var relPitch = 0.0
        var seatPitch = 0.0
        var frameValid = false

        /** Angle between the wanted direction and what the clamped gun can actually do. */
        var errorDeg = Double.MAX_VALUE

        /** World yaw / pitch the gun actually points at after the clamp (MC convention). */
        var clampedYaw = 0f
        var clampedPitch = 0f

        // cadence (absolute boss.tickCount values, 0 = none)
        var burstEnd = 0
        var pauseUntil = 0
        var nextShot = 0
        var lastCall = Int.MIN_VALUE / 2
    }

    private val states = EnumMap<Ac130Gun, GunState>(Ac130Gun::class.java)

    /** Console channel per gun, built once so the per-tick calls do not allocate. */
    private val fireChannels = Ac130Gun.entries.associateWith { "gunnery.fire.${it.name}" }
    private val ammoChannels = Ac130Gun.entries.associateWith { "gunnery.ammo.${it.name}" }

    /** Rounds left per weapon while `LIMITED_AMMO` is on. A missing entry means a full load. Keyed by weapon name. */
    private val ammo = HashMap<String, Int>()

    private var scatter: Vec3 = Vec3.ZERO
    private var nextScatterTick = 0

    // turn rotation for guns that may not fire simultaneously (absolute boss.tickCount values)
    private var turnIndex = 0
    private var turnStart = 0
    private var turnLastShot = 0
    private var turnShots = 0
    private var turnKey: List<Set<Ac130Gun>> = emptyList()

    private fun stateOf(gun: Ac130Gun): GunState = states.getOrPut(gun) { GunState() }

    // ================================================================================================== config / ammo

    fun enabled(gun: Ac130Gun): Boolean = when (gun) {
        Ac130Gun.M61 -> Ac130Config.CANNON_ENABLED.get()
        Ac130Gun.BOFORS -> Ac130Config.BOFORS_ENABLED.get()
        Ac130Gun.M102 -> Ac130Config.HOWITZER_ENABLED.get()
    }

    fun anyEnabled(): Boolean = Ac130Gun.entries.any { enabled(it) }

    /** Whether [gun] may fire together with the other simultaneous guns (otherwise it takes its own turn). */
    private fun simultaneous(gun: Ac130Gun): Boolean = when (gun) {
        Ac130Gun.M61 -> Ac130Config.CANNON_SIMULTANEOUS.get()
        Ac130Gun.BOFORS -> Ac130Config.BOFORS_SIMULTANEOUS.get()
        Ac130Gun.M102 -> Ac130Config.HOWITZER_SIMULTANEOUS.get()
    }

    /** Munitions left for [gun]; effectively unlimited unless the config enables a finite load. */
    fun ammoLeft(gun: Ac130Gun): Int {
        if (!Ac130Config.LIMITED_AMMO.get()) return Int.MAX_VALUE
        return ammo.getOrPut(gun.weaponName) { fullLoad(gun) }
    }

    fun hasAmmo(gun: Ac130Gun): Boolean = ammoLeft(gun) > 0

    /** One-line ammunition summary for the heartbeat: rounds left per gun and the flares. */
    fun ammoSummary(): String {
        val flares = boss.decoyCount
        if (!Ac130Config.LIMITED_AMMO.get()) return "unlimited, flares $flares"
        return Ac130Gun.entries.joinToString(" ") { "${it.name.lowercase()} ${ammoLeft(it)}" } + " flares $flares"
    }

    /**
     * True when finite ammo is on and any gun is below its full load or the flares are below the configured count:
     * rearming would gain something (see the idle resupply in the brain). Never true for unlimited ammo.
     */
    fun anySpent(): Boolean {
        if (!Ac130Config.LIMITED_AMMO.get()) return false
        return Ac130Gun.entries.any { ammoLeft(it) < fullLoad(it) } || boss.decoyCount < Ac130Config.FLARE_COUNT.get()
    }

    /** True when at least one enabled gun can still fire (always true without `limitedAmmo`, if any gun is enabled). */
    fun hasUsableGun(): Boolean = Ac130Gun.entries.any { enabled(it) && hasAmmo(it) }

    private fun consumeAmmo(gun: Ac130Gun) {
        if (!Ac130Config.LIMITED_AMMO.get()) return
        ammo[gun.weaponName] = max(0, ammoLeft(gun) - 1)
    }

    private fun fullLoad(gun: Ac130Gun): Int = when (gun) {
        Ac130Gun.M61 -> Ac130Config.CANNON_ROUNDS.get()
        Ac130Gun.BOFORS -> Ac130Config.BOFORS_ROUNDS.get()
        Ac130Gun.M102 -> Ac130Config.HOWITZER_ROUNDS.get()
    }

    fun saveAmmo(tag: CompoundTag) {
        if (ammo.isEmpty()) return
        val ammoTag = CompoundTag()
        for ((weapon, left) in ammo) ammoTag.putInt(weapon, left)
        tag.put(TAG_AMMO, ammoTag)
    }

    fun loadAmmo(tag: CompoundTag) {
        ammo.clear()
        if (!tag.contains(TAG_AMMO)) return
        val ammoTag = tag.getCompound(TAG_AMMO)
        for (weapon in ammoTag.allKeys) ammo[weapon] = ammoTag.getInt(weapon)
    }

    // ================================================================================================== per tick

    /**
     * Aims every seated gun at [aim] (or leaves it at the neutral aim without one) and fires those that are on target.
     *
     * @param aimVelocity per-tick velocity of [aim], for lead
     * @param mayFire false in states where the gunship must not shoot (taunt, resupply)
     * @param plan per-gun aim orders that replace [aim] (split / focus fire); with a plan every ready gun fires at once,
     *        whatever the `simultaneous` options say, since sharing the turns would defeat the pattern
     */
    fun tick(
        aim: Entity?,
        aimVelocity: Vec3,
        mayFire: Boolean,
        plan: Ac130FirePlan? = null,
        onLongBurstStart: () -> Unit = {}
    ) {
        val now = boss.tickCount
        if (now >= nextScatterTick) {
            nextScatterTick = now + SCATTER_REROLL_TICKS
            val angle = boss.getRandom().nextDouble() * Math.PI * 2.0
            val radius = boss.getRandom().nextDouble() * SCATTER_RADIUS
            scatter = Vec3(cos(angle) * radius, 0.0, sin(angle) * radius)
        }

        val live = !boss.crippled && !boss.isWreck
        val shared = if (aim != null && aim.isAlive && !aim.isRemoved) aim.boundingBox.center else null
        if (plan != null) updateTracks(plan)
        val anyAim = if (plan != null) plan.perGun.isNotEmpty() else shared != null
        val allowed = when {
            !mayFire || !anyAim -> emptySet()
            plan != null -> readyGuns().toSet()
            else -> allowedGuns(now)
        }
        val scatterScale = plan?.scatterScale ?: 1.0

        for (gun in Ac130Gun.entries) {
            val st = stateOf(gun)
            val gunner = seatedGunner(gun)
            if (gunner == null) {
                boss.thought.think(fireChannels.getValue(gun)) { "no gunner seated" }
                st.hasAim = false
                st.frameValid = false
                st.burstEnd = 0
                continue
            }

            // The shared target, or this gun's own order from the plan.
            val resolved = if (plan != null) plan.perGun[gun]?.let { resolve(it) }
            else shared?.let { Resolved(it, aimVelocity) }

            val usable = live && resolved != null && enabled(gun) && hasAmmo(gun)
            st.hasAim = false
            var hold = when {
                !live -> "boss is down"
                resolved == null -> "no aim point"
                !enabled(gun) -> "disabled in the config"
                else -> "out of ammo"
            }
            if (usable) {
                hold = "no valid aim vector"

                val origin = boss.getShootPos(gunner, 1f)
                var point = leadPoint(gun, origin, resolved!!.center, resolved.velocity)
                if (gun == Ac130Gun.M61) point = point.add(scatter.scale(scatterScale))
                selectAmmo(gun, origin, point)
                val wanted = point.subtract(origin)
                if (wanted.lengthSqr() > 1.0e-6 && finite(wanted)) {
                    st.hasAim = true
                    st.wantYaw = FlightMath.yaw(wanted)
                    st.wantPitch = FlightMath.pitch(wanted)
                    st.errorDeg = Double.MAX_VALUE
                    applyGun(gun, gunner, st)
                    val inRange = wanted.length() <= MAX_RANGE
                    if (gun in allowed && inRange &&
                        (st.errorDeg <= FIRE_ANGLE || landsNear(gun, origin, point, st))
                    ) {
                        tryFire(gun, gunner, st, now, onLongBurstStart)
                        continue
                    }
                    hold = when {
                        !mayFire -> "this state does not allow firing"
                        gun !in allowed -> "waiting for its turn"
                        !inRange -> "target out of range"
                        else -> "target outside the gun arc"
                    }
                }
            }
            boss.thought.think(fireChannels.getValue(gun)) { "holding fire: $hold" }
            if (!st.hasAim) applyGun(gun, gunner, st)
            // Aim lost or not allowed to fire: the next time the gun lines up it starts a fresh burst.
            if (now - st.lastCall > BURST_GAP_RESET) st.burstEnd = 0
        }
    }

    /** A gun's aim point and the velocity of what is there, for lead. */
    private class Resolved(val center: Vec3, val velocity: Vec3)

    /** Smoothed per-tick velocity of every entity a plan aims at, from position differences (a teleport is not motion). */
    private class Track(var last: Vec3, var velocity: Vec3)

    private val tracks = HashMap<Int, Track>()

    private fun updateTracks(plan: Ac130FirePlan) {
        val entities = plan.perGun.values.mapNotNull { it.entity }.filter { it.isAlive && !it.isRemoved }.distinctBy { it.id }
        tracks.keys.retainAll(entities.map { it.id }.toSet())
        for (entity in entities) {
            val position = entity.position()
            val track = tracks[entity.id]
            if (track == null) {
                tracks[entity.id] = Track(position, Vec3.ZERO)
                continue
            }
            val delta = position.subtract(track.last)
            track.last = position
            track.velocity = if (delta.lengthSqr() > TELEPORT_DISTANCE_SQR) Vec3.ZERO
            else track.velocity.scale(1.0 - VELOCITY_SMOOTHING).add(delta.scale(VELOCITY_SMOOTHING))
        }
    }

    private fun resolve(order: GunAim): Resolved? {
        val entity = order.entity ?: return Resolved(order.offset, Vec3.ZERO)
        if (!entity.isAlive || entity.isRemoved) return null
        return Resolved(entity.boundingBox.center.add(order.offset), tracks[entity.id]?.velocity ?: Vec3.ZERO)
    }

    /** Uses the piercing consumer only when a solid block is between this gun and its current aim point. */
    private fun selectAmmo(gun: Ac130Gun, origin: Vec3, point: Vec3) {
        val ammoType = if (boss.level().clip(
                ClipContext(origin, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, boss)
            ).type == net.minecraft.world.phys.HitResult.Type.BLOCK
        ) AP_AMMO_TYPE else STANDARD_AMMO_TYPE
        boss.thought.think(ammoChannels.getValue(gun)) {
            if (ammoType == AP_AMMO_TYPE) "armour-piercing rounds: block in the way" else "standard rounds: clear shot"
        }
        boss.getGunData(gun.seat)?.changeAmmoConsumer(ammoType, boss)
    }

    /** Enabled guns with ammo and a gunner in the seat, in seat order. */
    fun readyGuns(): List<Ac130Gun> = Ac130Gun.entries.filter { enabled(it) && hasAmmo(it) && seatedGunner(it) != null }

    /**
     * Where the clamped gun line crosses the height of [point] (the lead point, which already includes the drop
     * compensation, so an unclamped shot would pass through it), and whether that is within [barrageRadius] blocks of
     * it. Lets the guns keep hammering the area while the target sits just outside their arc.
     */
    private fun landsNear(gun: Ac130Gun, origin: Vec3, point: Vec3, st: GunState): Boolean {
        val dir = FlightMath.dir(st.clampedYaw, st.clampedPitch)
        if (dir.y > -MIN_BARRAGE_DESCENT) return false // level or rising: would land far beyond, or never
        val t = (point.y - origin.y) / dir.y
        if (t <= 0.0 || t > MAX_RANGE) return false
        val hit = origin.add(dir.scale(t))
        return FlightMath.horizontalDistance(hit, point) <= barrageRadius(gun)
    }

    /** Miss distance (blocks) a gun still fires at: the splash makes a near miss worth the round. */
    private fun barrageRadius(gun: Ac130Gun): Double = when (gun) {
        Ac130Gun.M61 -> M61_BARRAGE_RADIUS
        Ac130Gun.BOFORS -> BOFORS_BARRAGE_RADIUS
        Ac130Gun.M102 -> M102_BARRAGE_RADIUS
    }

    // ================================================================================================== turns

    /**
     * Guns that may fire this tick. Guns marked simultaneous in the config form one group; every other gun is a group
     * of its own. Only guns that could fire at all (seated, enabled, ammo) count. With one group (the default: all
     * simultaneous) everything fires at once; otherwise the groups take turns of [TURN_TICKS] ticks (a lone 105mm turn
     * ends after one shell), and a turn is passed on after [TURN_IDLE_TICKS] ticks without a shot, so a reloading or
     * out-of-arc gun never stalls the others.
     */
    private fun allowedGuns(now: Int): Set<Ac130Gun> {
        val ready = readyGuns()
        val group = ready.filter { simultaneous(it) }.toSet()
        val turns = buildList {
            if (group.isNotEmpty()) add(group)
            for (gun in ready) if (!simultaneous(gun)) add(setOf(gun))
        }
        boss.thought.think("gunnery.allowedGuns") {
            if (turns.size <= 1) "ready guns fire together" else "guns take turns"
        }
        if (turns.size <= 1) {
            turnKey = turns
            return turns.firstOrNull() ?: emptySet()
        }
        // The line-up changed (config toggle, gun ran dry, gunner lost): start over with the first turn.
        if (turns != turnKey) {
            turnKey = turns
            startTurn(0, now)
            boss.thought.event("gunnery.allowedGuns") { "gun line-up changed: ${turns.size} turn groups, starting over" }
        }
        val current = turns[turnIndex % turns.size]
        val lastActivity = max(turnStart, turnLastShot)
        val oneShell = current.size == 1 && Ac130Gun.M102 in current && turnShots > 0
        if (oneShell || now - turnStart >= TURN_TICKS || now - lastActivity >= TURN_IDLE_TICKS) {
            startTurn((turnIndex + 1) % turns.size, now)
        }
        return turns[turnIndex]
    }

    private fun startTurn(index: Int, now: Int) {
        turnIndex = index
        turnStart = now
        turnLastShot = now
        turnShots = 0
    }

    /**
     * Re-applies the stored aim to every seated gunner against the *current* seat frame. The entity calls this after
     * `super.baseTick()` so the shot SBW defers to the end of the tick reads the right look direction.
     */
    fun applyAim() {
        for (gun in Ac130Gun.entries) {
            val gunner = seatedGunner(gun) ?: continue
            applyGun(gun, gunner, stateOf(gun))
        }
    }

    // ================================================================================================== aiming

    /** The crew member sitting in [gun]'s seat, or null while it is missing / dead / not actually mounted. */
    private fun seatedGunner(gun: Ac130Gun): BossCrewEntity? {
        val gunner = boss.gunner(gun) ?: return null
        if (!gunner.isAlive || gunner.isRemoved || gunner.vehicle !== boss) return null
        if (boss.getSeatIndex(gunner) != gun.seat) return null
        return gunner
    }

    /**
     * Seat frame forward direction. SBW's own source of truth for the clamp; includes the vehicle yaw, pitch, roll and
     * the seat orientation. Null when the seat data is unavailable or degenerate.
     */
    private fun seatDirection(gunner: Entity): Vec3? {
        val dir = try {
            boss.getTransformDirection(1f, gunner)
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
        return if (finite(dir) && dir.lengthSqr() > 1.0e-6) dir else null
    }

    private fun finite(v: Vec3): Boolean = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

    /**
     * The clamp from `VehicleEntity.clampRotation`, for a mob: yaw and pitch relative to the seat frame, limited to the
     * gun's window, written back as absolute rotations. Without an aim the gun idles at the window's neutral pose.
     */
    private fun applyGun(gun: Ac130Gun, gunner: BossCrewEntity, st: GunState) {
        val seat = seatDirection(gunner)
        if (seat == null) {
            st.frameValid = false
            return
        }
        val seatYaw = FlightMath.yaw(seat)
        val seatPitch = FlightMath.pitch(seat)

        val neutralPitch = (gun.minDepression + gun.maxDepression) * 0.5f
        val relYaw = if (st.hasAim) Mth.wrapDegrees(st.wantYaw - seatYaw) else 0f
        val relPitch = if (st.hasAim) Mth.wrapDegrees(st.wantPitch - seatPitch) else neutralPitch
        val clampedYaw = Mth.clamp(relYaw, gun.minYaw, gun.maxYaw)
        val clampedPitch = Mth.clamp(relPitch, gun.minDepression, gun.maxDepression)

        st.frameValid = true
        st.relPitch = relPitch.toDouble()
        st.seatPitch = seatPitch.toDouble()
        if (st.hasAim) {
            // Yaw differences shrink by cos(pitch) on the aiming sphere.
            val yawError = (relYaw - clampedYaw) * cos(Math.toRadians((seatPitch + clampedPitch).toDouble()))
            st.errorDeg = hypot(yawError, (relPitch - clampedPitch).toDouble())
        }

        st.clampedYaw = Mth.wrapDegrees(seatYaw + clampedYaw)
        st.clampedPitch = (seatPitch + clampedPitch).coerceIn(-90f, 90f)
        pointGunner(gunner, st.clampedYaw, st.clampedPitch)
    }

    private fun pointGunner(gunner: BossCrewEntity, yaw: Float, pitch: Float) {
        gunner.yRot = yaw
        gunner.xRot = pitch
        gunner.yRotO = yaw
        gunner.xRotO = pitch
        gunner.setYHeadRot(yaw)
        gunner.yHeadRotO = yaw
        gunner.setYBodyRot(yaw)
        gunner.yBodyRotO = yaw
    }

    /**
     * Point to aim [gun] at so a round fired now meets [center] (moving at [velocity] per tick): two refinement passes of
     * the time of flight at the muzzle speed, target-velocity lead and compensation for the round's gravity drop.
     * Same maths as the A-10's `FireControl.cannonAimAt`.
     */
    private fun leadPoint(gun: Ac130Gun, origin: Vec3, center: Vec3, velocity: Vec3): Vec3 {
        val speed = max(boss.getProjectileVelocity(gun.seat).toDouble(), 1.0)
        val gravity = boss.getProjectileGravity(gun.seat).toDouble()

        var t = origin.distanceTo(center) / speed
        repeat(2) { t = origin.distanceTo(center.add(velocity.scale(t))) / speed }
        t = min(t, MAX_LEAD_TICKS)

        // SBW projectiles move pos += v; v.y -= g, so after t ticks the drop is g * t * (t - 1) / 2.
        val drop = max(0.0, gravity * t * (t - 1.0) * 0.5)
        return center.add(velocity.scale(t)).add(0.0, drop, 0.0)
    }

    /**
     * The orbit radius at which the enabled guns see [aim] in the middle of their depression windows, or null when no
     * gun is available. The wanted depression is the middle of the overlap of the windows (or the mean of their
     * middles when they do not overlap); the seat frame's own tilt (the bank of the turn) is added to get the world
     * depression, and `radius = height / tan(depression)`.
     */
    fun suggestedRadius(aim: Entity): Double? {
        var lo = -Double.MAX_VALUE
        var hi = Double.MAX_VALUE
        var midSum = 0.0
        var tiltSum = 0.0
        var count = 0
        for (gun in Ac130Gun.entries) {
            val st = states[gun] ?: continue
            if (!st.frameValid || !enabled(gun) || !hasAmmo(gun) || seatedGunner(gun) == null) continue
            lo = max(lo, gun.minDepression.toDouble())
            hi = min(hi, gun.maxDepression.toDouble())
            midSum += (gun.minDepression + gun.maxDepression) * 0.5
            tiltSum += st.seatPitch
            count++
        }
        if (count == 0) return null

        val mid = if (lo < hi) (lo + hi) * 0.5 else midSum / count
        val worldDepression = (mid + tiltSum / count).coerceIn(MIN_WORLD_DEPRESSION, MAX_WORLD_DEPRESSION)
        val height = boss.y - aim.boundingBox.center.y
        if (height < MIN_HEIGHT_FOR_RADIUS) return null
        return height / tan(Math.toRadians(worldDepression))
    }

    // ================================================================================================== firing

    private fun tryFire(
        gun: Ac130Gun,
        gunner: BossCrewEntity,
        st: GunState,
        now: Int,
        onLongBurstStart: () -> Unit
    ) {
        if (boss.getGunData(gun.seat)?.canShoot(boss.ammoSupplier) != true) {
            boss.thought.think(fireChannels.getValue(gun)) { "holding fire: reloading or overheated" }
            // Reloading / overheated: wait it out. A short back-off keeps a burst from restarting mid-reload.
            if (gun == Ac130Gun.M61) {
                st.burstEnd = 0
                st.pauseUntil = max(st.pauseUntil, now + CANNOT_SHOOT_BACKOFF)
            }
            return
        }
        boss.thought.think(fireChannels.getValue(gun)) { "firing" }
        when (gun) {
            Ac130Gun.M61 -> fireCannon(gun, gunner, st, now, onLongBurstStart)
            Ac130Gun.BOFORS -> if (now >= st.nextShot) shoot(gun, gunner, st, now, rateInterval(gun))
            Ac130Gun.M102 -> if (now >= st.nextShot) shoot(gun, gunner, st, now, howitzerCooldown())
        }
    }

    /** Bursts of 45-90 ticks, never longer than 10 seconds, separated by 2-4 tick pauses. */
    private fun fireCannon(
        gun: Ac130Gun,
        gunner: BossCrewEntity,
        st: GunState,
        now: Int,
        onLongBurstStart: () -> Unit
    ) {
        if (now - st.lastCall > BURST_GAP_RESET) st.burstEnd = 0
        st.lastCall = now
        if (now < st.pauseUntil) return
        if (st.burstEnd == 0) {
            st.burstEnd = now + min(MAX_CANNON_BURST_TICKS, between(CANNON_BURST_MIN, CANNON_BURST_MAX))
            boss.thought.event("gunnery.fireCannon") {
                "M61 burst of ${st.burstEnd - now} ticks, rounds left ${ammoLeft(gun).let { if (it == Int.MAX_VALUE) "unlimited" else "$it" }}"
            }
            if (boss.getRandom().nextInt(100) < Ac130Config.LONG_BURST_TARGET_SWITCH_CHANCE.get()) {
                boss.thought.event("gunnery.fireCannon") { "long burst: asking for a new target" }
                onLongBurstStart()
            }
        }
        if (now >= st.burstEnd) {
            st.burstEnd = 0
            st.pauseUntil = now + scaledPause(between(CANNON_PAUSE_MIN, CANNON_PAUSE_MAX))
            return
        }
        if (now < st.nextShot) return
        shoot(gun, gunner, st, now, rateInterval(gun))
    }

    /**
     * The M102 fires as soon as SBW says it is loaded (its own 100-tick reload is the rate). In the opening phase an
     * extra [HOWITZER_P1_COOLDOWN] ticks halve that rate, so the first third of the fight is survivable.
     */
    private fun howitzerCooldown(): Int = if (phase() == 1) HOWITZER_P1_COOLDOWN else 0

    private fun shoot(gun: Ac130Gun, gunner: BossCrewEntity, st: GunState, now: Int, interval: Int) {
        val uuid: UUID? = null
        val position: Vec3? = null
        boss.vehicleShoot(gunner, uuid, position)
        // The cannon logs once per burst (see fireCannon); the slow guns log every shot.
        if (gun != Ac130Gun.M61) {
            boss.thought.event("gunnery.shoot") {
                "${gun.name} fired, rounds left ${ammoLeft(gun).let { if (it == Int.MAX_VALUE) "unlimited" else "${it - 1}" }}"
            }
        }
        ventGun(gun)
        consumeAmmo(gun)
        st.nextShot = now + max(1, interval)
        turnLastShot = now
        turnShots++
    }

    /**
     * SBW locks a gun out at 100 heat until it cools below 80 (the M61 gains 2 per round, loses 1 per tick), which
     * silences it for seconds mid-burst. The gunship keeps every gun under [HEAT_CAP] so it never overheats.
     */
    private fun ventGun(gun: Ac130Gun) {
        boss.modifyGunData(gun.seat) { data ->
            if (data.heat.get() > HEAT_CAP) data.heat.set(HEAT_CAP)
            if (data.overHeat.get()) data.overHeat.set(false)
        }
    }

    /** Ticks between two rounds, from the weapon's rate of fire (1200 rpm = every tick). */
    private fun rateInterval(gun: Ac130Gun): Int {
        val rpm = boss.vehicleWeaponRpm(gun.seat).coerceAtLeast(1)
        return max(1, (1200.0 / rpm).roundToInt())
    }

    /** 1 above 60% health, 2 above 30%, 3 below. */
    private fun phase(): Int {
        val health = boss.healthFraction()
        return when {
            health > 0.6f -> 1
            health > 0.3f -> 2
            else -> 3
        }
    }

    private fun scaledPause(ticks: Int): Int = if (phase() == 3) max(1, (ticks * P3_PAUSE_SCALE).roundToInt()) else ticks

    private fun between(low: Int, high: Int): Int = low + boss.getRandom().nextInt(high - low + 1)

    private companion object {
        const val TAG_AMMO = "Ammo"

        // Firing gates
        const val FIRE_ANGLE = 3.0
        const val MAX_RANGE = 450.0
        const val MAX_LEAD_TICKS = 120.0
        const val STANDARD_AMMO_TYPE = 0
        const val AP_AMMO_TYPE = 1

        // Barrage: fire while the clamped line still lands this close to the target
        const val M61_BARRAGE_RADIUS = 14.0
        const val BOFORS_BARRAGE_RADIUS = 16.0
        const val M102_BARRAGE_RADIUS = 18.0
        const val MIN_BARRAGE_DESCENT = 0.05

        // Turns for guns that do not fire simultaneously
        const val TURN_TICKS = 24
        const val TURN_IDLE_TICKS = 6

        // M61 cadence and scatter
        const val CANNON_BURST_MIN = 45
        const val CANNON_BURST_MAX = 90
        const val MAX_CANNON_BURST_TICKS = 200
        const val CANNON_PAUSE_MIN = 2
        const val CANNON_PAUSE_MAX = 4
        const val BURST_GAP_RESET = 2
        const val HEAT_CAP = 70.0
        const val CANNOT_SHOOT_BACKOFF = 10
        const val SCATTER_RADIUS = 3.0
        const val SCATTER_REROLL_TICKS = 3
        const val P3_PAUSE_SCALE = 0.6

        // Motion estimate of split-fire targets
        const val VELOCITY_SMOOTHING = 0.3
        const val TELEPORT_DISTANCE_SQR = 20.0 * 20.0

        // M102
        const val HOWITZER_P1_COOLDOWN = 8

        // Orbit radius suggestion
        const val MIN_WORLD_DEPRESSION = 12.0
        const val MAX_WORLD_DEPRESSION = 80.0
        const val MIN_HEIGHT_FOR_RADIUS = 5.0
    }
}
