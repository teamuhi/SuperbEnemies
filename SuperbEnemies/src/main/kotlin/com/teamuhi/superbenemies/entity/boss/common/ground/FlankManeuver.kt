package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath.horizontalDistance
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * The FLANK state: smoke goes up, then the tank sprints to a point on the circle of radius `engageRange` around the target,
 * 60-110 degrees off the line from the target to the boss, and the brain goes back to ENGAGE. The roll that starts one
 * runs in ENGAGE ([roll]); a hit can start one too ([start]). One instance per brain.
 *
 * @param smoke popped when a flank starts, so the sprint is covered
 * @param isPerch whether a point is a perch (a rooftop or pinnacle, see [ElevationDetection.isPerch]); no flank point is one
 * @param onStart called when a flank starts, before its fields are set (the brain enters FLANK)
 */
class FlankManeuver(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val driver: GroundDriver,
    private val moves: GroundMoves,
    private val smoke: SmokeLauncher,
    private val isPerch: (Level, Vec3) -> Boolean,
    private val onStart: () -> Unit
) {

    private var nextFlankRoll = 0
    private var flankCooldownUntil = 0
    private var flankGoal: Vec3? = null
    private var flankEnd = 0

    /** The point being sprinted to, or null outside a flank (other tanks keep clear of it). */
    val goal: Vec3?
        get() = flankGoal

    /** True once the cooldown after the last flank is over. */
    fun ready(now: Int): Boolean = now >= flankCooldownUntil

    /**
     * ENGAGE: every [FLANK_ROLL_MIN] to [FLANK_ROLL_MAX] ticks, with `flankingEnabled` on, rolls `flankChance` percent (more
     * after a recent hit) to start a flank while the target is between 0.4 and 2 times the engage range away.
     * @return true when a flank has started (the brain is in FLANK now and leaves ENGAGE)
     */
    fun roll(level: Level, aim: Entity, distance: Double, range: Double): Boolean {
        val now = boss.tickCount
        if (!settings.flankingEnabled.get()) {
            boss.thought.think("flank.roll") { "flanking is off in the config" }
            return false
        }
        if (now < nextFlankRoll) return false
        nextFlankRoll = now + boss.randomBetween(FLANK_ROLL_MIN, FLANK_ROLL_MAX)
        val chance = settings.flankChance.get() + if (boss.recentlyDamaged(FLANK_HIT_WINDOW)) FLANK_HIT_BONUS else 0
        if (now < flankCooldownUntil) {
            boss.thought.think("flank.roll") { "no flank roll: cooling down after the last one" }
            return false
        }
        if (distance !in range * 0.4..range * 2.0) {
            boss.thought.think("flank.roll") { "no flank roll: ${aim.name.string} outside the flanking band" }
            return false
        }
        if (boss.getRandom().nextInt(100) >= chance) {
            boss.thought.think("flank.roll") { "flank rolled ($chance% chance): staying put" }
            return false
        }
        return start(level, aim)
    }

    /**
     * Starts a flank: a point on the circle of radius `engageRange` around the target, 60-110 degrees off the line from the
     * target to the boss, on either side; smoke goes up first so the sprint is covered. @return false if no point is passable
     */
    fun start(level: Level, aim: Entity): Boolean {
        val goal = pickFlankPoint(level, aim)
        if (goal == null) {
            boss.thought.event("flank.start") { "wanted to flank ${aim.name.string}, no passable point found" }
            return false
        }
        boss.thought.event("flank.start") { "flanking ${aim.name.string}: smoke and sprint to ${goal.x.toInt()},${goal.z.toInt()}" }
        onStart()
        flankGoal = goal
        flankEnd = boss.tickCount + FLANK_TIMEOUT
        flankCooldownUntil = boss.tickCount + FLANK_COOLDOWN
        smoke.pop()
        return true
    }

    private fun pickFlankPoint(level: Level, aim: Entity): Vec3? {
        val target = aim.position()
        val position = boss.position()
        val bearing = FlightMath.horizontal(Vec3(position.x - target.x, 0.0, position.z - target.z), Vec3(1.0, 0.0, 0.0))
        val range = settings.engageRange.get().toDouble()
        val firstSide = if (boss.getRandom().nextBoolean()) 1.0 else -1.0
        repeat(FLANK_ATTEMPTS) { i ->
            val side = if (i % 2 == 0) firstSide else -firstSide
            val angle = Math.toRadians((FLANK_ANGLE_MIN + boss.getRandom().nextInt(FLANK_ANGLE_MAX - FLANK_ANGLE_MIN + 1)) * side)
            val rx = bearing.x * cos(angle) - bearing.z * sin(angle)
            val rz = bearing.x * sin(angle) + bearing.z * cos(angle)
            val distance = range * (0.9 + 0.2 * boss.getRandom().nextDouble())
            val point = Vec3(target.x + rx * distance, position.y, target.z + rz * distance)
            if (moves.passable(level, point) && !moves.crowded(level, point) && !isPerch(level, point)) return point
        }
        return null
    }

    /** Sprints to the flank point. @return true when the flank is over (arrived, timed out, stuck or no route): the brain enters ENGAGE */
    fun tick(level: Level, aim: Entity): Boolean {
        val goal = flankGoal
        if (goal == null || boss.tickCount >= flankEnd || driver.gaveUp) {
            boss.thought.event("flank.end") { if (goal == null) "flank over: no goal" else if (driver.gaveUp) "flank over: stuck" else "flank over: timed out" }
            return true
        }
        val arrived = moves.goTo(level, goal, DriveMode.SPRINT)
        val done = arrived || horizontalDistance(boss.position(), goal) < FLANK_ARRIVE_RADIUS ||
                moves.planner.status == GroundRoutePlanner.Status.NO_ROUTE
        moves.look(aim.position())
        if (done) {
            boss.thought.event("flank.end") { if (moves.planner.status == GroundRoutePlanner.Status.NO_ROUTE) "flank over: no route" else "flank over: arrived" }
        } else {
            boss.thought.think("flank.tick") { "sprinting to the flank point, turret on ${aim.name.string}" }
        }
        return done
    }

    private companion object {
        const val FLANK_ROLL_MIN = 200
        const val FLANK_ROLL_MAX = 360
        const val FLANK_HIT_WINDOW = 80
        const val FLANK_HIT_BONUS = 20
        const val FLANK_ATTEMPTS = 6
        const val FLANK_ANGLE_MIN = 60
        const val FLANK_ANGLE_MAX = 110
        const val FLANK_TIMEOUT = 300
        const val FLANK_COOLDOWN = 500
        const val FLANK_ARRIVE_RADIUS = 12.0
    }
}
