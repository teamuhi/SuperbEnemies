package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath.horizontalDistance
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shoot and scoot: the ENGAGE movement of a heavy tank (one that snipes) that has no cover to peek from ([PeekAndShoot]).
 * One instance per brain.
 *
 * A firing spot is never a perch (a rooftop or pinnacle the tank could not drive off, see [ElevationDetection.isPerch]) and has
 * to be one the main gun can be laid on the target from (see [TankGunnery.canLay]); the bonus for standing high is capped at 6
 * blocks, so height never outweighs the rest of the score.
 *
 * @param routeProfile the profile the planner routes with (the brain's, which follows `crossWater`)
 * @param isPerch whether a spot is a perch (see [ElevationDetection.isPerch])
 */
class SnipePosition(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val gunnery: TankGunnery,
    private val driver: GroundDriver,
    private val moves: GroundMoves,
    private val routeProfile: () -> GroundProfile,
    private val isPerch: (Level, Vec3) -> Boolean
) {

    private var snipeSpot: Vec3? = null
    private var snipeSince = 0
    private var snipeNextPick = 0
    private var seenCannonShot = Int.MIN_VALUE / 2

    /** The firing spot being driven to or held, or null when none is picked (other tanks keep clear of it). */
    val spot: Vec3?
        get() = snipeSpot

    /** Forgets the firing spot, so the next [tick] picks one. */
    fun clear() {
        if (snipeSpot != null) boss.thought.event("snipe.clear") { "firing spot forgotten" }
        snipeSpot = null
    }

    /**
     * The sniper's engage: drive to a firing spot, hold there and shoot, and move to another spot nearby right after each
     * cannon round (shoot and scoot). A new spot is also picked when the target leaves the engage band or the line of fire
     * stays blocked. With no spot found it falls back to closing in on the target.
     * [blockedTicks]: how long the line of fire has been blocked (see the brain's `tickEngage`).
     */
    fun tick(level: Level, aim: Entity, distance: Double, range: Double, blockedTicks: Int) {
        val now = boss.tickCount
        val position = boss.position()
        val target = aim.position()

        val shot = gunnery.lastCannonShotTick
        val fired = shot != seenCannonShot && now - shot < SNIPE_SHOT_WINDOW
        seenCannonShot = shot

        val spot = snipeSpot
        val outOfBand = distance > range * EngageBand.ADVANCE_START || distance < range * EngageBand.BACK_OFF_FACTOR
        val blocked = blockedTicks > EngageBand.BLOCKED_REPOSITION_TICKS
        val spoiled = spot != null && horizontalDistance(spot, target) !in range * SNIPE_MIN_FACTOR..range * EngageBand.ADVANCE_START
        if (spot == null || ((fired || outOfBand || blocked || spoiled) && now >= snipeNextPick)) {
            snipeSpot = pickSnipeSpot(level, aim, range, relocate = spot != null && !outOfBand && !spoiled)
            boss.thought.event("snipe.pick") {
                val why = when {
                    spot == null -> "no spot yet"
                    fired -> "round fired, scooting"
                    outOfBand -> "target left the engage band"
                    blocked -> "line of fire blocked"
                    else -> "spot spoiled by the target's movement"
                }
                snipeSpot?.let { "new firing spot at ${it.x.toInt()},${it.z.toInt()} ($why)" } ?: "no firing spot found ($why)"
            }
            snipeSince = now
            snipeNextPick = now + SNIPE_REPICK_TICKS
            moves.newGoal()
        }

        val goal = snipeSpot
        if (goal != null && horizontalDistance(position, goal) > SNIPE_ARRIVE_RADIUS && now - snipeSince < SNIPE_TIMEOUT) {
            val mode = if (distance > range * 2.5 && !boss.recentlyDamaged(EngageBand.SPRINT_HIT_WINDOW)) DriveMode.SPRINT else DriveMode.CRUISE
            boss.thought.think("snipe.tick") { "driving to the firing spot ($mode)" }
            moves.goTo(level, goal, mode)
            moves.look(null)
        } else if (goal == null && distance > range * EngageBand.ADVANCE_STOP) {
            boss.thought.think("snipe.tick") { "no firing spot, closing in on ${aim.name.string}" }
            moves.goTo(level, target)
            moves.look(null)
        } else {
            val aligned = driver.turnToward(target, TankFacing.TRACKED)
            boss.thought.think("snipe.tick") { if (aligned) "at the firing spot, shooting at ${aim.name.string}" else "at the firing spot, turning the hull" }
            if (aligned) driver.hold()
        }
    }

    /**
     * A passable spot to fire from: a clear line from the turret to [aim], inside the engage band, preferring high ground
     * over the target and (when [relocate]) a short move from where the tank stands. Null when none of
     * [SNIPE_SAMPLES] random spots qualifies.
     */
    private fun pickSnipeSpot(level: Level, aim: Entity, range: Double, relocate: Boolean): Vec3? {
        val route = routeProfile()
        val position = boss.position()
        val target = aim.position()
        val targetCentre = aim.boundingBox.center
        val toward = FlightMath.horizontal(Vec3(position.x - target.x, 0.0, position.z - target.z), Vec3(1.0, 0.0, 0.0))
        val eyeHeight = boss.bbHeight * 0.9
        val gun = gunnery.mainGun()

        var best: Vec3? = null
        var bestScore = Double.NEGATIVE_INFINITY
        repeat(SNIPE_SAMPLES) {
            val x: Double
            val z: Double
            if (relocate) {
                // Stay in the area: a short hop in any direction.
                val angle = boss.getRandom().nextDouble() * Math.PI * 2.0
                val radius = SNIPE_HOP_MIN + boss.getRandom().nextDouble() * (SNIPE_HOP_MAX - SNIPE_HOP_MIN)
                x = position.x + cos(angle) * radius
                z = position.z + sin(angle) * radius
            } else {
                // A spot on the engage ring around the target, mostly on the side the tank already is.
                val spread = Math.toRadians((boss.getRandom().nextDouble() - 0.5) * 140.0)
                val dx = toward.x * cos(spread) - toward.z * sin(spread)
                val dz = toward.x * sin(spread) + toward.z * cos(spread)
                val radius = range * (SNIPE_MIN_FACTOR + 0.5 * boss.getRandom().nextDouble())
                x = target.x + dx * radius
                z = target.z + dz * radius
            }
            if (horizontalDistance(Vec3(x, 0.0, z), boss.homePos) > settings.leashRange.get() + range) return@repeat
            val cell = GroundTerrain.sample(level, GroundTerrain.cellOf(x, route), GroundTerrain.cellOf(z, route), route)
            if (!GroundTerrain.passable(cell, route)) return@repeat
            val spot = Vec3(x, cell.floorY.toDouble(), z)
            val away = horizontalDistance(spot, target)
            if (away < range * SNIPE_MIN_FACTOR || away > range * EngageBand.ADVANCE_START || moves.crowded(level, spot)) return@repeat
            if (isPerch(level, spot)) return@repeat
            val eye = spot.add(0.0, eyeHeight, 0.0)
            if (gun != null && !gunnery.canLay(eye, targetCentre, gun)) return@repeat
            if (TankSight.solidBlocks(level, eye, targetCentre, 0) != 0) return@repeat

            val high = (spot.y - target.y).coerceIn(-4.0, SNIPE_MAX_HIGH)
            var score = high * SNIPE_HEIGHT_WEIGHT - abs(away - range * SNIPE_IDEAL_FACTOR) / range
            score -= horizontalDistance(spot, position) * if (relocate) SNIPE_MOVE_WEIGHT else SNIPE_MOVE_WEIGHT * 0.25
            if (cell.foliage) score -= 1.0
            if (score > bestScore) {
                bestScore = score
                best = spot
            }
        }
        return best
    }

    private companion object {
        const val SNIPE_SAMPLES = 14
        const val SNIPE_SHOT_WINDOW = 5
        const val SNIPE_REPICK_TICKS = 40
        const val SNIPE_TIMEOUT = 600
        const val SNIPE_ARRIVE_RADIUS = 4.0
        const val SNIPE_HOP_MIN = 14.0
        const val SNIPE_HOP_MAX = 34.0
        const val SNIPE_MIN_FACTOR = 0.6
        const val SNIPE_IDEAL_FACTOR = 0.95
        const val SNIPE_HEIGHT_WEIGHT = 0.12

        /** The most blocks above the target that count as an advantage in the score of a firing spot. */
        const val SNIPE_MAX_HIGH = 6.0
        const val SNIPE_MOVE_WEIGHT = 0.02
    }
}
