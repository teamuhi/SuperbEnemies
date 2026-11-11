package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.debug.AiThought
import com.teamuhi.superbenemies.util.TickBudget
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Route finding for one ground boss: an incremental A* over the [GroundTerrain] cell grid (4 blocks per cell), spread over
 * server ticks by a node budget shared by every planner ([NODES_PER_TICK] expansions per tick in total), so any number of
 * tanks cannot stall a tick. The boss keeps following its previous route while a new one is being searched.
 *
 * Use: call [request] every tick with where the boss wants to go (cheap while nothing changed), [tick] once, then steer
 * with [nextWaypoint]. A search restarts when the goal moved more than [REPLAN_GOAL_SHIFT] blocks from the one the
 * route was made for, when the route is older than [REPLAN_AGE_TICKS], or after [invalidate] (the boss got stuck).
 *
 * Search area: a square of [MAX_RADIUS] blocks around the start. A goal outside it, unreachable (deep water for a sinking
 * vehicle, a cliff top, an unloaded chunk) or itself impassable ends the search at the **closest reachable cell** to the
 * goal ([partial] is then true); the boss drives there and asks again. A search that cannot even leave the start cell
 * ends in [Status.NO_ROUTE].
 *
 * The raw cell path is shortened with grid line-walk checks, so the waypoints are as far apart as straight, equally cheap
 * driving allows (at most [SMOOTH_LOOKAHEAD] cells = 32 blocks apart).
 *
 * Block awareness: the cell grid judges a cell from five sampled columns, so a pillar, a barricade or a one block wall can
 * hide inside a cell the search calls open. Two things close that gap. Every line of the smoothed route, and every cell
 * step of it, is checked with a block-exact sweep of the hull's footprint ([HullSweep]); a step that fails marks its cell
 * as blocked and the search runs again (up to [MAX_VERIFY_TRIES] times) so the route goes round. And the driver, which
 * looks ahead of the hull while it drives, reports what it meets through [markBlocked] and asks for a new route with
 * [reroute] (the old one is followed meanwhile). A blocked cell is remembered for [BLOCK_TTL_TICKS] ticks and counts as
 * impassable in every search of this planner.
 *
 * Breaching: the planner only ever routes round a wall. When that detour is long or there is no route at all, [WallBreach]
 * (driven from the brain's `goTo`) weighs it against shooting a hole through a thin, breakable wall on the straight line to
 * the goal; [remainingLength] and [status] / [partial] are what it reads. Once the tank has driven through the hole it throws
 * the route away ([invalidate]), because the cell grid cannot see a hole (a tunnel leaves the heightmap as it was) and the
 * old route still leads round the wall. Server thread only.
 *
 * [thought] is the owning boss's console voice for the AI Thinking debug option (null when the planner has no boss).
 */
class GroundRoutePlanner(private val profile: GroundProfile, private val thought: AiThought? = null) {

    enum class Status {
        /** Nothing requested, or [invalidate] / [clear] was called. */
        IDLE,

        /** A search is running (a previous route, if any, is still followed). */
        SEARCHING,

        /** A route exists. */
        READY,

        /** The last search found nothing but the start cell. */
        NO_ROUTE
    }

    var status = Status.IDLE
        private set

    /** True when the current route ends at the closest reachable cell instead of at the goal. */
    var partial = false
        private set

    /** The goal the current route (or, with none, the running search) was made for. */
    var goal: Vec3? = null
        private set

    /** True once the last waypoint has been reached; stays true until a new route replaces it. */
    var arrived = false
        private set

    private var route: List<Vec3> = emptyList()
    private var routeIndex = 0
    private var routeTick = 0L
    private var search: Search? = null

    /** Cells the hull cannot pass although the grid says so (cell key to the `gameTime` the mark runs out). */
    private val blocked = HashMap<Long, Long>()

    /** Searches in a row whose route failed the block-exact check (see [finish]). */
    private var verifyTries = 0

    /** True while a search is running. */
    val searching: Boolean get() = search != null

    /** True when there is a route to follow (a finished one may be [arrived]). */
    val hasRoute: Boolean get() = route.isNotEmpty()

    /** The whole current route, start to end, for debugging. */
    val waypoints: List<Vec3> get() = route

    /**
     * Horizontal length of the part of the route still to drive from [position]: to the waypoint being followed and on through
     * the rest, 0 with no route or once [arrived]. Compared with the straight distance to the [goal] it tells how big a
     * detour the route is, which is what [WallBreach] weighs against shooting a way through.
     */
    fun remainingLength(position: Vec3): Double {
        if (route.isEmpty() || arrived) return 0.0
        var total = 0.0
        var previous = position
        for (i in routeIndex until route.size) {
            total += sqrt(horizontalDistanceSqr(previous, route[i]))
            previous = route[i]
        }
        return total
    }

    // ================================================================================================== use

    /**
     * Asks for a route from [from] to [goal]. Starts a search when there is no route yet, when [goal] is more than
     * [REPLAN_GOAL_SHIFT] blocks from the goal of the current route or search, or when the route is older than
     * [REPLAN_AGE_TICKS]; otherwise it does nothing, so it is safe to call every tick. A caller whose goal keeps moving
     * (a formation slot) passes a smaller [replanShift] so the route follows it more closely.
     */
    fun request(level: Level, from: Vec3, goal: Vec3, replanShift: Double = REPLAN_GOAL_SHIFT) {
        val now = level.gameTime
        val current = this.goal
        val goalMoved = current == null || horizontalDistanceSqr(current, goal) > replanShift * replanShift
        val stale = route.isNotEmpty() && search == null && now - routeTick > REPLAN_AGE_TICKS
        val missing = route.isEmpty() && search == null && status != Status.NO_ROUTE
        // A failed search is retried after a while (the world changes: chunks load, doors open).
        val retry = status == Status.NO_ROUTE && search == null && now - routeTick > RETRY_TICKS
        if (!goalMoved && !stale && !missing && !retry) return

        thought?.event("planner.request") {
            val why = when {
                current == null -> "new goal"
                goalMoved -> "goal moved"
                stale -> "route is old"
                retry -> "retrying after no route"
                else -> "no route yet"
            }
            "route search to ${goal.x.toInt()},${goal.z.toInt()} ($why)"
        }
        this.goal = goal
        verifyTries = 0
        search = Search(level, from, goal, now)
        if (route.isEmpty()) status = Status.SEARCHING
    }

    /**
     * Starts a new search for the current goal from [from] without dropping the route being followed, for a boss that ran
     * into something the route did not know about (see [markBlocked]). Does nothing without a goal.
     */
    fun reroute(level: Level, from: Vec3) {
        val target = goal ?: return
        thought?.event("planner.reroute") { "searching again for ${target.x.toInt()},${target.z.toInt()}, keeping the old route meanwhile" }
        verifyTries = 0
        search = Search(level, from, target, level.gameTime)
        if (route.isEmpty()) status = Status.SEARCHING
    }

    /**
     * Remembers that the hull cannot pass the cell holding ([x], [z]) for [BLOCK_TTL_TICKS] ticks: every later search treats
     * it as a wall. The cell the search starts in and the one it ends in are never refused, so a mark next to the boss cannot
     * wall it in.
     */
    fun markBlocked(level: Level, x: Double, z: Double) {
        val now = level.gameTime
        thought?.event("planner.markBlocked") { "cell at ${x.toInt()},${z.toInt()} marked as blocked for ${BLOCK_TTL_TICKS / 20}s" }
        if (blocked.size >= BLOCK_CAP) blocked.values.removeIf { it <= now }
        if (blocked.size >= BLOCK_CAP) blocked.remove(blocked.entries.minByOrNull { it.value }?.key)
        blocked[cellKey(GroundTerrain.cellOf(x, profile), GroundTerrain.cellOf(z, profile))] = now + BLOCK_TTL_TICKS
    }

    private fun isBlocked(cx: Int, cz: Int, now: Long): Boolean {
        if (blocked.isEmpty()) return false
        val until = blocked[cellKey(cx, cz)] ?: return false
        if (until <= now) {
            blocked.remove(cellKey(cx, cz))
            return false
        }
        return true
    }

    /** Spends the shared node budget on this planner's search; call once per boss tick. */
    fun tick(level: Level) {
        val running = search ?: return
        while (budget.tryTake(level)) {
            if (running.step()) {
                finish(running, level.gameTime)
                return
            }
        }
    }

    /**
     * The waypoint to drive to now, advancing past the ones already reached or passed, or null when there is no route or
     * its end has been reached ([arrived]). Waypoints are on the ground (y is the standing height of the cell).
     */
    fun nextWaypoint(position: Vec3): Vec3? {
        if (route.isEmpty() || arrived) return null
        while (true) {
            val last = routeIndex >= route.size - 1
            val wp = route[routeIndex]
            val distance = sqrt(horizontalDistanceSqr(position, wp))
            if (last) {
                if (distance < FINAL_RADIUS) {
                    arrived = true
                    return null
                }
                return wp
            }
            val next = route[routeIndex + 1]
            val passed = (wp.x - position.x) * (next.x - wp.x) + (wp.z - position.z) * (next.z - wp.z) < 0.0 &&
                distance < PASSED_RADIUS
            if (distance < WAYPOINT_RADIUS || passed) routeIndex++ else return wp
        }
    }

    /** Throws the route and any search away (the boss is stuck on it); the next [request] plans again from scratch. */
    fun invalidate() {
        if (route.isNotEmpty() || search != null || goal != null) thought?.event("planner.invalidate") { "route and search thrown away" }
        route = emptyList()
        routeIndex = 0
        search = null
        arrived = false
        partial = false
        goal = null
        status = Status.IDLE
    }

    /** Same as [invalidate]; the name for a brain that simply switches to another activity. */
    fun clear() = invalidate()

    // ================================================================================================== finish

    private fun finish(done: Search, now: Long) {
        search = null
        routeTick = now
        val end = done.result
        if (end == null || (end.parent == null && !done.reachedGoal)) {
            // Nothing but the start cell: keep an old route if there is one, else report that there is none.
            if (route.isEmpty()) status = Status.NO_ROUTE
            thought?.event("planner.search") { if (route.isEmpty()) "search result: NO_ROUTE" else "search found nothing, keeping the old route" }
            return
        }
        val points = done.waypoints(end)
        if (done.rejected && verifyTries < MAX_VERIFY_TRIES) {
            // The route runs through a block the grid missed (now marked): search again with it in the way.
            verifyTries++
            thought?.event("planner.search") { "route crosses a block the grid missed, searching again (try $verifyTries)" }
            search = Search(done.level, done.from, done.goalPos, now)
            return
        }
        verifyTries = 0
        route = points
        routeIndex = 0
        arrived = false
        partial = !done.reachedGoal
        status = Status.READY
        thought?.event("planner.search") {
            (if (partial) "search result: partial route to the closest reachable cell" else "search result: route found") +
                    ", ${points.size} waypoints, ${done.expansionCount} cells expanded"
        }
    }

    // ================================================================================================== search

    private class Node(val cx: Int, val cz: Int, val g: Double, val f: Double, val parent: Node?) {
        var closed = false
    }

    private inner class Search(val level: Level, val from: Vec3, val goalPos: Vec3, private val born: Long) {
        private val startCx = GroundTerrain.cellOf(from.x, profile)
        private val startCz = GroundTerrain.cellOf(from.z, profile)
        private val reach = MAX_RADIUS / profile.cellSize
        private val cells = HashMap<Long, GroundCell>()
        private val nodes = HashMap<Long, Node>()
        private val open = PriorityQueue<Node>(compareBy { it.f })
        private var goalCx = GroundTerrain.cellOf(goalPos.x, profile)
        private var goalCz = GroundTerrain.cellOf(goalPos.z, profile)
        private val exactGoal: Vec3
        private var expansions = 0
        private var best: Node

        /** Node expansions the search used so far (for the AI Thinking line). */
        val expansionCount: Int get() = expansions

        /** The node the search ended on (the goal, or the closest reachable cell). Valid once [step] returned true. */
        var result: Node? = null
            private set

        /** True when [result] is the goal cell. */
        var reachedGoal = false
            private set

        /** True when [waypoints] found a step of the route the hull cannot make and marked its cell as blocked. */
        var rejected = false
            private set

        init {
            // A goal that cannot be driven to (water for a sinking vehicle, a hazard) is moved to the nearest cell that can.
            val goalCell = cell(goalCx, goalCz)
            var exact = goalPos
            if (!GroundTerrain.passable(goalCell, profile)) {
                var bestDistance = Int.MAX_VALUE
                for (dx in -GOAL_SNAP_CELLS..GOAL_SNAP_CELLS) for (dz in -GOAL_SNAP_CELLS..GOAL_SNAP_CELLS) {
                    val near = cell(goalCx + dx, goalCz + dz)
                    val distance = dx * dx + dz * dz
                    if (GroundTerrain.passable(near, profile) && distance < bestDistance) {
                        bestDistance = distance
                        goalCx = near.cx
                        goalCz = near.cz
                        exact = Vec3(GroundTerrain.centerOf(near.cx, profile), near.topY.toDouble(), GroundTerrain.centerOf(near.cz, profile))
                    }
                }
            }
            exactGoal = exact
            val start = Node(startCx, startCz, 0.0, heuristic(startCx, startCz), null)
            nodes[key(startCx, startCz)] = start
            open.add(start)
            best = start
        }

        private fun key(cx: Int, cz: Int): Long = cellKey(cx, cz)

        /** True when the cell can be driven through: passable terrain that no earlier run-in has marked as blocked. */
        private fun open(cx: Int, cz: Int): Boolean =
            GroundTerrain.passable(cell(cx, cz), profile) && !(isBlocked(cx, cz, born) && !isEndpoint(cx, cz))

        /** The cell the search starts in or is aimed at: a mark there is ignored, or a marked start would wall the boss in. */
        private fun isEndpoint(cx: Int, cz: Int): Boolean = (cx == startCx && cz == startCz) || (cx == goalCx && cz == goalCz)

        private fun cell(cx: Int, cz: Int): GroundCell =
            cells.getOrPut(key(cx, cz)) { GroundTerrain.sample(level, cx, cz, profile) }

        /** Octile distance to the goal cell in cell lengths, slightly inflated: a faster, almost optimal search. */
        private fun heuristic(cx: Int, cz: Int): Double {
            val dx = abs(cx - goalCx)
            val dz = abs(cz - goalCz)
            return (max(dx, dz) + 0.4142 * min(dx, dz)) * HEURISTIC_WEIGHT
        }

        /** One node expansion. True when the search is over ([result] is set). */
        fun step(): Boolean {
            val node = open.poll()
            if (node == null || ++expansions > MAX_EXPANSIONS) {
                result = best
                reachedGoal = false
                return true
            }
            val k = key(node.cx, node.cz)
            if (node.closed || nodes[k] !== node) return false
            node.closed = true
            if (node.cx == goalCx && node.cz == goalCz) {
                result = node
                reachedGoal = true
                return true
            }
            if (heuristic(node.cx, node.cz) < heuristic(best.cx, best.cz)) best = node

            val here = cell(node.cx, node.cz)
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val nx = node.cx + dx
                val nz = node.cz + dz
                if (abs(nx - startCx) > reach || abs(nz - startCz) > reach) continue
                val nk = key(nx, nz)
                if (nodes[nk]?.closed == true) continue
                val there = cell(nx, nz)
                if (!open(nx, nz)) continue
                // No cutting the corner of something impassable.
                if (dx != 0 && dz != 0 && (!open(node.cx + dx, node.cz) || !open(node.cx, node.cz + dz))) continue
                val cost = GroundTerrain.stepCost(level, here, there, profile)
                if (cost == GroundTerrain.IMPASSABLE) continue
                val g = node.g + cost
                val old = nodes[nk]
                if (old != null && old.g <= g) continue
                val next = Node(nx, nz, g, g + heuristic(nx, nz), node)
                nodes[nk] = next
                open.add(next)
            }
            return false
        }

        // ---------------------------------------------------------------------------------------------- waypoints

        /** The smoothed waypoints from just after the start to [end]. */
        fun waypoints(end: Node): List<Vec3> {
            val chain = ArrayList<Node>()
            var cursor: Node? = end
            while (cursor != null) {
                chain.add(cursor)
                cursor = cursor.parent
            }
            chain.reverse()

            val points = ArrayList<Vec3>()
            var i = 0
            while (i < chain.size - 1) {
                var j = min(chain.size - 1, i + SMOOTH_LOOKAHEAD)
                while (j > i + 1 && !lineIsFine(chain[i], chain[j])) j--
                // A single cell step was only judged from five sampled columns: look at the real blocks, and wall off the
                // cell of whatever the hull cannot get past so the next search goes round it.
                if (j == i + 1) {
                    val hit = sweepHit(chain[i], chain[j])
                    if (hit != null && !isEndpoint(GroundTerrain.cellOf(hit.x.toDouble(), profile), GroundTerrain.cellOf(hit.z.toDouble(), profile))) {
                        markBlocked(level, hit.x + 0.5, hit.z + 0.5)
                        rejected = true
                    }
                }
                points.add(center(chain[j]))
                i = j
            }
            if (reachedGoal) {
                // Aim at the exact goal inside the last cell rather than its centre (or, already in that cell, at it).
                val exact = Vec3(exactGoal.x, center(end).y, exactGoal.z)
                if (points.isEmpty()) points.add(exact) else points[points.size - 1] = exact
            }
            return points
        }

        /**
         * The first block column on the straight line from [a] to [b] that the hull's footprint cannot enter ([HullSweep]),
         * or null. The ground around the start cell is skipped: the boss stands there and the hull is already on it.
         */
        private fun sweepHit(a: Node, b: Node): SweepHit? {
            val from = center(a)
            val to = center(b)
            return HullSweep.sweep(level, from.x, from.z, from.y, to.x, to.z, profile, from = if (a.parent == null) START_SKIP else 0.0)
        }

        private fun center(node: Node): Vec3 {
            val c = cell(node.cx, node.cz)
            return Vec3(GroundTerrain.centerOf(node.cx, profile), c.topY.toDouble(), GroundTerrain.centerOf(node.cz, profile))
        }

        /**
         * True when walking the straight line of cells from [a] to [b] is possible and costs no more than 10% over the
         * path the search found between them (so smoothing never cuts through a lake or a forest the path went around).
         */
        private fun lineIsFine(a: Node, b: Node): Boolean {
            var x = a.cx
            var z = a.cz
            val dx = abs(b.cx - a.cx)
            val dz = abs(b.cz - a.cz)
            val sx = if (a.cx < b.cx) 1 else -1
            val sz = if (a.cz < b.cz) 1 else -1
            var error = dx - dz
            var total = 0.0
            val limit = (b.g - a.g) * LINE_COST_SLACK + 1.0e-6
            while (x != b.cx || z != b.cz) {
                val doubled = error * 2
                var stepX = 0
                var stepZ = 0
                if (doubled > -dz) {
                    error -= dz
                    stepX = sx
                }
                if (doubled < dx) {
                    error += dx
                    stepZ = sz
                }
                val from = cell(x, z)
                val to = cell(x + stepX, z + stepZ)
                if (!open(x + stepX, z + stepZ)) return false
                if (stepX != 0 && stepZ != 0 && (!open(x + stepX, z) || !open(x, z + stepZ))) return false
                val cost = GroundTerrain.stepCost(level, from, to, profile)
                if (cost == GroundTerrain.IMPASSABLE) return false
                total += cost
                if (total > limit) return false
                x += stepX
                z += stepZ
            }
            // The grid is fine with it; the real blocks have the last word.
            return sweepHit(a, b) == null
        }
    }

    companion object {
        /** Route-search node expansions all planners together may do per server tick. */
        const val NODES_PER_TICK = 400

        /** Half-width of the search area around the start, in blocks. */
        const val MAX_RADIUS = 192

        /** A search gives up (and reports the closest cell it reached) after this many expansions. */
        const val MAX_EXPANSIONS = 6000

        /** A new goal this far from the route's goal starts a new search. */
        const val REPLAN_GOAL_SHIFT = 16.0

        /** A route this old (10 s) is searched again, in case the world changed. */
        const val REPLAN_AGE_TICKS = 200L

        /** A search that found nothing is tried again after this long. */
        const val RETRY_TICKS = 100L

        /** Distance at which a waypoint counts as reached. */
        const val WAYPOINT_RADIUS = 4.0

        /** Distance at which the end of the route counts as reached. */
        const val FINAL_RADIUS = 3.0

        private const val PASSED_RADIUS = 8.0
        private const val SMOOTH_LOOKAHEAD = 8
        private const val LINE_COST_SLACK = 1.1
        private const val HEURISTIC_WEIGHT = 1.15
        private const val GOAL_SNAP_CELLS = 3

        /** How long a cell stays marked as blocked (60 s), and how many marks a planner keeps. */
        const val BLOCK_TTL_TICKS = 1200L
        private const val BLOCK_CAP = 96

        /** Searches in a row that may be repeated because the route failed the block-exact check. */
        const val MAX_VERIFY_TRIES = 3

        /** Blocks around the start the sweep skips: the hull already stands there. */
        private const val START_SKIP = 3.0

        private val budget = TickBudget(NODES_PER_TICK)

        private fun cellKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) xor (cz.toLong() and 0xFFFFFFFFL)

        private fun horizontalDistanceSqr(a: Vec3, b: Vec3): Double {
            val dx = a.x - b.x
            val dz = a.z - b.z
            return dx * dx + dz * dz
        }
    }
}
