package com.teamuhi.superbenemies.entity.boss.common.ground

import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3

/**
 * What the ground behaviours ([StrafeManeuver], [PeekAndShoot], [SnipePosition], [FlankManeuver], [CrowdSeparation],
 * [FormationFollow], [TeamRally], [SearchPattern], [DepotRetreat]) need from the brain: its route planner and driving along
 * it, the turret's look point and its debug log. The brain implements it with a private inner class.
 */
interface GroundMoves {
    /** The brain's route planner; a getter, because the brain builds a new one when `crossWater` changes. */
    val planner: GroundRoutePlanner

    /**
     * Drives toward [goal] along a planned route (see the brain's `goTo`).
     * @return true once the end of the route has been reached
     */
    fun goTo(
        level: Level,
        goal: Vec3,
        mode: DriveMode = DriveMode.CRUISE,
        replanShift: Double = GroundRoutePlanner.REPLAN_GOAL_SHIFT,
        rollOn: Boolean = false
    ): Boolean

    /** Throws the current route away so the next [goTo] plans a new one. */
    fun newGoal()

    /** Whether the hull can stand at [point] (see [GroundTerrain.passable]). */
    fun passable(level: Level, point: Vec3): Boolean

    /** Whether another tank boss stands at, or is heading for, [point] (see [CrowdSeparation.crowded]). */
    fun crowded(level: Level, point: Vec3): Boolean

    /** Whether the ground behind the hull can be reversed onto. */
    fun rearClear(level: Level): Boolean

    /** Where the turret should look when there is no target this tick; null for no preference. */
    fun look(at: Vec3?)

    fun debug(message: String, vararg args: Any?)
}

/** Heading error (degrees) under which a hull in a hold band counts as facing its goal (tracks pivot, wheels creep). */
internal object TankFacing {
    const val TRACKED = 12f
    const val WHEELED = 50f
}

/** The distances of the ENGAGE band, shared by the brain, [PeekAndShoot] and [SnipePosition]. */
internal object EngageBand {
    /** Closes in from beyond this many ranges and keeps closing until inside [ADVANCE_STOP]. */
    const val ADVANCE_START = 1.3
    const val ADVANCE_STOP = 1.0
    const val BACK_OFF_FACTOR = 0.45
    const val BLOCKED_REPOSITION_TICKS = 40
    const val REPOSITION_MIN_DISTANCE = 12.0
    const val SPRINT_HIT_WINDOW = 100
}

/** A random whole number from [min] to [max], both included. */
internal fun TankBossEntity.randomBetween(min: Int, max: Int): Int = min + getRandom().nextInt(max - min + 1)
