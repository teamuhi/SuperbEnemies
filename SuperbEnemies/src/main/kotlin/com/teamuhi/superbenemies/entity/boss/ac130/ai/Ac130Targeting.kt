package com.teamuhi.superbenemies.entity.boss.ac130.ai

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.config.Ac130Config
import com.teamuhi.superbenemies.config.TargetCategory
import com.teamuhi.superbenemies.config.TargetingMethod
import com.teamuhi.superbenemies.debug.AiVision
import com.teamuhi.superbenemies.entity.boss.ac130.Ac130BossEntity
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.ai.TargetChoice
import com.teamuhi.superbenemies.entity.boss.common.ai.LastKnownLocation
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedVehicles
import com.teamuhi.superbenemies.entity.boss.common.ai.categoryOf
import com.teamuhi.superbenemies.entity.boss.common.ai.dedupeByRootVehicle
import com.teamuhi.superbenemies.entity.boss.common.ai.isEnemyPlane
import com.teamuhi.superbenemies.entity.boss.common.ai.isEnemyTank
import com.teamuhi.superbenemies.entity.boss.common.ai.riddenBy
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import com.teamuhi.superbenemies.entity.dummy.TrackedDummies
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Target selection for the AC-130 boss. What it may attack is set by five weights (`playerWeight`, `mobWeight`,
 * `hostileMobWeight`, `planeWeight`, `tankWeight`, all re-read every tick, so setting one to 0 drops a target of that kind straight
 * away): 0 means never. `planeWeight` (experimental) hunts manned aircraft, see [isEnemyPlane], and `tankWeight`
 * (experimental) manned armour, see [isEnemyTank]; both follow the player range rule and the same choice rules.
 *
 * Unlike the A-10 the gunship does not care what kind of thing it shells, so animals and villagers count the same as
 * monsters, apart from their own weight. Rules, in priority order: the last thing that hurt the boss (if it is a valid
 * candidate), then the current target (unless a candidate scores better by more than [SWITCH_FACTOR] and the target has
 * been held for at least [MIN_DWELL_TICKS]), then the best-scoring candidate. The score is the horizontal distance
 * divided by the weight, so a weight-5 target counts as five times closer than a weight-1 one. The dwell time stops the
 * boss hopping between mobs while it circles: on a circle of radius r every mob near the ring is "closer" than the one
 * in the middle.
 *
 * Dummies (player dummy, dummy soldier) are a special case, controlled by their own `bossesTarget...` option (read live):
 * with it on a dummy is a player-class candidate (listed from the dummy tracker when the player weight allows it,
 * subject to the same range rule, and never added a second time through the mob query); with it off it is an ordinary
 * mob and follows the mob weights.
 *
 * With `randomTargeting` the choice above is replaced by a random draw every `randomTargetSeconds`, where each
 * candidate's chance is proportional to its weight (see [randomChoice]).
 *
 * `targetingMethod` decides what the boss knows. STATIC always knows exactly where the target is. DYNAMIC loses sight of
 * a target that has a cover block above it (see [LastKnownLocation]) and shells its last known position (the [aim] is then a
 * ghost) instead, while the real [target] still decides validity. It also prefers exposed targets: the candidate pool
 * is the exposed ones, or the hidden ones only when none is exposed, and a hidden target is dropped at once (ignoring
 * [MIN_DWELL_TICKS]) when an exposed candidate exists.
 *
 * The expensive part (the mob query) runs every [SCAN_INTERVAL] ticks, staggered by entity id; validity of the current
 * target is re-checked every tick with cheap field reads, and a lost or newly hidden target schedules a re-scan within
 * [RESCAN_AFTER_LOSS] ticks. All state is per instance.
 */
class Ac130Targeting(private val boss: Ac130BossEntity) {

    /** The chosen victim (player, mob or, with a plane weight, an aircraft). Null when nothing valid is around. */
    var target: Entity? = null
        private set

    /**
     * What the weapons aim at: the target itself, or the vehicle it rides. Under DYNAMIC, while the target is hidden, a
     * ghost at its last known position (see [LastKnownLocation]); validity and scans always use [target], never this.
     */
    var aim: Entity? = null
        private set

    /** Smoothed per-tick velocity of [aim], from position differences (server-side player velocity is unreliable). */
    var aimVelocity: Vec3 = Vec3.ZERO
        private set

    private var targetUuid: UUID? = null
    private var nextScanTick = 0
    private var initialScanDone = false
    private var lastSwitchTick = 0
    private var nextRerollTick = 0

    private var lastAimPosition: Vec3 = Vec3.ZERO
    private var trackedAimId = -1

    /** The ghost of a covered target and the remembered target positions; the brain reads [LastKnownLocation.anchor] from it. */
    val lastKnown = LastKnownLocation(boss, graceSeconds = { Ac130Config.SIGHT_LOSS_GRACE_SECONDS.get() })

    /** The weight of [entity]'s category; 0 for something that is no target at all. */
    private fun weightOf(entity: Entity): Int = Ac130Config.WEIGHTS.of(categoryOf(entity))

    // ================================================================================================== persistence

    fun save(tag: CompoundTag) {
        targetUuid?.let { tag.putUUID(TAG_TARGET, it) }
    }

    fun load(tag: CompoundTag) {
        targetUuid = if (tag.hasUUID(TAG_TARGET)) tag.getUUID(TAG_TARGET) else null
        target = null
        aim = null
    }

    // ================================================================================================== update

    /**
     * Call once per boss tick. [orbitRadius] is the radius the boss currently circles at; the mob query box is
     * widened by it, because the boss sits on the ring while its target is in the middle.
     */
    fun update(level: ServerLevel, orbitRadius: Double) {
        val now = boss.tickCount

        var current = target
        val remembered = targetUuid
        if (current == null && remembered != null) {
            current = level.getEntity(remembered)?.takeIf { stillValid(it) }
            target = current
        }
        if (current != null && !stillValid(current)) {
            val lost = current
            boss.thought.event("targeting.update") { "lost target ${lost.name.string}: no longer valid or out of reach" }
            drop()
            current = null
            // Look for a replacement soon instead of idling for up to a full scan interval.
            nextScanTick = minOf(nextScanTick, now + RESCAN_AFTER_LOSS)
        }

        if (!initialScanDone || now >= nextScanTick) {
            initialScanDone = true
            nextScanTick = nextAlignedScan(now)
            pickTarget(level, orbitRadius)
            current = target
        }

        val wasHidden = lastKnown.hidden
        aim = lastKnown.resolve(current?.rootVehicle, Ac130Config.TARGETING_METHOD.get())
        // The target just lost its sight line: look for an exposed one now instead of up to a full scan interval later.
        if (!wasHidden && lastKnown.hidden) nextScanTick = minOf(nextScanTick, now + RESCAN_AFTER_LOSS)
        trackAim(aim)
        if (boss.thought.enabled) {
            AiVision.report(boss, boss.thought, boss.eyePosition, current, aim, lastKnown.visionState(aim), lastKnown.graceLeftTicks)
        }
    }

    private fun drop() {
        target = null
        targetUuid = null
        aim = null
        trackedAimId = -1
        lastKnown.reset()
    }

    /** The next tick at which `(tick + id) % SCAN_INTERVAL == 0`, so bosses spread their scans over the interval. */
    private fun nextAlignedScan(now: Int): Int {
        val phase = Math.floorMod(now + boss.id, SCAN_INTERVAL)
        return now + (SCAN_INTERVAL - phase)
    }

    /** Re-scans and replaces the current target with another valid candidate when one is available. */
    fun snapToAnotherTarget(level: ServerLevel, orbitRadius: Double): Boolean {
        val previous = target
        pickTarget(level, orbitRadius, forceAlternate = true)
        boss.thought.event("targeting.snapToAnotherTarget") {
            if (target !== previous) "long burst: switched to ${target?.name?.string ?: "nobody"}" else "long burst: no other target to switch to"
        }
        return target !== previous
    }

    // ================================================================================================== validity

    /** Cheap per-tick check of the current target. Heavy conditions (sky exposure, scan box) are scan-only. */
    private fun stillValid(entity: Entity): Boolean {
        if (!entity.isAlive || entity.isRemoved || entity.level() !== boss.level()) return false
        if (entity is VehicleEntity) return validVehicle(entity, lose = true)
        if (entity is ServerPlayer) return validPlayer(entity, lose = true)
        if (entity is HumanoidDummyEntity && dummiesArePlayers(entity)) return validDummy(entity, lose = true)
        if (entity !is Mob) return false
        return weightOf(entity) > 0 && !entity.isInvulnerable && !boss.isCrew(entity) && entity.rootVehicle !== boss
    }

    private fun validPlayer(player: ServerPlayer, lose: Boolean): Boolean {
        if (!Ac130Config.WEIGHTS.allowsPlayers) return false
        if (!player.isAlive || player.isRemoved || player.isSpectator || player.isCreative) return false
        if (player.level() !== boss.level()) return false
        if (Ac130Config.GLOBAL_AGGRO.get()) return true
        val limit = Ac130Config.AGGRO_RANGE.get() * (if (lose) LOSE_TARGET_FACTOR else 1.0)
        val dx = player.x - boss.x
        val dz = player.z - boss.z
        return dx * dx + dz * dz <= limit * limit
    }

    /**
     * An aircraft for a plane weight above 0 (see [isEnemyPlane]) or armour for a tank weight above 0 (see
     * [isEnemyTank]) under the player range rule: [lose] widens it for a held target.
     */
    private fun validVehicle(vehicle: Entity, lose: Boolean): Boolean {
        val weights = Ac130Config.WEIGHTS
        val enemy = when (categoryOf(vehicle)) {
            TargetCategory.PLANE -> weights.allowsPlanes && isEnemyPlane(boss, vehicle)
            TargetCategory.TANK -> weights.allowsTanks && isEnemyTank(boss, vehicle)
            else -> false
        }
        if (!enemy) return false
        if (Ac130Config.GLOBAL_AGGRO.get()) return true
        val limit = Ac130Config.AGGRO_RANGE.get() * (if (lose) LOSE_TARGET_FACTOR else 1.0)
        val dx = vehicle.x - boss.x
        val dz = vehicle.z - boss.z
        return dx * dx + dz * dz <= limit * limit
    }

    private fun dummiesArePlayers(dummy: HumanoidDummyEntity): Boolean = dummy.settings.targetedByBosses()

    /** A dummy treated as a player: the player-class rules without the creative / spectator checks, same range rule. */
    private fun validDummy(dummy: HumanoidDummyEntity, lose: Boolean): Boolean {
        if (!Ac130Config.WEIGHTS.allowsPlayers) return false
        if (!dummy.isAlive || dummy.isRemoved || dummy.level() !== boss.level()) return false
        if (Ac130Config.GLOBAL_AGGRO.get()) return true
        val limit = Ac130Config.AGGRO_RANGE.get() * (if (lose) LOSE_TARGET_FACTOR else 1.0)
        val dx = dummy.x - boss.x
        val dz = dummy.z - boss.z
        return dx * dx + dz * dz <= limit * limit
    }

    /**
     * A mob worth shelling: alive, attackable, not one of the boss's own crew, and of a category with a weight above 0
     * (so a hostile mob weight of 0 skips zombies while a mob weight above 0 still keeps cows). Shells ignore block cover.
     */
    private fun eligibleMob(mob: Mob): Boolean {
        if (!mob.isAlive || mob.isRemoved || !mob.isAttackable || mob.isInvulnerable) return false
        if (weightOf(mob) <= 0) return false
        if (mob is HumanoidDummyEntity && dummiesArePlayers(mob)) return false // handled as a player-class candidate
        if (mob is BossCrewEntity || mob.rootVehicle === boss || boss.isCrew(mob)) return false
        return true
    }

    // ================================================================================================== choice

    private fun pickTarget(level: ServerLevel, orbitRadius: Double, forceAlternate: Boolean = false) {
        val now = boss.tickCount
        val bossPos = boss.position()
        val global = Ac130Config.GLOBAL_AGGRO.get()
        val aggro = Ac130Config.AGGRO_RANGE.get().toDouble()
        val weights = Ac130Config.WEIGHTS
        val method = Ac130Config.TARGETING_METHOD.get()

        val rawCandidates = ArrayList<Entity>()
        if (weights.allowsPlayers) {
            for (player in level.players()) {
                if (validPlayer(player, lose = false) &&
                    (global || FlightMath.horizontalDistance(player.position(), bossPos) <= aggro)
                ) {
                    rawCandidates.add(player)
                }
            }
        }
        if (weights.allowsPlayers) {
            for (dummy in TrackedDummies.all(level)) {
                if (dummy is HumanoidDummyEntity && dummiesArePlayers(dummy) && validDummy(dummy, lose = false) &&
                    (global || FlightMath.horizontalDistance(dummy.position(), bossPos) <= aggro)
                ) {
                    rawCandidates.add(dummy)
                }
            }
        }
        if (weights.allowsVehicles) {
            for (vehicle in TrackedVehicles.all(level)) {
                if (validVehicle(vehicle, lose = false) && (global || FlightMath.horizontalDistance(vehicle.position(), bossPos) <= aggro)) {
                    rawCandidates.add(vehicle)
                }
            }
        }
        if (weights.allowsMobs) {
            val reach = Ac130Config.MOB_SCAN_RANGE.get() + orbitRadius.coerceAtLeast(0.0)
            val box = AABB(
                boss.x - reach, level.minBuildHeight.toDouble(), boss.z - reach,
                boss.x + reach, level.maxBuildHeight.toDouble(), boss.z + reach
            )
            rawCandidates.addAll(level.getEntitiesOfClass(Mob::class.java, box) { eligibleMob(it) })
        }

        // A player slightly beyond the aggro range keeps its lock until the (wider) lose range.
        val rawHeld = target?.takeIf { t ->
            rawCandidates.any { it === t } || (t is ServerPlayer && validPlayer(t, lose = true)) ||
                    (t is HumanoidDummyEntity && dummiesArePlayers(t) && validDummy(t, lose = true)) ||
                    (t is VehicleEntity && validVehicle(t, lose = true))
        }
        if (rawHeld != null && rawCandidates.none { it === rawHeld }) rawCandidates.add(rawHeld)

        // A player riding a vehicle is listed twice (player and vehicle): keep the one with the higher weight. A held
        // target that lost that contest is replaced by the entry of its vehicle, so the lock is not dropped by it.
        val candidates = dedupeByRootVehicle(rawCandidates, this::weightOf)
        val held = rawHeld?.let { h -> candidates.firstOrNull { it === h } ?: candidates.firstOrNull { it.rootVehicle === h.rootVehicle } }

        fun distance(entity: Entity) = FlightMath.horizontalDistance(entity.position(), bossPos)
        fun score(entity: Entity) = TargetChoice.score(distance(entity), weightOf(entity))

        // DYNAMIC prefers exposed targets: hidden ones only count when nothing is exposed. A held target that is hidden
        // while an exposed candidate exists is dropped from `held`, so it is replaced now regardless of the dwell time.
        var pool: List<Entity> = candidates
        var effectiveHeld = held
        var hiddenCount = 0
        if (method == TargetingMethod.DYNAMIC) {
            val (hidden, exposed) = candidates.partition { lastKnown.isHidden(it, method) }
            hiddenCount = hidden.size
            pool = exposed.ifEmpty { hidden }
            if (effectiveHeld != null && exposed.isNotEmpty() && hidden.any { it === effectiveHeld }) effectiveHeld = null
        }

        val attackerId: String? = boss.lastAttackerUUID
        val attacker = if (attackerId.isNullOrEmpty()) null else pool.firstOrNull { riddenBy(it, attackerId) }
        val best = TargetChoice.bestScore(pool, this::weightOf, ::distance)

        val chosen = if (forceAlternate) {
            val alternates = pool
                .filter { it !== target && it.rootVehicle !== target?.rootVehicle }
            TargetChoice.weightedRandom(alternates, this::weightOf, boss.getRandom()) ?: target
        } else if (Ac130Config.RANDOM_TARGETING.get()) {
            randomChoice(pool, effectiveHeld, now)
        } else {
            attacker ?: when {
                effectiveHeld != null && best != null && best !== effectiveHeld &&
                        now - lastSwitchTick >= MIN_DWELL_TICKS &&
                        score(best) < score(effectiveHeld) * SWITCH_FACTOR -> best

                effectiveHeld != null -> effectiveHeld
                else -> best
            }
        }

        val why = when {
            forceAlternate -> "alternate target for a long burst"
            Ac130Config.RANDOM_TARGETING.get() ->
                if (chosen != null && chosen === effectiveHeld) "held, redraw not due" else "random draw by weight"
            attacker != null && chosen === attacker -> "most recent attacker"
            chosen != null && chosen === effectiveHeld ->
                if (chosen === best) "held, still the best score" else "held target"
            chosen != null && chosen === best ->
                if (effectiveHeld != null) "better score than the held target" else "best weighted distance"
            else -> "no candidate"
        }
        boss.thought.think("targeting.pickTarget") {
            if (chosen == null) "no target in reach" else "target ${chosen.name.string}: $why"
        }
        if (chosen !== target) {
            trackedAimId = -1 // new target: forget the old motion estimate
            lastSwitchTick = now
            boss.thought.event("targeting.pickTarget") {
                "target ${target?.name?.string ?: "none"} -> ${chosen?.name?.string ?: "none"} ($why; ${candidates.size} candidates, $hiddenCount hidden)"
            }
        }
        target = chosen
        targetUuid = chosen?.uuid
    }

    /**
     * `randomTargeting`: the held target stays until [nextRerollTick], then a random one is drawn from [pool] (it may be
     * the same), each candidate with a chance proportional to its weight. The last-attacker and best-score rules are not
     * used.
     */
    private fun randomChoice(pool: List<Entity>, held: Entity?, now: Int): Entity? {
        if (held != null && now < nextRerollTick) return held
        if (pool.isEmpty()) return null
        val seconds = Ac130Config.RANDOM_TARGET_SECONDS.get()
        nextRerollTick = now + (seconds * 20 * (0.75 + 0.5 * boss.getRandom().nextDouble())).toInt()
        return TargetChoice.weightedRandom(pool, this::weightOf, boss.getRandom())
    }

    /**
     * Up to [max] valid targets other than [primary] within [radius] blocks (horizontal) of it, best score first (distance
     * divided by weight), each as what the weapons aim at (the vehicle it rides). Used by the split fire pattern; follows
     * the weights and the same eligibility rules as the main target, but ignores the aggro range (they are next to a
     * target already held). Under DYNAMIC, hidden extras are skipped: the boss cannot see them to give them a gun.
     */
    fun extraTargets(level: ServerLevel, primary: Entity, radius: Double, max: Int): List<Entity> {
        val weights = Ac130Config.WEIGHTS
        val method = Ac130Config.TARGETING_METHOD.get()
        val found = ArrayList<Entity>()
        if (weights.allowsPlayers) {
            for (player in level.players()) if (validPlayer(player, lose = true)) found.add(player)
            for (dummy in TrackedDummies.all(level)) {
                if (dummy is HumanoidDummyEntity && dummiesArePlayers(dummy) && validDummy(dummy, lose = true)) found.add(dummy)
            }
        }
        if (weights.allowsVehicles) {
            for (vehicle in TrackedVehicles.all(level)) if (validVehicle(vehicle, lose = true)) found.add(vehicle)
        }
        if (weights.allowsMobs) {
            val box = AABB(
                primary.x - radius, level.minBuildHeight.toDouble(), primary.z - radius,
                primary.x + radius, level.maxBuildHeight.toDouble(), primary.z + radius
            )
            found.addAll(level.getEntitiesOfClass(Mob::class.java, box) { eligibleMob(it) })
        }
        val origin = primary.position()
        return found.asSequence()
            .filter { !lastKnown.isHidden(it, method) }
            .map { it.rootVehicle to weightOf(it) }
            .filter { (root, _) -> root.id != primary.id && FlightMath.horizontalDistance(root.position(), origin) <= radius }
            // Best score first, then one entry per vehicle: a rider and its vehicle both qualify, the higher weight wins.
            .sortedBy { (root, weight) -> TargetChoice.score(FlightMath.horizontalDistance(root.position(), origin), weight) }
            .distinctBy { (root, _) -> root.id }
            .take(max)
            .map { (root, _) -> root }
            .toList()
    }

    // ================================================================================================== motion estimate

    private fun trackAim(entity: Entity?) {
        if (entity == null) {
            trackedAimId = -1
            aimVelocity = Vec3.ZERO
            return
        }
        val position = entity.position()
        if (entity.id != trackedAimId) {
            trackedAimId = entity.id
            lastAimPosition = position
            aimVelocity = Vec3.ZERO
            return
        }
        val delta = position.subtract(lastAimPosition)
        lastAimPosition = position
        // A teleport or respawn is not motion.
        aimVelocity = if (delta.lengthSqr() > TELEPORT_DISTANCE_SQR) Vec3.ZERO
        else aimVelocity.scale(1.0 - VELOCITY_SMOOTHING).add(delta.scale(VELOCITY_SMOOTHING))
    }

    internal companion object {
        const val TAG_TARGET = "Target"

        const val SCAN_INTERVAL = 20
        const val RESCAN_AFTER_LOSS = 5
        const val LOSE_TARGET_FACTOR = 1.5
        const val SWITCH_FACTOR = 0.7 // a candidate must be at least 30% nearer to steal the lock
        const val MIN_DWELL_TICKS = 200
        const val VELOCITY_SMOOTHING = 0.3
        const val TELEPORT_DISTANCE_SQR = 20.0 * 20.0
    }
}
