package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath.horizontalDistance
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedBosses
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keeps the tank bosses (any two of them, teammates or not, in a formation or not) from piling up: the route planner
 * knows nothing about other tanks, so without this several tanks drive to the same patrol or firing spot, hold a slot
 * or reverse into one another and their hulls interlock. One instance per brain.
 *
 * Every [SEPARATION_SCAN_INTERVAL] ticks the tank reads the tank bosses within [SEPARATION_SCAN_RADIUS] blocks and
 * compares each distance with [separationNeed]: how far each hull reaches towards the other ([hullReach], a rectangle
 * of the real hull length and width turned the way the tank faces, not the square hitbox) plus [SEPARATION_GAP]
 * (capped for members of one formation, whose slots are `formationSpacing` apart). Two reactions:
 *  - steering ([steerAround], inside the brain's `goTo`): within [SEPARATION_STEER] times that distance a route waypoint that is
 *    on the way to the neighbour is bent to its right, so two tanks pass each other instead of through each other;
 *  - giving way ([separate]): inside the distance the tank with the higher entity id (or any tank, for a crippled
 *    wreck) drives away from the neighbour in place of whatever the state was doing, until it is [SEPARATION_RELEASE]
 *    times the distance away (hysteresis), for at most [SEPARATION_MAX_TICKS] ticks followed by a cooldown. The lower
 *    id never gives way, so two tanks never both back off from each other.
 * EVADE, RETREAT, FLANK, PULL_BACK and leaving water are not interrupted for a mere near miss; but when the hulls
 * actually touch ([crowdTouching]) the tank gives way in any state and without the cooldown, because overlapping hulls
 * are worse than a broken manoeuvre. The driver's stuck detection keeps running (the manoeuvre drives through
 * [GroundDriver]).
 */
class CrowdSeparation(
    private val boss: TankBossEntity,
    private val profile: GroundProfile,
    private val driver: GroundDriver,
    private val moves: GroundMoves
) {

    private var crowdNextScan = 0

    /** Unit vector pointing away from the tank bosses that crowd this one (null: none within steering distance). */
    private var crowdAway: Vec3? = null

    /** Same for the crowding tanks this one gives way to (higher id, or a wreck), and where the nearest of them stands. */
    private var crowdYieldAway: Vec3? = null
    private var crowdYieldFrom: Vec3? = null
    private var separating = false

    /** A tank this one has to give way to touches its hull right now (see [scanCrowd]): the manoeuvre then runs in every state. */
    private var crowdTouching = false
    private var separateStart = 0
    private var separateCooldownUntil = 0
    private var separateGoal: Vec3? = null
    private var separateRepick = 0

    /**
     * Runs the giving-way manoeuvre for this tick, if the neighbours ask for it.
     * @param stateAllows whether the brain's state may be interrupted for a near miss (PATROL, INVESTIGATE, SEARCH, ENGAGE)
     * @return true when this tick's driving was taken by the manoeuvre
     */
    fun separate(level: Level, stateAllows: Boolean): Boolean {
        scanCrowd(level)
        val now = boss.tickCount
        val away = crowdYieldAway
        val allowed = crowdTouching || stateAllows
        if (!allowed || away == null) {
            if (separating) boss.thought.event("crowd.separate") { "gave way, clear of the other tank" }
            separating = false
            return false
        }
        if (!separating) {
            if (now < separateCooldownUntil && !crowdTouching) return false
            separating = true
            separateStart = now
            separateGoal = null
            boss.thought.event("crowd.separate") { "too close to another tank, giving way" + if (crowdTouching) " (hulls touching)" else "" }
        } else if (now - separateStart > SEPARATION_MAX_TICKS) {
            separating = false
            separateCooldownUntil = now + SEPARATION_COOLDOWN
            boss.thought.event("crowd.separate") { "gave up giving way, resuming its state" }
            return false
        }

        val position = boss.position()
        var goal = separateGoal
        if (goal == null || now >= separateRepick || horizontalDistance(position, goal) < SEPARATION_ARRIVE) {
            goal = pickSeparationGoal(level, away)
            separateGoal = goal
            separateRepick = now + SEPARATION_REPICK
        }
        if (goal == null) {
            // Hemmed in: nowhere passable to go. Let the state carry on; try again after the cooldown.
            boss.thought.event("crowd.separate") { "hemmed in, nowhere to give way to" }
            separating = false
            separateCooldownUntil = now + SEPARATION_COOLDOWN
            return false
        }
        val from = crowdYieldFrom
        val forward = FlightMath.dir(boss.yRot, 0f)
        // The neighbour is ahead: reverse away from it with the nose toward it (no turning on top of it).
        if (from != null && forward.x * away.x + forward.z * away.z < 0.0 && moves.rearClear(level)) {
            boss.thought.think("crowd.separate") { "reversing away from the other tank" }
            driver.backAway(from)
        } else {
            boss.thought.think("crowd.separate") { "driving away from the other tank" }
            driver.drive(goal, DriveMode.CRUISE)
        }
        moves.look(null)
        return true
    }

    /**
     * How far this hull reaches from its centre along the horizontal direction ([dx], [dz], a unit vector): the edge of a
     * rectangle of the hull's real length and width, turned the way the tank faces. A long hull reaches twice as far over
     * its nose or tail as over its flank, which a circle of the hitbox width cannot tell.
     */
    fun hullReach(dx: Double, dz: Double): Double {
        val yaw = Math.toRadians(boss.yRot.toDouble())
        val along = abs(-sin(yaw) * dx + cos(yaw) * dz) // component along the hull's forward axis
        val across = abs(cos(yaw) * dx + sin(yaw) * dz)
        val edge = max(along / profile.halfLength, across / profile.halfWidth)
        return if (edge < 1.0e-6) profile.halfWidth else 1.0 / edge
    }

    /** The longest way this hull reaches from its centre (over the nose or tail). */
    fun longestReach(): Double = profile.halfLength

    /**
     * Distance (hull centre to hull centre) this tank wants to keep from [other], which stands along the unit vector
     * ([dx], [dz]) from this tank: the two hulls' reaches towards each other plus [SEPARATION_GAP]; with [touching] just
     * the distance at which the hulls meet.
     */
    private fun separationNeed(other: TankBossEntity, dx: Double, dz: Double, touching: Boolean = false): Double {
        val reaches = hullReach(dx, dz) + other.brain.hullReach(-dx, -dz)
        if (touching) return reaches
        val need = reaches + SEPARATION_GAP
        if (!TankFormations.sameGroup(boss, other)) return need
        // Slots are formationSpacing apart and a follower holds within a few blocks of its slot: stay clearly inside that.
        return min(need, max(TankFormations.spacingFor(boss) * SEPARATION_GROUP_FACTOR, reaches + 1.0))
    }

    /** Refreshes [crowdAway], [crowdYieldAway] and [crowdYieldFrom] from the tank bosses nearby (every few ticks). */
    private fun scanCrowd(level: Level) {
        val now = boss.tickCount
        if (now < crowdNextScan) return
        crowdNextScan = now + SEPARATION_SCAN_INTERVAL

        val release = if (separating) SEPARATION_RELEASE else 1.0
        var steerX = 0.0
        var steerZ = 0.0
        var yieldX = 0.0
        var yieldZ = 0.0
        var yieldNearest = Double.MAX_VALUE
        var yieldFrom: Vec3? = null
        var touching = false
        for (other in TrackedBosses.near(level, boss.position(), SEPARATION_SCAN_RADIUS)) {
            if (other === boss || other !is TankBossEntity || other.isRemoved || abs(other.y - boss.y) > SEPARATION_MAX_DY) continue
            var dx = boss.x - other.x
            var dz = boss.z - other.z
            val distance = sqrt(dx * dx + dz * dz)
            if (distance < 0.05) {
                // On top of each other: every tank leaves along its own direction (the golden angle of its id).
                val angle = boss.id * GOLDEN_ANGLE
                dx = cos(angle)
                dz = sin(angle)
            } else {
                dx /= distance
                dz /= distance
            }
            val ratio = distance / separationNeed(other, -dx, -dz)
            val gives = boss.id > other.id || other.crippled
            if (gives && distance < separationNeed(other, -dx, -dz, touching = true)) touching = true
            // Members of one formation stand in line on purpose (a follower is right behind its leader): they only bend away
            // from each other once they are closer than their wanted distance, or the column would sidestep itself.
            val steerRatio = if (TankFormations.sameGroup(boss, other)) SEPARATION_GROUP_STEER else SEPARATION_STEER
            if (ratio < steerRatio) {
                steerX += dx * (steerRatio - ratio)
                steerZ += dz * (steerRatio - ratio)
            }
            if (ratio < release && gives) {
                yieldX += dx * (release - ratio)
                yieldZ += dz * (release - ratio)
                if (distance < yieldNearest) {
                    yieldNearest = distance
                    yieldFrom = other.position()
                }
            }
        }
        crowdAway = unitOrNull(steerX, steerZ)
        crowdYieldAway = unitOrNull(yieldX, yieldZ)
        crowdYieldFrom = yieldFrom
        crowdTouching = touching && yieldFrom != null
    }

    private fun unitOrNull(x: Double, z: Double): Vec3? {
        val length = sqrt(x * x + z * z)
        return if (length < 1.0e-6) null else Vec3(x / length, 0.0, z / length)
    }

    /** A passable point [SEPARATION_HOP] blocks from here along [away], swung up to 120 degrees to either side; null if none. */
    private fun pickSeparationGoal(level: Level, away: Vec3): Vec3? {
        val position = boss.position()
        for (degrees in SEPARATION_SWING) {
            val radians = Math.toRadians(degrees)
            val c = cos(radians)
            val s = sin(radians)
            val dx = away.x * c - away.z * s
            val dz = away.x * s + away.z * c
            val point = Vec3(position.x + dx * SEPARATION_HOP, position.y, position.z + dz * SEPARATION_HOP)
            val half = Vec3(position.x + dx * SEPARATION_HOP * 0.5, position.y, position.z + dz * SEPARATION_HOP * 0.5)
            if (moves.passable(level, point) && moves.passable(level, half)) return point
        }
        return null
    }

    /**
     * Bends the drive target to the right of a tank that stands on the way (right-hand traffic, so two tanks that meet
     * head on pass on opposite sides). Only a waypoint in the middle of a route is moved: the last one is where the tank
     * brakes and stops, and a shifted one would never count as reached.
     */
    fun steerAround(waypoint: Vec3, last: Boolean): Vec3 {
        val away = crowdAway ?: return waypoint
        if (last) return waypoint
        val position = boss.position()
        val ahead = FlightMath.horizontal(waypoint.subtract(position), FlightMath.dir(boss.yRot, 0f))
        val along = away.x * ahead.x + away.z * ahead.z
        if (along > -0.2) return waypoint // the neighbour is not in the way
        // The part of "away" across the way; head on, the right-hand side.
        var sideX = away.x - ahead.x * along
        var sideZ = away.z - ahead.z * along
        val length = sqrt(sideX * sideX + sideZ * sideZ)
        if (length < 0.25) {
            sideX = -ahead.z
            sideZ = ahead.x
        } else {
            sideX /= length
            sideZ /= length
        }
        return Vec3(
            position.x + ahead.x * SEPARATION_LOOKAHEAD + sideX * SEPARATION_SIDESTEP,
            waypoint.y,
            position.z + ahead.z * SEPARATION_LOOKAHEAD + sideZ * SEPARATION_SIDESTEP
        )
    }

    /** True when another tank boss stands within its separation distance of [point], or is heading for it. */
    fun crowded(level: Level, point: Vec3): Boolean {
        for (other in TrackedBosses.near(level, point, SEPARATION_SCAN_RADIUS)) {
            if (other === boss || other !is TankBossEntity || other.isRemoved) continue
            // The tank's facing at the spot is not known yet: judge with the long reach of both hulls.
            val need = profile.halfLength + other.brain.longestReach() + SEPARATION_GAP
            if (horizontalDistance(point, other.position()) < need) return true
            val destination = other.brain.destination()
            if (destination != null && horizontalDistance(point, destination) < need) return true
        }
        return false
    }

    private companion object {
        /** Blocks added to the two hulls' reaches towards each other (see `hullReach`) to get the wanted distance. */
        const val SEPARATION_GAP = 3.0

        /** A formation member keeps at most this fraction of its formation spacing from the others (slots are a spacing apart). */
        const val SEPARATION_GROUP_FACTOR = 0.6

        /** Giving way stops at this many times the wanted distance; it starts inside 1 times it. */
        const val SEPARATION_RELEASE = 1.25

        /** Steering around a tank starts inside this many times the wanted distance. */
        const val SEPARATION_STEER = 1.6

        /** The same for two tanks of one formation: they only steer round each other once closer than the wanted distance. */
        const val SEPARATION_GROUP_STEER = 1.1
        const val SEPARATION_SCAN_RADIUS = 24.0
        const val SEPARATION_SCAN_INTERVAL = 2
        const val SEPARATION_MAX_DY = 8.0
        const val SEPARATION_HOP = 10.0
        const val SEPARATION_ARRIVE = 3.0
        const val SEPARATION_REPICK = 20
        const val SEPARATION_MAX_TICKS = 100
        const val SEPARATION_COOLDOWN = 60

        /** Steering target: this far ahead along the way and this far to the right of it. */
        const val SEPARATION_LOOKAHEAD = 10.0
        const val SEPARATION_SIDESTEP = 8.0
        const val GOLDEN_ANGLE = 2.399963
        val SEPARATION_SWING = doubleArrayOf(0.0, 40.0, -40.0, 80.0, -80.0, 120.0, -120.0)
    }
}
