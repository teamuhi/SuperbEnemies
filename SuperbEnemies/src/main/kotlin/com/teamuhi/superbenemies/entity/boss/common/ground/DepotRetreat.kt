package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.BossRespawns
import com.teamuhi.superbenemies.entity.boss.common.ResupplyNotices
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath.horizontalDistance
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * The RETREAT state and the withdrawal of a tank that cannot go on: out of ammunition, or wedged. One instance per brain.
 *
 * Out of ammunition ([tick]): drives [RETREAT_DISTANCE] blocks away from the fight. Once it has arrived, or no player can see
 * it any more, the boss is removed and a replacement with the same health and a full load appears around the fight after
 * `resupplySeconds` (see [BossRespawns]).
 *
 * Stuck ([withdrawIfStuck]): a tank that failed to free itself three times is flipped or wedged; it withdraws the same way.
 */
class DepotRetreat(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val driver: GroundDriver,
    private val profile: GroundProfile,
    private val moves: GroundMoves
) {

    private var depot: Vec3? = null
    private var retreatFrom: Vec3? = null

    /** Forgets the depot, so the next [tick] picks one (on entering RETREAT). */
    fun reset() {
        depot = null
    }

    /**
     * Out of ammunition: drives [RETREAT_DISTANCE] blocks away from the fight. Once it has arrived, or no player can see it
     * any more, the boss is removed and a replacement with the same health and a full load appears around the fight after
     * `resupplySeconds` (see [BossRespawns]). [stateTicks] is how long the brain has been in RETREAT.
     */
    fun tick(level: Level, aim: Entity?, stateTicks: Int) {
        val dest = depot ?: run {
            val from = aim?.position() ?: boss.position()
            retreatFrom = from
            pickDepot(level, from)
        }.also {
            depot = it
            boss.thought.event("retreat.depot") { "retreating to rearm, depot at ${it.x.toInt()},${it.z.toInt()}" }
        }
        boss.thought.think("retreat.tick") { "driving away to rearm" }
        val arrived = moves.goTo(level, dest, DriveMode.SPRINT)
        val unseen = stateTicks > RETREAT_MIN_VISIBLE_TICKS &&
                !BossRespawns.playerWithin(boss, settings.bossBarRange.get() + RETREAT_UNSEEN_MARGIN)
        if (arrived || unseen || stateTicks > RETREAT_TIMEOUT || driver.gaveUp) {
            boss.thought.event("retreat.withdraw") {
                "withdrawing to rearm (${if (arrived) "arrived" else if (unseen) "no player can see it" else if (driver.gaveUp) "stuck" else "timed out"})"
            }
            withdraw(aim?.position() ?: retreatFrom ?: boss.homePos)
        }
    }

    private fun pickDepot(level: Level, from: Vec3): Vec3 {
        val position = boss.position()
        val away = awayFrom(from)
        repeat(DEPOT_ATTEMPTS) { i ->
            val spread = Math.toRadians((i - DEPOT_ATTEMPTS / 2) * 25.0)
            val dx = away.x * cos(spread) - away.z * sin(spread)
            val dz = away.x * sin(spread) + away.z * cos(spread)
            val point = Vec3(position.x + dx * RETREAT_DISTANCE, position.y, position.z + dz * RETREAT_DISTANCE)
            if (moves.passable(level, point)) return point
        }
        return position.add(away.scale(RETREAT_DISTANCE * 0.4))
    }

    private fun awayFrom(point: Vec3): Vec3 =
        FlightMath.horizontal(Vec3(boss.x - point.x, 0.0, boss.z - point.z), FlightMath.dir(boss.yRot, 0f))

    private fun withdraw(anchor: Vec3) {
        BossRespawns.withdraw(
            boss, boss.messageKey("hunting"), anchor, boss.homePos,
            settings.resupplySeconds.get().toLong() * 20L, boss.clearanceHalfWidth
        )
    }

    /**
     * A tank that failed to free itself three times is flipped or wedged. It is first set down on the nearest free ground
     * ([GroundRescue]) and carries on; only when no such spot exists does it withdraw, and a replacement with the same
     * health appears nearby (announced as a rearm only with `limitedAmmo`, else as a recovery). Not while it is still in a
     * fight it can shoot in (the turret works without the tracks).
     * @param hidden whether [aim] is out of sight; [retreating] whether the brain is in RETREAT already
     * @return true if the boss was removed
     */
    fun withdrawIfStuck(aim: Entity?, hidden: Boolean, retreating: Boolean): Boolean {
        if (!driver.gaveUp || retreating) return false
        if (aim != null && !hidden && horizontalDistance(boss.position(), aim.position()) < STUCK_FIGHT_DISTANCE) return false
        if (rescueInPlace(null)) return false
        boss.thought.event("retreat.withdrawIfStuck") { "stuck and cannot be set down, withdrawing" }
        // Without limitedAmmo nothing is rearmed: the replacement is only a recovery of a wedged hull, and says so.
        boss.announce(boss.messageKey(if (settings.limitedAmmo.get()) "withdraw" else "recovered"), ResupplyNotices.WITHDRAW_STYLE)
        withdraw(aim?.position() ?: boss.position())
        return true
    }

    /**
     * A tank on a perch (a rooftop or ledge it cannot route off, see `ElevationDetection`) that could not drive off it: set
     * down on ground at least [PERCH_DROP] blocks below where it stands, the same rescue as for a wedged hull.
     * @return false when there is no lower ground to set it down on (the brain then leaves it alone for a while)
     */
    fun rescueFromPerch(): Boolean = rescueInPlace(boss.y - PERCH_DROP)

    /**
     * Sets the wedged tank down on the nearest free ground and starts its driving over (no stuck bookkeeping, a fresh route).
     * With [maxFloorY] only ground at that height or lower counts (see [GroundRescue.findSpot]).
     * @return false when there is no such spot (or the level is not a server level), so the caller withdraws instead
     */
    private fun rescueInPlace(maxFloorY: Double?): Boolean {
        val level = boss.level() as? ServerLevel ?: return false
        val spot = GroundRescue.findSpot(level, boss, boss.clearanceHalfWidth, profile, maxFloorY)
        if (spot == null) {
            boss.thought.event("retreat.rescue") { if (maxFloorY != null) "on a perch, and no lower ground to set the tank down on" else "stuck, and no free ground to set the tank down on" }
            return false
        }
        boss.thought.event("retreat.rescue") {
            (if (maxFloorY != null) "on a perch at ${boss.x.toInt()},${boss.y.toInt()},${boss.z.toInt()}" else "stuck at ${boss.x.toInt()},${boss.z.toInt()}") +
                    ", set down at ${spot.x.toInt()},${spot.y.toInt()},${spot.z.toInt()}"
        }
        boss.setDownAt(spot)
        driver.release()
        driver.resetStuck()
        moves.newGoal()
        return true
    }

    private companion object {
        const val RETREAT_DISTANCE = 90.0
        const val DEPOT_ATTEMPTS = 7
        const val RETREAT_TIMEOUT = 1500

        /** The boss only vanishes this long after leaving, and once no player is within the bar range plus the margin. */
        const val RETREAT_MIN_VISIBLE_TICKS = 100
        const val RETREAT_UNSEEN_MARGIN = 64.0
        const val STUCK_FIGHT_DISTANCE = 60.0

        /** Blocks below the tank the ground has to be for a perch rescue to set it down there (a little under the perch height of [ElevationDetection]). */
        const val PERCH_DROP = 3.0
    }
}
