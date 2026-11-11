package com.teamuhi.superbenemies.entity.boss.lav25

import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossEntity
import com.teamuhi.superbenemies.entity.boss.common.ground.TankGunnery
import com.teamuhi.superbenemies.entity.boss.common.ground.TankWeapon
import com.teamuhi.superbenemies.entity.boss.common.ground.WeaponKind
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.min

/**
 * The LAV-25's guns: the 25mm cannon (AP / HE, bursts), the coaxial machine gun and the wire-guided missile. The shared
 * [TankGunnery] does the aiming, shell choice and firing; this adds the missile's rules.
 *
 * Superb Warfare's wire missile only rides the launcher's barrel ray, which misses a moving target by the lag of the turret.
 * So the boss takes the missile over once it exists: it drops the launcher link (`setLauncherVehicle(null)`, which stops the
 * barrel steering) and turns the missile itself every tick toward the target's intercept point ([guideMissile]) until it is
 * gone or [MAX_FLIGHT_TICKS] pass. A missile always keeps to the target it was fired at: when that target is hidden, dead or
 * no longer the boss's aim, the missile is steered at the last place it was seen instead of flying straight on.
 * The missile no longer needs the barrel, so the cannon keeps firing while it flies.
 *
 * It goes at vehicles within [MIN_RANGE]..the missile range whenever it is loaded and the `missileCooldownSeconds` have
 * passed, and at soft targets (players, mobs) beyond [SOFT_MIN_RANGE] blocks on a coin toss rolled once per cooldown that
 * lands [SOFT_CHANCE] percent of the time, so the cannon is not the only gun in a fight at the usual engage range.
 */
class Lav25Gunnery(
    boss: TankBossEntity,
    settings: TankSettings,
    private val missileRange: Double = MAX_RANGE,
    cannonRange: Double = 150.0,
    elevation: Double = 27.0
) : TankGunnery(boss, settings, weaponsFor(cannonRange, missileRange, elevation)) {

    private var nextMissileTick = 0
    private var coinCycle = -1
    private var coin = false

    /** A missile this boss took over: the target it was fired at (entity id) and the last place that target was seen. */
    private class Flight(val missile: WireGuideMissileEntity, val targetId: Int, var point: Vec3)

    /** The missiles being steered (a new one can leave before the last has landed), oldest first. */
    private val flights = ArrayList<Flight>()

    /** The target of the latest launch, picked up by the missile that appears a tick later, and the tick until which it is looked for. */
    private var pendingTargetId = -1
    private var pendingPoint: Vec3? = null
    private var lookUntil = 0

    override fun wants(weapon: TankWeapon, aim: Entity, hidden: Boolean, suppressing: Boolean, distance: Double): Boolean {
        if (weapon.kind != WeaponKind.MISSILE) return super.wants(weapon, aim, hidden, suppressing, distance)
        val now = boss.tickCount
        val reason = when {
            hidden -> "target out of sight"
            now < nextMissileTick -> "on cooldown"
            distance < MIN_RANGE -> "target too close"
            distance > missileRange -> "target out of range"
            isAir(aim) -> "cannot hit aircraft"
            else -> null
        }
        if (reason != null) {
            boss.thought.think("lav25.missile") { "holding the missile: $reason" }
            return false
        }
        if (!isSoft(aim)) {
            boss.thought.think("lav25.missile") { "missile ready against armoured ${aim.name.string}" }
            return true
        }
        if (distance < SOFT_MIN_RANGE) {
            boss.thought.think("lav25.missile") { "holding the missile: soft target too close" }
            return false
        }
        if (coinCycle != nextMissileTick) {
            coinCycle = nextMissileTick
            coin = boss.getRandom().nextInt(100) < SOFT_CHANCE
        }
        boss.thought.think("lav25.missile") {
            if (coin) "missile ready against soft ${aim.name.string} (coin toss)" else "holding the missile: coin toss said no for soft ${aim.name.string}"
        }
        return coin
    }

    override fun afterShot(weapon: TankWeapon, aim: Entity, distance: Double, now: Int) {
        if (weapon.kind != WeaponKind.MISSILE) return
        nextMissileTick = now + (settings.missileCooldownSeconds?.get() ?: DEFAULT_COOLDOWN_SECONDS) * 20
        boss.thought.event("lav25.missile") {
            "missile launched at ${aim.name.string}, ${distance.toInt()} blocks, cooldown ${settings.missileCooldownSeconds?.get() ?: DEFAULT_COOLDOWN_SECONDS}s"
        }
        // The shot is queued, so the missile appears a tick or two later. It is steered at what it was fired at, whatever
        // the boss aims at later.
        pendingTargetId = aim.id
        pendingPoint = aim.boundingBox.center
        lookUntil = now + FIND_WINDOW_TICKS
    }

    /**
     * Takes over a missile that has just left the tube, then steers every missile in flight: onto the intercept point of
     * the target it was fired at while that target is in view, otherwise onto the last place it was seen (a hidden target,
     * a dead one, or another target chosen since must not leave the wire missile flying straight on to a random spot).
     */
    override fun guideMissile(aim: Entity?, centre: Vec3?, velocity: Vec3, hidden: Boolean) {
        val now = boss.tickCount
        if (now <= lookUntil) adoptNewMissiles()
        if (flights.isEmpty()) return

        val iterator = flights.iterator()
        while (iterator.hasNext()) {
            val flight = iterator.next()
            val missile = flight.missile
            if (!missile.isAlive || missile.isRemoved || missile.tickCount > MAX_FLIGHT_TICKS) {
                boss.thought.event("lav25.guideMissile") { if (missile.tickCount > MAX_FLIGHT_TICKS) "missile timed out" else "missile is gone" }
                iterator.remove()
                continue
            }

            val position = missile.position()
            val seen = aim != null && centre != null && !hidden && aim.id == flight.targetId
            boss.thought.think("lav25.guideMissile") { if (seen) "steering the missile onto its target" else "target unseen, steering the missile at the last seen spot" }
            val goal: Vec3
            if (seen) {
                flight.point = centre!!
                val speed = max(missile.deltaMovement.length(), MIN_SPEED)
                val time = min(position.distanceTo(centre) / speed, MAX_LEAD_TICKS)
                goal = centre.add(velocity.scale(time))
            } else {
                goal = flight.point
            }
            val toward = goal.subtract(position)
            // At the spot already: nothing left to steer toward (the warhead goes off on contact or burns out).
            if (!seen && toward.lengthSqr() < ARRIVE_DISTANCE * ARRIVE_DISTANCE) continue
            if (toward.lengthSqr() < 1.0e-6 || !finiteVec(toward)) continue
            missile.turn(toward.normalize(), (missile.tickCount * TURN_RAMP).coerceIn(MIN_TURN, MAX_TURN))
        }
    }

    /** Unlinks every new wire missile of this boss from the barrel (so Superb Warfare stops steering it) and starts tracking it. */
    private fun adoptNewMissiles() {
        for (found in findNewMissiles()) {
            if (flights.any { it.missile === found }) continue
            found.setLauncherVehicle(null)
            boss.thought.event("lav25.guideMissile") { "took over a missile in flight" }
            val point = pendingPoint ?: found.position().add(found.lookAngle.scale(FALLBACK_POINT_DISTANCE))
            flights.add(Flight(found, pendingTargetId, point))
        }
        // More than a couple in the air is not possible with the cooldown; never let the list grow.
        while (flights.size > MAX_FLIGHTS) flights.removeAt(0)
    }

    private fun finiteVec(v: Vec3): Boolean = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

    /** Wire missiles of this boss that are just out of the tube: fired from this vehicle (Superb Warfare records it) by its crew. */
    private fun findNewMissiles(): List<WireGuideMissileEntity> =
        boss.level().getEntitiesOfClass(WireGuideMissileEntity::class.java, boss.boundingBox.inflate(NEW_MISSILE_RADIUS)) {
            it.isAlive && it.tickCount <= NEW_MISSILE_AGE && (it.launcherVehicleUUID == boss.uuid || it.owner?.vehicle === boss)
        }

    private companion object {
        const val MIN_RANGE = 30.0
        const val MAX_RANGE = 170.0
        const val SOFT_MIN_RANGE = 30.0
        const val SOFT_CHANCE = 80
        const val DEFAULT_COOLDOWN_SECONDS = 20
        const val FIND_WINDOW_TICKS = 20
        const val NEW_MISSILE_RADIUS = 12.0
        const val NEW_MISSILE_AGE = 6
        const val MAX_FLIGHT_TICKS = 260
        const val MIN_SPEED = 1.0
        const val MAX_LEAD_TICKS = 40.0

        /** A missile this close to the last known spot is left alone; the most missiles kept under guidance at once. */
        const val ARRIVE_DISTANCE = 5.0
        const val MAX_FLIGHTS = 4

        /** Where a missile whose target is unknown is sent: straight ahead this far. */
        const val FALLBACK_POINT_DISTANCE = 120.0

        /** Degrees per tick the boss turns the missile: grows with its age, as the engine's own steering does. */
        const val TURN_RAMP = 1.5f
        const val MIN_TURN = 1.5f
        const val MAX_TURN = 6f

        /** The M2 Bradley reuses these guns with a longer reach and a higher elevation. */
        fun weaponsFor(cannonRange: Double, missileRange: Double, elevation: Double) = listOf(
            TankWeapon(
                "Cannon", WeaponKind.CANNON, seat = 0, fireAngle = 2.5, maxRange = cannonRange, maxElevation = elevation, maxDepression = 10.0,
                burstMin = 25, burstMax = 45, pauseMin = 15, pauseMax = 35, maxPierce = 2
            ),
            TankWeapon(
                "MachineGun", WeaponKind.COAX, seat = 0, fireAngle = 4.0, maxRange = 80.0, maxElevation = elevation, maxDepression = 10.0,
                burstMin = 20, burstMax = 50, pauseMin = 10, pauseMax = 25
            ),
            TankWeapon("Missile", WeaponKind.MISSILE, seat = 0, fireAngle = 8.0, maxRange = missileRange, maxElevation = elevation, maxDepression = 10.0, interval = 20)
        )
    }
}
