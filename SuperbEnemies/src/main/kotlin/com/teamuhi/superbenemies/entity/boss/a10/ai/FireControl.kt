package com.teamuhi.superbenemies.entity.boss.a10.ai

import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.tools.ProjectileCalculator
import com.teamuhi.superbenemies.config.BossConfig
import com.teamuhi.superbenemies.entity.boss.a10.A10WarthogBossEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Weapon handling and ballistic maths for the A-10 boss.
 *
 * Ammunition is unlimited (creative ammo box in the boss inventory) but every weapon still goes through Superb
 * Warfare's own magazine / reload / heat handling, so each fire method first checks `GunData.canShoot`. SBW does
 * not rate-limit server-side firing, therefore the per-weapon cooldowns below live here, per boss instance.
 *
 * Call [tick] once per boss tick, before the brain issues fire commands.
 */
class FireControl(private val boss: A10WarthogBossEntity) {

    // cannon burst state (all values are absolute boss.tickCount values, 0 = none)
    private var cannonBurstEnd = 0
    private var cannonPauseUntil = 0
    private var cannonNextShot = 0
    private var lastCannonCall = Int.MIN_VALUE / 2

    private var missileNextTick = 0
    private var rocketNextTick = 0
    private var lastRocketShot = Int.MIN_VALUE / 2

    // scheduled bomb stick
    private var stickRemaining = 0
    private var stickInterval = 6
    private var stickNextTick = 0

    /** Rounds / munitions left per weapon while `BossConfig.LIMITED_AMMO` is on. A missing entry means a full load. */
    private val ammo = HashMap<String, Int>()

    /** Fall time (ticks) of the last bomb prediction, for diagnostics. */
    var lastBombFallTicks = DEFAULT_BOMB_FALL_TICKS
        private set

    // ------------------------------------------------------------------------------------------------ tick

    /** Advances the scheduled bomb-stick drops and discards stale cannon burst state. */
    fun tick() {
        val now = boss.tickCount
        if (boss.isWreck || boss.crippled) {
            stickRemaining = 0
            cannonBurstEnd = 0
            return
        }
        // The brain stopped calling fireCannon (alignment lost): the next call starts a fresh burst.
        if (cannonBurstEnd != 0 && now - lastCannonCall > BURST_GAP_RESET) cannonBurstEnd = 0

        if (stickRemaining > 0 && now >= stickNextTick) {
            dropBomb() // a failed drop (rack empty / reloading) just skips that bomb
            stickRemaining--
            stickNextTick = now + stickInterval
        }
    }

    // ------------------------------------------------------------------------------------------------ cannon

    /**
     * Call every tick while the nose is aligned. Fires in internal bursts of 10-16 ticks separated by 8-14 tick
     * pauses (shorter pauses in the final phase); with [raze] 40-70 tick bursts and 3-6 tick pauses.
     * Returns true when a round was fired this call.
     */
    fun fireCannon(raze: Boolean = false): Boolean {
        val now = boss.tickCount
        if (!canShoot(CANNON)) {
            boss.thought.think("fire.fireCannon") { "holding fire: ${blockedReason(CANNON)}" }
            cannonBurstEnd = 0
            cannonPauseUntil = max(cannonPauseUntil, now + 10)
            return false
        }
        boss.thought.think("fire.fireCannon") { "cannon ready" }
        if (now - lastCannonCall > BURST_GAP_RESET) cannonBurstEnd = 0
        lastCannonCall = now

        if (now < cannonPauseUntil) return false
        // Razing an area wants long, almost continuous bursts; a precision strafe fires short ones.
        if (cannonBurstEnd == 0) {
            cannonBurstEnd = now + if (raze) between(40, 70) else between(10, 16)
            boss.thought.event("fire.fireCannon") {
                "cannon burst (${if (raze) "razing" else "strafe"}), ${cannonBurstEnd - now} ticks, rounds left ${roundsText(CANNON)}"
            }
        }
        if (now >= cannonBurstEnd) {
            cannonBurstEnd = 0
            cannonPauseUntil = now + scaled(if (raze) between(3, 6) else between(8, 14))
            return false
        }
        if (now < cannonNextShot) return false

        val noTargetPos: Vec3? = null
        boss.vehicleShoot(boss.pilot, CANNON, noTargetPos)
        consumeAmmo(CANNON)
        ventCannon()
        cannonNextShot = now + cannonInterval()
        return true
    }

    /**
     * The GAU-8 gains 1.5 heat per round and SBW locks a gun out at 100 until it has cooled below 80, which silenced the
     * cannon for seconds in the middle of a run. The boss keeps its heat under [HEAT_CAP] so it never overheats.
     */
    private fun ventCannon() {
        boss.modifyGunData(CANNON) { data ->
            if (data.heat.get() > HEAT_CAP) data.heat.set(HEAT_CAP)
            if (data.overHeat.get()) data.overHeat.set(false)
        }
    }

    /** Ticks between two cannon rounds, derived from the weapon's rate of fire (1200 rpm = every tick). */
    private fun cannonInterval(): Int = rateInterval(CANNON)

    // ------------------------------------------------------------------------------------------------ missile / rocket

    /**
     * Launches an AGM-65 locked on [target] (its root vehicle when it is riding one). The missile is launched towards
     * the target when that lies in the forward hemisphere, otherwise along the airframe boresight; it homes on the
     * lock UUID either way. Needs the pilot (SBW only applies the lock when there is a shooter).
     *
     * With [guided] false ([target] is the ghost of a hidden target, a stand-in that is not in the world) no lock UUID
     * is passed: SBW then finds no entity to lock and flies the missile unguided to the aim point instead (guide type 1,
     * the point's position as its target), i.e. to the last known position.
     */
    fun fireMissile(target: Entity, guided: Boolean = true): Boolean {
        val now = boss.tickCount
        if (now < missileNextTick) return false
        if (!pilotSeated() || !canShoot(MISSILE)) {
            boss.thought.think("fire.fireMissile") { "holding fire: ${if (!pilotSeated()) "no pilot seated" else blockedReason(MISSILE)}" }
            return false
        }

        val lock = target.rootVehicle
        val aim = if (guided) lock.boundingBox.center else target.position()
        val toTarget = aim.subtract(boss.getShootPos(MISSILE, 1f))
        val forwardOk = toTarget.lengthSqr() > 1.0 && toTarget.normalize().dot(boss.getViewVector(1f)) > 0.0
        val fired = shootNow(MISSILE, if (forwardOk) toTarget else null, if (guided) lock.uuid else null, aim)
        if (fired) {
            missileNextTick = now + scaled(between(40, 60))
            boss.thought.event("fire.fireMissile") {
                "AGM-65 launched at ${target.name.string} (${if (guided) "locked" else "unguided, last known spot"}), missiles left ${roundsText(MISSILE)}"
            }
        }
        return fired
    }

    /**
     * Fires one unguided 70mm rocket at [aimPoint]. SBW's rocket pylons have a fixed barrel direction, so the shot
     * vector is built by hand (shoot position to [aimPoint]). Refused when the point is more than ~78 degrees off the
     * nose, so a rocket never spawns pointing back into the airframe.
     */
    fun fireRocket(aimPoint: Vec3): Boolean {
        val now = boss.tickCount
        if (now < rocketNextTick) return false
        if (!canShoot(ROCKET)) {
            boss.thought.think("fire.fireRocket") { "holding fire: ${blockedReason(ROCKET)}" }
            return false
        }

        val toPoint = aimPoint.subtract(boss.getShootPos(ROCKET, 1f))
        if (toPoint.lengthSqr() < 1.0) return false
        if (toPoint.normalize().dot(boss.getViewVector(1f)) < MIN_ROCKET_FORWARD_DOT) return false

        val fired = shootNow(ROCKET, toPoint, null, null)
        if (fired) {
            rocketNextTick = now + rateInterval(ROCKET)
            // One line per salvo, not per rocket.
            if (now - lastRocketShot > ROCKET_SALVO_GAP) {
                boss.thought.event("fire.fireRocket") { "rocket salvo started, rockets left ${roundsText(ROCKET)}" }
            }
            lastRocketShot = now
        }
        return fired
    }

    /** Releases one flare burst this tick (unlimited: the dispenser is refilled instead of waiting for SBW's reload). */
    fun releaseFlare() {
        if (boss.isWreck || boss.crippled) return
        if (boss.decoyCount < 1) {
            if (BossConfig.LIMITED_AMMO.get()) {
                boss.thought.think("fire.releaseFlare") { "flares empty, none released" }
                return
            }
            boss.decoyCount = boss.computed().decoyMagazineSize
        }
        boss.decoyInputDown = true
    }

    // ------------------------------------------------------------------------------------------------ aiming

    /**
     * Point to aim the cannon at so a round fired now meets [target]. Two refinement passes of the time of flight at
     * the cannon's muzzle speed, target velocity lead, plus compensation for the round's gravity drop.
     */
    fun cannonLeadPoint(target: Entity): Vec3 {
        val subject = target.rootVehicle
        return cannonAimAt(subject.boundingBox.center, velocityOf(subject))
    }

    /**
     * Point to aim the cannon at so a round fired now lands on [center] (moving at [velocity] per tick, zero for a
     * fixed point such as a last known position).
     */
    fun cannonAimAt(center: Vec3, velocity: Vec3 = Vec3.ZERO): Vec3 {
        val origin = boss.getShootPos(CANNON, 1f)
        val speed = max(boss.getProjectileVelocity(CANNON).toDouble(), 1.0)
        val gravity = boss.getProjectileGravity(CANNON).toDouble()

        var t = origin.distanceTo(center) / speed
        repeat(2) { t = origin.distanceTo(center.add(velocity.scale(t))) / speed }
        t = min(t, MAX_LEAD_TICKS)

        // SBW projectiles move pos += v; v.y -= g, so after t ticks the drop is g * t * (t - 1) / 2.
        val drop = max(0.0, gravity * t * (t - 1.0) * 0.5)
        return center.add(velocity.scale(t)).add(0.0, drop, 0.0)
    }

    // ------------------------------------------------------------------------------------------------ bombs

    /**
     * Where a bomb released this tick would land. Mirrors what `VehicleEntity.bombHitPos` feeds the HUD pipper
     * (which only returns a value client-side) but with the bomb's real launch parameters: shoot position, the
     * velocity-vector direction, speed = |plane velocity| * Velocity, and the weapon's gravity. SBW bombs advance as
     * `pos += v; v.y -= g`, which equals the continuous model with the vertical speed raised by g/2, so that half
     * step is added to the launch vector.
     */
    fun predictBombImpact(): Vec3 {
        if (boss.getGunData(BOMB) == null) return boss.position()
        val origin = boss.getShootPos(BOMB, 1f)
        val dir = boss.getShootVec(BOMB, 1f)
        val speed = boss.getProjectileVelocity(BOMB).toDouble()
        val gravity = boss.getProjectileGravity(BOMB).toDouble()

        val launch = dir.normalize().scale(speed).add(0.0, gravity * 0.5, 0.0)
        val launchSpeed = launch.length()
        val impact = if (launchSpeed < 1.0e-4) {
            ProjectileCalculator.calculatePreciseImpactPoint(boss.level(), origin, Vec3(0.0, -1.0, 0.0), 0.0, -gravity)
        } else {
            ProjectileCalculator.calculatePreciseImpactPoint(boss.level(), origin, launch, launchSpeed, -gravity)
        }
        lastBombFallTicks = fallTicks(origin.y - impact.y, launch.y, gravity)
        return impact
    }

    /**
     * World-free version of [predictBombImpact]: the same launch parameters, but the ground is assumed flat at
     * [groundY]. Costs a few multiplications instead of SBW's per-block terrain sampling, so the brain uses it to rule
     * out a release while the plane is still far from the aim point. Terrain can only matter near the aim point, where
     * the ground is at about the target's height.
     */
    fun estimateBombImpact(groundY: Double): Vec3 {
        if (boss.getGunData(BOMB) == null) return boss.position()
        val origin = boss.getShootPos(BOMB, 1f)
        val dir = boss.getShootVec(BOMB, 1f).normalize()
        val speed = boss.getProjectileVelocity(BOMB).toDouble()
        val gravity = boss.getProjectileGravity(BOMB).toDouble()
        val t = fallTicks(max(0.0, origin.y - groundY), dir.y * speed + gravity * 0.5, gravity)
        return Vec3(origin.x + dir.x * speed * t, groundY, origin.z + dir.z * speed * t)
    }

    /**
     * Where [target] will be when a bomb released now reaches the ground: its position plus its velocity times the
     * estimated fall time from the current altitude. The Y coordinate is the target's feet.
     */
    fun bombLeadPoint(target: Entity): Vec3 {
        val subject = target.rootVehicle
        val origin = boss.getShootPos(BOMB, 1f)
        val speed = boss.getProjectileVelocity(BOMB).toDouble()
        val gravity = boss.getProjectileGravity(BOMB).toDouble()
        val dir = boss.getShootVec(BOMB, 1f).normalize()
        val verticalSpeed = dir.y * speed + gravity * 0.5

        val height = max(0.0, origin.y - subject.y)
        val t = fallTicks(height, verticalSpeed, gravity)
        val velocity = velocityOf(subject)
        return subject.position().add(velocity.x * t, 0.0, velocity.z * t)
    }

    /**
     * Releases one bomb now, along the velocity vector. Fires synchronously (SBW's `vehicleShoot` defers the shot to
     * the end of the tick, which would put the bomb a full plane step past the predicted release point).
     * Needs the pilot seated: SBW scales the bomb speed by the shooter's vehicle and refuses it otherwise.
     */
    fun dropBomb(): Boolean {
        selectBombVariant(false)
        if (!pilotSeated() || !canShoot(BOMB)) {
            boss.thought.think("fire.dropBomb") { "holding bombs: ${if (!pilotSeated()) "no pilot seated" else blockedReason(BOMB)}" }
            return false
        }
        return shootNow(BOMB, null, null, null)
    }

    /** Releases one selected Mk 84 bomb. The brain selects the variant before solving the release point. */
    fun dropNuke(): Boolean {
        if (!pilotSeated() || ammoLeft(NUKE) <= 0) {
            boss.thought.think("fire.dropNuke") { "holding the Mk 84: ${if (!pilotSeated()) "no pilot seated" else "none left"}" }
            return false
        }
        return shootNow(BOMB, null, null, null, NUKE, nuke = true)
    }

    /** Matches the player's N-key bomb switch: index 0 is Mk 82 and index 1 is Mk 84. */
    fun selectBombVariant(nuke: Boolean) {
        boss.modifyGunData(BOMB) { data ->
            data.changeAmmoConsumer(if (nuke) 1 else 0, boss)
            data.reloadAmmo(boss.ammoSupplier)
        }
    }

    /**
     * Schedules [count] further bomb drops, the first [intervalTicks] ticks from now and then every [intervalTicks]
     * ticks. This does not release a bomb immediately: call [dropBomb] for the first one. Replaces any stick that is
     * still pending.
     */
    fun dropBombStick(count: Int, intervalTicks: Int) {
        stickRemaining = max(0, count)
        stickInterval = max(1, intervalTicks)
        stickNextTick = boss.tickCount + stickInterval
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /**
     * Per-tick displacement of [entity] (reliable on the server, unlike `deltaMovement` for players). Zero for the ghost
     * of a hidden target: `LastKnownLocation` keeps its previous position equal to its position.
     */
    fun velocityOf(entity: Entity): Vec3 {
        val v = Vec3(entity.x - entity.xo, entity.y - entity.yo, entity.z - entity.zo)
        return if (entity.onGround()) Vec3(v.x, 0.0, v.z) else v
    }

    /** Whether [weapon] exists and SBW would let it fire now (loaded, cool, not reloading). */
    fun canShoot(weapon: String): Boolean {
        if (boss.isWreck || boss.crippled) return false
        if (ammoLeft(weapon) <= 0) return false
        val data = boss.getGunData(weapon) ?: return false
        return data.canShoot(boss.ammoSupplier)
    }

    // ------------------------------------------------------------------------------------------------ ammunition

    /** Why [canShoot] says no for [weapon], for the console. */
    private fun blockedReason(weapon: String): String = when {
        boss.isWreck || boss.crippled -> "boss is down"
        ammoLeft(weapon) <= 0 -> "out of ${weapon.lowercase()} ammo"
        boss.getGunData(weapon) == null -> "no $weapon fitted"
        else -> "$weapon reloading or cooling"
    }

    private fun roundsText(weapon: String): String = ammoLeft(weapon).let { if (it == Int.MAX_VALUE) "unlimited" else "$it" }

    /** One-line ammunition summary for the heartbeat: the finite loads and the flares. */
    fun ammoSummary(): String {
        val flares = boss.decoyCount
        if (!BossConfig.LIMITED_AMMO.get()) return "unlimited, flares $flares"
        return "cannon ${ammoLeft(CANNON)} rocket ${ammoLeft(ROCKET)} bomb ${ammoLeft(BOMB)} " +
                "mk84 ${ammoLeft(NUKE)} agm ${ammoLeft(MISSILE)} flares $flares"
    }

    /** Munitions left for [weapon]; effectively unlimited unless the config enables a finite load. */
    fun ammoLeft(weapon: String): Int {
        if (!BossConfig.LIMITED_AMMO.get()) return Int.MAX_VALUE
        return ammo.getOrPut(weapon) { fullLoad(weapon) }
    }

    fun hasAmmo(weapon: String): Boolean = ammoLeft(weapon) > 0

    /**
     * True when finite ammo is on and any weapon is below its full load or the flares are below the configured count:
     * the boss has something to gain from rearming (see the idle resupply in the brain). Never true for unlimited ammo.
     */
    fun anySpent(): Boolean {
        if (!BossConfig.LIMITED_AMMO.get()) return false
        return ammo.any { (weapon, left) -> left < fullLoad(weapon) } || boss.decoyCount < BossConfig.FLARE_COUNT.get()
    }

    private fun consumeAmmo(weapon: String) {
        if (!BossConfig.LIMITED_AMMO.get()) return
        ammo[weapon] = max(0, ammoLeft(weapon) - 1)
    }

    private fun fullLoad(weapon: String): Int = when (weapon) {
        CANNON -> BossConfig.CANNON_ROUNDS.get()
        ROCKET -> BossConfig.ROCKET_COUNT.get()
        BOMB -> BossConfig.BOMB_COUNT.get()
        NUKE -> BossConfig.NUKE_COUNT.get()
        MISSILE -> BossConfig.MISSILE_COUNT.get()
        else -> Int.MAX_VALUE
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

    private fun pilotSeated(): Boolean {
        val pilot = boss.pilot ?: return false
        return pilot.isAlive && pilot.rootVehicle === boss
    }

    /**
     * Fires [weapon] immediately, along [direction] or, when null, the weapon's own SBW shoot vector. This repeats the
     * steps of `VehicleEntity.vehicleShoot` without its end-of-tick queue, so custom directions are possible.
     */
    private fun shootNow(
        weapon: String,
        direction: Vec3?,
        uuid: UUID?,
        targetPos: Vec3?,
        ammoKey: String = weapon,
        nuke: Boolean = false
    ): Boolean {
        val level = boss.level() as? ServerLevel ?: return false
        val gunData = boss.getGunData(weapon) ?: return false
        val pilot = boss.pilot
        var shotDirection: Vec3? = null

        boss.modifyGunData(weapon) { data ->
            if (nuke) {
                data.changeAmmoConsumer(1, boss)
                data.reloadAmmo(boss.ammoSupplier)
            }
            if (!data.canShoot(boss.ammoSupplier)) return@modifyGunData
            val dir = (direction ?: boss.getShootVec(weapon, 1f)).normalize()
            data.shoot(
                ShootParameters(
                    boss.ammoSupplier, pilot, level,
                    boss.getShootPos(weapon, 1f), dir,
                    data, data.get(GunProp.SPREAD), true,
                    uuid, targetPos
                )
            )
            shotDirection = dir
        }

        val fired = shotDirection ?: run {
            if (nuke) {
                boss.thought.event("fire.fireBomb") {
                    "Mk 84 release rejected: selected ammo ${gunData.selectedAmmoType.get()}, magazine ${gunData.ammo.get()}, available ammo ${gunData.currentAvailableAmmo(boss.ammoSupplier)}"
                }
            }
            return false
        }
        boss.afterShoot(gunData, fired)
        boss.playShootSound3p(pilot, weapon)
        consumeAmmo(ammoKey)
        return true
    }

    /** Solves `h = -vy*t + g*t^2/2` for t (vy positive up, h the drop in blocks), in ticks. */
    private fun fallTicks(height: Double, verticalSpeed: Double, gravity: Double): Double {
        if (gravity <= 1.0e-6) return DEFAULT_BOMB_FALL_TICKS
        val discriminant = verticalSpeed * verticalSpeed + 2.0 * gravity * max(height, 0.0)
        return ((verticalSpeed + sqrt(discriminant)) / gravity).coerceIn(1.0, 400.0)
    }

    private fun rateInterval(weapon: String): Int {
        val rpm = boss.vehicleWeaponRpm(weapon).coerceAtLeast(1)
        return max(1, (1200.0 / rpm).roundToInt())
    }

    private fun between(low: Int, high: Int): Int = low + boss.getRandom().nextInt(high - low + 1)

    /** Applies the final-phase cooldown scale. */
    private fun scaled(ticks: Int): Int =
        if (boss.healthFraction() < P3_THRESHOLD) max(1, (ticks * P3_COOLDOWN_SCALE).roundToInt()) else ticks

    companion object {
        const val CANNON = "Cannon"
        const val ROCKET = "Rocket"
        const val BOMB = "Bomb"
        const val NUKE = "Nuke"
        const val MISSILE = "Missile"

        private const val TAG_AMMO = "Ammo"
        private const val DEFAULT_BOMB_FALL_TICKS = 70.0
        private const val MAX_LEAD_TICKS = 120.0
        private const val BURST_GAP_RESET = 2
        private const val ROCKET_SALVO_GAP = 30
        private const val HEAT_CAP = 70.0
        private const val MIN_ROCKET_FORWARD_DOT = 0.2
        private const val P3_THRESHOLD = 0.3f
        private const val P3_COOLDOWN_SCALE = 0.6
    }
}
