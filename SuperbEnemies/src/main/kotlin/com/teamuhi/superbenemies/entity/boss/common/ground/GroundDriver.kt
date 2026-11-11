package com.teamuhi.superbenemies.entity.boss.common.ground

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.debug.AiThought
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.Level
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** How hard a [GroundDriver] pushes: sprint is for dashes and flanking. */
enum class DriveMode { CRUISE, SPRINT }

/**
 * Turns "drive to this point" into the key presses Superb Warfare's ground engines understand (the counterpart of
 * `FlightController` for the planes). The engines read the synced booleans `forwardInputDown`, `backInputDown`,
 * `leftInputDown`, `rightInputDown` and `sprintInputDown` (and `upInputDown`, the handbrake) during `super.baseTick()`, so
 * the brain must call [drive] and friends **before** that, exactly like a plane's controller.
 *
 * Steering follows the engines: pressing right turns the yaw up when going forward and down when reversing. Tracked
 * vehicles (`pivots`) turn in place; the brain's yaw error above [PIVOT_START] degrees makes them stop and pivot until it
 * falls under [PIVOT_END]. Wheeled vehicles only turn while they move, so they crawl forward (power pulsed on two ticks
 * out of three) through a sharp turn instead.
 *
 * Stuck recovery: when the driver has asked for movement or a turn most of the last [STUCK_WINDOW] ticks and the vehicle
 * neither moved [STUCK_DISTANCE] blocks nor turned [STUCK_TURN] degrees (a hull wedged on a ledge, or against trunks, spins
 * its tracks without going anywhere), it reverses for [RECOVERY_TICKS] ticks (longer after each failure) with the opposite
 * turn, counts a failure
 * ([stuckFailures]) and raises a replan request ([consumeReplanRequest]) so the planner searches again. Three failures in
 * a row ([gaveUp]) mean the vehicle is flipped or wedged: the brain should withdraw it. The count resets after the vehicle
 * has got [PROGRESS_RESET_DISTANCE] blocks away from where it first failed.
 *
 * Obstacle awareness: while driving, [drive] sweeps the hull's footprint over the next few blocks of the way ([HullSweep],
 * block exact, so a pillar, a barricade or a one block wall the route planner could not see counts). When something is in
 * the way the vehicle swerves: it aims at the nearest heading, up to [MAX_SWERVE] degrees off, whose corridor is clear, and
 * keeps that side for at least [SWERVE_HOLD] ticks so it does not flicker at a corner. It does not sprint while swerving.
 * With no clear heading it pivots (tracks) or backs off ([BACKOFF_TICKS]) and reports the block through [consumeObstacle],
 * so the brain can mark the cell as blocked in its route planner and plan round it.
 *
 * @param pivots true for tracked vehicles (turn in place), false for wheeled ones
 * @param profile the vehicle's [GroundProfile], used to find a bank when it has to leave the water
 */
class GroundDriver(
    private val vehicle: VehicleEntity,
    private val pivots: Boolean,
    private val profile: GroundProfile
) {

    /** The console voice of the boss this driver steers (AI Thinking), or null for a vehicle that is no boss. */
    private val thought: AiThought? = (vehicle as? BossVehicle)?.thought

    /** Failed attempts to get unstuck since the vehicle last made real progress. */
    var stuckFailures = 0
        private set

    /** True after [MAX_STUCK_FAILURES] failures: the vehicle cannot free itself. */
    val gaveUp: Boolean get() = stuckFailures >= MAX_STUCK_FAILURES

    /** True while the reverse manoeuvre of the stuck recovery runs. */
    val recovering: Boolean get() = recoveryLeft > 0

    /** True while a tracked vehicle is turning on the spot (not moving) toward a large heading error. */
    var pivoting = false
        private set

    private var replanRequested = false

    /** The block a swerve could not get round, waiting for [consumeObstacle]. */
    private var obstacle: Vec3? = null

    /** Degrees (positive = counter-clockwise from above) the drive target is turned from the way to the waypoint. */
    private var swerve = 0.0
    private var swerveSince = 0
    private var nextScan = Int.MIN_VALUE
    private var boxedTicks = 0
    private var backoffLeft = 0
    private var lastHit: SweepHit? = null
    private var lastForwardTick = Int.MIN_VALUE / 2
    private var recoveryLeft = 0
    private var recoverySteerRight = false
    private var lastSteerRight = false

    private var windowStart = 0
    private var windowX = 0.0
    private var windowZ = 0.0
    private var demandTicks = 0
    private var turnedInWindow = 0f
    private var failureAnchor: Vec3? = null

    private var lastYaw = Float.NaN
    private var yawRate = 0f
    private var trackedTick = -1

    private var bank: Vec3? = null
    private var bankTick = Int.MIN_VALUE

    /** The profile the obstacle sweep looks ahead with: [profile], or one that may drop while the tank drives off a perch. */
    private var sweepProfile = profile
    private var sweepDrop = 0.0

    /**
     * Lets the look-ahead sweep treat a fall of up to [drop] blocks as drivable (0 = the normal limit again). The brain sets it
     * while the tank drives off a rooftop (see `ElevationDetection`); without it every edge would read as a wall and the tank
     * would swerve and back off from the very ledge it has to leave by.
     */
    fun allowDrop(drop: Double) {
        if (drop == sweepDrop) return
        sweepDrop = drop
        sweepProfile = if (drop > 0.0) profile.withDrop(drop) else profile
        clearSwerve()
    }

    /** Horizontal speed in blocks per tick. */
    val speed: Double get() = vehicle.deltaMovement.horizontalDistance()

    /**
     * True when the last stuck recovery began while the vehicle was driving forward at a waypoint (not reversing or turning):
     * whatever snagged it is then just ahead of the nose, where the brain marks a blocked cell.
     */
    var stuckDrivingForward = false
        private set

    /** True while the drive target is turned away from the waypoint to get round something. */
    val swerving: Boolean get() = swerve != 0.0

    /** The block that blocked the way and could not be driven round, once; null when there is none. */
    fun consumeObstacle(): Vec3? {
        val found = obstacle
        obstacle = null
        return found
    }

    /** True once after a stuck recovery finished: the planner's route is bad and must be searched again. */
    fun consumeReplanRequest(): Boolean {
        val requested = replanRequested
        replanRequested = false
        return requested
    }

    // ================================================================================================== driving

    /**
     * Drives toward [waypoint]. With [stopAtEnd] the vehicle brakes over the last [BRAKE_DISTANCE] blocks and holds
     * still inside [ARRIVE_RADIUS]; without it the vehicle keeps its speed (intermediate waypoints of a route).
     * @return true when [stopAtEnd] is set and the vehicle is within [ARRIVE_RADIUS]
     */
    fun drive(waypoint: Vec3, mode: DriveMode = DriveMode.CRUISE, stopAtEnd: Boolean = false): Boolean {
        observe()
        if (recoverStep() || backoffStep()) return false
        val dx = waypoint.x - vehicle.x
        val dz = waypoint.z - vehicle.z
        val distance = sqrt(dx * dx + dz * dz)
        if (stopAtEnd && distance < ARRIVE_RADIUS) {
            clearSwerve()
            hold()
            return true
        }

        val aim = scout(waypoint, dx, dz, distance)
        if (boxedTicks > 0 && pivots) {
            // Nothing free ahead: turn on the spot toward the most open way (tracks only; wheels back off instead).
            turnToward(aim)
            return false
        }
        val error = yawError(aim.x - vehicle.x, aim.z - vehicle.z)
        val forward = forwardFor(abs(error))
        val braking = stopAtEnd && distance < BRAKE_DISTANCE && speed > brakeSpeed(distance)
        thought?.think("driver.mode") {
            when {
                braking -> "braking for the end of the route"
                !forward -> if (pivots) "pivoting on the spot toward the way" else "crawling through a sharp turn"
                swerve != 0.0 -> "driving $mode, swerving round an obstacle"
                else -> "driving $mode"
            }
        }
        vehicle.forwardInputDown = forward && !braking
        if (forward && !braking) lastForwardTick = vehicle.tickCount
        vehicle.backInputDown = false
        vehicle.upInputDown = braking
        vehicle.sprintInputDown = mode == DriveMode.SPRINT && !braking && !pivoting && abs(error) < SPRINT_ERROR && swerve == 0.0
        steer(error, reversing = false)
        if (braking) {
            // Handbrake plus a steering key is a drift in SBW's engines: brake straight.
            vehicle.leftInputDown = false
            vehicle.rightInputDown = false
        }
        noteDemand(movement = (forward && !braking) || pivoting)
        return false
    }

    /**
     * Backs up while turning the nose toward [facing] (usually the threat): the vehicle retreats with its front armour
     * to the enemy. Keeps going as long as it is called.
     */
    fun backAway(facing: Vec3, mode: DriveMode = DriveMode.CRUISE) {
        observe()
        if (recoverStep()) return
        val error = yawError(facing.x - vehicle.x, facing.z - vehicle.z)
        thought?.think("driver.mode") { "backing away with the front to the threat ($mode)" }
        vehicle.forwardInputDown = false
        vehicle.backInputDown = true
        vehicle.upInputDown = false
        vehicle.sprintInputDown = mode == DriveMode.SPRINT
        pivoting = false
        steer(error, reversing = true)
        noteDemand(movement = true)
    }

    /**
     * Backs up to [point], the tail leading, and holds still once there: a tank that peeks over a crest drops back
     * into cover with its front armour still to the enemy. The steering keeps the nose on the line away from [point] (so the
     * hull travels straight at it); over the last few blocks it squares up to [facing] (usually the threat) instead. The
     * stuck watch runs as in [backAway], and so does the recovery manoeuvre.
     * @param arriveRadius distance from [point] at which the vehicle counts as there and holds still
     * @return true once the vehicle is within [arriveRadius] of [point]
     */
    fun reverseTo(point: Vec3, facing: Vec3, mode: DriveMode = DriveMode.CRUISE, arriveRadius: Double = ARRIVE_RADIUS): Boolean {
        observe()
        if (recoverStep()) return false
        val dx = vehicle.x - point.x
        val dz = vehicle.z - point.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < arriveRadius) {
            hold()
            return true
        }
        thought?.think("driver.mode") { "reversing to a spot, tail first ($mode)" }
        // The nose points away from the goal while the tail leads to it; close in, the hull squares up to the threat.
        val error = if (distance < REVERSE_SQUARE_DISTANCE) yawError(facing.x - vehicle.x, facing.z - vehicle.z) else yawError(dx, dz)
        vehicle.forwardInputDown = false
        vehicle.backInputDown = true
        vehicle.upInputDown = false
        vehicle.sprintInputDown = mode == DriveMode.SPRINT
        pivoting = false
        steer(error, reversing = true)
        noteDemand(movement = true)
        return false
    }

    /**
     * Turns the nose toward [point] without trying to go anywhere: tracks pivot in place, wheels creep forward in short
     * pulses (they cannot turn standing still).
     * @param tolerance heading error in degrees under which the vehicle counts as facing [point]
     * @return true when the heading error is under [tolerance] degrees
     */
    fun turnToward(point: Vec3, tolerance: Float = FACING_TOLERANCE): Boolean {
        observe()
        if (recoverStep()) return false
        val error = yawError(point.x - vehicle.x, point.z - vehicle.z)
        val aligned = abs(error) < tolerance
        thought?.think("driver.mode") {
            if (aligned) "facing the point" else if (pivots) "turning on the spot toward the point" else "creeping forward to turn toward the point"
        }
        vehicle.backInputDown = false
        vehicle.upInputDown = false
        vehicle.sprintInputDown = false
        pivoting = pivots && !aligned
        vehicle.forwardInputDown = !pivots && !aligned && vehicle.tickCount % 3 != 0
        steer(error, reversing = false)
        noteDemand(movement = !aligned)
        return aligned
    }

    /** Stops the vehicle: no throttle, no steering, handbrake while it still rolls. */
    fun hold() {
        thought?.think("driver.mode") { "holding still" }
        observe()
        vehicle.forwardInputDown = false
        vehicle.backInputDown = false
        vehicle.leftInputDown = false
        vehicle.rightInputDown = false
        vehicle.sprintInputDown = false
        vehicle.upInputDown = speed > HOLD_SPEED
        pivoting = false
        noteDemand(movement = false)
    }

    /** Clears every movement input (the vehicle coasts to a halt); also the state after the boss is crippled. */
    fun release() {
        thought?.think("driver.mode") { "controls released" }
        vehicle.forwardInputDown = false
        vehicle.backInputDown = false
        vehicle.leftInputDown = false
        vehicle.rightInputDown = false
        vehicle.upInputDown = false
        vehicle.sprintInputDown = false
        pivoting = false
        recoveryLeft = 0
        backoffLeft = 0
        clearSwerve()
        resetWindow()
    }

    /** Forgets stuck bookkeeping and failures, e.g. when the brain switches to a new activity. */
    fun resetStuck() {
        clearSwerve()
        backoffLeft = 0
        obstacle = null
        stuckFailures = 0
        failureAnchor = null
        recoveryLeft = 0
        replanRequested = false
        resetWindow()
    }

    // ================================================================================================== water

    /** True while the vehicle is in fluid (floating, or sinking on a bed of water). */
    val inWater: Boolean get() = vehicle.isInFluidType

    /**
     * Heads for the nearest low bank (a dry cell the vehicle can climb onto, see [GroundTerrain.nearestBank]); the
     * bank is looked up once per [BANK_REFRESH_TICKS]. Call it every tick while the vehicle should leave the water.
     * @return true while it is in water and driving to a bank; false when it is on land or no bank could be found
     */
    fun exitWater(mode: DriveMode = DriveMode.CRUISE): Boolean {
        if (!inWater) {
            bank = null
            thought?.think("driver.water") { "on land" }
            return false
        }
        val now = vehicle.tickCount
        if (bank == null || now - bankTick >= BANK_REFRESH_TICKS) {
            bank = GroundTerrain.nearestBank(vehicle.level(), vehicle.position(), profile)
            bankTick = now
        }
        val target = bank
        if (target == null) {
            thought?.think("driver.water") { "in water, no bank found" }
            return false
        }
        thought?.think("driver.water") { "in water, driving to the nearest bank" }
        drive(target, mode)
        return true
    }

    // ================================================================================================== internals

    // ---------------------------------------------------------------------------------------------- obstacles

    /**
     * The point [drive] steers at: [waypoint] itself while the way is clear, else a point [SWERVE_TARGET] blocks off along
     * the nearest clear heading. The sweep runs every [SCAN_INTERVAL] ticks; between two sweeps the stored swerve angle is
     * applied to the current bearing. Skipped in fluid (the hull is leaving water) and when the waypoint is within a hull
     * length.
     */
    private fun scout(waypoint: Vec3, dx: Double, dz: Double, distance: Double): Vec3 {
        val now = vehicle.tickCount
        if (inWater || distance <= profile.halfLength + 1.0) {
            clearSwerve()
            return waypoint
        }
        if (nextScan > now + SCAN_INTERVAL) nextScan = now // the tick counter went backwards (entity reloaded)
        val ux = dx / distance
        val uz = dz / distance
        if (now >= nextScan) {
            nextScan = now + SCAN_INTERVAL
            rescan(ux, uz, distance)
        }
        if (swerve == 0.0) return waypoint
        return Vec3(
            vehicle.x + rotateX(ux, uz, swerve) * SWERVE_TARGET,
            waypoint.y,
            vehicle.z + rotateZ(ux, uz, swerve) * SWERVE_TARGET
        )
    }

    /** Sweeps the way to the waypoint and chooses the swerve angle (see [scout]). */
    private fun rescan(ux: Double, uz: Double, distance: Double) {
        val level = vehicle.level()
        val now = vehicle.tickCount
        val look = (LOOK_BASE + speed * LOOK_PER_SPEED).coerceIn(LOOK_MIN, LOOK_MAX).coerceAtMost(distance - profile.halfLength)

        val direct = clearAhead(level, ux, uz, look)
        val directHit = lastHit
        // A swerve is kept for its hold time while its own way stays clear, so the vehicle does not flicker at a corner.
        val holding = swerve != 0.0 && now - swerveSince < SWERVE_HOLD &&
            clearAhead(level, rotateX(ux, uz, swerve), rotateZ(ux, uz, swerve), look) >= look
        if (direct >= look) {
            if (!holding) clearSwerve()
            return
        }
        if (holding) return

        // Something is in the way: the nearest heading with a free corridor, on the side taken before if there is one.
        val first = if (swerve != 0.0) Math.signum(swerve) else if (lastSteerRight) -1.0 else 1.0
        val altLook = min(look, ALT_LOOK)
        var best = direct
        var bestAngle = 0.0
        for (magnitude in SWERVE_ANGLES) for (side in doubleArrayOf(first, -first)) {
            val angle = magnitude * side
            val clear = clearAhead(level, rotateX(ux, uz, angle), rotateZ(ux, uz, angle), altLook)
            if (clear >= altLook) {
                setSwerve(angle, now)
                boxedTicks = 0
                if (abs(angle) >= REPORT_ANGLE) report(directHit)
                return
            }
            if (clear > best) {
                best = clear
                bestAngle = angle
            }
        }
        // No free heading. A short way is still driven (slowly round whatever is there); with none at all the vehicle turns
        // on the spot (tracks), then backs off, and tells the brain where it ran into something.
        setSwerve(bestAngle, now)
        if (best >= STOP_CLEAR) {
            boxedTicks = 0
            return
        }
        boxedTicks++
        report(directHit)
        if (boxedTicks > BOXED_PATIENCE || !pivots) {
            boxedTicks = 0
            backoffLeft = BACKOFF_TICKS
            thought?.event("driver.boxed") { "boxed in with no free heading, backing off and reporting the block" }
        } else {
            thought?.think("driver.swerve") { "boxed in, turning on the spot to look for a way" }
        }
    }

    /** Free distance in front of the hull's nose along the unit direction ([ux], [uz]), at most [look]; [look] when nothing is in the way. */
    private fun clearAhead(level: Level, ux: Double, uz: Double, look: Double): Double {
        val nose = profile.halfLength
        val hit = HullSweep.sweepDir(level, vehicle.x, vehicle.z, vehicle.y, ux, uz, nose, nose + look, sweepProfile)
        lastHit = hit
        return if (hit == null) look else max(0.0, hit.distance - nose)
    }

    /** Hands the brain the block on the direct way ([hit]): the route is wrong there. */
    private fun report(hit: SweepHit?) {
        if (hit != null) obstacle = Vec3(hit.x + 0.5, vehicle.y, hit.z + 0.5)
    }

    private fun setSwerve(angle: Double, now: Int) {
        if (angle != swerve) swerveSince = now
        swerve = angle
        thought?.think("driver.swerve") { if (angle == 0.0) "clear ahead" else "swerving ${if (angle > 0.0) "left" else "right"} round an obstacle" }
    }

    private fun clearSwerve() {
        swerve = 0.0
        boxedTicks = 0
        thought?.think("driver.swerve") { "clear ahead" }
    }

    private fun rotateX(ux: Double, uz: Double, degrees: Double): Double {
        val r = Math.toRadians(degrees)
        return ux * Math.cos(r) - uz * Math.sin(r)
    }

    private fun rotateZ(ux: Double, uz: Double, degrees: Double): Double {
        val r = Math.toRadians(degrees)
        return ux * Math.sin(r) + uz * Math.cos(r)
    }

    /** One tick of the reverse of a boxed-in vehicle (no failure counted, unlike [recoverStep]). @return true while it runs */
    private fun backoffStep(): Boolean {
        if (backoffLeft <= 0) return false
        backoffLeft--
        vehicle.forwardInputDown = false
        vehicle.backInputDown = true
        vehicle.upInputDown = false
        vehicle.sprintInputDown = false
        vehicle.rightInputDown = false
        vehicle.leftInputDown = false
        pivoting = false
        noteDemand(movement = true)
        return true
    }

    /** Yaw error in degrees toward the horizontal vector ([dx], [dz]); positive means the yaw has to increase. */
    private fun yawError(dx: Double, dz: Double): Float {
        if (dx * dx + dz * dz < 1.0e-6) return 0f
        return Mth.wrapDegrees(FlightMath.yaw(Vec3(dx, 0.0, dz)) - vehicle.yRot)
    }

    /** Whether to press forward: always for wheels (pulsed through a sharp turn), only when aligned for tracks. */
    private fun forwardFor(absError: Float): Boolean {
        if (pivots) {
            if (pivoting) {
                if (absError < PIVOT_END) pivoting = false
            } else if (absError > PIVOT_START) {
                pivoting = true
            }
            return !pivoting
        }
        pivoting = false
        return absError < PIVOT_START || vehicle.tickCount % 3 != 0
    }

    /** Brake only while faster than a speed that scales with the distance left, so it rolls up to the point and stops. */
    private fun brakeSpeed(distance: Double): Double = 0.08 + distance * 0.04

    /**
     * Sets left / right from the yaw [error], with a lead on the measured yaw rate so the vehicle stops turning just
     * before it is aligned instead of overshooting. Reversing swaps the keys.
     */
    private fun steer(error: Float, reversing: Boolean) {
        val predicted = error - yawRate * STEER_LEAD
        val turn = abs(error) > STEER_DEADBAND && predicted * error > 0f && abs(predicted) > STEER_DEADBAND * 0.5f
        val increaseYaw = error > 0f
        val right = turn && (increaseYaw != reversing)
        val left = turn && !right
        vehicle.rightInputDown = right
        vehicle.leftInputDown = left
        if (turn) lastSteerRight = right
    }

    /** Samples the yaw rate once per tick (smoothed degrees per tick, positive = yaw increasing). */
    private fun observe() {
        val now = vehicle.tickCount
        if (now == trackedTick) return
        trackedTick = now
        val yaw = vehicle.yRot
        if (!lastYaw.isNaN()) {
            val change = Mth.wrapDegrees(yaw - lastYaw)
            yawRate = yawRate * 0.5f + change * 0.5f
            turnedInWindow += abs(change)
        }
        lastYaw = yaw
    }

    // ---------------------------------------------------------------------------------------------- stuck handling

    private fun resetWindow() {
        windowStart = vehicle.tickCount
        windowX = vehicle.x
        windowZ = vehicle.z
        demandTicks = 0
        turnedInWindow = 0f
    }

    /** Counts a tick on which movement was asked for, and judges the window once it is [STUCK_WINDOW] ticks long. */
    private fun noteDemand(movement: Boolean) {
        val now = vehicle.tickCount
        if (windowStart > now) resetWindow() // the tick counter went backwards (entity reloaded)
        if (movement) demandTicks++
        if (now - windowStart < STUCK_WINDOW) return

        val dx = vehicle.x - windowX
        val dz = vehicle.z - windowZ
        val moved = sqrt(dx * dx + dz * dz)
        val stuck = demandTicks >= STUCK_DEMAND_TICKS && moved < STUCK_DISTANCE && turnedInWindow < STUCK_TURN
        resetWindow()
        if (stuck) beginRecovery()

        // Real progress: far from the first failure, the failures no longer count.
        val anchor = failureAnchor
        if (anchor != null && !stuck) {
            val ax = vehicle.x - anchor.x
            val az = vehicle.z - anchor.z
            if (ax * ax + az * az > PROGRESS_RESET_DISTANCE * PROGRESS_RESET_DISTANCE) {
                stuckFailures = 0
                failureAnchor = null
            }
        }
    }

    private fun beginRecovery() {
        // Each failure backs up further: the first reverse often is not enough to clear a ledge or a trunk.
        stuckDrivingForward = vehicle.tickCount - lastForwardTick <= STUCK_WINDOW
        recoveryLeft = RECOVERY_TICKS + stuckFailures * RECOVERY_EXTRA_TICKS
        recoverySteerRight = !lastSteerRight
        thought?.event("driver.stuck") {
            "stuck${if (stuckDrivingForward) " driving forward" else ""}: reversing for ${recoveryLeft} ticks (failure ${stuckFailures + 1} of $MAX_STUCK_FAILURES)"
        }
        if (failureAnchor == null) failureAnchor = vehicle.position()
    }

    /** Runs one tick of the reverse manoeuvre. @return true while it is active (the caller must not drive that tick) */
    private fun recoverStep(): Boolean {
        if (recoveryLeft <= 0) return false
        recoveryLeft--
        vehicle.forwardInputDown = false
        vehicle.backInputDown = true
        vehicle.upInputDown = false
        vehicle.sprintInputDown = false
        vehicle.rightInputDown = recoverySteerRight
        vehicle.leftInputDown = !recoverySteerRight
        pivoting = false
        if (recoveryLeft == 0) {
            stuckFailures++
            replanRequested = true
            resetWindow()
            thought?.event("driver.stuck") {
                if (gaveUp) "stuck recovery failed $stuckFailures times: cannot free itself" else "stuck recovery over, asking for a new route ($stuckFailures failed so far)"
            }
        }
        return true
    }

    companion object {
        /** Heading error (degrees) above which a tracked vehicle stops and pivots, and wheels crawl. */
        const val PIVOT_START = 70f

        /** A pivoting tracked vehicle drives on again once the error is under this (hysteresis). */
        const val PIVOT_END = 30f

        /** Sprint only while the nose is within this many degrees of the way. */
        const val SPRINT_ERROR = 25f

        /** [turnToward] counts as aligned inside this many degrees. */
        const val FACING_TOLERANCE = 8f

        /** Distance at which a [drive] with `stopAtEnd` counts as arrived. */
        const val ARRIVE_RADIUS = 3.0

        /** [reverseTo] squares the hull up to its facing point inside this many blocks of the goal. */
        const val REVERSE_SQUARE_DISTANCE = 6.0

        /** Distance over which a [drive] with `stopAtEnd` brakes. */
        const val BRAKE_DISTANCE = 12.0

        const val STUCK_WINDOW = 40
        const val STUCK_DEMAND_TICKS = 26
        const val STUCK_DISTANCE = 1.0

        /** A vehicle that turned less than this many degrees in the window is not turning, whatever the keys say. */
        const val STUCK_TURN = 25f
        const val RECOVERY_TICKS = 30
        const val RECOVERY_EXTRA_TICKS = 15
        const val MAX_STUCK_FAILURES = 3
        const val PROGRESS_RESET_DISTANCE = 16.0

        /** Largest swerve (degrees off the way to the waypoint) tried, and the steps up to it. */
        const val MAX_SWERVE = 100.0
        private val SWERVE_ANGLES = doubleArrayOf(15.0, 30.0, 45.0, 60.0, 80.0, MAX_SWERVE)

        /** The sweep ahead is [LOOK_BASE] blocks plus [LOOK_PER_SPEED] per block-per-tick of speed, within [LOOK_MIN]..[LOOK_MAX], from the nose. */
        private const val LOOK_BASE = 8.0
        private const val LOOK_PER_SPEED = 24.0
        private const val LOOK_MIN = 8.0
        private const val LOOK_MAX = 22.0

        /** The other headings are only checked this far ahead. */
        private const val ALT_LOOK = 12.0

        /** Ticks between two sweeps. */
        private const val SCAN_INTERVAL = 3

        /** A swerve is kept at least this many ticks while its own way stays clear. */
        const val SWERVE_HOLD = 14

        /** How far from the vehicle the point is that a swerving vehicle steers at. */
        private const val SWERVE_TARGET = 12.0

        /** Free distance ahead of the nose under which no heading counts as open. */
        private const val STOP_CLEAR = 2.5

        /** Sweeps in a row a tracked vehicle turns on the spot looking for a way before it backs off. */
        private const val BOXED_PATIENCE = 4

        /** Ticks a boxed-in vehicle reverses. */
        const val BACKOFF_TICKS = 24

        /** A swerve of at least this many degrees means the route is wrong there: the brain is told about the block. */
        private const val REPORT_ANGLE = 45.0

        private const val STEER_DEADBAND = 6f
        private const val STEER_LEAD = 3f
        private const val HOLD_SPEED = 0.05
        private const val BANK_REFRESH_TICKS = 20
    }
}
