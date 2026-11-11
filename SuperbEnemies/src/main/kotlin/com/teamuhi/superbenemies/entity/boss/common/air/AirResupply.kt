package com.teamuhi.superbenemies.entity.boss.common.air

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.entity.boss.common.BossRespawns
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.min

/**
 * The rearm trip of an aircraft boss, shared by the A-10 and the AC-130: the idle roll that sends a boss with spent
 * weapons home when it has had nothing to fight for a while, and the RESUPPLY flight itself. One per brain; what differs
 * between the planes (config options, ranges, messages) comes in through [Settings], and the brain's own flight helpers
 * and state machine through [Hooks].
 *
 * The brain calls [countIdle] every tick, [rollIdle] while it loiters with no target, [reset] when it enters RESUPPLY
 * and [tick] while it is in that state.
 */
class AirResupply<B>(
    private val boss: B,
    private val settings: Settings,
    private val hooks: Hooks
) where B : VehicleEntity, B : BossVehicle {

    /** The per-plane values: the config options (read live, hence the suppliers) and the few numbers that differ. */
    data class Settings(
        val limitedAmmo: () -> Boolean,
        val idleResupplySeconds: () -> Int,
        val idleResupplyChance: () -> Int,
        val resupplySeconds: () -> Int,
        val globalAggro: () -> Boolean,
        val aggroRange: () -> Int,
        val bossBarRange: () -> Int,
        /** A target farther than aggroRange x this is dropped by the plane's targeting. */
        val loseTargetFactor: Double,
        /** Half width of the area the replacement boss needs clear around its spawn point. */
        val clearanceHalfWidth: Double,
        /** Steepest climb (degrees) flown on the way to the depot. */
        val maxClimb: Double,
        /** Translation key of the message sent when the replacement is on the hunt again. */
        val huntingMessage: String
    )

    /** What the brain provides: its state, targeting and flight helpers. */
    interface Hooks {
        val homePos: Vec3
        val aim: Entity?
        val stateTicks: Int

        /** Where the fight was when there is no target: the last known position, else whatever else the plane circles. */
        fun fallbackAnchor(): Vec3?

        /** True when any weapon has lost rounds (a full load never triggers the idle resupply). */
        fun anySpent(): Boolean
        fun fly(dir: Vec3, throttle: Float)
        fun cruiseAltitude(): Double
        fun awayFrom(entity: Entity): Vec3
        fun currentHeading(): Vec3
        fun towards(point: Vec3): Vec3

        /** Switches the brain to its RESUPPLY state. */
        fun enterResupply()
    }

    /** Ticks spent in INGRESS with nothing to fight (resets otherwise); drives the idle resupply roll. */
    private var idleTicks = 0

    private var depot: Vec3? = null

    /** Where the fight was when the boss left to rearm; its replacement appears around here. */
    private var resupplyFrom: Vec3? = null

    /** Counts a tick of [idle] (in INGRESS with no target), or starts over when the boss is not idle. */
    fun countIdle(idle: Boolean) {
        idleTicks = if (idle) idleTicks + 1 else 0
    }

    /** Forgets the depot: the next [tick] picks a new one (the brain just entered RESUPPLY). */
    fun reset() {
        depot = null
    }

    /**
     * Finite ammo, nobody to fight for `idleResupplySeconds` and something spent: every [IDLE_ROLL_INTERVAL] ticks there
     * is an `idleResupplyChance` percent chance to leave and rearm (the normal withdraw-and-return flow). A full load
     * never triggers it.
     */
    fun rollIdle() {
        val seconds = settings.idleResupplySeconds()
        if (seconds <= 0 || !settings.limitedAmmo() || idleTicks < seconds * 20) return
        if (idleTicks % IDLE_ROLL_INTERVAL != 0 || !hooks.anySpent()) return
        val roll = boss.getRandom().nextInt(100)
        val chance = settings.idleResupplyChance()
        boss.thought.event("resupply.rollIdle") {
            if (roll < chance) "idle roll $roll < $chance%: leaving to rearm" else "idle roll $roll >= $chance%: staying"
        }
        if (roll < chance) hooks.enterResupply()
    }

    /**
     * Out of ammunition: climb and fly to a point [RESUPPLY_DISTANCE] blocks beyond the target (away from where the
     * fight was). Once it has arrived, or no player can see it any more, the boss is removed and a replacement with the
     * same health and a full load appears around the fight after `resupplySeconds` (see [BossRespawns]), which saves
     * keeping a far-away plane loaded and ticking.
     */
    fun tick() {
        val aim = hooks.aim
        val departing = depot == null
        val dest = depot ?: run {
            val base = aim?.position() ?: boss.position()
            // With no target (idle resupply) the replacement still comes back to the area the boss was circling.
            resupplyFrom = aim?.position() ?: hooks.fallbackAnchor() ?: base
            val away = aim?.let { hooks.awayFrom(it) } ?: hooks.currentHeading()
            base.add(away.scale(resupplyDistance()))
        }.also { depot = it }
        if (departing) boss.thought.event("resupply.tick") { "departing for the depot at ${dest.x.toInt()}, ${dest.z.toInt()}, to rearm" }
        boss.thought.think("resupply.tick") { "flying to the depot to rearm" }
        val altitude = hooks.cruiseAltitude() + RESUPPLY_EXTRA_HEIGHT

        val altitudeError = altitude - boss.y
        hooks.fly(FlightMath.tilted(hooks.towards(dest), (altitudeError * 0.1).coerceIn(-4.0, settings.maxClimb)), 2.6f)
        val arrived = FlightMath.horizontalDistance(boss.position(), dest) < RESUPPLY_ARRIVE_DISTANCE
        val stateTicks = hooks.stateTicks
        val unseen = stateTicks > RESUPPLY_MIN_VISIBLE_TICKS &&
                !BossRespawns.playerWithin(boss, settings.bossBarRange() + RESUPPLY_UNSEEN_MARGIN)
        if (arrived || unseen || stateTicks > RESUPPLY_TRAVEL_TIMEOUT) {
            boss.thought.event("resupply.tick") {
                "withdrawing to rearm: " + when {
                    arrived -> "reached the depot"
                    unseen -> "no player in sight"
                    else -> "trip timed out"
                }
            }
            BossRespawns.withdraw(
                boss, settings.huntingMessage, aim?.position() ?: resupplyFrom ?: hooks.homePos, hooks.homePos,
                settings.resupplySeconds() * 20L, settings.clearanceHalfWidth
            )
        }
    }

    /**
     * How far beyond the target the depot lies. Without `globalAggro` a target farther than aggroRange x
     * [Settings.loseTargetFactor] is dropped, so the depot stays inside that (the boss then comes back to the same
     * player).
     */
    private fun resupplyDistance(): Double {
        if (settings.globalAggro()) return RESUPPLY_DISTANCE
        val keep = settings.aggroRange() * settings.loseTargetFactor * RESUPPLY_KEEP_TARGET_FRACTION
        return min(RESUPPLY_DISTANCE, keep)
    }

    private companion object {
        const val RESUPPLY_DISTANCE = 600.0

        /** Without globalAggro the depot is at most this fraction of the lose-target distance from the target. */
        const val RESUPPLY_KEEP_TARGET_FRACTION = 0.75
        const val RESUPPLY_EXTRA_HEIGHT = 60.0
        const val RESUPPLY_ARRIVE_DISTANCE = 100.0
        const val RESUPPLY_TRAVEL_TIMEOUT = 2400

        /** The boss only vanishes this long after leaving, and once no player is within the bar range plus the margin. */
        const val RESUPPLY_MIN_VISIBLE_TICKS = 100
        const val RESUPPLY_UNSEEN_MARGIN = 64.0

        /** Ticks between the idle resupply rolls once the boss has been idle long enough. */
        const val IDLE_ROLL_INTERVAL = 200
    }
}
