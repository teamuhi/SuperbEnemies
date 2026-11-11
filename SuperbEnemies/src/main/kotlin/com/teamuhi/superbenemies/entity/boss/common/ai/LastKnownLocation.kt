package com.teamuhi.superbenemies.entity.boss.common.ai

import com.teamuhi.superbenemies.config.TargetingMethod
import com.teamuhi.superbenemies.debug.AiThought
import com.teamuhi.superbenemies.debug.VisionState
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.Vec3

/**
 * Everything the boss brains remember about where their target was, in one place: the A-10, the AC-130 and the tank
 * bosses all call it instead of keeping their own copy. One per boss brain.
 *
 * **Sight and the ghost** (`DYNAMIC` targeting). While the chosen entity is seen ([sees]) it is returned as is and its
 * position is remembered. Once it is hidden, [resolve] returns a ghost standing at that last known position instead, so
 * the boss keeps attacking the spot where it lost sight, not the hidden target.
 *
 * Why a Marker stand-in: the brains and the gunneries are written against `aim: Entity` (bounding box centre, id,
 * `rootVehicle`, `isAlive`). A never-spawned [EntityType.MARKER] gives "attack the last known position" without touching
 * any attack or flight code. Its id is new, so the brains' velocity estimate resets to zero, which is right for a fixed
 * spot. The marker has zero size, so its bounding box centre is its position (the feet of the last seen spot). The ghost
 * is never added to the world, so nothing ticks, saves or syncs it. A second marker ([spot]) serves a brain that aims at
 * an estimated spot while the first one is in use (the tanks' return fire).
 *
 * **Sight-loss grace** (`graceSeconds`, the `sightLossGraceSeconds` option): when a target that has been seen breaks line
 * of sight, the boss does not drop to the ghost at once. For the grace it keeps tracking the target's real position (its
 * general location, [sensing] is true and [resolve] returns the real entity, so turrets and flight keep following it),
 * and only after the grace is it finally lost: [hidden] turns true and the ghost stands at the position the target had
 * when the grace ended. Exposure during the grace cancels it. The grace does not apply to a target that was hidden the
 * first time it was looked at (it was never seen, which keeps the attacker-first and teammate-call behaviour), it restarts
 * for every new target, and 0 reproduces the behaviour without grace. Because the held target counts as not hidden while
 * [sensing] (see [isHidden]), the brains' "drop a hidden target for an exposed one" rules wait for the grace too.
 *
 * The sight check is cached per target and repeated every [CHECK_INTERVAL] ticks (staggered by the boss id so bosses
 * do not all scan on the same tick); a new target is checked at once.
 *
 * **Remembered positions** (the plane bosses):
 *  - [known]: where the target was last seen, refreshed by [track] every [KNOWN_POSITION_INTERVAL] ticks. Gun runs raze it.
 *  - [anchor]: where the boss last had a target (set every tick through [anchorAt]), or the fight a replacement boss came
 *    back to. With no target the boss circles it.
 *
 * **Last-known strike** (the A-10): when the held target is lost while still alive, [startStrike] keeps the boss attacking
 * [known] (or [anchor]) through [strikeAim] for `lastKnownStrikeSeconds`; [cancelStrike] ends it when a real target is found.
 */
class LastKnownLocation(
    private val boss: Entity,
    /**
     * Whether [boss] can see an entity right now. The default is the planes' rule (open sky above it, or a clear line of
     * sight); the tanks pass a ground sight test instead.
     */
    private val sees: (Entity) -> Boolean = { SkyExposure.isExposed(it) || SkyExposure.hasLineOfSight(boss, it) },
    /** Seconds the real position of a target is still tracked after it breaks line of sight; read live, 0 or less = no grace. */
    private val graceSeconds: () -> Int = { 0 }
) {

    /** The boss's console voice; looked up when used, so building this class never touches the boss's own state. */
    private val thought: AiThought? get() = (boss as? BossVehicle)?.thought

    private var ghost: Entity? = null
    private var spotMarker: Entity? = null
    private var checkedId = -1
    private var exposed = true
    private var lastSeen: Vec3? = null

    /** Whether the current target has been seen at least once; only such a target gets the sight-loss grace. */
    private var seenThisTarget = false

    /** Tick (boss `tickCount`) of the first failed sight check of the current loss of sight, or -1 while seen. */
    private var lostSightAt = -1

    /** The grace of the current loss of sight has run out (reported once, and not restarted by a raised option). */
    private var graceSpent = false

    /** True while the last [resolve] returned the ghost (the real target is hidden). */
    var hidden: Boolean = false
        private set

    /**
     * True during the sight-loss grace: the target is out of sight but still tracked at its real position. [hidden] is
     * false meanwhile, and the held target is not reported hidden by [isHidden].
     */
    var sensing: Boolean = false
        private set

    /** Ticks left of the grace while [sensing], 0 otherwise. */
    val graceLeftTicks: Int
        get() = if (sensing && lostSightAt >= 0) maxOf(0, graceSeconds().coerceAtLeast(0) * 20 - (boss.tickCount - lostSightAt)) else 0

    /** What the target looks like to the boss right now, for the vision debug (see `AiVision`): [aim] is what the last [resolve] returned. */
    fun visionState(aim: Entity?): VisionState = when {
        aim == null -> VisionState.NONE
        hidden -> VisionState.GHOST
        sensing -> VisionState.SENSING
        else -> VisionState.SEEN
    }

    /** Where the target was last seen, refreshed every [KNOWN_POSITION_INTERVAL] ticks by [track]; null until the first sighting. */
    var known: Vec3? = null

    /** Where the boss last had a target, or the fight a replacement boss came back to; null before either. */
    var anchor: Vec3? = null
        private set

    private var strikeUntil = 0
    private var strikePos: Vec3? = null

    /** Whether the strike was running at the last check, so its end is reported once. */
    private var striking = false

    // ================================================================================================== sight and ghost

    /**
     * What to aim at for [aim]: [aim] itself for a null aim or the STATIC method, otherwise [aim] while seen and the
     * ghost at the last known position while hidden. A target that is hidden the first time it is looked at counts as
     * last seen where it is now: the boss "saw" it when it chose it, and it gets no sight-loss grace.
     *
     * With a grace above 0, a target that was seen and then breaks line of sight is first returned as is for the grace
     * ([sensing], its position is still remembered every tick, so the ghost later stands at where it was at the end of the
     * grace); only then is it hidden. Seen again at any time: back to normal.
     */
    fun resolve(aim: Entity?, method: TargetingMethod): Entity? {
        if (aim == null || method == TargetingMethod.STATIC) {
            reset()
            return aim
        }
        val changed = aim.id != checkedId
        if (changed) {
            // A different target: whatever was remembered belongs to the old one.
            checkedId = aim.id
            lastSeen = null
            seenThisTarget = false
            lostSightAt = -1
            graceSpent = false
            sensing = false
        }
        if (changed || (boss.tickCount + boss.id) % CHECK_INTERVAL == 0) {
            exposed = sees(aim)
        }

        if (exposed) {
            lastSeen = aim.position()
            hidden = false
            sensing = false
            seenThisTarget = true
            lostSightAt = -1
            graceSpent = false
            thought?.think("lastKnown.resolve") { "target ${aim.name.string} exposed, aiming at it" }
            return aim
        }

        val graceTicks = graceSeconds().coerceAtLeast(0) * 20
        if (seenThisTarget && graceTicks > 0 && !graceSpent) {
            val now = boss.tickCount
            if (lostSightAt < 0) {
                lostSightAt = now
                thought?.event("lastKnown.grace") { "lost sight of ${aim.name.string}: tracking its general location for ${graceTicks / 20}s" }
            }
            if (now - lostSightAt < graceTicks) {
                lastSeen = aim.position()
                hidden = false
                sensing = true
                val left = (graceTicks - (now - lostSightAt) + 19) / 20
                thought?.think("lastKnown.resolve") { "target ${aim.name.string} out of sight, sensing its general location (${left}s left)" }
                return aim
            }
            graceSpent = true
        }
        if (sensing) {
            sensing = false
            thought?.event("lastKnown.grace") { "grace over: ${aim.name.string}'s last known spot fixed" }
        }
        val seen = lastSeen ?: aim.position().also { lastSeen = it }
        hidden = true
        thought?.think("lastKnown.resolve") { "target ${aim.name.string} hidden, aiming at its last known spot" }
        return ghost(seen)
    }

    /**
     * Moves the remembered last known position of [targetId] to [pos] while that target is hidden (a teammate saw where it
     * went); nothing happens for another target or while the target is seen.
     */
    fun tell(targetId: Int, pos: Vec3) {
        if (hidden && targetId == checkedId) lastSeen = pos
    }

    /** True when [entity] is one of this brain's markers (the real target is hidden, or it aims at a spot), not a real entity. */
    fun isGhost(entity: Entity?): Boolean = entity != null && (entity === ghost || entity === spotMarker)

    /**
     * Whether [entity] is hidden under `DYNAMIC` right now. Not cached, for scanning candidates. The held target is not
     * hidden while the grace runs ([sensing]), so the brains do not swap it for an exposed one before the grace is over.
     */
    fun isHidden(entity: Entity, method: TargetingMethod): Boolean {
        if (method != TargetingMethod.DYNAMIC) return false
        if (sensing && (entity.id == checkedId || entity.rootVehicle.id == checkedId)) return false
        return !sees(entity)
    }

    /** Forgets the cached target and last known position (a new fight, or the STATIC method). */
    fun reset() {
        checkedId = -1
        exposed = true
        lastSeen = null
        hidden = false
        sensing = false
        seenThisTarget = false
        lostSightAt = -1
        graceSpent = false
    }

    /**
     * The reused ghost, moved to [pos] (also used by a brain that wants to attack a spot with no entity there, e.g. the
     * A-10's last-known strike); recreated if the boss changed dimension. The previous position is set too: the
     * ghost is never ticked, so `xo/yo/zo` would otherwise stay at the origin and any `x - xo` velocity read (the A-10's
     * lead calculations) would see a huge bogus speed instead of a fixed spot.
     */
    fun ghost(pos: Vec3): Entity? {
        val marker = markerAt(ghost, pos)
        ghost = marker
        return marker
    }

    /** A second marker, independent of [ghost], moved to [pos]: the tanks' return-fire aim, which can exist beside a hidden target's ghost. */
    fun spot(pos: Vec3): Entity? {
        val marker = markerAt(spotMarker, pos)
        spotMarker = marker
        return marker
    }

    private fun markerAt(existing: Entity?, pos: Vec3): Entity? {
        val level = boss.level()
        val marker = existing?.takeIf { it.level() === level } ?: EntityType.MARKER.create(level)
        marker?.let {
            it.setPos(pos.x, pos.y, pos.z)
            it.setOldPosAndRot()
        }
        return marker
    }

    // ================================================================================================== remembered positions

    /**
     * Refreshes [known] from [aim] on the first sighting and then every [KNOWN_POSITION_INTERVAL] ticks. [allowed] false
     * freezes it: a gun run keeps razing the spot it started on, so a replacement target must not drag the dive elsewhere.
     */
    fun track(aim: Entity?, allowed: Boolean = true) {
        if (aim == null || !allowed) return
        if (known == null || (boss.tickCount + boss.id) % KNOWN_POSITION_INTERVAL == 0) known = aim.position()
    }

    /** Remembers [pos] as where the boss had a target (every tick while it has one), or as the fight a replacement came back to. */
    fun anchorAt(pos: Vec3) {
        anchor = pos
    }

    // ================================================================================================== last-known strike

    /**
     * Starts the strike on [known], else [anchor], for [seconds] (a lost target that is still alive). Does nothing for 0
     * seconds (the option is off) or with no spot remembered. Returns whether a strike started.
     */
    fun startStrike(seconds: Int): Boolean {
        val ticks = seconds * 20
        val spot = known ?: anchor
        if (ticks <= 0 || spot == null) {
            thought?.event("lastKnown.startStrike") { if (ticks <= 0) "no last-known strike (option off)" else "no last-known strike (no spot remembered)" }
            return false
        }
        strikePos = spot
        strikeUntil = boss.tickCount + ticks
        striking = true
        thought?.event("lastKnown.startStrike") { "target lost: striking its last known spot for ${seconds}s" }
        return true
    }

    /** True while the boss is attacking the spot a lost target was last seen at; [hasTarget] (a real target is held) ends it. */
    fun strikeActive(hasTarget: Boolean): Boolean = !hasTarget && strikePos != null && boss.tickCount < strikeUntil

    /** The ghost on the strike spot while [strikeActive], else null. */
    fun strikeAim(hasTarget: Boolean): Entity? {
        if (striking && !strikeActive(hasTarget)) {
            striking = false
            thought?.event("lastKnown.strikeAim") { "last-known strike over: time is up" }
        }
        return strikePos?.takeIf { strikeActive(hasTarget) }?.let { ghost(it) }
    }

    /** A real target took over: the strike is over. */
    fun cancelStrike() {
        if (striking) {
            striking = false
            thought?.event("lastKnown.cancelStrike") { "real target found: last-known strike cancelled" }
        }
        strikeUntil = 0
    }

    private companion object {
        /** Ticks between sight re-checks of the same target. */
        const val CHECK_INTERVAL = 5

        /** Ticks between refreshes of [known] while the target is followed. */
        const val KNOWN_POSITION_INTERVAL = 30
    }
}
