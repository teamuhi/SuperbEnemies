package com.teamuhi.superbenemies.entity.boss.common.ground

/**
 * What a tank boss is doing. The brain ([TankBossBrain]) moves between these; EVADE and PULL_BACK interrupt any of them
 * and return to the interrupted one, CRIPPLED is terminal.
 */
enum class TankState {
    /** No target: roams within the leash of its home point along planner routes. */
    PATROL,

    /** Drives to a noise it heard (DYNAMIC targeting only). */
    INVESTIGATE,

    /** DYNAMIC only: the target is out of sight; sweeps rings around the last seen spot until `searchSeconds` run out. */
    SEARCH,

    /** Holds `engageRange` from its target, hull to the threat, firing on the move. */
    ENGAGE,

    /** Pops smoke and sprints to a point 60-110 degrees off the line to the target, then goes back to ENGAGE. */
    FLANK,

    /** Reacts to an incoming guided missile (or an aircraft it cannot reach): smoke, a hard turn and a dash. */
    EVADE,

    /** Took a large share of its health in a few seconds: drives to cover (see [PullBackRetreat]), holds there, then resumes. */
    PULL_BACK,

    /** Limited ammo is spent: drives away, then withdraws to rearm (see `BossRespawns`). */
    RETREAT,

    /** Destroyed (the wreck is declared at 0 health, there is no earlier knocked-out phase): the controls are released. */
    CRIPPLED
}
