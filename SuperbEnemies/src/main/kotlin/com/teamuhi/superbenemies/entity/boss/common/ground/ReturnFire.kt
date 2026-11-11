package com.teamuhi.superbenemies.entity.boss.common.ground

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.ai.isAirType
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Return fire: hit by something it cannot see (a sniper beyond sight), a tank shoots at an estimated spot near the attacker
 * for `returnFireSeconds` (off with `returnFireEnabled` false or 0 seconds), and the turret swings round toward it. One instance per brain.
 *
 * **Where.** A shooter the tank can name (its entity is known and alive) is where it really is, give or take
 * [KNOWN_SCATTER] blocks, and the spot follows it while the hits go on ([follow]). A shot with no known shooter is located by
 * the damage source ([TankBossEntity.lastHitOrigin]), off by a random horizontal error of [SCATTER] of the distance (between
 * [SCATTER_MIN] and [SCATTER_MAX] blocks) because the tank only knows roughly where it came from.
 *
 * **When.** Not started while a target is in sight (it keeps the guns), when the attacker is the held target (hidden or not:
 * the ordinary rules shell its last seen spot), for an aircraft (the pull-back retreat deals with those) or for a spot no gun
 * can elevate to. It ends when its time is up, the option is turned off (`returnFireEnabled` or 0 seconds), the boss withdraws, a target comes into sight, or
 * the attacker turns out to be the held target.
 *
 * The aim is a marker entity ([LastKnownLocation.spot][com.teamuhi.superbenemies.entity.boss.common.ai.LastKnownLocation.spot],
 * the same technique as the ghost of a hidden target) that the guns shoot at on purpose (`suppressing`, see [TankGunnery]).
 * All state is per instance; server thread only.
 *
 * @param isWithdrawn whether the boss is away rearming; return fire never runs then
 * @param onStart called with the chosen spot each time a return fire starts (the brain's debug log)
 */
class ReturnFire(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val gunnery: TankGunnery,
    private val targeting: TankTargeting,
    private val isWithdrawn: () -> Boolean,
    private val onStart: (Vec3) -> Unit
) {

    /** The estimated spot being shot at, valid until [until]; null when return fire is off. The brain points the turret at it. */
    var point: Vec3? = null
        private set

    private var until = 0

    /** Entity id of the vehicle the attacker was in, or -1 when unknown. */
    private var attackerId = -1

    /**
     * Call once per hit. Starts return fire at the shooter, if the rules in the class doc allow it.
     *
     * @param attacker the entity credited with the hit, if any; [aim] what the guns are on right now
     */
    fun start(attacker: Entity?, aim: Entity?) {
        val seconds = settings.returnFireSeconds.get()
        if (!settings.returnFireEnabled.get() || seconds <= 0 || isWithdrawn() || boss.isWreck) {
            boss.thought.think("returnfire.start") { "no return fire: off, withdrawn or wrecked" }
            return
        }
        if (aim != null && !targeting.hidden) {
            boss.thought.think("returnfire.start") { "no return fire: ${aim.name.string} is in sight" }
            return
        }
        val root = attacker?.rootVehicle
        val known = attacker != null && attacker.isAlive
        val origin = (if (known) root?.position() else boss.lastHitOrigin) ?: run {
            boss.thought.think("returnfire.start") { "no return fire: no idea where the hit came from" }
            return
        }
        if (root != null) {
            if (root.id == targeting.targetRootId()) {
                boss.thought.think("returnfire.start") { "no return fire: the attacker is the held target" }
                return
            }
            if (root is VehicleEntity && isAirType(root.vehicleType)) {
                boss.thought.think("returnfire.start") { "no return fire: the attacker is an aircraft" }
                return
            }
        }
        val scatter = if (known) KNOWN_SCATTER else (FlightMath.horizontalDistance(boss.position(), origin) * SCATTER)
            .coerceIn(SCATTER_MIN, SCATTER_MAX)
        val spot = scattered(origin, scatter)
        val ghost = ghostAt(spot) ?: return
        if (!gunnery.canReach(ghost, 0.0)) {
            boss.thought.think("returnfire.start") { "no return fire: no gun can elevate to the spot" }
            return
        }
        point = spot
        until = boss.tickCount + seconds * 20
        attackerId = root?.id ?: -1
        boss.thought.event("returnfire.start") {
            "shooting back at ${if (known) attacker!!.name.string else "an estimated spot"} (${spot.x.toInt()},${spot.z.toInt()}) for ${seconds}s"
        }
        onStart(spot)
    }

    /**
     * The marker the guns aim at while return fire is on, else null. Call every tick with what the guns are on ([aim], and
     * whether it is [hidden]) and the boss's tick count [now]; return fire is dropped here once it should end.
     */
    fun aim(aim: Entity?, hidden: Boolean, now: Int): Entity? {
        val spot = point ?: return null
        val tracked = attackerId >= 0 && aim != null && targeting.targetRootId() == attackerId
        if (!settings.returnFireEnabled.get() || settings.returnFireSeconds.get() <= 0 || now >= until || (aim != null && !hidden) || tracked ||
            isWithdrawn() || boss.isWreck
        ) {
            boss.thought.event("returnfire.end") {
                when {
                    now >= until -> "return fire over: time up"
                    aim != null && !hidden -> "return fire over: a target is in sight"
                    tracked -> "return fire over: the attacker is the held target now"
                    else -> "return fire over: off, withdrawn or wrecked"
                }
            }
            point = null
            return null
        }
        return ghostAt(spot)
    }

    /**
     * Moves a running return-fire spot to the refreshed fix [where] of [attacker] (the team call's), so the tank's own guns
     * and its teammates' shells go to the same place. Does nothing for another attacker than the one being shot back at.
     */
    fun follow(attacker: Entity, where: Vec3) {
        if (point != null && attackerId == attacker.rootVehicle.id) point = scattered(where, KNOWN_SCATTER)
    }

    /**
     * Joins a teammate's return fire: shoots at the exact spot [spot] the attacked tank is shooting at ([TankAlerts.Alert.firePoint]),
     * or at the place it reported for the enemy, so the whole group puts its shells on one place. [enemy] is the attacker if the
     * call named one. Call it for as long as the call is fresh; every call keeps the fire going for another `returnFireSeconds`.
     * The same rules as [start] apply, and it does not start when this tank has the enemy as its own target (the ordinary rules
     * shell its last seen spot then) or when the call came from an aircraft.
     */
    fun join(spot: Vec3, enemy: Entity?) {
        val seconds = settings.returnFireSeconds.get()
        if (!settings.returnFireEnabled.get() || seconds <= 0 || isWithdrawn() || boss.isWreck) return
        if (targeting.aim != null && !targeting.hidden) return
        val root = enemy?.rootVehicle
        if (root != null) {
            if (root.id == targeting.targetRootId()) return
            if (root is VehicleEntity && isAirType(root.vehicleType)) return
        }
        val id = root?.id ?: -1
        val current = point
        if (current != null && attackerId == id && FlightMath.horizontalDistance(current, spot) < JOIN_KEEP) {
            // The spot hardly moved: the ghost stays where it is, only the time runs on.
            until = boss.tickCount + seconds * 20
            return
        }
        val ghost = ghostAt(spot) ?: return
        if (!gunnery.canReach(ghost, 0.0)) {
            boss.thought.think("returnfire.join") { "not joining the teammate's fire: no gun can elevate to the spot" }
            return
        }
        point = spot
        until = boss.tickCount + seconds * 20
        attackerId = id
        boss.thought.event("returnfire.join") { "joining a teammate's fire at ${spot.x.toInt()},${spot.z.toInt()}" }
    }

    private fun ghostAt(pos: Vec3): Entity? = targeting.lastKnown.spot(pos)

    /** [origin] moved by a random horizontal error of up to [scatter] blocks, at the height of a body above the ground. */
    private fun scattered(origin: Vec3, scatter: Double): Vec3 {
        val random = boss.getRandom()
        val angle = random.nextDouble() * Math.PI * 2.0
        val radius = scatter * sqrt(random.nextDouble())
        return Vec3(origin.x + cos(angle) * radius, origin.y + HEIGHT, origin.z + sin(angle) * radius)
    }

    companion object {
        /** The estimated spot is off by this fraction of the distance to the shooter, clamped to the limits below (blocks). */
        const val SCATTER = 0.06
        const val SCATTER_MIN = 2.0
        const val SCATTER_MAX = 10.0

        /** The estimated spot is this far above the attacker's feet, about body height. */
        const val HEIGHT = 1.0

        /** Error (blocks) of the spot when the shooter is known by name, so its real position is. */
        const val KNOWN_SCATTER = 1.5

        /** A spot a teammate's call moved by less than this (blocks) is not a new spot to [join]. */
        const val JOIN_KEEP = 4.0
    }
}
