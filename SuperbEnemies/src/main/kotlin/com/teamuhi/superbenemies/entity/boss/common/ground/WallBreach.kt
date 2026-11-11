package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.config.TankSettings
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shooting a way through a wall instead of driving round it. The route planner ([GroundRoutePlanner]) only routes round
 * obstacles; when that detour is long (or there is no route at all) and a thin, breakable wall stands on the straight line
 * to the goal, the tank shoots a hole in it with its main gun, drives through and plans again from the far side.
 *
 * Run from the brain's `goTo` (so every state that drives to a goal can use it), once per tick: [tick] returns true while the
 * breach drives the hull, and the brain aims the main gun at [aim] and fires it when [mayFire]. One instance per brain.
 *
 * **When.** `wallBreaching` and `blockDamage` are on, the main gun is usable (with `limitedAmmo` more than [AMMO_RESERVE]
 * rounds left) and the tank has no target in sight (it fights first). Every [SCAN_INTERVAL] ticks, while the route is a
 * detour (at least [MIN_DETOUR] blocks and [DETOUR_RATIO] times the straight distance), ends short of the goal ([GroundRoutePlanner.partial])
 * or does not exist, the line to the goal is swept with the hull's footprint ([HullSweep]) for [LOOK_RANGE] blocks. A blocked
 * column there is a wall to breach when the blocks in it that stop the hull are all breakable (see [breakable]) and the hull
 * can enter the line again within [MAX_THICKNESS] blocks behind it. Water, lava, a drop, an unloaded chunk or bedrock are no wall.
 *
 * **How.** APPROACH drives to [STANDOFF_APPROACH] blocks short of the wall when it is further than [STANDOFF_MAX]; AIM stands
 * still between [STANDOFF_MIN] and [STANDOFF_MAX] (backing off when closer: the cannon will not fire inside its minimum range and
 * a close blast would hurt the tank) while the turret lays the main gun, alone, on the wall block ([aim] is a marker entity, the
 * technique of return fire). [IMPACT_TICKS] after the last round the line is swept again: free means THROUGH, a wall still there
 * is aimed at again (the target moves deeper, or to the side if the hole is narrower than the hull), anything unbreakable ends the
 * attempt. THROUGH drives the straight line out the far side; the route is then thrown away and planned again from there.
 *
 * An attempt that fails (too many rounds, too long, no room to back off, the way turns out unbreakable) puts the breach on a
 * [FAIL_COOLDOWN] ticks pause. All state is per instance; server thread only.
 *
 * @param profile the hull the planner routes with (see the brain's `routeProfile`)
 */
class WallBreach(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val gunnery: TankGunnery,
    private val targeting: TankTargeting,
    private val driver: GroundDriver,
    private val moves: GroundMoves,
    private val profile: () -> GroundProfile
) {

    private enum class Phase { IDLE, APPROACH, AIM, THROUGH }

    private enum class Kind { CLEAR, WALL, BLOCKED }

    /** What a sweep along a line found: [CLEAR], a [WALL] ([block] is the one to shoot, [thickness] blocks deep) or something that cannot be breached. */
    private class Scan(val kind: Kind, val block: BlockPos? = null, val thickness: Double = 0.0)

    private var phase = Phase.IDLE
    private var target: BlockPos? = null
    private var thickness = 0.0
    private var exit: Vec3? = null

    private var startedTick = 0
    private var phaseTick = 0
    private var lastCalled = Int.MIN_VALUE / 2
    private var nextScan = 0
    private var cooldownUntil = 0

    private var seenShot = 0
    private var rounds = 0
    private var assessAt = 0
    private var fireOk = false

    /** True while a breach is under way (it then owns the driving, see [tick]). */
    val active: Boolean get() = phase != Phase.IDLE

    // ================================================================================================== use

    /**
     * One tick of the breach, called whenever the brain drives toward a goal. Starts a breach when the rules in the class doc
     * allow it and runs the one in progress.
     * @param allowed false while the tank has a target in sight: a breach in progress is dropped and none is started
     * @return true when the breach drives the hull this tick (the caller must not drive)
     */
    fun tick(level: Level, planner: GroundRoutePlanner, allowed: Boolean): Boolean {
        val now = boss.tickCount
        if (phase != Phase.IDLE && now - lastCalled > STALE_TICKS) phase = Phase.IDLE // nobody drove with it for a while
        lastCalled = now
        if (phase != Phase.IDLE) return run(level, planner, allowed, now)

        if (!allowed || now < cooldownUntil || now < nextScan || !canBreach()) return false
        nextScan = now + SCAN_INTERVAL
        val goal = planner.goal ?: return false
        if (!worthIt(planner, goal)) return false
        val scan = scan(level, goal) ?: return false
        if (scan.kind != Kind.WALL) {
            nextScan = now + SCAN_INTERVAL * 2
            boss.thought.think("breach.tick") { "the way is a detour, but no breachable wall on the line (${scan.kind})" }
            return false
        }
        begin(scan, now)
        return run(level, planner, allowed, now)
    }

    /** The marker the main gun is laid on while a breach waits and fires, else null. Valid for the tick it is asked in. */
    fun aim(): Entity? {
        if (phase != Phase.AIM || boss.tickCount - lastCalled > STALE_TICKS) return null
        return targeting.lastKnown.spot(center() ?: return null)
    }

    /** True when the main gun may fire at [aim] this tick: the tank stands still inside the firing band. */
    fun mayFire(): Boolean = phase == Phase.AIM && fireOk

    /** Drops a breach in progress (the brain switched to another activity); a short pause keeps it from starting again at once. */
    fun cancel() {
        if (phase == Phase.IDLE) return
        boss.thought.event("breach.cancel") { "breach dropped from $phase: the brain switched activity" }
        phase = Phase.IDLE
        cooldownUntil = max(cooldownUntil, boss.tickCount + CANCEL_COOLDOWN)
    }

    // ================================================================================================== deciding

    /** Config on, the main gun there and (with finite ammo) not nearly empty. */
    private fun canBreach(): Boolean {
        if (!settings.wallBreaching.get() || !settings.blockDamage.get() || boss.isWreck || boss.crippled) return false
        val gun = gunnery.mainGun() ?: return false
        return gunnery.ammoLeft(gun) > AMMO_RESERVE
    }

    /** Whether the way to [goal] is bad enough to shoot through a wall: no route, one that stops short, or a long detour. */
    private fun worthIt(planner: GroundRoutePlanner, goal: Vec3): Boolean {
        if (planner.arrived) return false
        if (planner.status == GroundRoutePlanner.Status.NO_ROUTE) return true
        if (!planner.hasRoute) return false
        if (planner.partial) return true
        val straight = horizontalDistance(boss.position(), goal)
        val along = planner.remainingLength(boss.position())
        return along - straight >= MIN_DETOUR && along >= straight * DETOUR_RATIO
    }

    /** Sweeps the straight line from the tank to [goal]; null when the goal is too close to bother. */
    private fun scan(level: Level, goal: Vec3): Scan? {
        val dx = goal.x - boss.x
        val dz = goal.z - boss.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < MIN_GOAL_DISTANCE) return null
        return scan(level, dx / distance, dz / distance, min(distance, LOOK_RANGE))
    }

    /**
     * Sweeps the hull's footprint from the nose along the unit direction ([ux], [uz]) for [look] blocks. The first blocked column
     * is a wall when what stops the hull there is solid and breakable and the line is free again within [MAX_THICKNESS] blocks.
     */
    private fun scan(level: Level, ux: Double, uz: Double, look: Double): Scan {
        val hull = profile()
        val nose = hull.halfLength
        val hit = HullSweep.sweepDir(level, boss.x, boss.z, boss.y, ux, uz, nose, nose + look, hull) ?: return Scan(Kind.CLEAR)
        val chunk = level.chunkSource.getChunkNow(hit.x shr 4, hit.z shr 4) ?: return Scan(Kind.BLOCKED)

        // Blocks that stop the hull in the blocked column: everything with a collision in the hull's height.
        val base = Mth.floor(boss.y + 0.05)
        val pos = BlockPos.MutableBlockPos()
        var chosen: BlockPos? = null
        for (y in base until base + HullSweep.CLEARANCE) {
            pos.set(hit.x, y, hit.z)
            val state = chunk.getBlockState(pos)
            if (state.getCollisionShape(chunk, pos).isEmpty) continue
            if (!breakable(chunk, pos, state)) return Scan(Kind.BLOCKED)
            // The second layer is the one to aim at: the shell flies in over the lowest block and the blast reaches both.
            if (chosen == null || y == base + 1) chosen = pos.immutable()
        }
        val block = chosen ?: return Scan(Kind.BLOCKED)

        // How deep the wall is: the first distance behind the hit from which the hull can enter the line again.
        var depth = 0
        while (depth <= MAX_THICKNESS) {
            val from = hit.distance + depth
            if (HullSweep.sweepDir(level, boss.x, boss.z, boss.y, ux, uz, from, from + EXIT_CLEAR, hull) == null) {
                return Scan(Kind.WALL, block, depth.toDouble())
            }
            depth++
        }
        return Scan(Kind.BLOCKED)
    }

    /** A block a shell can be asked to remove: not bedrock, a hazard or a container, and no tougher than [MAX_RESISTANCE]. */
    private fun breakable(chunk: ChunkAccess, pos: BlockPos, state: BlockState): Boolean =
        !GroundTerrain.isHazard(state) && !state.hasBlockEntity() && state.getDestroySpeed(chunk, pos) >= 0f &&
            state.block.getExplosionResistance() <= MAX_RESISTANCE

    // ================================================================================================== running

    private fun begin(scan: Scan, now: Int) {
        target = scan.block
        thickness = scan.thickness
        exit = null
        startedTick = now
        rounds = 0
        seenShot = gunnery.lastCannonShotTick
        assessAt = 0
        fireOk = false
        moveTo(Phase.AIM, now)
        boss.thought.event("breach.begin") { "breaching a wall at ${scan.block!!.x},${scan.block.y},${scan.block.z}, ${scan.thickness.toInt()} blocks thick" }
    }

    private fun moveTo(next: Phase, now: Int) {
        if (next != phase) boss.thought.event("breach.phase") { "$phase -> $next" }
        phase = next
        phaseTick = now
    }

    private fun run(level: Level, planner: GroundRoutePlanner, allowed: Boolean, now: Int): Boolean {
        if (!allowed || boss.isWreck || boss.crippled) return abort(CANCEL_COOLDOWN, "target in sight or wreck")
        if (now - startedTick > MAX_TICKS) return abort(FAIL_COOLDOWN, "took too long")
        if (phase != Phase.THROUGH && !canBreach()) return abort(FAIL_COOLDOWN, "gun or ammunition gone")
        val center = center() ?: return abort(FAIL_COOLDOWN, "no target block")
        val distance = horizontalDistance(boss.position(), center)
        fireOk = false

        when (phase) {
            Phase.APPROACH -> {
                if (distance <= STANDOFF_MAX) {
                    moveTo(Phase.AIM, now)
                    driver.hold()
                } else if (now - phaseTick > APPROACH_TICKS) {
                    return abort(FAIL_COOLDOWN, "could not get in range")
                } else {
                    boss.thought.think("breach.run") { "approaching the wall to get in range" }
                    val away = Vec3((boss.x - center.x) / distance, 0.0, (boss.z - center.z) / distance)
                    driver.drive(Vec3(center.x + away.x * STANDOFF_APPROACH, boss.y, center.z + away.z * STANDOFF_APPROACH))
                }
            }

            Phase.AIM -> {
                when {
                    distance > STANDOFF_MAX + STANDOFF_SLACK -> moveTo(Phase.APPROACH, now)
                    distance < STANDOFF_MIN -> {
                        if (!moves.rearClear(level)) return abort(FAIL_COOLDOWN, "no room to back off")
                        boss.thought.think("breach.run") { "backing off: too close to the wall for the cannon" }
                        driver.backAway(center)
                    }

                    else -> {
                        driver.hold()
                        fireOk = driver.speed < SETTLE_SPEED
                        boss.thought.think("breach.run") { if (fireOk) "laid on the wall, firing" else "laid on the wall, settling before firing" }
                    }
                }
                countRounds(now)
                if (assessAt != 0 && now >= assessAt && driver.speed < SETTLE_SPEED) {
                    assessAt = 0
                    if (!reassess(level, center, now)) return false
                }
            }

            Phase.THROUGH -> {
                val way = exit ?: return abort(FAIL_COOLDOWN, "no exit")
                val left = horizontalDistance(boss.position(), way)
                if (left < THROUGH_ARRIVE) return finish(planner, now)
                if (now - phaseTick > THROUGH_TICKS || driver.gaveUp) return abort(FAIL_COOLDOWN, "stuck in the breach")
                boss.thought.think("breach.run") { "driving through the hole" }
                driver.drive(way)
            }

            Phase.IDLE -> return false
        }
        return true
    }

    /** Counts the rounds the main gun fired since the last look and schedules the next look [IMPACT_TICKS] after the latest. */
    private fun countRounds(now: Int) {
        val shot = gunnery.lastCannonShotTick
        if (shot == seenShot) return
        seenShot = shot
        rounds++
        assessAt = now + IMPACT_TICKS
    }

    /**
     * Looks at the wall again after the rounds have landed.
     * @return false when the breach ended (the caller must not drive); true to carry on
     */
    private fun reassess(level: Level, center: Vec3, now: Int): Boolean {
        val dx = center.x - boss.x
        val dz = center.z - boss.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < 1.0e-3) return abort(FAIL_COOLDOWN, "on top of the target")
        val ux = dx / distance
        val uz = dz / distance
        val scan = scan(level, ux, uz, distance + thickness + EXIT_CLEAR + 1.0)
        when (scan.kind) {
            Kind.CLEAR -> {
                exit = Vec3(center.x + ux * (thickness + EXIT_MARGIN), boss.y, center.z + uz * (thickness + EXIT_MARGIN))
                moveTo(Phase.THROUGH, now)
                boss.thought.event("breach.reassess") { "wall breached after $rounds rounds, driving through" }
            }

            Kind.WALL -> {
                if (rounds >= maxRounds()) return abort(FAIL_COOLDOWN, "still standing after $rounds rounds")
                target = scan.block
                thickness = max(thickness, scan.thickness)
                boss.thought.event("breach.reassess") { "wall still standing after $rounds rounds, aiming again" }
            }

            Kind.BLOCKED -> return abort(FAIL_COOLDOWN, "the rest of it cannot be breached")
        }
        return true
    }

    /** Rounds one attempt may fire: a few shells for a slow gun, many for an automatic cannon. */
    private fun maxRounds(): Int {
        val burst = gunnery.mainGun()?.let { it.burstMax > 0 } ?: false
        return if (burst) MAX_BURST_ROUNDS else MAX_ROUNDS
    }

    /** Through the hole: the route is thrown away (the cell grid cannot see a hole, the old route leads round the wall) and planned again from here. */
    private fun finish(planner: GroundRoutePlanner, now: Int): Boolean {
        phase = Phase.IDLE
        cooldownUntil = now + SUCCESS_COOLDOWN
        planner.invalidate()
        driver.resetStuck()
        boss.thought.event("breach.finish") { "through the wall, planning a new route" }
        return false
    }

    /** Ends the breach and starts a pause. Always returns false: the caller drives normally this tick. */
    private fun abort(cooldown: Int, reason: String): Boolean {
        boss.thought.event("breach.abort") { "breach given up: $reason" }
        phase = Phase.IDLE
        cooldownUntil = boss.tickCount + cooldown
        driver.resetStuck()
        return false
    }

    private fun center(): Vec3? = target?.let { Vec3(it.x + 0.5, it.y + 0.5, it.z + 0.5) }

    private fun horizontalDistance(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private companion object {
        /** Ticks between two looks along the line to the goal while the route is a detour. */
        const val SCAN_INTERVAL = 20

        /** A route has to be at least this many blocks longer than the straight line, and [DETOUR_RATIO] times as long, to be worth a breach. */
        const val MIN_DETOUR = 40.0
        const val DETOUR_RATIO = 2.0

        /** A goal nearer than this is not worth shooting a wall for. */
        const val MIN_GOAL_DISTANCE = 12.0

        /** How far along the line to the goal a wall is looked for (blocks). */
        const val LOOK_RANGE = 48.0

        /** The thickest wall (blocks along the line) a tank shoots through. */
        const val MAX_THICKNESS = 6

        /** Free blocks the hull needs behind a wall for the line to count as open again. */
        const val EXIT_CLEAR = 3.0

        /** Blocks past the far side of the wall the hull drives to. */
        const val EXIT_MARGIN = 4.0

        /** The toughest block (blast resistance: stone is 6, obsidian 1200) a tank tries to shoot away. */
        const val MAX_RESISTANCE = 30f

        /** With `limitedAmmo`, the main gun keeps this many rounds for fighting. */
        const val AMMO_RESERVE = 3

        // Standing off: the cannon does not fire inside 6 blocks, and the blast of a heavy shell reaches about ten.
        const val STANDOFF_MIN = 11.0
        const val STANDOFF_MAX = 34.0
        const val STANDOFF_SLACK = 4.0

        /** Distance from the wall the approach drives to. */
        const val STANDOFF_APPROACH = 24.0

        /** Blocks per tick under which the hull counts as standing still. */
        const val SETTLE_SPEED = 0.06

        /** Ticks after the last round before the wall is looked at again. */
        const val IMPACT_TICKS = 14

        const val MAX_ROUNDS = 8
        const val MAX_BURST_ROUNDS = 60

        /** Longest a whole attempt, an approach and a drive through may take (ticks). */
        const val MAX_TICKS = 1500
        const val APPROACH_TICKS = 500
        const val THROUGH_TICKS = 240

        /** Distance from the exit point at which the drive through is over. */
        const val THROUGH_ARRIVE = 3.0

        /** Ticks without a call after which a breach in progress is forgotten. */
        const val STALE_TICKS = 10

        const val SUCCESS_COOLDOWN = 100
        const val FAIL_COOLDOWN = 1200
        const val CANCEL_COOLDOWN = 40
    }
}
