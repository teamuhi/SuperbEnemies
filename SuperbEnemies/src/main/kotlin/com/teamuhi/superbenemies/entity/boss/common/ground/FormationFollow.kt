package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath.horizontalDistance
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * How a follower of a tank formation ([TankFormations]) holds its slot behind the leader. One instance per brain.
 *
 * @param facingTolerance the heading error (degrees) under which the hull counts as facing the formation's heading
 */
class FormationFollow(
    private val boss: TankBossEntity,
    private val profile: GroundProfile,
    private val driver: GroundDriver,
    private val moves: GroundMoves,
    private val facingTolerance: Float
) {

    private var nextFollowReplan = 0
    private var followHolding = false
    private var followStalled = 0

    /** Tick before which the tank acts alone, having failed to reach its slot (see [follow]). */
    private var formationGiveUpUntil = 0

    /** Whether the straight way to the leader's trail is clear, and when that is looked at again (see [trailClear]). */
    private var trailCheckAt = 0
    private var trailClearResult = false

    /** True while the tank has dropped out of its formation after it could not reach the slot. */
    fun gaveUp(now: Int): Boolean = now < formationGiveUpUntil

    /**
     * Drives to [slot], the place behind the leader this tank holds in its formation: at a sprint when more than two
     * spacings away, else at a cruise. Once on the slot it holds still, hull turned to the formation's heading. The slot
     * moves with the leader; the planner replans when it shifts far enough, and a route that has already ended is
     * thrown away once the slot has moved off (at most every [FOLLOW_REPLAN_TICKS] ticks, so a partial route cannot loop).
     */
    fun follow(level: Level, slot: Vec3) {
        val now = boss.tickCount
        val position = boss.position()
        val distance = horizontalDistance(position, slot)
        // Holds inside FOLLOW_HOLD_RADIUS and keeps holding until the slot is FOLLOW_RESUME_RADIUS away, so a slot that
        // creeps along with the leader does not make the tank stop and start every few ticks.
        if (distance <= FOLLOW_HOLD_RADIUS || (followHolding && distance <= FOLLOW_RESUME_RADIUS)) {
            followHolding = true
            followStalled = 0
            val heading = TankFormations.headingFor(boss)
            boss.thought.think("follow.follow") { "on its formation slot, holding" }
            // A wheeled hull only turns by creeping forward, which would walk it off the slot: it tolerates a wide error.
            if (heading == null || driver.turnToward(position.add(heading.scale(FOLLOW_FACING_DISTANCE)), facingTolerance)) driver.hold()
            return
        }
        followHolding = false
        // In a column the follower retraces the path its leader drove (round the same corners, through the same gaps) rather
        // than planning a route to a slot that moves on; the planner only takes over when that path is blocked or far away.
        val carrot = TankFormations.carrotFor(boss)
        if (carrot != null && trailClear(level, carrot.point, now)) {
            followStalled = 0
            val mode = if (carrot.gap > TankFormations.spacingFor(boss) * 2.0) DriveMode.SPRINT else DriveMode.CRUISE
            boss.thought.think("follow.follow") { "following the leader's trail ($mode)" }
            driver.drive(carrot.point, mode, stopAtEnd = false)
            moves.look(carrot.point)
            return
        }
        val planner = moves.planner
        if (planner.arrived && distance > FOLLOW_RETARGET_RADIUS && now >= nextFollowReplan) {
            nextFollowReplan = now + FOLLOW_REPLAN_TICKS
            moves.newGoal()
        }
        // Asking here (goTo asks again, harmlessly) lets a failed search retry before the status is read.
        planner.request(level, position, slot, REPLAN_SHIFT)
        planner.tick(level)
        // A slot that cannot be reached (no route at all, or a partial route that ended far from it) for too long: the
        // tank drops out of the formation for a while instead of standing there, or turning toward an unreachable goal.
        when {
            distance <= FOLLOW_RETARGET_RADIUS -> followStalled = 0
            planner.status == GroundRoutePlanner.Status.NO_ROUTE || planner.arrived -> followStalled++
            planner.hasRoute -> followStalled = 0
        }
        if (followStalled > FOLLOW_STALL_TICKS) {
            followStalled = 0
            formationGiveUpUntil = now + BREAK_TICKS
            boss.thought.event("follow.follow") { "cannot reach the formation slot, acting alone for a while" }
            moves.newGoal()
            driver.hold()
            return
        }
        if (planner.status == GroundRoutePlanner.Status.NO_ROUTE) {
            boss.thought.think("follow.follow") { "no route to the formation slot, holding" }
            driver.hold()
            return
        }
        val mode = if (distance > TankFormations.spacingFor(boss) * 2.0) DriveMode.SPRINT else DriveMode.CRUISE
        boss.thought.think("follow.follow") { "routing to the formation slot ($mode)" }
        moves.goTo(level, slot, mode, REPLAN_SHIFT)
    }

    /** True when the hull can drive straight from here to [point] (block exact, see [HullSweep]); asked at most every [TRAIL_CHECK_INTERVAL] ticks. */
    private fun trailClear(level: Level, point: Vec3, now: Int): Boolean {
        val position = boss.position()
        if (horizontalDistance(position, point) <= profile.halfLength + 1.0) return true
        if (now < trailCheckAt) return trailClearResult
        trailCheckAt = now + TRAIL_CHECK_INTERVAL
        trailClearResult = HullSweep.sweep(level, position.x, position.z, position.y, point.x, point.z, profile, from = profile.halfLength) == null
        return trailClearResult
    }

    companion object {
        /** The planner replans a follower's route when the slot moved this far from the route's goal (default 16). Also the rally's. */
        internal const val REPLAN_SHIFT = 6.0

        /** Ticks a follower stays out of formation after its line of fire was blocked for too long or its slot was unreachable. */
        internal const val BREAK_TICKS = 200

        /** Within this many blocks of its slot a follower holds still. */
        private const val FOLLOW_HOLD_RADIUS = 3.0

        /** A finished route is dropped (and a new one planned) once the slot is farther than this. */
        private const val FOLLOW_RETARGET_RADIUS = 6.0
        private const val FOLLOW_REPLAN_TICKS = 20

        /** How far ahead (blocks) the point is that a follower on its slot turns its hull toward. */
        private const val FOLLOW_FACING_DISTANCE = 16.0

        /** A follower that holds its slot starts driving again once the slot is this far away. */
        private const val FOLLOW_RESUME_RADIUS = 5.0

        /** Ticks a follower may stand at a route that ended short of its slot (or have no route) before it acts alone. */
        private const val FOLLOW_STALL_TICKS = 100

        /** Ticks between two looks at whether the straight way to the leader's trail is clear. */
        private const val TRAIL_CHECK_INTERVAL = 5
    }
}
