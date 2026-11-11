package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath.horizontalDistance
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The SEARCH sweep: a ring of waypoints around the search centre, nearest first. A finished ring is replaced by a wider one
 * (up to [SEARCH_MAX_RADIUS]). The brain decides when a search starts and when its time is up ([expired]); this class holds
 * the centre, the ring and the driving along it, and saves and loads the search. One instance per brain.
 */
class SearchPattern(
    private val boss: TankBossEntity,
    private val driver: GroundDriver,
    private val moves: GroundMoves
) {

    private var searchCenter: Vec3? = null
    private var searchEnd = 0
    private var searchStart = 0
    private var searchForTarget = false
    private var searchRadius = 0.0
    private val searchPoints = ArrayList<Vec3>()
    private var searchIndex = 0
    private var searchPointSince = 0

    /** True when the search hunts a target that went out of sight (running out of time then makes the brain forget it). */
    val forTarget: Boolean
        get() = searchForTarget

    /** Begins a search around [center] that lasts [ticks] ticks. */
    fun start(center: Vec3, ticks: Int, forTarget: Boolean) {
        boss.thought.event("search.start") {
            "searching ${if (forTarget) "for a lost target" else "a heard spot"} around ${center.x.toInt()},${center.z.toInt()} for ${ticks / 20}s"
        }
        searchCenter = center
        searchStart = boss.tickCount
        searchEnd = boss.tickCount + ticks
        searchForTarget = forTarget
        searchRadius = 24.0 + boss.getRandom().nextInt(25)
        searchPoints.clear()
        searchIndex = 0
    }

    /** True when there is no centre or the time is up: the boss forgets the target and goes back to patrolling. */
    fun expired(now: Int): Boolean = searchCenter == null || now >= searchEnd

    /** Whether the boss is still within [ticks] ticks of the start, when it shells the last seen spot (suppressing fire). */
    fun suppressing(now: Int, ticks: Int): Boolean = now - searchStart < ticks

    /** The waypoint being driven to, or null when there is none (other tanks keep clear of it). */
    fun currentPoint(): Vec3? = searchPoints.getOrNull(searchIndex)

    /** A noise led somewhere else: moves the centre to [pos] and gives the search at least [extendTicks] more ticks. */
    fun hear(pos: Vec3, extendTicks: Int) {
        boss.thought.event("search.hear") { "search centre moved to ${pos.x.toInt()},${pos.z.toInt()}, time extended" }
        searchCenter = pos
        searchPoints.clear()
        searchIndex = 0
        searchEnd = max(searchEnd, boss.tickCount + extendTicks)
    }

    /** Drives the ring of waypoints around the search centre, nearest first (the caller has checked [expired]). */
    fun tick(level: Level) {
        val center = searchCenter ?: return
        if (searchIndex >= searchPoints.size) {
            if (searchPoints.isNotEmpty()) searchRadius = min(searchRadius + SEARCH_WIDEN, SEARCH_MAX_RADIUS)
            buildSearchRing(level, center)
            boss.thought.event("search.ring") { "new search ring of ${searchPoints.size} points, radius ${searchRadius.toInt()}" }
        }
        val point = searchPoints.getOrNull(searchIndex)
        if (point == null) {
            boss.thought.think("search.tick") { "nothing passable on the ring, holding" }
            driver.hold()
            return
        }
        boss.thought.think("search.tick") { "driving to search point ${searchIndex + 1} of ${searchPoints.size}" }
        val arrived = moves.goTo(level, point)
        val near = horizontalDistance(boss.position(), point) < SEARCH_POINT_RADIUS
        if (arrived || near || boss.tickCount - searchPointSince > SEARCH_POINT_TIMEOUT ||
            moves.planner.status == GroundRoutePlanner.Status.NO_ROUTE
        ) {
            searchIndex++
            searchPointSince = boss.tickCount
            moves.newGoal()
        }
    }

    private fun buildSearchRing(level: Level, center: Vec3) {
        searchPoints.clear()
        searchIndex = 0
        searchPointSince = boss.tickCount
        val base = boss.getRandom().nextDouble() * Math.PI * 2.0
        for (i in 0 until SEARCH_POINTS) {
            val angle = base + i * (Math.PI * 2.0 / SEARCH_POINTS)
            val point = Vec3(center.x + cos(angle) * searchRadius, boss.y, center.z + sin(angle) * searchRadius)
            if (moves.passable(level, point) && !moves.crowded(level, point)) searchPoints.add(point)
        }
        val position = boss.position()
        searchPoints.sortBy { horizontalDistance(position, it) }
        // A ring with nothing passable on it: stand on the centre itself.
        if (searchPoints.isEmpty() && moves.passable(level, center)) searchPoints.add(center)
        moves.newGoal()
    }

    /** Writes the search into [tag] (nothing when there is no centre). */
    fun save(tag: CompoundTag) {
        val center = searchCenter ?: return
        tag.putDouble(TAG_SEARCH_X, center.x)
        tag.putDouble(TAG_SEARCH_Y, center.y)
        tag.putDouble(TAG_SEARCH_Z, center.z)
        tag.putInt(TAG_SEARCH_LEFT, max(0, searchEnd - boss.tickCount))
        tag.putBoolean(TAG_SEARCH_TARGET, searchForTarget)
        tag.putDouble(TAG_SEARCH_RADIUS, searchRadius)
        tag.putInt(TAG_SEARCH_INDEX, searchIndex)
    }

    /** @return false when [tag] holds no search (the brain falls back to PATROL) */
    fun load(tag: CompoundTag): Boolean {
        if (!tag.contains(TAG_SEARCH_X)) return false
        searchCenter = Vec3(tag.getDouble(TAG_SEARCH_X), tag.getDouble(TAG_SEARCH_Y), tag.getDouble(TAG_SEARCH_Z))
        searchForTarget = tag.getBoolean(TAG_SEARCH_TARGET)
        searchRadius = tag.getDouble(TAG_SEARCH_RADIUS)
        searchIndex = tag.getInt(TAG_SEARCH_INDEX)
        // The tick counter restarts with the entity: the saved remainder is counted from the first tick again.
        searchEnd = tag.getInt(TAG_SEARCH_LEFT)
        // Long ago: a loaded search does not shell the last seen spot again (the same as SUPPRESS_TICKS before the first tick).
        searchStart = Int.MIN_VALUE / 2
        searchPoints.clear()
        return true
    }

    private companion object {
        const val TAG_SEARCH_X = "SearchX"
        const val TAG_SEARCH_Y = "SearchY"
        const val TAG_SEARCH_Z = "SearchZ"
        const val TAG_SEARCH_LEFT = "SearchLeft"
        const val TAG_SEARCH_TARGET = "SearchTarget"
        const val TAG_SEARCH_RADIUS = "SearchRadius"
        const val TAG_SEARCH_INDEX = "SearchIndex"

        const val SEARCH_POINTS = 5
        const val SEARCH_POINT_RADIUS = 8.0
        const val SEARCH_POINT_TIMEOUT = 600
        const val SEARCH_WIDEN = 16.0
        const val SEARCH_MAX_RADIUS = 96.0
    }
}
