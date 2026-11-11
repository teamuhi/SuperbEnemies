package com.teamuhi.superbenemies.entity.drone.ai

import com.teamuhi.superbenemies.config.DroneConfig
import com.teamuhi.superbenemies.entity.drone.DroneEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.EnumSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The side of its target a drone dives in from. [localAngle] is the horizontal direction of that side seen from the target,
 * counted from the way the target faces (0 = in front of it, a positive angle turns to its right); [TOP] dives from above.
 */
enum class DroneApproach(val localAngle: Double) {
    FRONT(0.0),
    RIGHT(PI / 2),
    BACK(PI),
    LEFT(-PI / 2),
    TOP(0.0)
}

/**
 * Flies the drone at the player or tank it picked ([DroneEntity.chaseTarget]); the drone itself detonates once it is close enough.
 *
 * With `fanOut` an attack has three phases, so a swarm does not arrive in one cluster and go off together:
 * - **Approach:** straight at the target, weaving when `evasive` is on (a sideways swing around the target's centre that
 *   shrinks to nothing as the drone closes in; it changes at random every `jinkSeconds` or so and at once on a dash, see
 *   [DroneEntity.jinkRequested]).
 * - **Strafe:** at a ring round the target (a random distance outside the detonation distance, a random height) the drone
 *   circles it, in a random direction, until it is on the way round that matches the side it chose, at the earliest after a
 *   random strafing time.
 * - **Dive:** straight at the target from that side (left, right, front, back or above), where it detonates as soon as it is
 *   within `detonateDistance`. A dive that has not ended in [DIVE_MAX_TICKS] goes back to strafing with a new side.
 *
 * The side is picked among the sides the other drones on the same target have taken the least, and drones push each other
 * apart, so a swarm spreads over the whole target. Without `fanOut` the drone flies straight at the target all the way.
 *
 * The straight line to the point is checked every few ticks: while it is clear the drone flies it directly (its move control
 * collides with blocks on its own), while a block is in the way the flying navigation finds a path around it.
 *
 * Runs only while there is a target; without one the idle goal takes over.
 */
class DroneAttackGoal(private val drone: DroneEntity) : Goal() {

    private enum class Phase { APPROACH, STRAFE, DIVE }

    private var phase = 0.0
    private var rate = WEAVE_RATE
    private var width = 1.0
    private var nextJink = Int.MAX_VALUE
    private var blocked = false
    private var nextBlockCheck = 0
    private var nextPath = 0

    private var attackPhase = Phase.APPROACH
    private var attackTarget: Entity? = null
    private var phaseStart = 0
    private var minStrafeTicks = 0
    private var orbitSign = 1.0
    private var ringRadius = 0.0
    private var ringHeight = 0.0
    private var separation = Vec3.ZERO
    private var nextSeparationCheck = 0

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun canUse(): Boolean {
        val target = drone.chaseTarget
        val can = drone.isAlive && target != null
        drone.thought.think("canUse") {
            if (can) "yes, chasing ${target?.name?.string}" else if (!drone.isAlive) "no, drone is dead" else "no, no target"
        }
        return can
    }

    override fun canContinueToUse(): Boolean = canUse()

    override fun start() {
        drone.thought.event("start") { "attack goal started, target ${drone.chaseTarget?.name?.string}" }
        blocked = false
        nextBlockCheck = 0
        nextSeparationCheck = 0
        nextPath = 0
        attackTarget = null
        attackPhase = Phase.APPROACH
        jink(drone.tickCount)
    }

    override fun stop() {
        drone.thought.event("stop") { "attack goal stopped, ${if (drone.chaseTarget == null) "target lost" else "interrupted"}" }
        drone.approach = null
        attackTarget = null
        if (drone.navigation.isInProgress) drone.navigation.stop()
    }

    override fun tick() {
        val target = drone.chaseTarget ?: return
        val now = drone.tickCount
        val fanOut = DroneConfig.FAN_OUT.get()
        if (target !== attackTarget) beginAttack(target, now)
        if (!fanOut) {
            attackPhase = Phase.APPROACH
            drone.approach = null
        }

        if (drone.jinkRequested && attackPhase == Phase.STRAFE) {
            orbitSign = -orbitSign // a dash while strafing turns it round
            drone.jinkRequested = false
            drone.thought.event("tick") { "dashed while strafing, orbit now ${orbitName()}" }
        }
        // nextJink is "never" while evasive is off: switching it on mid-chase needs a first jink.
        if (drone.jinkRequested || now >= nextJink || (nextJink == Int.MAX_VALUE && DroneConfig.EVASIVE.get())) jink(now)

        val centre = target.boundingBox.center
        if (fanOut) advancePhase(target, centre, now)

        if (now >= nextSeparationCheck) {
            nextSeparationCheck = now + BLOCK_CHECK_INTERVAL
            separation = if (fanOut) separationPush() else Vec3.ZERO
        }
        val point = when (attackPhase) {
            Phase.APPROACH -> aimPoint(centre).add(separation)
            Phase.STRAFE -> strafePoint(centre).add(separation)
            Phase.DIVE -> divePoint(target, centre).add(separation.scale(DIVE_SEPARATION_SHARE))
        }

        if (now >= nextBlockCheck) {
            nextBlockCheck = now + BLOCK_CHECK_INTERVAL
            blocked = !isClear(point)
        }
        drone.thought.think("steer") {
            val how = if (blocked) "pathfinding round a block" else "flying straight"
            when (attackPhase) {
                Phase.APPROACH -> "approaching the target, $how"
                Phase.STRAFE -> "strafing ${orbitName()} on side ${drone.approach}, $how"
                Phase.DIVE -> "diving in from side ${drone.approach}, $how"
            }
        }
        if (!blocked) {
            if (drone.navigation.isInProgress) drone.navigation.stop()
            drone.moveControl.setWantedPosition(point.x, point.y, point.z, 1.0)
        } else if (now >= nextPath || drone.navigation.isDone) {
            nextPath = now + PATH_INTERVAL
            drone.navigation.moveTo(target.x, target.y + 1.0, target.z, 1.0)
        }
    }

    // ================================================================================================== fan out

    /** A new target (or a first one): back to the approach, with a new ring, a new strafing time and a side to dive in from. */
    private fun beginAttack(target: Entity, now: Int) {
        drone.thought.event("beginAttack") { "new target ${target.name.string}, back to the approach" }
        attackTarget = target
        attackPhase = Phase.APPROACH
        phaseStart = now
        val dice = drone.getRandom()
        ringRadius = target.bbWidth * RING_WIDTH_SHARE + DroneConfig.DETONATE_DISTANCE.get() + RING_MARGIN + dice.nextDouble() * RING_SPREAD
        orbitSign = if (dice.nextBoolean()) 1.0 else -1.0
        chooseSide(target)
    }

    /**
     * Picks the side to dive in from: one of those the other drones on [target] within [SIDE_SCAN_RADIUS] blocks have taken
     * the fewest times (any of them when all are equally taken), and a random ring height and strafing time to go with it.
     */
    private fun chooseSide(target: Entity) {
        val sides = DroneApproach.values()
        val taken = IntArray(sides.size)
        val box = drone.boundingBox.inflate(SIDE_SCAN_RADIUS)
        for (other in drone.level().getEntitiesOfClass(DroneEntity::class.java, box) { it !== drone && it.chaseTarget === target }) {
            other.approach?.let { taken[it.ordinal]++ }
        }
        var fewest = Int.MAX_VALUE
        for (count in taken) if (count < fewest) fewest = count
        val candidates = sides.filter { taken[it.ordinal] == fewest }
        val dice = drone.getRandom()
        val side = candidates[dice.nextInt(candidates.size)]
        drone.approach = side
        ringHeight = if (side == DroneApproach.TOP) {
            target.bbHeight / 2.0 + DroneConfig.DETONATE_DISTANCE.get() + TOP_CLEARANCE + dice.nextDouble() * TOP_SPREAD
        } else {
            RING_LOW + dice.nextDouble() * (RING_HIGH - RING_LOW)
        }
        minStrafeTicks = STRAFE_MIN_TICKS + dice.nextInt(STRAFE_EXTRA_TICKS)
        drone.thought.event("chooseSide") {
            "dive side $side (other drones on this target took ${sides.joinToString { "${it}x${taken[it.ordinal]}" }}), ring height ${"%.1f".format(ringHeight)}, strafe for at least $minStrafeTicks ticks"
        }
    }

    /** Moves to [next] at tick [now] and says why. */
    private fun shiftPhase(next: Phase, now: Int, why: String) {
        val previous = attackPhase
        attackPhase = next
        phaseStart = now
        drone.thought.event("advancePhase") { "$previous -> $next ($why)" }
    }

    private fun orbitName(): String = if (orbitSign > 0.0) "counter-clockwise" else "clockwise"

    /** Approach to strafe once the drone is near the ring; strafe to dive when it is round to its side; dive back to strafe on a miss. */
    private fun advancePhase(target: Entity, centre: Vec3, now: Int) {
        val elapsed = now - phaseStart
        when (attackPhase) {
            Phase.APPROACH -> if (drone.position().distanceTo(centre) <= ringRadius + RING_ENTER_MARGIN) {
                shiftPhase(Phase.STRAFE, now, "near the ring")
            }
            Phase.STRAFE -> {
                val roundToSide = drone.approach == DroneApproach.TOP || abs(angleToSide(target, centre)) <= DIVE_ANGLE
                if ((elapsed >= minStrafeTicks && roundToSide) || elapsed >= STRAFE_MAX_TICKS) {
                    shiftPhase(Phase.DIVE, now, if (elapsed >= STRAFE_MAX_TICKS) "strafed long enough" else "round to its side")
                }
            }
            Phase.DIVE -> if (elapsed >= DIVE_MAX_TICKS) {
                chooseSide(target)
                minStrafeTicks = 0
                shiftPhase(Phase.STRAFE, now, "dive missed, new side")
            }
        }
    }

    /** How far (radians, -pi to pi) the drone is round the target from the middle of the side it chose. */
    private fun angleToSide(target: Entity, centre: Vec3): Double {
        val side = drone.approach ?: return 0.0
        val yaw = Math.toRadians(target.yRot.toDouble())
        val sideAngle = atan2(cos(yaw), -sin(yaw)) + side.localAngle // the way the target faces, turned to the chosen side
        val away = drone.position().subtract(centre)
        return wrapPi(atan2(away.z, away.x) - sideAngle)
    }

    /** The point a little ahead of the drone on its ring, in the direction of the strafe. */
    private fun strafePoint(centre: Vec3): Vec3 {
        val away = drone.position().subtract(centre)
        val angle = atan2(away.z, away.x) + orbitSign * STRAFE_LOOKAHEAD
        return Vec3(centre.x + cos(angle) * ringRadius, centre.y + ringHeight, centre.z + sin(angle) * ringRadius)
    }

    /** Dive at the target's middle (above its head for [DroneApproach.TOP], so the drone arrives over it, not through it). */
    private fun divePoint(target: Entity, centre: Vec3): Vec3 =
        if (drone.approach == DroneApproach.TOP) centre.add(0.0, target.bbHeight / 2.0, 0.0) else centre

    /** A push away from the drones within [SEPARATION_RADIUS] blocks (strongest when touching), at most [SEPARATION_PUSH] blocks long. */
    private fun separationPush(): Vec3 {
        var push = Vec3.ZERO
        val box = drone.boundingBox.inflate(SEPARATION_RADIUS)
        for (other in drone.level().getEntitiesOfClass(DroneEntity::class.java, box) { it !== drone && it.isAlive }) {
            val away = drone.position().subtract(other.position())
            val distance = away.length()
            if (distance >= SEPARATION_RADIUS) continue
            val direction = if (distance < 1.0e-3) Vec3(drone.getRandom().nextDouble() - 0.5, 0.2, drone.getRandom().nextDouble() - 0.5).normalize() else away.scale(1.0 / distance)
            push = push.add(direction.scale((SEPARATION_RADIUS - distance) / SEPARATION_RADIUS))
        }
        val length = push.length()
        return if (length > 1.0) push.scale(SEPARATION_PUSH / length) else push.scale(SEPARATION_PUSH)
    }

    private fun wrapPi(angle: Double): Double {
        var wrapped = angle % (2.0 * PI)
        if (wrapped > PI) wrapped -= 2.0 * PI else if (wrapped < -PI) wrapped += 2.0 * PI
        return wrapped
    }

    // ================================================================================================== weave

    /**
     * Where to fly now: the target's centre, moved sideways (across the line from the drone) and up or down by the weave.
     * The weave is at most [MAX_WEAVE] blocks wide and a third of the distance left, so it fades out on the final approach.
     */
    private fun aimPoint(centre: Vec3): Vec3 {
        if (!DroneConfig.EVASIVE.get()) return centre
        phase += rate
        val toTarget = centre.subtract(drone.position())
        val horizontal = Vec3(toTarget.x, 0.0, toTarget.z)
        if (horizontal.lengthSqr() < 1.0e-4) return centre
        val side = Vec3(-horizontal.z, 0.0, horizontal.x).normalize()
        val amount = min(MAX_WEAVE, toTarget.length() * WEAVE_FRACTION) * width
        return centre.add(side.scale(sin(phase) * amount)).add(0.0, cos(phase * VERTICAL_RATIO) * amount * VERTICAL_SHARE, 0.0)
    }

    /** True when nothing solid lies between the drone and [point]. */
    private fun isClear(point: Vec3): Boolean {
        val hit = drone.level().clip(ClipContext(drone.position(), point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, drone))
        return hit.type == HitResult.Type.MISS
    }

    /**
     * A new weave: a random point in the cycle, speed, direction and width (60% to 140%), until the next jink in about
     * `jinkSeconds`. Without `evasive` there is nothing to change.
     */
    private fun jink(now: Int) {
        drone.jinkRequested = false
        if (!DroneConfig.EVASIVE.get()) {
            nextJink = Int.MAX_VALUE
            return
        }
        val dice = drone.getRandom()
        phase = dice.nextDouble() * 2.0 * Math.PI
        rate = WEAVE_RATE * (0.7 + dice.nextDouble() * 0.6) * (if (dice.nextBoolean()) 1.0 else -1.0)
        width = 0.6 + dice.nextDouble() * 0.8
        nextJink = now + (DroneConfig.JINK_SECONDS.get() * 20 * (0.5 + dice.nextDouble())).toInt()
    }

    private companion object {
        /** Radians per tick of the weave (a full side-to-side swing in about 25 ticks). */
        const val WEAVE_RATE = 0.25
        const val MAX_WEAVE = 3.0
        const val WEAVE_FRACTION = 0.3
        const val VERTICAL_RATIO = 0.7
        const val VERTICAL_SHARE = 0.4
        const val BLOCK_CHECK_INTERVAL = 5
        const val PATH_INTERVAL = 10

        /** Strafing ring: radius is three quarters of the target's width plus the detonation distance plus this much (and up to [RING_SPREAD] more). */
        const val RING_WIDTH_SHARE = 0.75
        const val RING_MARGIN = 2.0
        const val RING_SPREAD = 3.0

        /** The approach turns into the strafe this far (blocks) outside the ring. */
        const val RING_ENTER_MARGIN = 4.0

        /** Ring height above the target's middle for the sides, and the clearance above its head (plus up to [TOP_SPREAD]) for the top. */
        const val RING_LOW = -0.3
        const val RING_HIGH = 1.5
        const val TOP_CLEARANCE = 2.0
        const val TOP_SPREAD = 2.0

        /** The strafe aims this many radians ahead of the drone on its ring. */
        const val STRAFE_LOOKAHEAD = 0.6

        /** Strafing lasts at least this long (plus up to [STRAFE_EXTRA_TICKS]), at most [STRAFE_MAX_TICKS]. */
        const val STRAFE_MIN_TICKS = 25
        const val STRAFE_EXTRA_TICKS = 40
        const val STRAFE_MAX_TICKS = 140

        /** The dive starts once the drone is within this many radians of the middle of its side. */
        const val DIVE_ANGLE = 0.5
        const val DIVE_MAX_TICKS = 60

        /** How far (blocks) other drones are looked for when picking a side. */
        const val SIDE_SCAN_RADIUS = 32.0

        /** Drones closer than this push each other apart (up to [SEPARATION_PUSH] blocks of aim offset); a diving drone feels a share of it. */
        const val SEPARATION_RADIUS = 6.0
        const val SEPARATION_PUSH = 4.0
        const val DIVE_SEPARATION_SHARE = 0.4
    }
}
