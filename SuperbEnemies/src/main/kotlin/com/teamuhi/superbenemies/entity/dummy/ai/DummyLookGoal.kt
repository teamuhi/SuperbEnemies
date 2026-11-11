package com.teamuhi.superbenemies.entity.dummy.ai

import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.player.Player
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns the dummy to face the nearest threat the way a player would: [HumanoidDummyEntity.lookThreat] (by default the
 * nearest boss within [DummyAi.BOSS_RANGE] blocks), otherwise the nearest player within [PLAYER_RANGE]. Runs in every movement mode, so a
 * STATIC dummy still follows the action.
 *
 * Body, head and pitch are turned directly (vanilla's look control only moves the head and adds an offset). While the
 * dummy is following a path, whatever the reason (running to cover, walking home, roaming) it faces where it is running
 * instead: the head traces the path (see [traceWalkingPath]) and the move control turns the body. The threat is picked up
 * again as soon as it stands still. The threat is re-picked every [SCAN_INTERVAL] ticks; aiming happens every tick.
 *
 * In ROAM mode ([HumanoidDummyEntity.roaming]) the dummy also glances around while standing with no threat; with a
 * threat it faces it while standing.
 */
class DummyLookGoal(private val dummy: HumanoidDummyEntity) : Goal() {

    private var threat: Entity? = null
    private var nextScanTick = 0

    private var glanceUntil = 0
    private var glanceX = 0.0
    private var glanceY = 0.0
    private var glanceZ = 0.0

    init {
        flags = EnumSet.of(Flag.LOOK)
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun canUse(): Boolean {
        if (dummy.isPassenger || !dummy.isAlive) return false
        rescanIfDue()
        val can = threat != null || dummy.roaming || dummy.navigation.isInProgress
        dummy.thought.think("look.canUse") {
            if (threat != null) "yes: watching ${threat?.name?.string}"
            else if (dummy.roaming) "yes: roaming, glancing around"
            else if (dummy.navigation.isInProgress) "yes: following its path"
            else "no: nothing to look at"
        }
        return can
    }

    override fun canContinueToUse(): Boolean = canUse()

    override fun stop() {
        dummy.thought.event("look.stop") { "look goal over" }
        threat = null
        glanceUntil = 0
    }

    override fun tick() {
        rescanIfDue()
        // Running anywhere (to cover, back home, roaming): face where the path leads. Near the last node the head just
        // keeps its heading instead of swinging round to the threat a moment before arriving.
        if (dummy.navigation.isInProgress) {
            dummy.thought.think("look.tick") { "looking along its walking path" }
            traceWalkingPath()
            return
        }
        val target = threat
        if (target == null) {
            if (dummy.roaming) {
                dummy.thought.think("look.tick") { "glancing around while roaming" }
                glanceAround()
            }
            return
        }
        dummy.thought.think("look.tick") { "facing ${target.name.string}" }

        // Players are looked at in the face, a (large) aircraft at its centre.
        val aimY = if (target is Player) target.eyeY else target.y + target.bbHeight * 0.5
        val dx = target.x - dummy.x
        val dz = target.z - dummy.z
        val horizontal = hypot(dx, dz)

        if (horizontal > 1.0e-3) {
            val wantedYaw = (Mth.atan2(dz, dx) * RAD_TO_DEG).toFloat() - 90f
            val yaw = Mth.approachDegrees(dummy.yRot, wantedYaw, MAX_TURN_PER_TICK)
            dummy.yRot = yaw
            dummy.yBodyRot = yaw
            dummy.yHeadRot = yaw
        }
        val wantedPitch = (-(Mth.atan2(aimY - dummy.eyeY, horizontal) * RAD_TO_DEG)).toFloat().coerceIn(-dummy.maxLookPitch, dummy.maxLookPitch)
        dummy.xRot = Mth.approachDegrees(dummy.xRot, wantedPitch, MAX_TURN_PER_TICK)
    }

    /**
     * ROAM, walking: the head looks at a path node [TRACE_LOOK_AHEAD] nodes past the one being approached, at eye
     * height, so it turns into corners a moment before the body does. False when the path has no usable node (the
     * threat logic then applies).
     */
    private fun traceWalkingPath(): Boolean {
        val path = dummy.navigation.path ?: return false
        val count = path.nodeCount
        if (count == 0) return false
        val node = path.getNode((path.nextNodeIndex + TRACE_LOOK_AHEAD).coerceAtMost(count - 1))
        val x = node.x + 0.5
        val z = node.z + 0.5
        if (hypot(x - dummy.x, z - dummy.z) < TRACE_MIN_DISTANCE) return false
        dummy.lookControl.setLookAt(x, dummy.eyeY + (node.y - dummy.y).coerceIn(-1.0, 1.0), z, TRACE_TURN_SPEED, TRACE_PITCH_SPEED)
        return true
    }

    /** ROAM, standing and with no threat around: now and then looks at a random spot ahead of the body, like a player. */
    private fun glanceAround() {
        val now = dummy.tickCount
        if (now >= glanceUntil) {
            val rng = dummy.getRandom()
            glanceUntil = now + GLANCE_MIN_TICKS + rng.nextInt(GLANCE_EXTRA_TICKS)
            val yaw = Math.toRadians((dummy.yBodyRot + (rng.nextFloat() * 2f - 1f) * GLANCE_YAW).toDouble())
            val rise = (rng.nextFloat() * 2f - 1f) * GLANCE_RISE
            glanceX = dummy.x - sin(yaw) * GLANCE_DISTANCE
            glanceY = dummy.eyeY + rise
            glanceZ = dummy.z + cos(yaw) * GLANCE_DISTANCE
        }
        dummy.lookControl.setLookAt(glanceX, glanceY, glanceZ, GLANCE_TURN_SPEED, GLANCE_PITCH_SPEED)
    }

    private fun rescanIfDue() {
        val now = dummy.tickCount
        if (now < nextScanTick) {
            // A threat that died or left the level since the last scan is dropped at once.
            threat?.let { if (!it.isAlive || it.level() !== dummy.level()) threat = null }
            return
        }
        nextScanTick = now + SCAN_INTERVAL
        val previous = threat
        threat = dummy.lookThreat()
            ?: dummy.level().getNearestPlayer(dummy, PLAYER_RANGE)
        if (threat !== previous) {
            dummy.thought.event("look.rescan") { "look target ${previous?.name?.string ?: "none"} -> ${threat?.name?.string ?: "none"}" }
        }
    }

    private companion object {
        const val SCAN_INTERVAL = 10
        const val PLAYER_RANGE = 32.0
        const val MAX_TURN_PER_TICK = 25f
        const val RAD_TO_DEG = 57.29577951308232

        const val TRACE_LOOK_AHEAD = 2
        const val TRACE_MIN_DISTANCE = 0.6
        const val TRACE_TURN_SPEED = 30f
        const val TRACE_PITCH_SPEED = 20f

        const val GLANCE_MIN_TICKS = 20
        const val GLANCE_EXTRA_TICKS = 41
        const val GLANCE_YAW = 60f
        const val GLANCE_RISE = 1.5f
        const val GLANCE_DISTANCE = 8.0
        const val GLANCE_TURN_SPEED = 12f
        const val GLANCE_PITCH_SPEED = 8f
    }
}
