package com.teamuhi.superbenemies.entity.soldier.ai

import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The aiming the launcher soldiers share (the Igla goal and the anti-tank goal): swinging body, head and pitch onto a
 * target, measuring how far off it the launcher points, holding still, and the line-of-sight test.
 *
 * One instance per goal: the aim wobble is state (a smoothed random offset), so goals must not share it. [reset] clears
 * it when an engagement starts.
 */
internal class SoldierAiming(private val soldier: HumanoidDummyEntity) {

    private var wobbleYaw = 0f
    private var wobblePitch = 0f

    /** Forgets the aim wobble (start of an engagement). */
    fun reset() {
        wobbleYaw = 0f
        wobblePitch = 0f
    }

    /** Stands still: no path, no strafing, no sprinting, no crouching. */
    fun hold() {
        soldier.navigation.stop()
        soldier.strafeAt(0f, 0f, 0f) // clears the move control, so a path that was cancelled does not keep walking
        soldier.isSprinting = false
        soldier.setCrouched(false)
    }

    /** The point of [target] the launcher aims at: the centre of its hitbox. */
    fun centreOf(target: Entity): Vec3 = Vec3(target.x, target.y + target.bbHeight * 0.5, target.z)

    /** Angle in degrees between the launcher's direction and the direction from the eyes to the centre of [target]. */
    fun angleTo(target: Entity): Double =
        angleToVector(target.x - soldier.x, target.y + target.bbHeight * 0.5 - soldier.eyeY, target.z - soldier.z)

    /** Angle in degrees between the launcher's direction and the direction given by Minecraft [yaw] and [pitch] (degrees). */
    fun angleToAngles(yaw: Float, pitch: Float): Double {
        val yawRad = yaw * DEG_TO_RAD
        val pitchRad = pitch * DEG_TO_RAD
        return angleToVector(-sin(yawRad) * cos(pitchRad), -sin(pitchRad), cos(yawRad) * cos(pitchRad))
    }

    private fun angleToVector(dx: Double, dy: Double, dz: Double): Double {
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1.0e-6) return 0.0
        val yaw = soldier.yRot * DEG_TO_RAD
        val pitch = soldier.xRot * DEG_TO_RAD
        val dot = (-sin(yaw) * cos(pitch) * dx - sin(pitch) * dy + cos(yaw) * cos(pitch) * dz) / length
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
    }

    /**
     * Swings body, head and pitch toward the centre of [target] at [speed] degrees per tick, plus a small smoothed
     * random wobble so the aim is not machine-perfect.
     */
    fun turnTo(target: Entity, speed: Float) {
        val dx = target.x - soldier.x
        val dz = target.z - soldier.z
        val dy = target.y + target.bbHeight * 0.5 - soldier.eyeY
        val horizontal = hypot(dx, dz)
        val wantedYaw =
            if (horizontal > 1.0e-3) (atan2(dz, dx) * RAD_TO_DEG).toFloat() - 90f else soldier.yRot
        val wantedPitch = (-(atan2(dy, horizontal) * RAD_TO_DEG)).toFloat()
        turnToAngles(wantedYaw, wantedPitch, speed)
    }

    /**
     * Swings onto the Minecraft [yaw] and [pitch] (degrees, pitch positive down) at [speed] degrees per tick, with the
     * wobble. Rotations are written directly (vanilla's look control only moves the head); the look control is also
     * pointed along the new direction every tick, because it resets the pitch of a mob that is not looking at anything.
     */
    fun turnToAngles(yaw: Float, pitch: Float, speed: Float) {
        val rng = soldier.getRandom()
        wobbleYaw = wobbleYaw * WOBBLE_KEEP + (rng.nextFloat() * 2f - 1f) * WOBBLE_DEG * (1f - WOBBLE_KEEP)
        wobblePitch = wobblePitch * WOBBLE_KEEP + (rng.nextFloat() * 2f - 1f) * WOBBLE_DEG * (1f - WOBBLE_KEEP)

        val newYaw = Mth.approachDegrees(soldier.yRot, yaw + wobbleYaw, speed)
        val newPitch = Mth.approachDegrees(soldier.xRot, (pitch + wobblePitch).coerceIn(-MAX_PITCH, MAX_PITCH), speed)
        soldier.yRot = newYaw
        soldier.yBodyRot = newYaw
        soldier.yHeadRot = newYaw
        soldier.xRot = newPitch

        val yawRad = newYaw * DEG_TO_RAD
        val pitchRad = newPitch * DEG_TO_RAD
        soldier.lookControl.setLookAt(
            soldier.x - sin(yawRad) * cos(pitchRad) * LOOK_DISTANCE,
            soldier.eyeY - sin(pitchRad) * LOOK_DISTANCE,
            soldier.z + cos(yawRad) * cos(pitchRad) * LOOK_DISTANCE,
            LOOK_TURN_SPEED, LOOK_TURN_SPEED
        )
    }

    /** SBW's client `noClip` rule (collider clip, fluids ignored): no block between [from] and [to]. */
    fun clearLine(from: Vec3, to: Vec3): Boolean =
        soldier.level().clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, soldier)).type != HitResult.Type.BLOCK

    private companion object {
        const val MAX_PITCH = 89f

        /** Aim wobble: amplitude in degrees and how much of the previous value is kept each tick (smoothing). */
        const val WOBBLE_DEG = 1.0f
        const val WOBBLE_KEEP = 0.9f

        const val LOOK_DISTANCE = 8.0
        const val LOOK_TURN_SPEED = 90f

        const val DEG_TO_RAD = 0.017453292519943295
        const val RAD_TO_DEG = 57.29577951308232
    }
}
