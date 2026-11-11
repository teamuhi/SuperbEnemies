package com.teamuhi.superbenemies.entity.soldier.ai

import com.teamuhi.superbenemies.config.AtSoldierConfig
import com.teamuhi.superbenemies.entity.boss.common.ground.TankSight
import com.teamuhi.superbenemies.entity.dummy.ai.DummyAi
import com.teamuhi.superbenemies.entity.soldier.AtSoldierEntity
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.EnumSet
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where the anti-tank soldier goes when it is not shooting: a new spot after a shot (shoot and scoot) and, with nothing left
 * to shoot, away from the tanks. Runs while [AtSoldierEntity.repositionPending] or [AtSoldierEntity.isRetreating], above the
 * roam goal, and holds MOVE and JUMP.
 *
 * A spot is a random point `repositionMinDistance`..`repositionMaxDistance` blocks away, resolved to a dry, standable block
 * ([DummyAi.standingSpot]) and accepted only when it is loaded and inside the [LEASH] around the spawn point. Up to
 * [SPOT_TRIES] are drawn, scored, and the best are tried for a path ([PATH_TRIES] at most, with the shared path budget).
 *  - Shoot and scoot: a spot that brings the soldier more than [MAX_CLOSER] blocks closer to the last tank it fired at,
 *    inside the weapon's minimum range or beyond its maximum range is rejected. The score is how sideways the spot lies to
 *    the tank (the sine of the angle between the move and the bearing to the tank), plus [SCOOT_HIDDEN_BONUS] when the tank's
 *    turret cannot see it.
 *  - Retreat: a spot nearer to the nearest tank than the soldier is now (beyond [RETREAT_SLACK]) is rejected. The score is the
 *    distance gained, plus [RETREAT_HIDDEN_BONUS] when the tank's turret cannot see the spot. A soldier that is already out of
 *    sight stays put. A retreat is tried again every `repositionMaxDistance` seconds while the soldier is still empty and a
 *    tank is near.
 *
 * The walk ends on arrival, when the soldier has barely moved for [STUCK_TICKS] ticks, or after [LEG_TIMEOUT_TICKS]; any end
 * clears the pending reposition, so a soldier that finds no spot or cannot get there simply carries on (it is never locked
 * out of shooting). The soldier sprints on the way when `sprinting` is on. While it walks, [AtSoldierEntity.roaming] is set so
 * the look goal traces the path. Reload and resupply keep ticking meanwhile. All state is per instance and options are read live.
 */
class SoldierRepositionGoal(private val soldier: AtSoldierEntity) : Goal() {

    private enum class Phase { SEARCH, WALK }

    private class Candidate(val spot: BlockPos, var score: Double)

    private var phase = Phase.SEARCH
    private var finished = false
    private var retreating = false
    private var retryAt = 0

    private var searchDeadline = 0
    private var deferred = false

    private var legDeadline = 0
    private var stuckCheckTick = 0
    private var stuckX = 0.0
    private var stuckY = 0.0
    private var stuckZ = 0.0

    private val candidates = ArrayList<Candidate>(SPOT_TRIES)

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.JUMP)
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun canUse(): Boolean {
        if (!soldier.isAlive || soldier.isPassenger || soldier.tickCount < retryAt) return false
        val pending = soldier.repositionPending
        val retreat = !pending && soldier.isRetreating()
        soldier.thought.think("reposition.canUse") {
            if (pending) "yes: reposition pending" else if (retreat) "yes: out of ammo with a tank near, retreating" else "no: nothing to move away from"
        }
        return pending || retreat
    }

    override fun canContinueToUse(): Boolean = !finished && soldier.isAlive && !soldier.isPassenger

    override fun start() {
        finished = false
        phase = Phase.SEARCH
        retreating = soldier.isRetreating()
        soldier.thought.event("reposition.start") { if (retreating) "starting a retreat out of sight" else "shoot and scoot: looking for a new firing spot" }
        searchDeadline = soldier.tickCount + SEARCH_TIMEOUT_TICKS
        deferred = false
        soldier.roaming = true
        soldier.navigation.stop()
    }

    override fun stop() {
        soldier.thought.event("reposition.stop") { "reposition over" }
        soldier.roaming = false
        soldier.navigation.stop()
        soldier.isSprinting = false
    }

    override fun tick() {
        when (phase) {
            Phase.SEARCH -> tickSearch()
            Phase.WALK -> tickWalk()
        }
    }

    // ================================================================================================== phases

    private fun tickSearch() {
        val now = soldier.tickCount
        val threat = threat()
        if (threat == null) return finish(RETRY_TICKS, "the tank is gone") // nothing to get away from
        if (now > searchDeadline) return finish(RETRY_TICKS, "search timed out")
        if (retreating && hidden(threat, soldier.eyePosition)) return finish(retreatRecheckTicks(), "already out of sight") // already out of sight

        deferred = false
        val path = findPath(threat)
        if (path != null && soldier.navigation.moveTo(path, 1.0)) {
            soldier.thought.event("reposition.tickSearch") { "found a spot ${path.target.toShortString()}, walking there" }
            phase = Phase.WALK
            legDeadline = now + LEG_TIMEOUT_TICKS
            rememberPosition()
        } else if (!deferred) {
            finish(if (retreating) retreatRecheckTicks() else RETRY_TICKS, "no reachable spot, staying put") // no reachable spot: carry on from here
        } else {
            soldier.thought.think("reposition.tickSearch") { "path search budget spent, trying again next tick" }
        }
    }

    private fun tickWalk() {
        val now = soldier.tickCount

        // Sprint on the way, but not while the soldier is barely moving (blocked, turning a corner), so no sprint
        // particles puff out of a slow walk.
        val velocity = soldier.deltaMovement
        val sprint = soldier.settings.sprinting() && hypot(velocity.x, velocity.z) > SPRINT_MIN_SPEED
        if (soldier.isSprinting != sprint) soldier.isSprinting = sprint

        soldier.thought.think("reposition.tickWalk") { "walking to the new spot${if (sprint) ", sprinting" else ""}" }
        if (soldier.navigation.isDone) return arrived("arrived")
        if (now > legDeadline) return arrived("walk timed out")
        if (now >= stuckCheckTick) {
            if (soldier.distanceToSqr(stuckX, stuckY, stuckZ) < STUCK_DISTANCE * STUCK_DISTANCE) return arrived("stuck")
            rememberPosition()
        }
    }

    private fun arrived(why: String) = finish(if (retreating) retreatRecheckTicks() else 0, why)

    /** Ends the goal: the pending reposition is done (or given up), and the goal does not start again for [retry] ticks. */
    private fun finish(retry: Int, why: String) {
        soldier.thought.event("reposition.finish") { "reposition ended: $why" }
        soldier.repositionPending = false
        retryAt = soldier.tickCount + retry
        finished = true
    }

    private fun rememberPosition() {
        stuckCheckTick = soldier.tickCount + STUCK_TICKS
        stuckX = soldier.x
        stuckY = soldier.y
        stuckZ = soldier.z
    }

    /** A retreat that is still needed after this many ticks is made again. */
    private fun retreatRecheckTicks(): Int = (AtSoldierConfig.repositionMax() * 20).toInt()

    // ================================================================================================== spot search

    /** The turret of the tank to get away from: the nearest tank while retreating, else the one last fired at (or the nearest). */
    private fun threat(): Vec3? {
        if (!retreating) soldier.lastThreat?.let { return it }
        return DummyAi.nearestTank(soldier, includeCrippled = false)?.let { TankSight.eyeOf(it) }
    }

    /** Whether the block geometry hides [point] from [threat]'s turret. */
    private fun hidden(threat: Vec3, point: Vec3): Boolean =
        soldier.level().clip(ClipContext(threat, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, soldier)).type == HitResult.Type.BLOCK

    /** The path to the best of up to [SPOT_TRIES] random spots, or null (with [deferred] set when only the path budget ran out). */
    private fun findPath(threat: Vec3): Path? {
        val level = soldier.level()
        val rng = soldier.getRandom()
        val minDistance = AtSoldierConfig.repositionMin()
        val maxDistance = AtSoldierConfig.repositionMax()
        val anchor = soldier.anchorCenter()
        val minRange = AtSoldierConfig.minRange(soldier.weapon)
        val maxRange = AtSoldierConfig.maxRange(soldier.weapon)
        val here = soldier.position()
        val toThreatX = threat.x - here.x
        val toThreatZ = threat.z - here.z
        val currentDistance = hypot(toThreatX, toThreatZ)
        if (currentDistance < 1.0e-3) return null

        candidates.clear()
        repeat(SPOT_TRIES) {
            val angle = rng.nextDouble() * Math.PI * 2.0
            val distance = minDistance + rng.nextDouble() * (maxDistance - minDistance)
            val dx = cos(angle) * distance
            val dz = sin(angle) * distance
            val x = floor(here.x + dx).toInt()
            val z = floor(here.z + dz).toInt()
            if (anchor != null && hypot(x + 0.5 - anchor.x, z + 0.5 - anchor.z) > LEASH) return@repeat
            val spot = DummyAi.standingSpot(soldier, x, z) ?: return@repeat
            val spotDistance = hypot(threat.x - (x + 0.5), threat.z - (z + 0.5))

            val score = if (retreating) {
                if (spotDistance < currentDistance - RETREAT_SLACK) return@repeat
                (spotDistance - currentDistance) / maxDistance
            } else {
                if (spotDistance < currentDistance - MAX_CLOSER || spotDistance < minRange || spotDistance > maxRange) return@repeat
                abs(dx * toThreatZ - dz * toThreatX) / (distance * currentDistance) // sine of the angle to the tank's bearing
            }
            candidates += Candidate(spot, score)
        }
        if (candidates.isEmpty()) return null

        // A look at the best few: can the tank's turret see the spot? (Spends the shared raycast budget.)
        candidates.sortByDescending { it.score }
        val bonus = if (retreating) RETREAT_HIDDEN_BONUS else SCOOT_HIDDEN_BONUS
        for (candidate in candidates.take(VISIBILITY_CHECKS)) {
            if (!DummyAi.sightRays.tryTake(level)) break
            val eye = Vec3(candidate.spot.x + 0.5, candidate.spot.y + EYE_HEIGHT, candidate.spot.z + 0.5)
            if (hidden(threat, eye)) candidate.score += bonus
        }
        candidates.sortByDescending { it.score }

        for (candidate in candidates.take(PATH_TRIES)) {
            // Out of path searches for this tick (other dummies used them): try the whole search again next tick.
            if (!DummyAi.pathfinds.tryTake(level)) {
                deferred = true
                return null
            }
            val path = soldier.navigation.createPath(candidate.spot, 1)
            if (path != null && path.canReach()) return path
        }
        return null
    }

    private companion object {
        /** Candidate spots drawn per search, how many are checked for turret visibility and how many get a path search. */
        const val SPOT_TRIES = 16
        const val VISIBILITY_CHECKS = 4
        const val PATH_TRIES = 3

        /** Keep this far (blocks) from the anchor; under the entity's own 64 block re-anchor limit (the roam goal's leash). */
        const val LEASH = 48.0

        /** Shoot and scoot never closes in on the tank by more than this many blocks. */
        const val MAX_CLOSER = 4.0

        /** A retreat tolerates a spot this many blocks nearer to the tank than the soldier is now (sidestepping behind cover). */
        const val RETREAT_SLACK = 2.0

        const val SCOOT_HIDDEN_BONUS = 0.3
        const val RETREAT_HIDDEN_BONUS = 1.0

        /** Eye height of a standing soldier above its feet, for the visibility test. */
        const val EYE_HEIGHT = 1.62

        /** Pause (ticks) before the goal looks again after it found no spot. */
        const val RETRY_TICKS = 40

        /** A search that keeps being deferred by the path budget gives up after this many ticks. */
        const val SEARCH_TIMEOUT_TICKS = 40

        const val LEG_TIMEOUT_TICKS = 200
        const val STUCK_TICKS = 40
        const val STUCK_DISTANCE = 0.5

        /** Horizontal speed (blocks/tick) below which the soldier is not shown as sprinting; walking is about 0.2. */
        const val SPRINT_MIN_SPEED = 0.1
    }
}
