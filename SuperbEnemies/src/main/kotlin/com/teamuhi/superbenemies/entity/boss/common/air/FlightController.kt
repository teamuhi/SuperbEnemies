package com.teamuhi.superbenemies.entity.boss.common.air

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.debug.AiThought
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Angle helpers using Minecraft entity conventions: yaw 0 faces +Z and grows clockwise seen from above
 * (a direction vector is `(-sin yaw, ., cos yaw)`), and **positive pitch looks DOWN**.
 */
object FlightMath {
    fun yaw(dir: Vec3): Float = Math.toDegrees(atan2(-dir.x, dir.z)).toFloat()

    /** Pitch in Minecraft convention: positive = nose down. */
    fun pitch(dir: Vec3): Float {
        val horizontal = sqrt(dir.x * dir.x + dir.z * dir.z)
        return (-Math.toDegrees(atan2(dir.y, horizontal))).toFloat()
    }

    /** Unit direction for a yaw / pitch pair (Minecraft convention, positive pitch = down). */
    fun dir(yaw: Float, pitch: Float): Vec3 {
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())
        return Vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    /** Horizontal unit vector of [v], or [fallback] when [v] has (almost) no horizontal component. */
    fun horizontal(v: Vec3, fallback: Vec3): Vec3 {
        val len = sqrt(v.x * v.x + v.z * v.z)
        return if (len < 1.0e-4) fallback else Vec3(v.x / len, 0.0, v.z / len)
    }

    /** Takes a horizontal heading and tilts it [upDegrees] above (positive) or below (negative) the horizon. */
    fun tilted(heading: Vec3, upDegrees: Double): Vec3 {
        val r = Math.toRadians(upDegrees)
        val h = horizontal(heading, Vec3(0.0, 0.0, 1.0))
        return Vec3(h.x * cos(r), sin(r), h.z * cos(r))
    }

    /** Elevation of [dir] above the horizon in degrees (positive = up). */
    fun elevation(dir: Vec3): Double = -pitch(dir).toDouble()

    fun horizontalDistance(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }
}

/**
 * Turns "fly toward this direction at this throttle" into the control inputs Superb Warfare's aircraft engine
 * (`VehicleEngineUtils.aircraftEngine`) understands. These are the same fields a player's mouse and keyboard write:
 *
 *  - `mouseMoveSpeedX` yaw rate command, positive turns right (yaw increases)
 *  - `mouseMoveSpeedY` pitch rate command, positive pitches the nose DOWN
 *  - `deltaRot` roll rate command, negative banks right (roll increases)
 *  - `power` throttle (about 0 to 3)
 *
 * The aircraft only turns quickly when banked (the yaw rate cap grows with roll angle), so the controller commands a
 * bank proportional to the heading error, like SBW's own loiter autopilot does.
 *
 * The control loops are PD: the proportional term acts on the angle error, the derivative term on the measured
 * rotation rate (from `xRotO`/`yRotO`) so the aircraft does not overshoot.
 * One instance per boss; the entity holds all flight state.
 */
class FlightController(private val boss: VehicleEntity) {

    private val thought: AiThought? get() = (boss as? BossVehicle)?.thought

    /** Set by [pullUp] for the one [steer] call it makes, so the mode shows as a pull-up. */
    private var pullingUp = false

    /** Angle in degrees between where the nose points now and [desiredDir]. */
    fun angleErrorDeg(desiredDir: Vec3): Double {
        if (desiredDir.lengthSqr() < 1.0e-6) return 0.0
        val view = boss.getViewVector(1f)
        val dot = (view.dot(desiredDir.normalize()) / max(view.length(), 1.0e-6)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }

    /**
     * @param desiredDir world-space direction the nose should point to (need not be normalised)
     * @param throttle target throttle; eased in (lerp 0.1), not applied instantly
     * @param maxBank largest bank angle in degrees. Lift is lost when banked, so keep it small for pull-outs
     * @param airBrake extends the speed brakes (SBW downInputDown)
     * @param converge when the nose is within [CONVERGE_ANGLE] degrees, also nudges xRot/yRot directly, the same
     *        trick aircraftLoiter uses, so the guns can settle on the target
     * @param stallGuard when slow, adds power and refuses a climb. Off only for the stall turn, which hangs on purpose
     */
    fun steer(
        desiredDir: Vec3,
        throttle: Float,
        maxBank: Float = 60f,
        airBrake: Boolean = false,
        converge: Boolean = false,
        stallGuard: Boolean = true
    ) {
        // The brain flies the plane now, not SBW's orbit autopilot.
        if (boss.loiterActive) boss.loiterActive = false

        val dir = if (desiredDir.lengthSqr() < 1.0e-6) FlightMath.dir(boss.yRot, 0f) else desiredDir.normalize()
        val speed = boss.deltaMovement.length()

        var wantedPitch = FlightMath.pitch(dir)
        var wantedThrottle = throttle
        if (stallGuard && speed < STALL_SPEED) {
            // Stall guard: add power and never ask for a climb while this slow.
            wantedThrottle = max(wantedThrottle, STALL_THROTTLE)
            wantedPitch = max(wantedPitch, STALL_RECOVERY_PITCH)
        }

        val yawError = Mth.wrapDegrees(FlightMath.yaw(dir) - boss.yRot)
        val pitchError = Mth.wrapDegrees(wantedPitch - boss.xRot)
        thought?.think("flight.steer") {
            val mode = when {
                pullingUp -> "pull-up, wings level"
                stallGuard && speed < STALL_SPEED -> "stall guard, too slow: adding power"
                else -> "steering"
            }
            if (airBrake) "$mode, air brake out" else mode
        }
        pullingUp = false
        val yawRate = Mth.wrapDegrees(boss.yRot - boss.yRotO)
        val pitchRate = boss.xRot - boss.xRotO

        boss.mouseMoveSpeedY = (pitchError * PITCH_KP - pitchRate * PITCH_KD).coerceIn(-MAX_MOUSE_Y, MAX_MOUSE_Y)
        boss.mouseMoveSpeedX = (yawError * YAW_KP - yawRate * YAW_KD).coerceIn(-MAX_MOUSE_X, MAX_MOUSE_X)

        // Roll-aware yaw: bank into the turn. Near-vertical flight has no meaningful heading, so level the wings.
        val bankTarget = if (abs(wantedPitch) > 75f) 0f else (yawError * BANK_PER_DEGREE).coerceIn(-maxBank, maxBank)
        boss.deltaRot = -((bankTarget - boss.roll) * ROLL_GAIN).coerceIn(-MAX_ROLL_RATE, MAX_ROLL_RATE)

        boss.power = Mth.lerp(THROTTLE_EASING, boss.power, wantedThrottle)
        boss.downInputDown = airBrake
        clearButtons()

        if (converge && angleErrorDeg(dir) < CONVERGE_ANGLE) {
            boss.yRot += yawError * NUDGE_FACTOR
            boss.xRot = Mth.clamp(boss.xRot + pitchError * NUDGE_FACTOR, -89f, 89f)
        }
    }

    /**
     * Overrides the roll command of the last [steer] call with a constant full-rate roll, for barrel rolls.
     * [side] > 0 rolls right (roll angle increases), < 0 rolls left. Pitch and yaw keep following [steer].
     */
    fun applyRoll(side: Double) {
        boss.deltaRot = (-side.coerceIn(-1.0, 1.0) * MAX_ROLL_RATE).toFloat()
    }

    /**
     * Swings the nose toward [desiredDir]'s heading by at most [maxStepDeg] per tick by writing the yaw directly, for
     * the stall turn: SBW's yaw rate is capped by the roll angle and the wings level out near vertical, so a
     * rudder-only pivot at the top of a climb would take far too long. Call after [steer] (which still commands pitch).
     */
    fun pivotYaw(desiredDir: Vec3, maxStepDeg: Float) {
        if (desiredDir.lengthSqr() < 1.0e-6) return
        val yawError = Mth.wrapDegrees(FlightMath.yaw(desiredDir) - boss.yRot)
        boss.yRot += yawError.coerceIn(-maxStepDeg, maxStepDeg)
    }

    /** Wings nearly level, nose up, full power. Used by the terrain guard and the pull-out. */
    fun pullUp(heading: Vec3, urgency: Double) {
        val u = urgency.coerceIn(0.0, 1.0)
        pullingUp = true
        steer(FlightMath.tilted(heading, 12.0 + 33.0 * u), (2.4 + 0.6 * u).toFloat(), maxBank = 20f)
    }

    /** Releases the sticks. The aircraft keeps whatever attitude it has (or crashes, once crippled). */
    fun release() {
        thought?.think("flight.steer") { "controls released" }
        boss.mouseMoveSpeedX = 0f
        boss.mouseMoveSpeedY = 0f
        boss.deltaRot = 0f
        boss.downInputDown = false
        clearButtons()
    }

    private fun clearButtons() {
        boss.forwardInputDown = false
        boss.backInputDown = false
        boss.leftInputDown = false
        boss.rightInputDown = false
        boss.upInputDown = false
        boss.sprintInputDown = false
    }

    private companion object {
        // Tuning constants. Unverified in game: adjust here if the aircraft hunts or turns too slowly.
        const val STALL_SPEED = 1.2
        const val STALL_THROTTLE = 3.0f
        const val STALL_RECOVERY_PITCH = 6f

        const val PITCH_KP = 1.5f
        const val PITCH_KD = 1.0f
        const val YAW_KP = 1.0f
        const val YAW_KD = 0.6f
        const val MAX_MOUSE_Y = 22f
        const val MAX_MOUSE_X = 20f

        const val BANK_PER_DEGREE = 1.4f
        const val ROLL_GAIN = 0.25f
        const val MAX_ROLL_RATE = 5f

        const val THROTTLE_EASING = 0.1f

        const val CONVERGE_ANGLE = 8.0
        const val NUDGE_FACTOR = 0.12f
    }
}
