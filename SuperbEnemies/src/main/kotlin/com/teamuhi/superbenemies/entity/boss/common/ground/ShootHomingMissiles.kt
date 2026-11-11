package com.teamuhi.superbenemies.entity.boss.common.ground

import com.atsuishio.superbwarfare.entity.projectile.DestroyableProjectile
import com.atsuishio.superbwarfare.entity.projectile.ITrackableProjectile
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.ai.ThreatMonitor
import net.minecraft.world.entity.Entity
import java.util.UUID

/**
 * Hard-kill answer to an incoming guided missile, paired with the smoke salvo ([SmokeLauncher]): the shared [ThreatMonitor]
 * notices the missile and pops the smoke, and this class turns the tank's machine guns on the same missile to shoot it down.
 * Both run side by side; whichever works first saves the tank (smoke lures the missile away, or the rounds destroy it).
 *
 * **Which missiles.** Only the ones the monitor reports as [ThreatMonitor.inbound]: tracking this tank (or its crew), closing in,
 * not lured away and not written off by the monitor's own "not noticed" roll. The nearest one that a machine gun can be laid
 * on is engaged. A missile closer than [MIN_RANGE] blocks is left alone: the guns could not stop it in time, and a warhead
 * destroyed that close would go off on the hull.
 *
 * **Which guns.** Every enabled machine gun with rounds left and a crew member in its seat (the coaxial gun, and the weapon
 * station of the M1A2 / Ztz-99A). While this runs the turret and the station follow the missile and the cannon stays quiet;
 * [tick] returns true on such a tick so the brain skips its normal aiming. A barrel held on the tank's own guided missile is
 * never taken over (see [TankGunnery.fireAtMissile]).
 *
 * **Outcome.** The first time a missile is engaged it is rolled once: `shootDownFailPercent` percent of the missiles are
 * *failures* (the guns still track and fire, the missile is not stopped). A missile that passed the roll is destroyed
 * [KILL_DELAY] ticks after the first round left a barrel at it, provided it is still [MIN_RANGE] blocks or more away by then
 * (the guns can also be too slow to bring to bear, and then the missile gets through); it blows up where it is.
 *
 * One instance per brain; all state is per instance.
 */
class ShootHomingMissiles(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val gunnery: TankGunnery,
    private val threats: ThreatMonitor<*>
) {

    /** The roll and progress of the shoot-down of one missile. */
    private class Engagement(
        /** False when the roll said this missile gets through (`shootDownFailPercent`). */
        val stoppable: Boolean,
        /** Tick at which the missile is destroyed, or -1 until the first round has been fired at it. */
        var killTick: Int = -1
    )

    private val engagements = HashMap<UUID, Engagement>()

    /**
     * Runs the defence for this tick. Call it before the brain's own gunnery tick.
     *
     * @return true when the machine guns were used on a missile this tick, so the brain must not aim the guns elsewhere
     */
    fun tick(): Boolean {
        if (!settings.shootDownMissiles.get() || boss.crippled || boss.isWreck) {
            if (engagements.isNotEmpty()) engagements.clear()
            return false
        }
        val inbound = threats.inbound
        if (inbound.isEmpty()) {
            if (engagements.isNotEmpty()) engagements.clear()
            return false
        }
        // Forget the missiles that are gone (shot down, exploded, lured away).
        if (engagements.isNotEmpty()) engagements.keys.retainAll { id -> inbound.any { it.uuid == id } }

        val missile = nearestEngageable(inbound)
        if (missile == null) {
            boss.thought.think("shootDown") { "${inbound.size} missile(s) inbound, none in reach of a machine gun" }
            return false
        }
        val now = boss.tickCount
        val engagement = engagements.getOrPut(missile.uuid) { roll(missile) }

        val fired = gunnery.fireAtMissile(missile)
        if (fired && engagement.stoppable && engagement.killTick < 0) engagement.killTick = now + KILL_DELAY
        if (engagement.stoppable && engagement.killTick in 0..now) finish(missile)
        return true
    }

    /** The closest inbound missile that is still a live, un-distracted threat at least [MIN_RANGE] away and that a machine gun can be laid on. */
    private fun nearestEngageable(inbound: List<Entity>): Entity? {
        val position = boss.position()
        var best: Entity? = null
        var bestDistance = Double.MAX_VALUE
        for (candidate in inbound) {
            if (!candidate.isAlive || candidate.isRemoved) continue
            if (candidate is ITrackableProjectile && candidate.isDistracted()) continue // the smoke worked
            if (candidate is DestroyableProjectile && candidate.health <= 0f) continue // already going off
            val distance = position.distanceTo(candidate.position())
            if (distance < MIN_RANGE || distance >= bestDistance) continue
            if (!gunnery.canShootDown(candidate)) continue
            best = candidate
            bestDistance = distance
        }
        return best
    }

    private fun roll(missile: Entity): Engagement {
        val failPercent = settings.shootDownFailPercent.get()
        val stoppable = boss.getRandom().nextInt(100) >= failPercent
        boss.thought.event("shootDown") {
            "engaging a missile ${boss.position().distanceTo(missile.position()).toInt()} blocks out with the machine guns: " +
                    if (stoppable) "the gunners will hit it" else "the gunners will miss it ($failPercent% fail chance)"
        }
        return Engagement(stoppable)
    }

    /** The rounds have arrived: destroys [missile] where it is, unless it came too close in the meantime. */
    private fun finish(missile: Entity) {
        if (!missile.isAlive || missile.isRemoved) return
        val distance = boss.position().distanceTo(missile.position())
        if (distance < MIN_RANGE) {
            boss.thought.event("shootDown") { "too late: the missile is already ${distance.toInt()} blocks away, it gets through" }
            engagements[missile.uuid] = Engagement(stoppable = false)
            return
        }
        boss.thought.event("shootDown") { "missile shot down ${distance.toInt()} blocks out" }
        // A guided missile has health and blows up when it reaches zero; anything else is simply removed.
        if (missile is DestroyableProjectile) missile.health = 0f else missile.discard()
    }

    private companion object {
        /** Ticks between the first round fired at a missile and its destruction (the rounds' flight and the burst needed to hit it). */
        const val KILL_DELAY = 6

        /** Blocks. Closer than this the missile is not engaged and is no longer destroyed: its warhead would go off on the hull. */
        const val MIN_RANGE = 16.0
    }
}
