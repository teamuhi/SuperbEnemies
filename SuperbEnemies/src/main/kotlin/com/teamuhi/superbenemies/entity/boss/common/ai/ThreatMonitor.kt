package com.teamuhi.superbenemies.entity.boss.common.ai

import com.atsuishio.superbwarfare.entity.projectile.ITrackableProjectile
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Detects homing missiles that are tracking the boss and drives the flare response.
 *
 * A missile is a threat when it implements SBW's [ITrackableProjectile], its target UUID is the boss (or one of its crew),
 * it has not already been lured away by a flare (`isDistracted`), it is closing in, and its time to impact is below
 * the phase threshold (or it is within [PANIC_DISTANCE] blocks). The first time a missile qualifies the boss rolls
 * [BossVehicle.flareMissChance]: on a miss that missile is ignored for good ("the pilot did not notice").
 *
 * Reaction (flares only with [BossVehicle.defensiveFlares], off by default; the brain still breaks away): three flare bursts [FLARE_INTERVAL] ticks apart. Because a flare only distracts a missile that is within
 * 32 blocks and 60 degrees of its heading, a missile that is still undistracted inside [REINFORCE_DISTANCE] blocks gets
 * further single bursts (up to [MAX_EXTRA_BURSTS] per missile), so late flares still have a chance to seduce it.
 *
 * [tick] must run every boss tick, before `super.baseTick()`: SBW's `releaseDecoy` consumes `decoyInputDown` during
 * the vehicle tick. All state is per instance.
 */
class ThreatMonitor<T>(
    private val boss: T,
    /** Decoy bursts released per new missile: the planes throw three flares, a tank one smoke salvo. */
    private val bursts: Int = FLARE_BURSTS
) where T : VehicleEntity, T : BossVehicle {

    /**
     * Horizontal unit direction from the boss to the missile that most recently triggered a reaction (valid after
     * [tick] returned true). Null until the first threat.
     */
    var threatBearing: Vec3? = null
        private set

    /**
     * The missiles this boss has noticed and that are still on it: tracking the boss or its crew, closing in, not lured away
     * and not written off by the miss roll. Refreshed by every scan (every [SCAN_INTERVAL] ticks), so a consumer must check
     * that an entry is still alive. Read by the tank's missile shoot-down (see `ShootHomingMissiles`).
     */
    val inbound: List<Entity> get() = inboundMissiles
    private val inboundMissiles = ArrayList<Entity>()

    /** Missile UUID -> tick of the first reaction, or [IGNORED]. */
    private val handled = HashMap<UUID, Int>()
    private val extraBursts = HashMap<UUID, Int>()

    private var flaresLeft = 0
    private var nextFlareTick = 0
    private var lastScanTick = Int.MIN_VALUE / 2
    private var flareInputPending = false

    /**
     * @return true exactly once for each new threat (the tick on which the reaction is triggered), so the brain can
     *         switch to its evasive state. Flare release continues on the following ticks without further returns.
     */
    fun tick(): Boolean {
        val now = boss.tickCount
        if (boss.crippled || boss.isWreck) {
            flaresLeft = 0
            flareInputPending = false
            inboundMissiles.clear()
            return false
        }
        driveFlares(now)

        if (now - lastScanTick < SCAN_INTERVAL) return false
        lastScanTick = now
        return scan(now)
    }

    /** Applies a staged flare after the boss has queued its weapon shots for this tick. */
    fun applyFlareInput() {
        if (flareInputPending) {
            boss.decoyInputDown = true
            flareInputPending = false
        }
    }

    private fun scan(now: Int): Boolean {
        val position = boss.position()
        // Event-driven list of guided projectiles: empty (and free) unless somebody has fired a missile.
        val candidates = TrackedProjectiles.near(boss.level(), position, SCAN_RADIUS)
        inboundMissiles.clear()
        if (candidates.isEmpty()) {
            if (handled.isNotEmpty()) handled.clear()
            if (extraBursts.isNotEmpty()) extraBursts.clear()
            return false
        }

        val bossId = boss.stringUUID
        val threshold = leadTicks()
        val chance = boss.flareMissChance()
        val velocity = boss.deltaMovement

        val present = HashSet<UUID>()
        var newThreat = false
        var bestTime = Double.MAX_VALUE
        var bestBearing: Vec3? = null

        for (entity in candidates) {
            if (entity !is ITrackableProjectile) continue
            present.add(entity.uuid)
            if (entity.isDistracted()) continue

            val targetId = entity.getTargetUUID()
            if (targetId != bossId && !boss.isCrewUuid(targetId)) continue

            val last = handled[entity.uuid]
            if (last == IGNORED) continue

            val toBoss = position.subtract(entity.position())
            val distance = toBoss.length()
            if (distance < 1.0e-3) continue
            val closing = entity.deltaMovement.subtract(velocity).dot(toBoss.scale(1.0 / distance))
            if (closing <= MIN_CLOSING_SPEED) continue // not closing in: not a threat (yet)
            val timeToImpact = distance / closing

            if (last != null) {
                inboundMissiles.add(entity)
                reinforce(entity.uuid, distance)
                continue
            }

            if (timeToImpact >= threshold && distance >= PANIC_DISTANCE) continue

            // First time this missile qualifies: the pilot may simply not notice it.
            if (chance > 0.0 && boss.getRandom().nextDouble() < chance) {
                handled[entity.uuid] = IGNORED
                boss.thought.event("threats.scan") {
                    "missile not noticed (${"%.1f".format(timeToImpact / 20.0)}s out, ${distance.toInt()} blocks): ignored"
                }
                continue
            }

            handled[entity.uuid] = now
            inboundMissiles.add(entity)
            newThreat = true
            boss.thought.event("threats.scan") {
                "missile detected: impact in ${"%.1f".format(timeToImpact / 20.0)}s, ${distance.toInt()} blocks away"
            }
            if (timeToImpact < bestTime) {
                bestTime = timeToImpact
                bestBearing = bearingTo(entity, position)
            }
        }

        // Forget missiles that are gone (exploded, out of range, or lured away).
        handled.keys.retainAll(present)
        extraBursts.keys.retainAll(present)

        if (newThreat) {
            threatBearing = bestBearing ?: threatBearing
            if (boss.defensiveFlares()) {
                flaresLeft = bursts
                nextFlareTick = now
                boss.thought.event("threats.scan") { "decoy response: $bursts bursts queued" }
            } else {
                boss.thought.event("threats.scan") { "no decoys (defensive flares off), breaking away only" }
            }
        }
        return newThreat
    }

    /** A missile that was already answered but is still on the boss and undistracted: add late flare bursts. */
    private fun reinforce(id: UUID, distance: Double) {
        if (distance > REINFORCE_DISTANCE || flaresLeft > 0 || !boss.defensiveFlares()) return
        val used = extraBursts[id] ?: 0
        if (used >= MAX_EXTRA_BURSTS) return
        extraBursts[id] = used + 1
        flaresLeft = 1
        nextFlareTick = boss.tickCount
        boss.thought.event("threats.reinforce") { "missile still on us ${distance.toInt()} blocks out: late decoy burst ${used + 1}/$MAX_EXTRA_BURSTS" }
    }

    /** Releases one flare burst per [FLARE_INTERVAL] ticks while a response is pending. */
    private fun driveFlares(now: Int) {
        if (flaresLeft <= 0 || now < nextFlareTick) return
        if (boss.decoyCount < 1) {
            if (boss.limitedAmmo()) {
                boss.thought.think("threats.driveFlares") { "decoys empty, none released" }
                return
            }
            boss.decoyCount = boss.computed().decoyMagazineSize
        }
        flareInputPending = true
        flaresLeft--
        boss.thought.event("threats.driveFlares") { "decoy burst released, $flaresLeft left in this response" }
        nextFlareTick = now + FLARE_INTERVAL
    }

    /** Time-to-impact threshold in ticks: reacts earlier in the final phase, later in the opening one. */
    private fun leadTicks(): Int {
        val health = boss.healthFraction()
        return when {
            health > 0.6f -> 40
            health < 0.3f -> 70
            else -> 60
        }
    }

    private fun bearingTo(missile: Entity, from: Vec3): Vec3 {
        val away = missile.position().subtract(from)
        val flat = Vec3(away.x, 0.0, away.z)
        if (flat.lengthSqr() > 1.0e-4) return flat.normalize()
        // Missile directly above / below: fall back to the boss's own heading.
        val view = boss.getViewVector(1f)
        val viewFlat = Vec3(view.x, 0.0, view.z)
        return if (viewFlat.lengthSqr() > 1.0e-4) viewFlat.normalize() else Vec3(0.0, 0.0, 1.0)
    }

    private companion object {
        const val IGNORED = Int.MAX_VALUE
        const val SCAN_INTERVAL = 2
        const val SCAN_RADIUS = 160.0 // cube half-width, as the old AABB inflate
        const val PANIC_DISTANCE = 64.0
        const val MIN_CLOSING_SPEED = 0.05
        const val FLARE_BURSTS = 3
        const val FLARE_INTERVAL = 4
        const val REINFORCE_DISTANCE = 48.0
        const val MAX_EXTRA_BURSTS = 6
    }
}
