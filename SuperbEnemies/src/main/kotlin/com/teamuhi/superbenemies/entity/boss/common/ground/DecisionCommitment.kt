package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.config.TankSettings

/**
 * A stopwatch for "stick to it for a while": the tank bosses used to pick their plan afresh every tick, so a target
 * hovering at the edge of a distance band made them flicker between two plans and a new noise sent them in a new direction
 * every few ticks. A decision that was [commit]ted keeps [ready] false for `decisionCommitSeconds`, and whoever asks only
 * takes a different plan once it turns true (urgent things skip the wait, which is the caller's call). With
 * `decisionCommitSeconds` at 0 it is always ready: every moment decides afresh, as before.
 *
 * One instance per purpose (the engage move, the investigation spot), per brain. Server thread only.
 */
class DecisionCommitment(private val settings: TankSettings) {

    private var lastCommit = NEVER

    /** The length of the commitment in ticks (0 = off). */
    val ticks: Int
        get() = settings.decisionCommitSeconds.get() * 20

    /** True when no decision is being held: nothing was committed yet, the window has run out, or the option is off. */
    fun ready(now: Int): Boolean {
        val wait = ticks
        // A tick counter that went backwards (the entity was reloaded) means the old commitment belongs to another life.
        return wait <= 0 || now < lastCommit || now - lastCommit >= wait
    }

    /** Ticks since the last commit (a large number when there was none). */
    fun age(now: Int): Int = if (lastCommit == NEVER) Int.MAX_VALUE else now - lastCommit

    /** Starts the commitment: the decision just taken is held for [ticks] ticks. */
    fun commit(now: Int) {
        lastCommit = now
    }

    /** Forgets the last commitment, so the next decision is taken at once (a new fight, a new state). */
    fun reset() {
        lastCommit = NEVER
    }

    private companion object {
        const val NEVER = Int.MIN_VALUE / 2
    }
}
