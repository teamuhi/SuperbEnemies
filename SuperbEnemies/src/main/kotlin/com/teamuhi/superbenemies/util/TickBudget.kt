package com.teamuhi.superbenemies.util

import net.minecraft.world.level.Level

/**
 * Caps how many expensive operations (pathfinds, long raycasts, route-search node expansions) all users of one budget
 * may start in one server tick. Each user times its work on its own clock, so with many of them the same tick can
 * collect all of it; a user that is refused simply tries again on the next tick. Server thread only.
 */
internal class TickBudget(private val perTick: Int) {
    private var tick = Long.MIN_VALUE
    private var left = 0

    /** True (and one unit spent) while the budget for the level's current game tick has not run out. */
    fun tryTake(level: Level): Boolean {
        val now = level.gameTime
        if (now != tick) {
            tick = now
            left = perTick
        }
        if (left <= 0) return false
        left--
        return true
    }
}
