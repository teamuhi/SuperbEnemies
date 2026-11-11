package com.teamuhi.superbenemies.entity.boss.common.ground

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.config.DroneConfig
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.config.TargetCategory
import com.teamuhi.superbenemies.config.TargetingMethod
import com.teamuhi.superbenemies.debug.AiVision
import com.teamuhi.superbenemies.debug.VisionCone
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
import com.teamuhi.superbenemies.entity.drone.DroneEntity
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
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Target selection of the tank bosses, modelled on the AC-130's `Ac130Targeting`: five weights (`playerWeight`,
 * `mobWeight`, `hostileMobWeight`, `planeWeight`, `tankWeight`, re-read every tick, 0 = never), the same choice rules (the
 * last thing that hurt the boss first, then the held target unless a candidate scores better by more than
 * [SWITCH_FACTOR] after [MIN_DWELL_TICKS], then the best `distance / weight` score), the same random targeting, and the
 * same [LastKnownLocation] ghosts. What differs is how a tank knows where its target is:
 *
 *  - **STATIC**: every candidate inside the aggro range is known, wherever it is; nothing is ever hidden.
 *  - **DYNAMIC**: a tank only knows what it sees. Sight is a ray from the turret ([TankSight]) within `sightRange`; blocks
 *    and smoke block it. A new target is picked among the visible candidates only, and a held target that drops out of
 *    sight turns into a ghost at its last seen spot ([aim] with [hidden] true) while the real [target] still decides
 *    validity. The brain then searches; [giveUp] ends that. The one thing that can name an unseen target is the boss being
 *    shot (the attacker-first rule, for [ATTACKER_MEMORY_TICKS] after the hit): it knows roughly where the shot came from.
 *    A target that stays hidden for `breakDetectionSeconds` is lost ([breakDetection]): it is no candidate any more, even
 *    when it steps back into view, until it comes within `closeDetectionRange` or shoots the boss ([isLost]).
 *
 * Sight is always judged on the root vehicle of a candidate (a player riding a tank is seen when the tank is). Under
 * DYNAMIC, a **sight cone** around the turret's heading (`sightConeDegrees` wide) makes the boss see `sightConeRangeMultiplier`
 * times farther inside it (sight range and the reach of the candidate scan alike); outside the cone, and under STATIC,
 * nothing changes. The range is worked out inside the lambda given to [LastKnownLocation], so the shared class needs no change.
 *
 * Aircraft are only candidates while some gun can elevate to them ([canReach]; a held one gets [REACH_MARGIN] degrees of
 * slack). Heavy work (the mob query, the sight rays of every candidate) runs every [SCAN_INTERVAL] ticks, staggered by
 * entity id; validity of the held target is re-checked every tick with cheap reads. All state is per instance.
 *
 * @param canReach whether some weapon can elevate to an entity (second argument: extra degrees of slack)
 */
class TankTargeting(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val canReach: (Entity, Double) -> Boolean
) {

    /** The chosen victim (player, mob, aircraft or armour). Null when nothing valid is around. */
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

    /** Entity id of the vehicle (or the entity itself) the held target stands in, or -1 without a target. */
    fun targetRootId(): Int = target?.rootVehicle?.id ?: -1

    /** True while [aim] is a ghost: the target is out of sight (DYNAMIC only). */
    val hidden: Boolean
        get() = lastKnown.hidden

    private var targetUuid: UUID? = null
    private var nextScanTick = 0
    private var initialScanDone = false
    private var lastSwitchTick = 0
    private var nextRerollTick = 0

    private var lastAimPosition: Vec3 = Vec3.ZERO
    private var trackedAimId = -1

    private var ignoredId = -1
    private var ignoreUntil = 0

    /** While `tickCount` is below this, a target a teammate called out is not lost for being out of sight (see [inform]). */
    private var informedUntil = 0

    /** Entity id of the vehicle (or entity) the call was about; only meaningful while [informedUntil] has not passed. */
    private var informedRootId = -1

    /** Ids of the vehicles / entities the boss lost track of (see [breakDetection]); they stay lost until revealed. */
    private val lost = HashSet<Int>()
    private var hiddenSince = -1
    private var lastHitSeen = -1

    private var secondary: Entity? = null
    private var nextSecondaryScan = 0

    // Sight is always judged on the root vehicle: a player riding a tank is seen when the tank is, and what is tested is
    // the same box whichever of the two the candidate list holds.
    // The brain also takes its return-fire marker from it ([LastKnownLocation.spot]).
    val lastKnown = LastKnownLocation(
        boss,
        sees = { entity ->
            val root = entity.rootVehicle
            TankSight.canSee(boss, root, sightRangeFor(root))
        },
        graceSeconds = { settings.sightLossGraceSeconds.get() }
    )

    private var coneTick = Int.MIN_VALUE
    private var coneForward: Vec3 = Vec3.ZERO

    /** Whether the sight cone is in use: DYNAMIC targeting, a cone wider than 0 degrees and a multiplier above 1. */
    private fun coneOn(): Boolean =
        settings.targetingMethod.get() == TargetingMethod.DYNAMIC && settings.sightConeDegrees.get() > 0 &&
                settings.sightConeRangeMultiplier.get() > 1.0

    /** The horizontal unit direction the turret points along, read once per tick; the hull's heading when the turret points straight up. */
    private fun turretForward(): Vec3 {
        val now = boss.tickCount
        if (now != coneTick) {
            coneTick = now
            val barrel = boss.getBarrelVector(1f)
            val length = sqrt(barrel.x * barrel.x + barrel.z * barrel.z)
            coneForward = if (length > CONE_MIN_LENGTH && length.isFinite()) Vec3(barrel.x / length, 0.0, barrel.z / length)
            else FlightMath.dir(boss.yRot, 0f)
        }
        return coneForward
    }

    /** Whether the root vehicle of [entity] lies inside the sight cone: within half the cone width of the turret's heading. */
    private fun inCone(entity: Entity): Boolean {
        val centre = entity.rootVehicle.boundingBox.center
        val dx = centre.x - boss.x
        val dz = centre.z - boss.z
        val length = sqrt(dx * dx + dz * dz)
        if (length < 1.0) return true
        val forward = turretForward()
        val cosine = (forward.x * dx + forward.z * dz) / length
        return cosine >= cos(Math.toRadians(settings.sightConeDegrees.get() / 2.0))
    }

    /** How far the boss sees [entity]: `sightRange`, times `sightConeRangeMultiplier` while it is inside the cone. */
    private fun sightRangeFor(entity: Entity): Double {
        val range = settings.sightRange.get().toDouble()
        return if (coneOn() && inCone(entity)) range * settings.sightConeRangeMultiplier.get() else range
    }

    /** The weight of [entity]'s category; 0 for something that is no target at all. */
    private fun weightOf(entity: Entity): Int =
        if (entity is DroneEntity) DroneConfig.TANK_DRONE_WEIGHT.get() else settings.weights.of(categoryOf(entity))

    /** True when soft targets other than players may be shot: a mob weight, or tanks being set to shoot drones. */
    private fun allowsMobsOrDrones(): Boolean = settings.weights.allowsMobs || DroneConfig.TANK_DRONE_WEIGHT.get() > 0

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

    /** Call once per boss tick. */
    fun update(level: ServerLevel) {
        val now = boss.tickCount

        var current = target
        val remembered = targetUuid
        if (current == null && remembered != null) {
            current = level.getEntity(remembered)?.takeIf { stillValid(it) }
            target = current
        }
        if (current != null && !stillValid(current)) {
            val dropped = current
            boss.thought.event("targeting.update") { "dropped ${dropped.name.string}: no longer a valid target" }
            drop()
            current = null
            // Look for a replacement soon instead of idling for up to a full scan interval.
            nextScanTick = minOf(nextScanTick, now + RESCAN_AFTER_LOSS)
        }

        // A hit may reveal a lost attacker: scan for it now.
        val hit = boss.lastDamageTick()
        if (hit != lastHitSeen) {
            lastHitSeen = hit
            if (lost.isNotEmpty()) nextScanTick = now
        }

        if (!initialScanDone || now >= nextScanTick) {
            initialScanDone = true
            nextScanTick = nextAlignedScan(now)
            pickTarget(level)
            current = target
        }

        val method = settings.targetingMethod.get()
        val wasHidden = lastKnown.hidden
        aim = lastKnown.resolve(current?.rootVehicle, method)
        // The target just lost its sight line: look for a visible one now instead of up to a full scan interval later.
        if (!wasHidden && lastKnown.hidden) {
            nextScanTick = minOf(nextScanTick, now + RESCAN_AFTER_LOSS)
            boss.thought.event("targeting.update") { "${current?.name?.string ?: "target"} went out of sight, aiming at the last seen spot" }
        } else if (wasHidden && !lastKnown.hidden && current != null) {
            val seen = current
            boss.thought.event("targeting.update") { "spotted ${seen.name.string} again" }
        }
        trackAim(aim)
        watchDetection(now, current, method)
        reportVision(current)
    }

    /** The vision debug (see [AiVision]): what the boss makes of [current], with its sight cone while that is in use. */
    private fun reportVision(current: Entity?) {
        if (!boss.thought.enabled) return
        val cone = if (coneOn()) {
            val forward = turretForward()
            VisionCone(
                forward.x.toFloat(), forward.z.toFloat(), (settings.sightConeDegrees.get() / 2.0).toFloat(),
                (settings.sightRange.get() * settings.sightConeRangeMultiplier.get()).toFloat()
            )
        } else null
        AiVision.report(boss, boss.thought, TankSight.eyeOf(boss), current, aim, lastKnown.visionState(aim), lastKnown.graceLeftTicks, cone)
    }

    // ================================================================================================== breaking detection

    /**
     * DYNAMIC only: the held [current] target has been out of sight for `breakDetectionSeconds` without being near or
     * shooting the boss: it is lost. The timer restarts whenever the target is seen, is very close, or has just hit the boss.
     */
    private fun watchDetection(now: Int, current: Entity?, method: TargetingMethod) {
        val breakTicks = settings.breakDetectionSeconds.get() * 20
        if (now < informedUntil && current?.rootVehicle?.id == informedRootId) {
            hiddenSince = -1
            boss.thought.think("targeting.watchDetection") { "target told by a teammate, not lost to being out of sight" }
            return
        }
        if (method != TargetingMethod.DYNAMIC || breakTicks <= 0 || current == null || !lastKnown.hidden ||
            isClose(current) || shotBy(current)
        ) {
            hiddenSince = -1
            boss.thought.think("targeting.watchDetection") { "target in view, close or shooting: not losing it" }
            return
        }
        boss.thought.think("targeting.watchDetection") { "target hidden, the detection timer is running" }
        if (hiddenSince < 0) hiddenSince = now
        if (now - hiddenSince >= breakTicks) breakDetection()
    }

    /** Forgets the held target for good (until it is revealed, see [isLost]) and looks for another one soon. */
    private fun breakDetection() {
        val held = target ?: return
        val victim = held.rootVehicle
        if (lost.size >= MAX_LOST - 1) lost.clear()
        // Both ids: boarding or leaving a vehicle must not change whether the list applies (see isLost).
        lost.add(victim.id)
        lost.add(held.id)
        drop()
        nextScanTick = minOf(nextScanTick, boss.tickCount + RESCAN_AFTER_LOSS)
        boss.thought.event("targeting.loseTarget") { "lost track of ${victim.name.string}" }
    }

    /** Within `closeDetectionRange` blocks of the boss: too close to stay hidden. */
    private fun isClose(entity: Entity): Boolean {
        val range = settings.closeDetectionRange.get().toDouble()
        return entity.rootVehicle.distanceToSqr(boss) <= range * range
    }

    /** [entity] (or the vehicle it rides, or whoever rides it) is what just damaged the boss. */
    private fun shotBy(entity: Entity): Boolean {
        val attacker = boss.lastAttackerUUID
        if (attacker.isNullOrEmpty() || !boss.recentlyDamaged(REVEAL_HIT_TICKS)) return false
        return riddenBy(entity, attacker) || riddenBy(entity.rootVehicle, attacker)
    }

    /**
     * True for a target the boss lost track of: it is neither a candidate nor a secondary target until it comes within
     * `closeDetectionRange` or shoots the boss, which also takes it off the list. Only DYNAMIC loses targets. The list holds
     * the ids of the vehicle and of the target itself, and either one matches, so a player who gets into or out of a lost
     * tank stays lost.
     */
    private fun isLost(entity: Entity): Boolean {
        if (lost.isEmpty() || settings.targetingMethod.get() != TargetingMethod.DYNAMIC) return false
        val rootId = entity.rootVehicle.id
        if (rootId !in lost && entity.id !in lost) return false
        if (isClose(entity) || shotBy(entity)) {
            lost.remove(rootId)
            lost.remove(entity.id)
            return false
        }
        return true
    }

    private fun drop() {
        hiddenSince = -1
        target = null
        targetUuid = null
        aim = null
        trackedAimId = -1
        lastKnown.reset()
    }

    /**
     * A teammate under attack calls out [enemy] at [position] (see [TankAlerts]). The tank adopts it as its target when it
     * has none, or only a hidden one, and is not looking at a visible target of its own: [enemy] itself, else the vehicle it
     * rides, else a rider of that vehicle, whichever is a valid target of this tank. The adopted target is not lost to
     * `breakDetectionSeconds` for [holdTicks] ticks, and while it is hidden its last known spot is moved to [position], so
     * the guns and the search aim at where the enemy really is. Refusing (`false`): a visible target of its own, the
     * target is not valid for this tank, or the weights ignore it.
     */
    fun inform(enemy: Entity, position: Vec3, holdTicks: Int): Boolean {
        val now = boss.tickCount
        val root = enemy.rootVehicle
        val held = target
        if (held != null && held.rootVehicle.id != root.id && !lastKnown.hidden) {
            boss.thought.think("targeting.inform") { "ignoring the call about ${enemy.name.string}: busy with a visible ${held.name.string}" }
            return false
        }

        // The called-out target counts as inside the aggro range for the hold time (the call reaches farther than the tank
        // would look); put back if the target turns out to be no valid one.
        val previousUntil = informedUntil
        val previousRoot = informedRootId
        informedUntil = maxOf(previousUntil, now + holdTicks)
        informedRootId = root.id
        val chosen = sequenceOf(enemy, root).plus(root.indirectPassengers).firstOrNull { stillValid(it) }
        if (chosen == null) {
            informedUntil = previousUntil
            informedRootId = previousRoot
            boss.thought.think("targeting.inform") { "ignoring the call about ${enemy.name.string}: not a valid target for this tank" }
            return false
        }

        if (held == null || held.rootVehicle.id != root.id) {
            trackedAimId = -1
            lastSwitchTick = now
            target = chosen
            targetUuid = chosen.uuid
            lastKnown.reset()
            if (ignoredId == root.id) ignoreUntil = 0
            lost.remove(root.id)
            lost.remove(chosen.id)
            nextScanTick = maxOf(nextScanTick, now + RESCAN_AFTER_LOSS)
            boss.thought.event("targeting.tell") { "told about ${chosen.name.string} at $position" }
        }
        lastKnown.tell(root.id, position)
        return true
    }

    /** Entity id of the vehicle (or entity) the tank sees right now, i.e. its aim is no ghost; -1 otherwise. Shown to a spotted player. */
    fun seenRootId(): Int = if (aim != null && !lastKnown.hidden) target?.rootVehicle?.id ?: -1 else -1

    /**
     * The boss gave up searching for a hidden target: forgets it and refuses it as a new choice for [IGNORE_TICKS] ticks,
     * so the boss goes back to patrolling instead of walking back to the same empty spot.
     */
    fun giveUp() {
        val victim = target?.rootVehicle
        if (victim != null) {
            ignoredId = victim.id
            ignoreUntil = boss.tickCount + IGNORE_TICKS
            boss.thought.event("targeting.giveUp") { "gave up on ${victim.name.string}, ignoring it for ${IGNORE_TICKS / 20}s" }
        }
        drop()
    }

    /** The next tick at which `(tick + id) % SCAN_INTERVAL == 0`, so bosses spread their scans over the interval. */
    private fun nextAlignedScan(now: Int): Int {
        val phase = Math.floorMod(now + boss.id, SCAN_INTERVAL)
        return now + (SCAN_INTERVAL - phase)
    }

    private fun isIgnored(entity: Entity): Boolean =
        boss.tickCount < ignoreUntil && entity.rootVehicle.id == ignoredId

    // ================================================================================================== validity

    /** Cheap per-tick check of the current target. Heavy conditions (sight, scan box) are scan-only. */
    private fun stillValid(entity: Entity): Boolean {
        if (!entity.isAlive || entity.isRemoved || entity.level() !== boss.level()) return false
        if (entity is VehicleEntity) return validVehicle(entity, lose = true)
        if (entity is ServerPlayer) return validPlayer(entity, lose = true)
        if (entity is HumanoidDummyEntity && dummiesArePlayers(entity)) return validDummy(entity, lose = true)
        if (entity !is Mob) return false
        return weightOf(entity) > 0 && !entity.isInvulnerable && !boss.isCrew(entity) && entity.rootVehicle !== boss &&
                withinAggro(entity, lose = true)
    }

    /**
     * The aggro range rule: horizontal distance within `aggroRange` (times [LOSE_TARGET_FACTOR] for a held target). Under
     * DYNAMIC the sight cone widens it: inside the cone the reach is also `sightRange * sightConeRangeMultiplier`, so a far
     * sniper in front of the turret is a candidate even beyond the aggro range.
     */
    private fun withinAggro(entity: Entity, lose: Boolean): Boolean {
        if (settings.globalAggro.get()) return true
        if (boss.tickCount < informedUntil && entity.rootVehicle.id == informedRootId) return true
        val factor = if (lose) LOSE_TARGET_FACTOR else 1.0
        val limit = settings.aggroRange.get() * factor
        val dx = entity.x - boss.x
        val dz = entity.z - boss.z
        val distanceSqr = dx * dx + dz * dz
        if (distanceSqr <= limit * limit) return true
        if (!coneOn()) return false
        val coneLimit = settings.sightRange.get() * settings.sightConeRangeMultiplier.get() * factor
        return distanceSqr <= coneLimit * coneLimit && inCone(entity)
    }

    private fun validPlayer(player: ServerPlayer, lose: Boolean): Boolean {
        if (!settings.weights.allowsPlayers) return false
        if (!player.isAlive || player.isRemoved || player.isSpectator || player.isCreative) return false
        if (player.level() !== boss.level()) return false
        return withinAggro(player, lose)
    }

    /**
     * An aircraft for a plane weight above 0 (see [isEnemyPlane]) that a gun can elevate to, or armour for a tank weight
     * above 0 (see [isEnemyTank]), under the player range rule.
     */
    private fun validVehicle(vehicle: Entity, lose: Boolean): Boolean {
        val weights = settings.weights
        val enemy = when (categoryOf(vehicle)) {
            TargetCategory.PLANE -> weights.allowsPlanes && isEnemyPlane(boss, vehicle) &&
                    canReach(vehicle, if (lose) REACH_MARGIN else 0.0)

            TargetCategory.TANK -> weights.allowsTanks && isEnemyTank(boss, vehicle)
            else -> false
        }
        return enemy && withinAggro(vehicle, lose)
    }

    private fun dummiesArePlayers(dummy: HumanoidDummyEntity): Boolean = dummy.settings.targetedByBosses()

    /** A dummy treated as a player: the player-class rules without the creative / spectator checks, same range rule. */
    private fun validDummy(dummy: HumanoidDummyEntity, lose: Boolean): Boolean {
        if (!settings.weights.allowsPlayers) return false
        if (!dummy.isAlive || dummy.isRemoved || dummy.level() !== boss.level()) return false
        return withinAggro(dummy, lose)
    }

    /** A mob worth shooting: alive, attackable, not one of the boss's own crew, and of a category with a weight above 0. */
    private fun eligibleMob(mob: Mob): Boolean {
        if (!mob.isAlive || mob.isRemoved || !mob.isAttackable || mob.isInvulnerable) return false
        if (weightOf(mob) <= 0) return false
        if (mob is HumanoidDummyEntity && dummiesArePlayers(mob)) return false // handled as a player-class candidate
        if (mob is BossCrewEntity || mob.rootVehicle === boss || boss.isCrew(mob)) return false
        return true
    }

    // ================================================================================================== choice

    private fun pickTarget(level: ServerLevel) {
        val now = boss.tickCount
        val bossPos = boss.position()
        val weights = settings.weights
        val method = settings.targetingMethod.get()

        val rawCandidates = ArrayList<Entity>()
        if (weights.allowsPlayers) {
            for (player in level.players()) {
                if (validPlayer(player, lose = false)) rawCandidates.add(player)
            }
            for (dummy in TrackedDummies.all(level)) {
                if (dummy is HumanoidDummyEntity && dummiesArePlayers(dummy) && validDummy(dummy, lose = false)) {
                    rawCandidates.add(dummy)
                }
            }
        }
        if (weights.allowsVehicles) {
            for (vehicle in TrackedVehicles.all(level)) {
                if (validVehicle(vehicle, lose = false)) rawCandidates.add(vehicle)
            }
        }
        if (allowsMobsOrDrones()) {
            val reach = settings.mobScanRange.get().toDouble()
            val box = AABB(
                boss.x - reach, level.minBuildHeight.toDouble(), boss.z - reach,
                boss.x + reach, level.maxBuildHeight.toDouble(), boss.z + reach
            )
            rawCandidates.addAll(level.getEntitiesOfClass(Mob::class.java, box) { eligibleMob(it) })
        }

        // A target slightly beyond the aggro range keeps its lock until the (wider) lose range.
        val rawHeld = target?.takeIf { t ->
            rawCandidates.any { it === t } || (t is ServerPlayer && validPlayer(t, lose = true)) ||
                    (t is HumanoidDummyEntity && dummiesArePlayers(t) && validDummy(t, lose = true)) ||
                    (t is VehicleEntity && validVehicle(t, lose = true))
        }
        if (rawHeld != null && rawCandidates.none { it === rawHeld }) rawCandidates.add(rawHeld)

        // A player riding a vehicle is listed twice (player and vehicle): keep the one with the higher weight. A held
        // target that lost that contest is replaced by the entry of its vehicle, so the lock is not dropped by it.
        val candidates = dedupeByRootVehicle(rawCandidates.filter { !isIgnored(it) && !isLost(it) }, this::weightOf)
        val held = rawHeld?.let { h -> candidates.firstOrNull { it === h } ?: candidates.firstOrNull { it.rootVehicle === h.rootVehicle } }

        fun distance(entity: Entity) = FlightMath.horizontalDistance(entity.position(), bossPos)
        fun score(entity: Entity) = TargetChoice.score(distance(entity), weightOf(entity))

        // DYNAMIC: the boss only knows what it sees. New targets come from the visible candidates; the held one stays
        // while it is the only thing known (hidden, searched for), and is dropped for any visible candidate.
        var pool: List<Entity> = candidates
        var effectiveHeld = held
        var hiddenCount = 0
        if (method == TargetingMethod.DYNAMIC) {
            // Something very close is noticed whether or not a wall is in the way.
            val (hidden, visible) = candidates.partition { lastKnown.isHidden(it, method) && !isClose(it) }
            hiddenCount = hidden.size
            pool = visible
            if (effectiveHeld != null && hidden.any { it === effectiveHeld }) {
                if (visible.isEmpty()) pool = pool + effectiveHeld else effectiveHeld = null
            }
        }

        // The boss knows who just shot it, seen or not (for a few seconds): that one is the first choice.
        val attackerId: String? = boss.lastAttackerUUID
        val attacker = if (attackerId.isNullOrEmpty() || !boss.recentlyDamaged(ATTACKER_MEMORY_TICKS)) null
        else candidates.firstOrNull { riddenBy(it, attackerId) }
        val best = TargetChoice.bestScore(pool, this::weightOf, ::distance)

        val chosen = if (settings.randomTargeting.get()) {
            attacker ?: randomChoice(pool, effectiveHeld, now)
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
            chosen == null -> "nothing valid in range"
            attacker === chosen -> "it just shot this tank"
            settings.randomTargeting.get() -> "random draw"
            chosen === effectiveHeld -> "kept the lock"
            chosen === best -> "best distance/weight score"
            else -> "chosen"
        }
        if (chosen !== target) {
            trackedAimId = -1 // new target: forget the old motion estimate
            lastSwitchTick = now
            boss.thought.event("targeting.pickTarget") {
                "target ${target?.name?.string ?: "none"} -> ${chosen?.name?.string ?: "none"} ($why, ${candidates.size} candidates, $hiddenCount hidden)"
            }
        } else {
            boss.thought.think("targeting.pickTarget") { if (chosen == null) "no target: $why" else "holding ${chosen.name.string} ($why)" }
        }
        target = chosen
        targetUuid = chosen?.uuid
    }

    /**
     * `randomTargeting`: the held target stays until [nextRerollTick], then a random one is drawn from [pool] (it may be
     * the same), each candidate with a chance proportional to its weight.
     */
    private fun randomChoice(pool: List<Entity>, held: Entity?, now: Int): Entity? {
        if (held != null && now < nextRerollTick) return held
        if (pool.isEmpty()) return null
        val seconds = settings.randomTargetSeconds.get()
        nextRerollTick = now + (seconds * 20 * (0.75 + 0.5 * boss.getRandom().nextDouble())).toInt()
        return TargetChoice.weightedRandom(pool, this::weightOf, boss.getRandom())
    }

    // ================================================================================================== secondary target

    /**
     * The nearest visible soft target (player, dummy or mob with a weight above 0) within [range] blocks that is not
     * the main target's vehicle: what a second gun (the M1A2's .50 cal) shoots at while the main guns are busy with
     * armour. Rescanned every [SECONDARY_SCAN_INTERVAL] ticks; null when there is none.
     */
    fun secondaryTarget(level: ServerLevel, range: Double): Entity? {
        val now = boss.tickCount
        secondary?.let { if (!it.isAlive || it.isRemoved || it.level() !== level || it.distanceToSqr(boss) > range * range * 1.5) secondary = null }
        if (now < nextSecondaryScan) return secondary
        nextSecondaryScan = now + SECONDARY_SCAN_INTERVAL + Math.floorMod(boss.id, 5)

        val weights = settings.weights
        val sightRange = settings.sightRange.get().toDouble()
        val mainRoot = target?.rootVehicle
        val found = ArrayList<Entity>()
        if (weights.allowsPlayers) {
            for (player in level.players()) if (validPlayer(player, lose = true)) found.add(player)
            for (dummy in TrackedDummies.all(level)) {
                if (dummy is HumanoidDummyEntity && dummiesArePlayers(dummy) && validDummy(dummy, lose = true)) found.add(dummy)
            }
        }
        if (allowsMobsOrDrones()) {
            val box = AABB(boss.x - range, boss.y - range, boss.z - range, boss.x + range, boss.y + range, boss.z + range)
            found.addAll(level.getEntitiesOfClass(Mob::class.java, box) { eligibleMob(it) })
        }
        secondary = found.asSequence()
            .filter { it.rootVehicle !== mainRoot && !isLost(it) && it.distanceToSqr(boss) <= range * range }
            .sortedBy { it.distanceToSqr(boss) }
            .firstOrNull { TankSight.canSee(boss, it.rootVehicle, minOf(range, sightRange)) }
        boss.thought.think("targeting.secondaryTarget") { secondary?.let { "weapon station on ${it.name.string}" } ?: "weapon station has no soft target" }
        return secondary
    }

    // ================================================================================================== motion estimate

    private fun trackAim(entity: Entity?) {
        if (entity == null) {
            trackedAimId = -1
            aimVelocity = Vec3.ZERO
            return
        }
        val position = entity.position()
        // The ghost is one reused marker (same id) that jumps to a new spot when the hidden target changes: that jump is
        // not motion, and a spot does not move, so it never gets a lead.
        if (entity.id != trackedAimId || lastKnown.isGhost(entity)) {
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

        /** Extra elevation (degrees) a held aircraft may have beyond what the guns reach before it is dropped. */
        const val REACH_MARGIN = 8.0

        /** How long after being hit the boss still knows who hit it, in ticks. */
        const val ATTACKER_MEMORY_TICKS = 200

        /** How long a target the boss gave up on is refused, in ticks. */
        const val IGNORE_TICKS = 600

        /** A hit this recent by the target keeps it detected (and reveals it if it was lost), in ticks. */
        const val REVEAL_HIT_TICKS = 60
        const val MAX_LOST = 32
        const val SECONDARY_SCAN_INTERVAL = 20

        /** A turret pointing straighter up or down than this (horizontal part of its barrel vector) has no heading for the sight cone. */
        const val CONE_MIN_LENGTH = 1.0e-3
    }
}
