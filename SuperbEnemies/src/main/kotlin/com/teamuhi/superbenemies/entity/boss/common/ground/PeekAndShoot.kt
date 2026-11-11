package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath.horizontalDistance
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The ENGAGE movement of a heavy tank (one that snipes) that has cover about ([CoverAwareness.bestPair]): a hide spot behind
 * a wall or ridge and a peek spot next to it from which the turret sees the target and the main gun can be laid on it.
 * One instance per brain.
 *  - TO_HIDE: drives to the hide spot (at a sprint when it was hit lately), reversing when it is close behind the hull so
 *    the front armour stays to the target;
 *  - HIDE: waits there, hull to the target, until the main gun is loaded ([TankGunnery.cannonReady]) or [hideCapTicks]
 *    have passed. The cannon is held ([holdsCannon]), the turret keeps its aim, so it is laid when the tank steps out;
 *  - PEEK: drives to the peek spot and fires; the round that leaves ([TankGunnery.lastCannonShotTick] changes) sends it
 *    straight back to TO_HIDE. No round within [PEEK_MAX] ticks fails the pair.
 * A pair is dropped (and rejected, so it is not picked again for a while) when its rays no longer hold
 * ([CoverAwareness.stillValid]), the line stays blocked at the peek spot, the tank is stuck or a leg takes too long;
 * it is dropped without blame when the target leaves the engage band. Another pair is looked for at once; with none (open
 * ground, `useCover` off) the caller falls back to shoot and scoot ([SnipePosition]).
 *
 * @param onCycleStart called each time a new pair is picked (the brain drops its sniping spot)
 */
class PeekAndShoot(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val gunnery: TankGunnery,
    private val driver: GroundDriver,
    private val cover: CoverAwareness,
    private val moves: GroundMoves,
    private val onCycleStart: () -> Unit
) {

    private enum class PeekPhase { NONE, TO_HIDE, HIDE, PEEK }

    private var peekPhase = PeekPhase.NONE

    /** The hide / peek spots being cycled between, or null when the tank is not in the peek cycle. */
    private var peekPair: CoverPair? = null
    private var peekPhaseStart = 0

    /** [TankGunnery.lastCannonShotTick] when the PEEK phase began: a different value means the round was fired. */
    private var peekShotSeen = Int.MIN_VALUE / 2
    private var peekNextPick = 0

    /** True while the tank hides on purpose: a pair is held and the tank is on its way to the hide spot or waiting there. */
    val hiding: Boolean
        get() = peekPair != null && (peekPhase == PeekPhase.TO_HIDE || peekPhase == PeekPhase.HIDE)

    /**
     * True when `useCover` is on, a pair is held and the tank is not in the PEEK phase at the peek spot. The line of fire is
     * meant to be blocked then, so the brain resets its blocked count before [tick] (it only counts once the tank is at the peek spot).
     */
    fun awayFromPeek(): Boolean {
        if (!settings.useCover.get()) return false
        val pair = peekPair ?: return false
        return !(peekPhase == PeekPhase.PEEK && horizontalDistance(boss.position(), pair.peek) <= PEEK_ARRIVE_RADIUS)
    }

    /**
     * True while the main gun must keep quiet: a heavy tank behind its hide spot has nothing to shoot at but the wall (an
     * armour-piercing round would be asked to pierce it), and one on its way there with the line blocked ([lineBlocked]) likewise.
     */
    fun holdsCannon(lineBlocked: Boolean): Boolean =
        peekPair != null && (peekPhase == PeekPhase.HIDE || (peekPhase == PeekPhase.TO_HIDE && lineBlocked))

    /** The longest the peek cycle waits out of sight: [PEEK_HIDE_MAX], and less than the time after which a hidden target is dropped. */
    fun hideCapTicks(): Int {
        val breakTicks = settings.breakDetectionSeconds.get() * 20
        return if (breakTicks <= 0) PEEK_HIDE_MAX else min(PEEK_HIDE_MAX, max(PEEK_HIDE_MIN_CAP, breakTicks - PEEK_BREAK_MARGIN))
    }

    /**
     * Runs the cycle for this tick. [blockedTicks]: how long the line of fire has been blocked (already reset by the brain
     * while the tank is away from the peek spot, see [awayFromPeek]).
     * @return true when the cycle has the wheel this tick
     */
    fun tick(level: Level, aim: Entity, distance: Double, range: Double, blockedTicks: Int): Boolean {
        if (!settings.useCover.get()) {
            boss.thought.think("peek.tick") { "cover is off in the config: no peek cycle" }
            drop()
            return false
        }
        val now = boss.tickCount
        val position = boss.position()
        val target = aim.position()

        var pair = peekPair
        if (pair != null) {
            val atPeek = peekPhase == PeekPhase.PEEK && horizontalDistance(position, pair.peek) <= PEEK_ARRIVE_RADIUS
            val outOfBand = distance > range * EngageBand.ADVANCE_START || distance < range * EngageBand.BACK_OFF_FACTOR
            val failed = (atPeek && blockedTicks > EngageBand.BLOCKED_REPOSITION_TICKS) || driver.gaveUp || !cover.stillValid(level, pair, aim)
            if (outOfBand || failed) {
                boss.thought.event("peek.tick") { "leaving the peek cycle (${if (failed) "the pair failed" else "the target left the band"})" }
                drop(reject = failed)
                pair = null
            }
        }
        if (pair == null) {
            if (now < peekNextPick) return false
            pair = cover.bestPair(aim)
            if (pair == null) {
                peekNextPick = now + PEEK_REPICK_TICKS
                boss.thought.think("peek.tick") { "no cover pair found, falling back to a firing spot" }
                return false
            }
            val chosen = pair
            boss.thought.event("peek.pair") {
                "peek cycle: hide at ${chosen.hide.x.toInt()},${chosen.hide.z.toInt()}, peek at ${chosen.peek.x.toInt()},${chosen.peek.z.toInt()}" +
                        if (chosen.hullDown) " (hull-down)" else ""
            }
            peekPair = pair
            peekPhase = PeekPhase.TO_HIDE
            peekPhaseStart = now
            onCycleStart()
            moves.newGoal()
        }

        val shot = gunnery.lastCannonShotTick
        if (peekPhase == PeekPhase.TO_HIDE && horizontalDistance(position, pair.hide) <= PEEK_ARRIVE_RADIUS) {
            peekPhase = PeekPhase.HIDE
            peekPhaseStart = now
            boss.thought.event("peek.phase") { "TO_HIDE -> HIDE (behind cover, waiting for the gun)" }
        }
        if (peekPhase == PeekPhase.HIDE && (gunnery.cannonReady() || now - peekPhaseStart >= hideCapTicks())) {
            val loaded = gunnery.cannonReady()
            boss.thought.event("peek.phase") { "HIDE -> PEEK (${if (loaded) "gun loaded" else "waited long enough"})" }
            peekPhase = PeekPhase.PEEK
            peekPhaseStart = now
            peekShotSeen = shot
            moves.newGoal()
        }
        if (peekPhase == PeekPhase.PEEK && shot != peekShotSeen) {
            boss.thought.event("peek.phase") { "PEEK -> TO_HIDE (round fired, dropping back)" }
            peekPhase = PeekPhase.TO_HIDE
            peekPhaseStart = now
            moves.newGoal()
        }
        val timeout = if (peekPhase == PeekPhase.PEEK) PEEK_MAX else PEEK_TRAVEL_TIMEOUT
        if (peekPhase != PeekPhase.HIDE && now - peekPhaseStart > timeout) {
            if (peekPhase == PeekPhase.PEEK && !gunnery.cannonReady()) {
                // The gun was not loaded when the tank stepped out (the hide time ran out first): that is no fault of the
                // pair, back behind cover to wait for it.
                boss.thought.event("peek.phase") { "PEEK -> TO_HIDE (gun was not loaded, waiting behind cover)" }
                peekPhase = PeekPhase.TO_HIDE
                peekPhaseStart = now
                moves.newGoal()
            } else {
                boss.thought.event("peek.tick") { "peek cycle: $peekPhase took too long, pair rejected" }
                drop(reject = true)
                return false
            }
        }

        when (peekPhase) {
            PeekPhase.TO_HIDE -> {
                val mode = if (boss.recentlyDamaged(EngageBand.SPRINT_HIT_WINDOW)) DriveMode.SPRINT else DriveMode.CRUISE
                boss.thought.think("peek.tick") { "going to the hide spot ($mode)" }
                driveCover(level, pair.hide, target, mode)
            }

            PeekPhase.HIDE -> {
                // Hull to the target, the turret already laid on it; the machine guns may still fire.
                boss.thought.think("peek.tick") { "hiding, waiting for the main gun to load" }
                if (driver.turnToward(target, TankFacing.TRACKED)) driver.hold()
                moves.look(null)
            }

            PeekPhase.PEEK -> {
                if (horizontalDistance(position, pair.peek) <= PEEK_ARRIVE_RADIUS) {
                    boss.thought.think("peek.tick") { "at the peek spot, firing one round" }
                    if (driver.turnToward(target, TankFacing.TRACKED)) driver.hold()
                    moves.look(null)
                } else {
                    boss.thought.think("peek.tick") { "stepping out to the peek spot" }
                    driveCover(level, pair.peek, target, DriveMode.CRUISE)
                }
            }

            PeekPhase.NONE -> return false
        }
        return true
    }

    /**
     * Drives to [spot] of the peek cycle: backwards, front armour to [facing], when the spot lies close behind the hull
     * (within [PEEK_REVERSE_MAX] blocks and within about 35 degrees of the tail's line, over ground the rear probes find
     * clear); along a planned route otherwise.
     */
    private fun driveCover(level: Level, spot: Vec3, facing: Vec3, mode: DriveMode) {
        val position = boss.position()
        val dx = spot.x - position.x
        val dz = spot.z - position.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance in 0.5..PEEK_REVERSE_MAX) {
            val forward = FlightMath.dir(boss.yRot, 0f)
            val along = (forward.x * dx + forward.z * dz) / distance
            if (along < -PEEK_REVERSE_COS && moves.rearClear(level)) {
                boss.thought.think("peek.driveCover") { "reversing onto the spot, front armour to the target" }
                driver.reverseTo(spot, facing, mode, arriveRadius = PEEK_REVERSE_ARRIVE)
                moves.look(null)
                return
            }
        }
        boss.thought.think("peek.driveCover") { "driving to the spot along a route" }
        moves.goTo(level, spot, mode)
        moves.look(null)
    }

    /** The spot the peek cycle is heading for or waiting at, so other tanks keep clear of it; null outside the cycle. */
    fun destination(): Vec3? {
        val pair = peekPair ?: return null
        return if (peekPhase == PeekPhase.PEEK) pair.peek else pair.hide
    }

    /** Leaves the peek cycle; with [reject] the pair is remembered as one that did not work. */
    fun drop(reject: Boolean = false) {
        if (peekPair != null) boss.thought.event("peek.drop") { "peek cycle dropped" + if (reject) " (pair rejected)" else "" }
        if (reject) peekPair?.let { cover.reject(it) }
        peekPair = null
        peekPhase = PeekPhase.NONE
    }

    private companion object {
        /** Longest the tank waits behind its hide spot for the gun, in ticks (and less than `breakDetectionSeconds` allows). */
        const val PEEK_HIDE_MAX = 100
        const val PEEK_HIDE_MIN_CAP = 20

        /** Ticks kept clear of the time after which a hidden target is dropped. */
        const val PEEK_BREAK_MARGIN = 20

        /** Ticks at the peek spot without a round leaving before the pair is given up. */
        const val PEEK_MAX = 80
        const val PEEK_TRAVEL_TIMEOUT = 300
        const val PEEK_ARRIVE_RADIUS = 3.5
        const val PEEK_REPICK_TICKS = 40

        /** The hide / peek spot is reversed onto when it lies within this many blocks and within acos(PEEK_REVERSE_COS) of the tail line. */
        const val PEEK_REVERSE_MAX = 12.0
        const val PEEK_REVERSE_COS = 0.82
        const val PEEK_REVERSE_ARRIVE = 2.0
    }
}
