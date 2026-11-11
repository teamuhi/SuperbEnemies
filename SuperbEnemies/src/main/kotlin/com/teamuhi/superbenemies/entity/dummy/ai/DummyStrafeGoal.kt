package com.teamuhi.superbenemies.entity.dummy.ai

import com.teamuhi.superbenemies.config.DummyMovement
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.goal.Goal
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The dummy's movement, the way a player dodges: side-to-side strafing with optional jumps, sprint bursts and crouching.
 *
 * Runs in STRAFE mode, or for [HumanoidDummyEntity.forcedStrafeUntil] ticks after the hide goal found no cover (in any
 * mode, ROAM included). In STATIC mode it only walks the dummy back to its spawn point if it was pushed away
 * (knockback, hiding), then stops. ROAM mode is [DummyRoamGoal]'s.
 *
 * Strafing uses vanilla's strafe move control, so the dummy keeps facing the threat while it sidesteps (the look goal
 * turns it). All inputs are re-issued every tick; the config options are read live, so toggling one mid-run takes effect
 * straight away. All state is per instance.
 */
class DummyStrafeGoal(private val dummy: HumanoidDummyEntity) : Goal() {

    private var moveTicks = 0
    private var forward = 0f
    private var left = 0f

    private var sprintTicks = 0
    private var sprintForward = 0f
    private var sprintLeft = 0f

    private var crouchTicks = 0

    /** STATIC mode: no path back to the anchor was found, so stop trying until this tick. */
    private var giveUpUntil = 0

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.JUMP)
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun canUse(): Boolean {
        if (dummy.isPassenger || !dummy.isAlive) return false
        val strafe = strafing()
        val drifted = !strafe && driftedFromAnchor()
        dummy.thought.think("strafe.canUse") {
            if (strafe) "yes: strafing" else if (drifted) "yes: pushed away from the anchor, walking back" else "no: not strafing, at the anchor"
        }
        return strafe || drifted
    }

    override fun canContinueToUse(): Boolean = canUse()

    override fun start() {
        dummy.thought.event("strafe.start") { "strafe goal started (${dummy.settings.movement()})" }
        moveTicks = 0
        sprintTicks = 0
        crouchTicks = 0
    }

    override fun stop() {
        dummy.thought.event("strafe.stop") { "strafe goal over" }
        dummy.navigation.stop()
        dummy.isSprinting = false
        dummy.setCrouched(false)
        sprintTicks = 0
        crouchTicks = 0
    }

    override fun tick() {
        if (!strafing()) {
            walkBack()
            return
        }
        val rng = dummy.getRandom()
        val config = dummy.settings

        // A path left over from walking back would fight the strafe input.
        if (dummy.navigation.isInProgress) dummy.navigation.stop()

        // --- crouch spell: slow, low, never while sprinting ---
        if (crouchTicks > 0) {
            crouchTicks--
            if (!config.crouching()) crouchTicks = 0
            if (crouchTicks == 0) dummy.setCrouched(false)
        } else if (config.crouching() && sprintTicks == 0 && rng.nextInt(CROUCH_CHANCE) == 0) {
            crouchTicks = CROUCH_MIN_TICKS + rng.nextInt(CROUCH_EXTRA_TICKS)
            dummy.setCrouched(true)
            dummy.thought.event("strafe.crouch") { "crouching for $crouchTicks ticks" }
        }

        // --- sprint burst: a random direction, vanilla's +30% comes with the sprint flag ---
        if (sprintTicks > 0) {
            sprintTicks--
            if (!config.sprinting()) sprintTicks = 0
            if (sprintTicks == 0) dummy.isSprinting = false
        } else if (config.sprinting() && crouchTicks == 0 && rng.nextInt(SPRINT_CHANCE) == 0) {
            sprintTicks = SPRINT_MIN_TICKS + rng.nextInt(SPRINT_EXTRA_TICKS)
            val angle = rng.nextFloat() * Mth.TWO_PI
            sprintForward = cos(angle)
            sprintLeft = sin(angle)
            dummy.isSprinting = true
            dummy.thought.event("strafe.sprint") { "sprint burst for $sprintTicks ticks" }
        }

        // --- jump ---
        // Only in STRAFE mode: the temporary fallback in ROAM / STATIC must not make the dummy bounce on the spot.
        if (config.jumping() && config.movement() == DummyMovement.STRAFE && dummy.onGround() && rng.nextInt(JUMP_CHANCE) == 0) dummy.hop()

        // --- direction: sprint burst, else the current strafe leg; the leash overrides both ---
        if (sprintTicks == 0 && --moveTicks <= 0) pickLeg()
        var inputForward = if (sprintTicks > 0) sprintForward else forward
        var inputLeft = if (sprintTicks > 0) sprintLeft else left

        val home = leashDirection()
        if (home != null) {
            inputForward = DummyAi.localForward(dummy.yRot, home.first, home.second)
            inputLeft = DummyAi.localLeft(dummy.yRot, home.first, home.second)
        }

        dummy.thought.think("strafe.move") {
            val how = when {
                home != null -> "heading back towards the anchor (outside the strafe radius)"
                sprintTicks > 0 -> "sprint burst"
                forward > 0f -> "stepping forward"
                forward < 0f -> "stepping back"
                left > 0f -> "strafing left"
                else -> "strafing right"
            }
            if (crouchTicks > 0) "$how, crouched" else how
        }
        dummy.strafeAt(inputForward, inputLeft, if (crouchTicks > 0) CROUCH_SPEED else 1f)
    }

    // ================================================================================================== helpers

    private fun strafing(): Boolean =
        dummy.settings.movement() == DummyMovement.STRAFE || dummy.tickCount < dummy.forcedStrafeUntil

    /**
     * STATIC mode only (ROAM has its own leash in [DummyRoamGoal], and walking back here would compete with it for the
     * move flag): pushed more than [RETURN_DISTANCE] away and a way back is not known to be blocked.
     */
    private fun driftedFromAnchor(): Boolean {
        if (dummy.settings.movement() != DummyMovement.STATIC) return false
        if (dummy.tickCount < giveUpUntil) return false
        val distance = dummy.distanceToAnchor() ?: return false
        return distance > RETURN_DISTANCE
    }

    /** Mostly sideways (left or right), occasionally a step forwards or back; each leg lasts 10-30 ticks. */
    private fun pickLeg() {
        val rng = dummy.getRandom()
        val roll = rng.nextInt(100)
        forward = 0f
        left = 0f
        when {
            roll < 80 -> left = if (rng.nextBoolean()) 1f else -1f
            roll < 90 -> forward = 1f
            else -> forward = -1f
        }
        moveTicks = LEG_MIN_TICKS + rng.nextInt(LEG_EXTRA_TICKS)
    }

    /** Beyond the strafe radius: the horizontal unit vector (x, z) towards the anchor. Null when inside or no anchor. */
    private fun leashDirection(): Pair<Double, Double>? {
        val centre = dummy.anchorCenter() ?: return null
        val dx = centre.x - dummy.x
        val dz = centre.z - dummy.z
        val distance = hypot(dx, dz)
        if (distance <= dummy.settings.strafeRadius() || distance < 1.0e-3) return null
        return Pair(dx / distance, dz / distance)
    }

    /** STATIC mode: pathfind back to the spawn point; the goal ends once it is close enough. */
    private fun walkBack() {
        val centre = dummy.anchorCenter() ?: return
        if (dummy.navigation.isInProgress) return
        if (!DummyAi.pathfinds.tryTake(dummy.level())) return
        if (!dummy.navigation.moveTo(centre.x, centre.y, centre.z, 1.0)) {
            giveUpUntil = dummy.tickCount + GIVE_UP_TICKS
            dummy.thought.event("strafe.walkBack") { "no path back to the anchor, giving up for $GIVE_UP_TICKS ticks" }
        } else {
            dummy.thought.think("strafe.walkBack") { "STATIC mode: walking back to the anchor" }
        }
    }

    private companion object {
        /** One-in-N chance per tick, per behaviour. */
        const val JUMP_CHANCE = 40
        const val SPRINT_CHANCE = 100
        const val CROUCH_CHANCE = 120

        const val LEG_MIN_TICKS = 10
        const val LEG_EXTRA_TICKS = 21
        const val SPRINT_MIN_TICKS = 20
        const val SPRINT_EXTRA_TICKS = 21
        const val CROUCH_MIN_TICKS = 10
        const val CROUCH_EXTRA_TICKS = 31

        /** A player sneaks at 30% of walking speed. */
        const val CROUCH_SPEED = 0.3f

        /** STATIC mode walks back only when pushed this far (blocks) from the spawn point. */
        const val RETURN_DISTANCE = 1.5
        const val GIVE_UP_TICKS = 100
    }
}
