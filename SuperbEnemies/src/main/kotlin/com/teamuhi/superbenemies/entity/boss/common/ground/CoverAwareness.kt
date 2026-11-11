package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.ai.SkyExposure
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A place to hide and a place to fire from, next to each other: [hide] is out of the threat's sight entirely (body and
 * turret), [peek] is a spot a few blocks away from which the turret sees the threat and the main gun can be laid on it.
 * [hullDown] is true when the body is still covered at the peek spot (a crest or a wall top), so only the turret shows.
 */
class CoverPair(val hide: Vec3, val peek: Vec3, val hullDown: Boolean, val score: Double) {
    internal var checkedAt = Int.MIN_VALUE / 2
    internal var valid = true
}

/**
 * Cover awareness of the tank bosses: finds structures and terrain a tank can hide behind and the best spots next to them
 * to fire from. The light tanks use the spots as hull-down parking places and dodge spots ([bestHullDown], [bestHide]), the
 * heavy tanks as hide / peek pairs for their peek and shoot cycle ([bestPair]). One instance per brain.
 *
 * **Scan.** Around the tank, on a 2 block grid between [SCAN_MIN_RADIUS] and [SCAN_MAX_RADIUS] blocks (nearest first), a
 * column counts as *cover* when the block on top of it blocks sight ([SkyExposure.blocksSight]) and it stands at least
 * [MIN_RISE] blocks (and a body height) above the lowest of its four neighbours [NEIGHBOUR_DISTANCE] blocks off: a wall, a
 * building, a crest. Columns near an accepted one are skipped, so a building yields a few columns, not all of them. Only
 * [SCAN_BUDGET] columns are read per tick and the sweep resumes on the next tick. Chunks are never loaded (`getChunkNow`).
 *
 * **Evaluation.** For each cover column, working from the threat's position: the *hide* spot lies [HIDE_BEHIND] blocks (plus
 * the hull's half length) behind the column on the line from the threat, and counts when it is passable, not crowded, inside
 * the leash and the rays from the threat to the body and to the turret eye are both blocked. The *peek* spots are 3-8
 * blocks straight toward the threat from the hide spot (a crest or wall top) and 4-8 blocks to either side (around a
 * corner). A peek counts when it is passable, in the engage band, reachable from the hide spot by a short straight drive,
 * the turret eye sees the threat and the main gun can be laid on it from there (the elevation / depression limits of
 * [TankWeapon], see [TankGunnery.canLay]). [EVAL_PER_TICK] columns are evaluated per tick. The best few of each kind are
 * published when the sweep ends; they are refreshed when the threat has moved [THREAT_MOVE] blocks, the tank [TANK_MOVE], or
 * after [REFRESH_TICKS] ticks. Scoring: full cover, a hull-down peek, high ground over the threat, the range, a short drive
 * and a gun that is not at the end of its travel.
 *
 * The results are a few ticks old by nature, so every answer is re-checked against the current threat before it is handed out
 * ([stillValid] for a pair). When nothing qualifies (open ground, no loaded cover), or `useCover` is off, [bestHide] and
 * [bestHullDown] fall back to the random samples the tanks always used, and [bestPair] answers null.
 *
 * All state is per instance; server thread only.
 */
class CoverAwareness(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val gunnery: TankGunnery,
    private val routeProfile: () -> GroundProfile,
    private val crowded: (Level, Vec3) -> Boolean,
    private val isPerch: (Level, Vec3) -> Boolean
) {

    private class Column(val x: Int, val z: Int)

    private class Spot(val pos: Vec3, val score: Double)

    private class Ground(val spot: Vec3, val foliage: Boolean)

    private class Rejection(val peek: Vec3, val until: Int)

    private enum class Phase { IDLE, SCAN, EVAL }

    private var phase = Phase.IDLE

    // --- scan ---
    private val columns = ArrayList<Column>()
    private var scanCursor = 0
    private var scanOriginX = 0
    private var scanOriginZ = 0
    private var scanTick = Int.MIN_VALUE / 2
    private var scanOrigin: Vec3? = null

    // --- evaluation (the snapshot an epoch works on) ---
    private var evalIndex = 0
    private var evalTarget = Vec3.ZERO
    private var evalCentre = Vec3.ZERO
    private var evalTank = Vec3.ZERO
    private var evalAimId = -1
    private var evalRange = 0.0
    private val workPairs = ArrayList<CoverPair>()
    private val workHides = ArrayList<Spot>()
    private val workHulls = ArrayList<Spot>()

    // --- published results ---
    private var pairs: List<CoverPair> = emptyList()
    private var hides: List<Spot> = emptyList()
    private var hulls: List<Spot> = emptyList()
    private var readyAt = Int.MIN_VALUE / 2
    private var readyAimId = -1
    private var readyTarget = Vec3.ZERO
    private var readyTank = Vec3.ZERO
    private var readyRange = 0.0

    private val rejections = ArrayList<Rejection>()

    // ================================================================================================== work

    /**
     * Advances the scan / evaluation by one slice of work: call once per tick while the tank fights [aim] at [range] (the
     * engage range). Cheap when nothing is stale. Does nothing with `useCover` off.
     */
    fun update(level: Level, aim: Entity, range: Double) {
        if (!settings.useCover.get()) {
            boss.thought.think("cover.update") { "cover awareness is off in the config" }
            if (phase != Phase.IDLE) clearWork()
            return
        }
        val now = boss.tickCount
        if (now < scanTick || now < readyAt) {
            // The tick counter went backwards (entity reloaded): everything cached is from another life.
            clearAll()
        }
        when (phase) {
            Phase.IDLE -> if (stale(now, aim, range)) begin(level, aim, range, now)
            Phase.SCAN -> scanStep(level)
            Phase.EVAL -> evalStep(level)
        }
    }

    /** Drops every cached result and the work in progress (a new fight). */
    fun reset() {
        clearAll()
        rejections.clear()
    }

    private fun clearWork() {
        phase = Phase.IDLE
        workPairs.clear()
        workHides.clear()
        workHulls.clear()
    }

    private fun clearAll() {
        clearWork()
        columns.clear()
        scanOrigin = null
        scanTick = Int.MIN_VALUE / 2
        pairs = emptyList()
        hides = emptyList()
        hulls = emptyList()
        readyAt = Int.MIN_VALUE / 2
    }

    private fun stale(now: Int, aim: Entity, range: Double): Boolean =
        now - readyAt >= REFRESH_TICKS || aim.id != readyAimId || abs(range - readyRange) > 1.0 ||
                FlightMath.horizontalDistance(aim.position(), readyTarget) > THREAT_MOVE ||
                FlightMath.horizontalDistance(boss.position(), readyTank) > TANK_MOVE

    private fun begin(level: Level, aim: Entity, range: Double, now: Int) {
        evalTarget = aim.position()
        evalCentre = aim.boundingBox.center
        evalTank = boss.position()
        evalAimId = aim.id
        evalRange = range
        workPairs.clear()
        workHides.clear()
        workHulls.clear()
        val origin = scanOrigin
        val rescan = columns.isEmpty() || origin == null || now - scanTick > COLUMN_MAX_AGE ||
                FlightMath.horizontalDistance(boss.position(), origin) > TANK_MOVE
        boss.thought.event("cover.begin") { if (rescan) "scanning for cover around the tank" else "re-evaluating the known cover against ${aim.name.string}" }
        if (rescan) {
            columns.clear()
            scanCursor = 0
            scanOriginX = Math.floor(boss.x).toInt()
            scanOriginZ = Math.floor(boss.z).toInt()
            scanOrigin = boss.position()
            scanTick = now
            phase = Phase.SCAN
            scanStep(level)
        } else {
            beginEval()
        }
    }

    private fun beginEval() {
        evalIndex = 0
        phase = Phase.EVAL
    }

    private fun scanStep(level: Level) {
        var budget = SCAN_BUDGET
        val cells = OFFSETS.size / 2
        val minRise = max(MIN_RISE, ceil(boss.bbHeight * HULL_DOWN_BODY).toInt())
        while (budget > 0 && scanCursor < cells && columns.size < MAX_COLUMNS) {
            val i = scanCursor++ * 2
            budget--
            probe(level, scanOriginX + OFFSETS[i], scanOriginZ + OFFSETS[i + 1], minRise)
        }
        if (scanCursor >= cells || columns.size >= MAX_COLUMNS) beginEval()
    }

    /** Adds the column ([x], [z]) when it is sight-blocking cover standing at least [minRise] blocks above its surroundings. */
    private fun probe(level: Level, x: Int, z: Int, minRise: Int) {
        val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return
        val top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15)
        if (top <= level.minBuildHeight) return
        val pos = BlockPos(x, top - 1, z)
        if (!SkyExposure.blocksSight(chunk, pos, chunk.getBlockState(pos))) return
        var low = top
        for (side in NEIGHBOURS) {
            val height = surface(level, x + side[0] * NEIGHBOUR_DISTANCE, z + side[1] * NEIGHBOUR_DISTANCE) ?: continue
            low = min(low, height)
        }
        if (top - low < minRise) return
        for (known in columns) {
            val dx = known.x - x
            val dz = known.z - z
            if (dx * dx + dz * dz < DEDUPE_DISTANCE * DEDUPE_DISTANCE) return
        }
        columns.add(Column(x, z))
    }

    private fun surface(level: Level, x: Int, z: Int): Int? {
        val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return null
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x and 15, z and 15)
    }

    private fun evalStep(level: Level) {
        var left = EVAL_PER_TICK
        while (left > 0 && evalIndex < columns.size) {
            left--
            evaluate(level, columns[evalIndex++])
        }
        if (evalIndex < columns.size) return
        pairs = ArrayList(workPairs)
        hides = ArrayList(workHides)
        hulls = ArrayList(workHulls)
        readyAt = boss.tickCount
        readyAimId = evalAimId
        readyTarget = evalTarget
        readyTank = evalTank
        readyRange = evalRange
        phase = Phase.IDLE
        boss.thought.event("cover.update") {
            "cover ready: ${columns.size} cover columns, ${pairs.size} hide/peek pairs, ${hides.size} hide spots, ${hulls.size} hull-down spots"
        }
    }

    // ================================================================================================== evaluation

    /** Evaluates one cover column against the epoch's snapshot of the threat: its hide spot and the peek spots around it. */
    private fun evaluate(level: Level, column: Column) {
        val route = routeProfile()
        val cx = column.x + 0.5
        val cz = column.z + 0.5
        var ax = cx - evalTarget.x
        var az = cz - evalTarget.z
        val length = hypot(ax, az)
        if (length < MIN_COLUMN_DISTANCE) return
        ax /= length
        az /= length

        for (behind in doubleArrayOf(route.halfLength + HIDE_BEHIND, route.halfLength + HIDE_BEHIND_FAR)) {
            val hide = hideGround(level, route, cx + ax * behind, cz + az * behind) ?: continue
            val away = FlightMath.horizontalDistance(hide.spot, evalTarget)
            var hideScore = COVER_BONUS + away / evalRange * HIDE_RANGE_WEIGHT - FlightMath.horizontalDistance(hide.spot, evalTank) * MOVE_WEIGHT
            if (hide.foliage) hideScore -= FOLIAGE_PENALTY
            keep(workHides, Spot(hide.spot, hideScore)) { it.score }
            evaluatePeeks(level, route, hide, ax, az)
            return
        }
    }

    private fun evaluatePeeks(level: Level, route: GroundProfile, hide: Ground, ax: Double, az: Double) {
        for (k in PEEK_TOWARD) peekAt(level, route, hide, hide.spot.x - ax * k, hide.spot.z - az * k)
        for (s in PEEK_SIDE) {
            peekAt(level, route, hide, hide.spot.x - az * s, hide.spot.z + ax * s)
            peekAt(level, route, hide, hide.spot.x + az * s, hide.spot.z - ax * s)
        }
    }

    private fun peekAt(level: Level, route: GroundProfile, hide: Ground, x: Double, z: Double) {
        val range = evalRange
        if (!inLeash(x, z, range)) return
        val ground = groundAt(level, x, z, route) ?: return
        val spot = ground.spot
        val away = FlightMath.horizontalDistance(spot, evalTarget)
        if (away < range * PEEK_MIN_FACTOR || away > range * PEEK_MAX_FACTOR) return

        val eye = spot.add(0.0, boss.bbHeight * HULL_DOWN_EYE, 0.0)
        val gun = gunnery.mainGun()
        val margin = if (gun == null) GUN_MARGIN_OK else gunnery.layMargin(eye, evalCentre, gun)
        if (margin < 0.0) return
        if (TankSight.solidBlocks(level, eye, evalCentre, 0) != 0) return
        if (!drivable(level, route, hide.spot, spot) || crowded(level, spot)) return

        val hullDown = TankSight.solidBlocks(level, evalCentre, spot.add(0.0, boss.bbHeight * HULL_DOWN_BODY, 0.0), 0) > 0
        val high = (spot.y - evalTarget.y).coerceIn(-4.0, MAX_HIGH)
        var common = high * HEIGHT_WEIGHT - abs(away - range * IDEAL_FACTOR) / range
        if (ground.foliage) common -= FOLIAGE_PENALTY
        if (margin < GUN_MARGIN_OK) common -= (GUN_MARGIN_OK - margin) * GUN_LIMIT_PENALTY

        val pairScore = PAIR_BONUS + (if (hullDown) HULL_DOWN_BONUS else 0.0) + common -
                FlightMath.horizontalDistance(evalTank, hide.spot) * MOVE_WEIGHT -
                FlightMath.horizontalDistance(hide.spot, spot) * MOVE_WEIGHT
        // A pair needs room to move between its spots, or the tank would never tell the one from the other.
        if (FlightMath.horizontalDistance(hide.spot, spot) >= MIN_PAIR_SEPARATION) {
            keep(workPairs, CoverPair(hide.spot, spot, hullDown, pairScore)) { it.score }
        }
        if (hullDown) {
            val hullScore = COVER_BONUS + common - FlightMath.horizontalDistance(evalTank, spot) * MOVE_WEIGHT
            keep(workHulls, Spot(spot, hullScore)) { it.score }
        }
    }

    /**
     * The hide ground at ([x], [z]): passable, inside the leash and the cover band around the threat, not crowded, and both
     * the body and the turret eye hidden from the threat. Null when it is none of that.
     */
    private fun hideGround(level: Level, route: GroundProfile, x: Double, z: Double): Ground? {
        val range = evalRange
        if (!inLeash(x, z, range)) return null
        val ground = groundAt(level, x, z, route) ?: return null
        val spot = ground.spot
        val away = FlightMath.horizontalDistance(spot, evalTarget)
        if (away < range * COVER_MIN_FACTOR || away > range * COVER_MAX_FACTOR) return null
        if (!hidden(level, evalCentre, spot)) return null
        return if (crowded(level, spot)) null else ground
    }

    /** True when neither the body nor the turret eye of a hull standing at [spot] is seen from [from]. */
    private fun hidden(level: Level, from: Vec3, spot: Vec3): Boolean =
        TankSight.solidBlocks(level, from, spot.add(0.0, boss.bbHeight * HULL_DOWN_BODY, 0.0), 0) > 0 &&
                TankSight.solidBlocks(level, from, spot.add(0.0, boss.bbHeight * HULL_DOWN_EYE, 0.0), 0) > 0

    /** True when a hull at [spot] has its body covered from [from] while the turret eye sees [from] (hull-down). */
    private fun hullDownFrom(level: Level, from: Vec3, spot: Vec3): Boolean =
        TankSight.solidBlocks(level, from, spot.add(0.0, boss.bbHeight * HULL_DOWN_BODY, 0.0), 0) > 0 &&
                TankSight.solidBlocks(level, from, spot.add(0.0, boss.bbHeight * HULL_DOWN_EYE, 0.0), 0) == 0

    private fun inLeash(x: Double, z: Double, range: Double): Boolean =
        FlightMath.horizontalDistance(Vec3(x, 0.0, z), boss.homePos) <= settings.leashRange.get() + range

    /** The ground at ([x], [z]) as a candidate spot: passable and no perch ([isPerch]); null when it is either. */
    private fun groundAt(level: Level, x: Double, z: Double, route: GroundProfile): Ground? {
        val cell = GroundTerrain.sample(level, GroundTerrain.cellOf(x, route), GroundTerrain.cellOf(z, route), route)
        if (!GroundTerrain.passable(cell, route)) return null
        val spot = Vec3(x, cell.floorY.toDouble(), z)
        // A rooftop or a pinnacle is no place to hide or to fire from: the tank could not drive off it (see ElevationDetection).
        if (isPerch(level, spot)) return null
        return Ground(spot, cell.foliage)
    }

    /** Whether the straight drive from [from] to [to] stays on passable cells without a step the hull cannot climb (checked every 2 blocks). */
    private fun drivable(level: Level, route: GroundProfile, from: Vec3, to: Vec3): Boolean {
        val distance = FlightMath.horizontalDistance(from, to)
        if (distance > MAX_PAIR_TRAVEL) return false
        val steps = max(1, ceil(distance / DRIVE_STEP).toInt())
        var lastY = from.y
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val cell = GroundTerrain.sample(
                level,
                GroundTerrain.cellOf(from.x + (to.x - from.x) * t, route),
                GroundTerrain.cellOf(from.z + (to.z - from.z) * t, route),
                route
            )
            if (!GroundTerrain.passable(cell, route) || abs(cell.floorY - lastY) > route.upStep) return false
            lastY = cell.floorY.toDouble()
        }
        return true
    }

    /** Inserts [item] into [list] and keeps only the [KEEP] best by [score]. */
    private fun <T> keep(list: ArrayList<T>, item: T, score: (T) -> Double) {
        list.add(item)
        list.sortByDescending(score)
        while (list.size > KEEP) list.removeAt(list.size - 1)
    }

    // ================================================================================================== answers

    /** True while the published results belong to [aim] and are not too old to use. */
    private fun fresh(aim: Entity): Boolean =
        settings.useCover.get() && aim.id == readyAimId && boss.tickCount - readyAt < RESULT_MAX_AGE

    /**
     * The best hide / peek pair for the heavy tanks' peek and shoot cycle, or null (no cover found, nothing fresh, `useCover`
     * off, or every pair was [reject]ed lately). The pair is only as current as the last evaluation: ask [stillValid] before
     * and while using it.
     */
    fun bestPair(aim: Entity): CoverPair? {
        if (!fresh(aim)) {
            boss.thought.think("cover.bestPair") { "no fresh cover results for ${aim.name.string}" }
            return null
        }
        val now = boss.tickCount
        rejections.removeAll { now >= it.until }
        val pick = pairs.firstOrNull { pair -> rejections.none { FlightMath.horizontalDistance(it.peek, pair.peek) < REJECT_RADIUS } }
        boss.thought.think("cover.bestPair") {
            if (pick != null) "best hide/peek pair found" + (if (pick.hullDown) " (hull-down)" else "")
            else "no usable pair (${pairs.size} known, ${rejections.size} rejected spots)"
        }
        return pick
    }

    /** Remembers that [pair] did not work (the shot never came, the line stayed blocked...) so [bestPair] skips its peek spot for a while. */
    fun reject(pair: CoverPair) {
        boss.thought.event("cover.reject") { "rejecting a peek spot for ${REJECT_TICKS / 20}s" }
        if (rejections.size >= MAX_REJECTIONS) rejections.removeAt(0)
        rejections.add(Rejection(pair.peek, boss.tickCount + REJECT_TICKS))
    }

    /**
     * Whether [pair] still hides and still shoots against the current position of [aim]: both rays to the hide spot blocked,
     * the peek spot's eye ray clear and the gun still able to lay on the target from there. The answer is kept for
     * [VALID_INTERVAL] ticks, so calling it every tick is cheap.
     */
    fun stillValid(level: Level, pair: CoverPair, aim: Entity): Boolean {
        val now = boss.tickCount
        if (now - pair.checkedAt < VALID_INTERVAL) return pair.valid
        pair.checkedAt = now
        val centre = aim.boundingBox.center
        val eye = pair.peek.add(0.0, boss.bbHeight * HULL_DOWN_EYE, 0.0)
        val gun = gunnery.mainGun()
        pair.valid = hidden(level, centre, pair.hide) && TankSight.solidBlocks(level, eye, centre, 0) == 0 &&
                (gun == null || gunnery.canLay(eye, centre, gun))
        if (!pair.valid) boss.thought.event("cover.stillValid") { "hide/peek pair no longer hides or no longer shoots" }
        return pair.valid
    }

    /**
     * A spot out of the sight of [aim] for the cover dash of a tank that is being shot at: the best scanned hide spot that
     * still hides, else (nothing scanned, or `useCover` off) the best of a few random samples [COVER_MIN_HOP]..[COVER_MAX_HOP]
     * blocks off. Null when none qualifies.
     */
    fun bestHide(level: Level, aim: Entity, range: Double, distanceNow: Double): Vec3? {
        if (fresh(aim)) {
            val centre = aim.boundingBox.center
            for (spot in hides) {
                if (hidden(level, centre, spot.pos) && !crowded(level, spot.pos)) {
                    boss.thought.event("cover.bestHide") { "hide spot from the scan" }
                    return spot.pos
                }
            }
        }
        val sampled = randomSpot(level, aim, range, distanceNow, hullDown = false)
        boss.thought.event("cover.bestHide") { if (sampled != null) "hide spot from a random sample" else "no hide spot found" }
        return sampled
    }

    /**
     * A hull-down spot for a strafing tank: the body hidden from [aim], the turret eye seeing it, the main gun able to lay on
     * it. The best scanned one that still holds, else the best random sample that qualifies. Null when none does.
     */
    fun bestHullDown(level: Level, aim: Entity, range: Double, distanceNow: Double): Vec3? {
        if (fresh(aim)) {
            val centre = aim.boundingBox.center
            for (spot in hulls) {
                if (FlightMath.horizontalDistance(spot.pos, boss.position()) < MIN_HULL_DOWN_HOP) continue
                if (hullDownFrom(level, centre, spot.pos) && !crowded(level, spot.pos)) {
                    boss.thought.event("cover.bestHullDown") { "hull-down spot from the scan" }
                    return spot.pos
                }
            }
        }
        val sampled = randomSpot(level, aim, range, distanceNow, hullDown = true)
        boss.thought.event("cover.bestHullDown") { if (sampled != null) "hull-down spot from a random sample" else "no hull-down spot found" }
        return sampled
    }

    /**
     * A spot 3-6 blocks directly behind [spot] (away from [aim]; the nearest of three distances that works) from which the
     * turret is out of sight: where a tank parked hull-down at [spot] rocks back to. Null when there is none (open ground).
     */
    fun hideNear(level: Level, spot: Vec3, aim: Entity): Vec3? {
        val route = routeProfile()
        val centre = aim.boundingBox.center
        val dx = spot.x - aim.x
        val dz = spot.z - aim.z
        val length = hypot(dx, dz)
        if (length < 1.0e-3) return null
        for (back in ROCK_DISTANCES) {
            val ground = groundAt(level, spot.x + dx / length * back, spot.z + dz / length * back, route) ?: continue
            if (abs(ground.spot.y - spot.y) > route.upStep) continue
            if (TankSight.solidBlocks(level, centre, ground.spot.add(0.0, boss.bbHeight * HULL_DOWN_EYE, 0.0), 0) > 0) return ground.spot
        }
        return null
    }

    // ================================================================================================== random samples

    /**
     * A passable spot [COVER_MIN_HOP]..[COVER_MAX_HOP] blocks away, inside the cover band around [aim], scored by: out of the
     * attacker's line of sight (the big bonus), a little for being further from it than now, less for a long drive and for
     * foliage. Null when no sample is passable.
     *
     * With [hullDown] only a spot where the threat cannot see the body (the ray to 0.4 of the hull height is blocked) but the
     * turret can see the threat (the ray to the turret eye is clear) and the main gun can be laid on it qualifies. Null when
     * no sample is such a spot. This is what the tanks did before they scanned for cover, and what they still do on open
     * ground and with `useCover` off.
     */
    private fun randomSpot(level: Level, aim: Entity, range: Double, distanceNow: Double, hullDown: Boolean): Vec3? {
        val route = routeProfile()
        val position = boss.position()
        val target = aim.position()
        val threat = aim.boundingBox.center
        val bodyHeight = boss.bbHeight * COVER_BODY
        val gun = gunnery.mainGun()
        val random = boss.getRandom()

        var best: Vec3? = null
        var bestScore = Double.NEGATIVE_INFINITY
        repeat(if (hullDown) HULL_DOWN_SAMPLES else COVER_SAMPLES) {
            val angle = random.nextDouble() * Math.PI * 2.0
            val radius = COVER_MIN_HOP + random.nextDouble() * (COVER_MAX_HOP - COVER_MIN_HOP)
            val x = position.x + cos(angle) * radius
            val z = position.z + sin(angle) * radius
            if (!inLeash(x, z, range)) return@repeat
            val ground = groundAt(level, x, z, route) ?: return@repeat
            val spot = ground.spot
            val away = FlightMath.horizontalDistance(spot, target)
            if (away < range * COVER_MIN_FACTOR || away > range * COVER_MAX_FACTOR || crowded(level, spot)) return@repeat

            val covered = if (hullDown) {
                hullDownFrom(level, threat, spot) &&
                        (gun == null || gunnery.canLay(spot.add(0.0, boss.bbHeight * HULL_DOWN_EYE, 0.0), threat, gun))
            } else {
                TankSight.solidBlocks(level, threat, spot.add(0.0, bodyHeight, 0.0), 0) > 0
            }
            if (hullDown && !covered) return@repeat
            var score = if (covered) COVER_BONUS else 0.0
            score += (away - distanceNow) / range * COVER_DISTANCE_WEIGHT
            score -= FlightMath.horizontalDistance(spot, position) * MOVE_WEIGHT
            if (ground.foliage) score -= FOLIAGE_PENALTY
            if (score > bestScore) {
                bestScore = score
                best = spot
            }
        }
        return best
    }

    private companion object {
        // Scan
        const val SCAN_MIN_RADIUS = 6
        const val SCAN_MAX_RADIUS = 40
        const val SCAN_GRID = 2

        /** Columns read per tick. */
        const val SCAN_BUDGET = 48
        const val MAX_COLUMNS = 48

        /** An accepted cover column keeps the columns within this many blocks from being accepted too. */
        const val DEDUPE_DISTANCE = 4
        const val NEIGHBOUR_DISTANCE = 4

        /** Smallest rise (blocks) of a cover column over its lowest neighbour, whatever the hull height. */
        const val MIN_RISE = 2
        val NEIGHBOURS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

        /** The scan offsets from the tank, (dx, dz) pairs flattened, nearest first. */
        val OFFSETS: IntArray = run {
            val cells = SCAN_MAX_RADIUS / SCAN_GRID
            val list = ArrayList<IntArray>()
            for (gx in -cells..cells) for (gz in -cells..cells) {
                val x = gx * SCAN_GRID
                val z = gz * SCAN_GRID
                val d2 = x * x + z * z
                if (d2 < SCAN_MIN_RADIUS * SCAN_MIN_RADIUS || d2 > SCAN_MAX_RADIUS * SCAN_MAX_RADIUS) continue
                list.add(intArrayOf(x, z))
            }
            list.sortBy { it[0] * it[0] + it[1] * it[1] }
            IntArray(list.size * 2).also { out ->
                list.forEachIndexed { i, offset ->
                    out[i * 2] = offset[0]
                    out[i * 2 + 1] = offset[1]
                }
            }
        }

        // Evaluation / refresh
        const val EVAL_PER_TICK = 2
        const val REFRESH_TICKS = 60
        const val THREAT_MOVE = 8.0
        const val TANK_MOVE = 12.0
        const val COLUMN_MAX_AGE = 200
        const val RESULT_MAX_AGE = 200
        const val KEEP = 4
        const val VALID_INTERVAL = 10

        /** Columns this close to the threat are skipped: no room to hide in front of the threat's own feet. */
        const val MIN_COLUMN_DISTANCE = 4.0

        /** Blocks behind the cover column (beyond the hull's half length) of the hide spot, and the second try. */
        const val HIDE_BEHIND = 1.5
        const val HIDE_BEHIND_FAR = 4.0

        /** Peek spots: blocks toward the threat from the hide spot, and blocks to either side of it. */
        val PEEK_TOWARD = doubleArrayOf(3.0, 5.5, 8.0)
        val PEEK_SIDE = doubleArrayOf(4.5, 8.0)
        const val PEEK_MIN_FACTOR = 0.6
        const val PEEK_MAX_FACTOR = 1.3
        const val IDEAL_FACTOR = 0.95

        /** Longest straight drive (blocks) between the hide and the peek spot of a pair. */
        const val MAX_PAIR_TRAVEL = 12.0
        const val MIN_PAIR_SEPARATION = 4.0
        const val DRIVE_STEP = 2.0

        /** Degrees of travel the gun should keep to its elevation / depression limit; closer costs score. */
        const val GUN_MARGIN_OK = 4.0
        const val GUN_LIMIT_PENALTY = 0.1

        // Scoring
        const val PAIR_BONUS = 2.0
        const val HULL_DOWN_BONUS = 1.5
        const val HEIGHT_WEIGHT = 0.12

        /** The most blocks above the threat that count as an advantage in a peek spot's score. */
        const val MAX_HIGH = 6.0
        const val HIDE_RANGE_WEIGHT = 0.5
        const val FOLIAGE_PENALTY = 0.5
        const val MIN_HULL_DOWN_HOP = 3.0

        // Rejected pairs
        const val REJECT_TICKS = 600
        const val REJECT_RADIUS = 4.0
        const val MAX_REJECTIONS = 8

        // Rocking back from a hull-down spot
        val ROCK_DISTANCES = doubleArrayOf(3.0, 4.5, 6.0)

        // Random samples (the tanks' older way of finding a spot) and the hull heights they test
        const val HULL_DOWN_SAMPLES = 24
        const val COVER_SAMPLES = 16
        const val COVER_MIN_HOP = 14.0
        const val COVER_MAX_HOP = 42.0
        const val COVER_MIN_FACTOR = 0.4
        const val COVER_MAX_FACTOR = 1.6
        const val COVER_BONUS = 3.0
        const val COVER_DISTANCE_WEIGHT = 0.5
        const val MOVE_WEIGHT = 0.03

        /** Fractions of the hull height of the body ray (must be blocked) and of the turret eye ray. */
        const val HULL_DOWN_BODY = 0.4
        const val HULL_DOWN_EYE = 0.9

        /** Height fraction of the single body ray the dodge test (not hull-down) uses. */
        const val COVER_BODY = 0.6
    }
}
