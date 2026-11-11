package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.config.TargetingMethod
import com.teamuhi.superbenemies.entity.boss.common.ResupplyNotices
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.ai.ThreatMonitor
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The decision making of a tank boss: the glue between targeting ([TankTargeting]), route finding
 * ([GroundRoutePlanner]), driving ([GroundDriver]), the threat monitor, hearing ([NoiseMap]), smoke ([SmokeScreen] is what
 * other bosses' sight rays test) and the guns ([TankGunnery]). One class for both tanks: the LAV-25 and the M1A2 pass their
 * own config, gunnery and driving profile to a trivial subclass.
 *
 * `tick()` runs on the server **before** `VehicleEntity.baseTick`, so the driving inputs and smoke command written here
 * are in place when Superb Warfare's engine consumes them; `applyAim()` runs **after** it (see [TankGunnery]).
 *
 * States ([TankState]):
 *  - PATROL: no target; roams within `leashRange` of home.
 *  - INVESTIGATE (DYNAMIC): drives to a noise it heard ([NoiseMap]) or to a smoke cloud it saw ([SmokeScout]), then SEARCH.
 *    The cannon and machine gun fire of other vehicles is pinpointed: the shooter becomes the target at the heard spot.
 *  - SEARCH (DYNAMIC): the target is out of sight (or a noise led here); shells the last seen spot for a few seconds, then
 *    sweeps rings of waypoints around it, nearest first and widening, for `searchSeconds`, then gives up (PATROL).
 *  - ENGAGE: holds `engageRange` (closes in when far or when walls block the shot, backs off when close), hull to the
 *    threat, firing on the move. Occasionally rolls a flank. A tank that [snipes] (the M1A2 and ZTZ-99A) uses the cover
 *    around it ([CoverAwareness]) to peek, fire one cannon round and drop back behind a wall or ridge while the gun
 *    reloads ([PeekAndShoot]); with no cover about (or `useCover` off) it picks a firing spot with a clear line, at range and
 *    on high ground, and after every cannon round moves to another spot nearby ([SnipePosition]). A tank that [strafes]
 *    (LAV-25 and Bradley) circles the target on the engage ring in legs that run into one another (it never stops to plan
 *    the next), and now and then rocks in and out of a hull-down spot (body hidden from the target, turret able to see it)
 *    for a second or two ([StrafeManeuver]).
 *  - FLANK: pops smoke and sprints to a point 60-110 degrees off the line to the target, then back to ENGAGE.
 *  - EVADE: an incoming guided missile (or an aircraft it cannot reach hitting it): smoke, a hard turn and a dash, then
 *    back to whatever it was doing.
 *  - PULL_BACK ([PullBackRetreat], every tank): hit hard (`pullBackDamagePercent` of the maximum health within 3 seconds):
 *    sprints under smoke to cover, behind a wall or ridge from a ground attacker or out of an aircraft's sight, holds there
 *    and then back to whatever it was doing.
 *  - RETREAT: limited ammo is spent: drives away and withdraws (`BossRespawns`) once unseen or arrived; its replacement
 *    comes back with a full load. A tank that cannot free itself (stuck three times) withdraws the same way.
 *  - CRIPPLED: terminal; the tank is a wreck (it fights to its last point of health, there is no knocked-out phase before that), the controls are released.
 *
 * Elevation ([ElevationDetection]): a gun only lays between its depression and elevation limits. A tank that cannot lay it on its
 * target from where it stands (on a rooftop above a street, say) drives to a stand-off spot from which it can (the ELEVATION engage
 * move); one that is stuck on a perch it cannot route off (a closed island of cells with lower ground round it) drives off the edge
 * with a descent planner that may step down ([GroundProfile.maxDrop]), and is set down on the ground below by [DepotRetreat]'s
 * rescue when that fails. No goal picker (patrol, firing spots, cover, strafing legs, flank, pull-back shelter) chooses a perch.
 *
 * Decision commitment (`decisionCommitSeconds`, [DecisionCommitment]): the engage move (advance, back off, circle, peek / snipe,
 * stand-off...) is re-decided every [DECISION_INTERVAL] ticks (staggered by entity id), and a different one replaces the committed one
 * only once the commitment has run out. Urgent things skip the wait: the target inside the back-off distance, a new target, the
 * gun needing (or no longer needing) a stand-off, a move that cannot go on or has reached its end. Each tick still runs the body of
 * the committed move, so the driving stays smooth. The same window holds the spot a noise or smoke sent the tank to and the dwell
 * in the soft states (PATROL, INVESTIGATE and SEARCH among themselves, and ENGAGE to SEARCH); seeing a target, losing it, EVADE,
 * PULL_BACK, RETREAT and CRIPPLED never wait. 0 = every moment decides afresh.
 *
 * Obstacle awareness: the driver sweeps the hull's footprint ahead ([HullSweep]) and swerves round blocks the planner's
 * cell grid could not see; what it cannot get round is passed to the planner ([learnFromDriver]), which marks the cell as
 * blocked and plans round it.
 *
 * Wall breaching ([WallBreach]): when the route round a wall is a long detour (or there is none) and the tank has no target in
 * sight, `goTo` hands the driving to [WallBreach], which shoots a hole through a thin, breakable wall on the straight line to the
 * goal with the main gun and drives through; the main gun alone is laid on the wall block in the gun section of `tick()`. Any
 * change of state drops it.
 *
 * Formations ([TankFormations]): a tank that has a place in a group of teammate tanks (a follower) holds its slot instead of
 * wandering in PATROL and INVESTIGATE, and in ENGAGE (turret still firing at its own aim; not a tank that [strafes], which
 * keeps moving). In a column a follower drives along the leader's trail ([TankFormations.carrotFor], [FormationFollow.follow]) rather
 * than routing to the slot; tanks of one group do not steer round each other until they are closer than their wanted distance. EVADE, RETREAT, SEARCH, FLANK and leaving water override it, so does a line of fire blocked by a teammate
 * for too long, and a follower that is too far from its leader acts on its own. The leader drives as usual, but waits in
 * PATROL for a follower that strung out. On the first contact the whole group halts and fires for `formationHaltSeconds`
 * (a tank that [strafes] drives on meanwhile), then the formation breaks (no slots, no waiting) until the group has been
 * out of contact for a while ([TankFormations.Phase]).
 *
 * Team call ([TankAlerts]): a tank that is under attack files the position of the enemy every few ticks ([TeamRally.share]). Every
 * tank that lists it as a teammate adopts that enemy as its target (even from beyond its own aggro range), shells the
 * reported spot, and while it sees no target of its own drives to a spot beside the attacked tank, facing the enemy, so the
 * teammates end up close together ([TeamRally.tick]). A hit sends the call out at once, and when the attacked tank shoots
 * back (below) the call carries that exact spot: the teammates shoot at the same place ([ReturnFire.join]).
 *
 * Return fire: hit by something it cannot see (a sniper beyond sight), the tank shoots at an estimated spot near the
 * attacker for `returnFireSeconds`, with scatter, and the turret swings round toward it ([ReturnFire]). With
 * `limitedAmmo` a patrolling tank that is partly spent rolls to go and rearm after `idleResupplySeconds` ([idleResupply]).
 *
 * Guided missiles: the shared [ThreatMonitor] notices them and pops smoke; [ShootHomingMissiles] turns the machine guns on the
 * same missile (`shootDownMissiles`, with `shootDownFailPercent` percent of the attempts failing).
 *
 * Smoke is a command to Superb Warfare's own dispenser (`decoyInputDown`): eight smoke grenades in an arc around the
 * turret's direction, so it is popped while the turret faces the threat. It is rate-limited by [SmokeLauncher] and,
 * with `limitedAmmo`, counted by the vehicle's own decoy count (`smokeCharges`) with its automatic reload blocked.
 *
 * All state is per instance (no statics), so several tanks can drive at once.
 */
abstract class TankBossBrain(
    protected val boss: TankBossEntity,
    protected val settings: TankSettings,
    private val profile: GroundProfile,
    private val pivots: Boolean,
    protected val gunnery: TankGunnery
) {

    /** True for a tank that peeks from cover for one cannon round at a time, or else shoots and scoots (see [PeekAndShoot], [SnipePosition]). */
    protected open val snipes: Boolean get() = false

    /** True for a tank that circles its target on the engage ring and sometimes parks hull-down (see [StrafeManeuver]). */
    protected open val strafes: Boolean get() = false

    /** How far (blocks) the weapon-station gun looks for its own soft target. */
    protected open val stationRange: Double get() = STATION_RANGE

    private val facing = if (pivots) TankFacing.TRACKED else TankFacing.WHEELED
    private val driver = GroundDriver(boss, pivots, profile)
    private var plannerSwims = canSwim()
    private var planner = GroundRoutePlanner(routeProfile(), boss.thought)

    /** Used only while driving off a perch: it may step down ([DESCENT_DROP] blocks), so normal routing never drives off a cliff. */
    private var descentPlanner = GroundRoutePlanner(routeProfile().withDrop(DESCENT_DROP), boss.thought)
    private val moves = Moves()
    private val elevation = ElevationDetection(boss, settings, gunnery, moves, ::routeProfile)
    private val threats = ThreatMonitor(boss, MISSILE_SMOKE_BURSTS)
    private val targeting = TankTargeting(boss, settings) { entity, margin -> gunnery.canReach(entity, margin) }
    private val smoke = SmokeLauncher(boss, settings)
    private val shootDown = ShootHomingMissiles(boss, settings, gunnery, threats)
    private val crowd = CrowdSeparation(boss, profile, driver, moves)
    private val cover = CoverAwareness(boss, settings, gunnery, ::routeProfile, ::crowded, elevation::isPerch)
    private val pullBack = PullBackRetreat(boss, settings, gunnery, targeting, cover, driver, facing, elevation::isPerch, PullBackHooks())

    private val returnFire = ReturnFire(boss, settings, gunnery, targeting, { withdrawn }) {
        debug("hit by something unseen, shooting back at {}", it)
    }

    // The behaviours of the states, one class each; the brain only decides which one runs.
    private val strafe = StrafeManeuver(boss, settings, driver, cover, moves, facing, elevation::isPerch)
    private val snipe = SnipePosition(boss, settings, gunnery, driver, moves, ::routeProfile, elevation::isPerch)
    private val peek = PeekAndShoot(boss, settings, gunnery, driver, cover, moves) { snipe.clear() }
    private val flank = FlankManeuver(boss, settings, driver, moves, smoke, elevation::isPerch) { enter(TankState.FLANK, why = "flanking") }
    private val follow = FormationFollow(boss, profile, driver, moves, facing)
    private val rally = TeamRally(boss, settings, targeting, returnFire, driver, moves, facing) { withdrawn }
    private val search = SearchPattern(boss, driver, moves)
    private val smokeScout = SmokeScout(boss, settings)
    private val retreat = DepotRetreat(boss, settings, driver, profile, moves)
    private val breach = WallBreach(boss, settings, gunnery, targeting, driver, moves, ::routeProfile)

    var state: TankState = TankState.PATROL
        private set
    private var stateTicks = 0

    /** What the guns aim at right now (see [TankTargeting.aim]); [TankFormations] points the formation at the leader's. */
    val currentAim: Entity?
        get() = targeting.aim

    /** True while the boss is away rearming, including an evade made on the way; the boss bar is hidden meanwhile. */
    val withdrawn: Boolean
        get() = state == TankState.RETREAT || (state == TankState.EVADE && resumeState == TankState.RETREAT)

    // --- patrol / investigate ---
    private var patrolGoal: Vec3? = null
    private var patrolGoalSince = 0
    private var patrolWaitUntil = 0
    private var investigateGoal: Vec3? = null
    private var lastHeardTick = Long.MIN_VALUE

    /** The place of the shot just heard and the tick until which the turret looks at it (see [lookAtHeard]). */
    private var heardSpot: Vec3? = null
    private var heardLookUntil = 0

    /** When the last noise or smoke spot was taken up; holds the next one back (see [mayRedirect]). */
    private val investigateCommit = DecisionCommitment(settings)

    // --- engage ---
    private var blockedTicks = 0
    private var advancing = false

    /** The committed engage move (see [decideEngageMove]); null at the start of a fight. */
    private var engageMove: EngageMove? = null
    private val engageCommit = DecisionCommitment(settings)

    /** The id of the aim the committed move was decided for. */
    private var engageAimId = -1

    /** Set by the body of a move that cannot go on: the next tick decides at once. */
    private var engageRedecide = false
    private var nextCloseCheck = 0

    // --- elevation (see [ElevationDetection]) ---
    /** Whether the gun can be laid on the aim this tick (always OK outside a fight in sight), and since when it could not. */
    private var layStatus = LayStatus.OK
    private var layBadSince = -1

    private var descending = false
    private var descentSince = 0
    private var descentProgressAt = 0
    private var descentBest = Double.MAX_VALUE
    private var descentLowest = Double.MAX_VALUE
    private var descentCooldownUntil = 0

    /** Ticks at which the main planner got nowhere (no route, the end of a partial route, a block): a loop on a perch shows here. */
    private val routeTroubles = ArrayDeque<Int>()

    // --- evade ---
    private var resumeState = TankState.PATROL
    private var resumeTicks = 0
    private var evadeSide = 1.0
    private var evadeDuration = 30
    private var evadeBearing: Vec3? = null

    // --- reactions ---
    private var lastHitHandled = -1

    // --- idle resupply (see [idleResupply]) ---
    private var idleTicks = 0
    private var nextIdleRoll = 0

    // --- formation ---
    /** The group's phase in a fight, read each tick (see [TankFormations.Phase]). */
    private var formationPhase = TankFormations.Phase.NONE

    /** The slot to hold behind the leader this tick, or null (a leader, a tank in no group, or one released from it). */
    private var formationSlot: Vec3? = null
    private var formationBreakUntil = 0
    private var leaderWaited = 0

    /** Tick before which a block reported by the driver does not start another search (see [learnFromDriver]). */
    private var nextObstacleReroute = 0

    // --- misc ---
    /** Tick at which the aim last turned into a ghost (the target went out of sight), or -1 while the aim is seen or absent. */
    private var hiddenSince = -1
    private var wading = false
    private var wadingCheckTick = 0

    /** Where the turret should look when there is no target, set by the state handlers each tick. */
    private var lookAt: Vec3? = null

    // ================================================================================================== main loop

    fun tick() {
        val level = boss.level() as? ServerLevel ?: return
        val now = boss.tickCount

        if (boss.crippled) {
            if (state != TankState.CRIPPLED) enterCrippled()
            boss.spottedId = -1
            TankAlerts.clear(boss)
            driver.release()
            return
        }

        stateTicks++
        lookAt = null
        // The slot is asked for even when it will not be used: a leader lays its trail of breadcrumbs through this call.
        val slot = TankFormations.slotFor(boss)
        val phase = TankFormations.phaseFor(boss)
        if (phase != formationPhase) boss.thought.event("formation") { "$formationPhase -> $phase" }
        formationPhase = phase
        formationSlot = if (follow.gaveUp(now) || phase == TankFormations.Phase.BROKEN) null else slot
        refreshPlanner()
        planner.tick(level)
        learnFromDriver(level)

        rally.poll(level, now)
        targeting.update(level)
        val aim = targeting.aim
        val hidden = targeting.hidden
        if (!hidden) hiddenSince = -1 else if (hiddenSince < 0) hiddenSince = now
        boss.spottedId = if (boss.isWreck || withdrawn) -1 else targeting.seenRootId()
        boss.thought.heartbeat { snapshot(aim, hidden) }
        updateLay(aim, hidden, now)

        listen(level, aim)
        scoutSmoke(level, aim)
        if (threats.tick()) enterEvade(null, "guided missile inbound")
        // Before reactToHits: a pull-back claims the smoke salvo and the state, so no flank is rolled on the same hit.
        considerPullBack(level, aim, hidden)
        reactToHits(level, aim)
        rally.share(level, aim, now) { lastAttacker(level) }
        if (retreat.withdrawIfStuck(aim, hidden, state == TankState.RETREAT)) return

        if (state != TankState.RETREAT && state != TankState.EVADE && gunnery.needsResupply()) enter(TankState.RETREAT, why = "out of ammo")
        idleResupply(aim, now)

        followAim(aim, hidden)
        if ((state == TankState.ENGAGE || state == TankState.FLANK) && aim != null && !hidden) TankFormations.reportContact(boss)

        if (mustLeaveWater(level, now)) {
            driver.exitWater()
        } else if (mustDescend(level, aim, now)) {
            tickDescent(level, now)
        } else if (!crowd.separate(level, separationAllowed())) {
            if (formationPhase == TankFormations.Phase.HALT && holdsWhenHalted()) {
                // The group met the enemy: every member stands still while the guns work (EVADE and RETREAT carry on).
                boss.thought.think("formationHalt") { "formation halted: standing still while the guns work (state $state)" }
                driver.hold()
            } else {
                when (state) {
                    TankState.PATROL -> tickPatrol(level)
                    TankState.INVESTIGATE -> tickInvestigate(level)
                    TankState.SEARCH -> tickSearch(level)
                    TankState.ENGAGE -> if (aim != null) tickEngage(level, aim)
                    TankState.FLANK -> if (aim != null && flank.tick(level, aim)) enter(TankState.ENGAGE, why = "flank done")
                    TankState.EVADE -> tickEvade(level)
                    TankState.PULL_BACK -> tickPullBack(level)
                    TankState.RETREAT -> retreat.tick(level, aim, stateTicks)
                    TankState.CRIPPLED -> {}
                }
            }
        }

        // A retreat can end in the boss being removed (withdrawn) this very tick.
        if (boss.isRemoved) return

        val secondary = if (gunnery.hasStation) targeting.secondaryTarget(level, stationRange) else null
        // Hit by something unseen: the guns go to a ghost at the estimated spot (firing at it on purpose, see TankGunnery).
        val shootBack = returnFire.aim(aim, hidden, now)
        // Shooting a hole through a wall (see [WallBreach]): the main gun alone, on the wall block.
        val wall = if (shootBack == null) breach.aim() else null
        // A shot just heard turns the turret toward it for a moment; return fire (the shooter that hit this tank) comes first.
        val heard = heardSpot
        if (heard != null) {
            if (now < heardLookUntil) lookAt = heard else heardSpot = null
        }
        // Points the turret (and the sight cone with it) toward the shooter.
        returnFire.point?.let { lookAt = it }
        val idleLook = lookAt?.let { FlightMath.horizontal(it.subtract(boss.position()), FlightMath.dir(boss.yRot, 0f)) }
        if (shootDown.tick()) {
            // An incoming guided missile: the machine guns are on it (alongside the smoke), so the guns are not aimed elsewhere this tick.
        } else if (shootBack != null) {
            gunnery.tick(shootBack, Vec3.ZERO, hidden = true, mayFire = true, secondary = secondary, idleLook = idleLook, suppressing = true)
        } else if (wall != null) {
            gunnery.tick(
                wall, Vec3.ZERO, hidden = true, mayFire = breach.mayFire(), secondary = null, idleLook = idleLook,
                suppressing = true, cannonOnly = true
            )
        } else {
            gunnery.tick(
                aim, targeting.aimVelocity, hidden, mayFire(hidden, now), secondary, idleLook, holdCannon = holdsCannon()
            )
        }
        threats.applyFlareInput()
        smoke.holdReload()
    }

    /** Hands the guns' aim to the vehicle; the entity calls this right after `baseTick` (see [TankGunnery.applyAim]). */
    fun applyAim() {
        if (boss.crippled) return
        gunnery.applyAim()
    }

    /**
     * The states that stand still while their formation is halted; an evade or a retreat is never held, and neither is a
     * fight of a tank that [strafes]: it keeps rolling and firing while the rest of the group stands.
     */
    private fun holdsWhenHalted(): Boolean = when (state) {
        TankState.PATROL, TankState.INVESTIGATE, TankState.SEARCH -> true
        TankState.ENGAGE, TankState.FLANK -> !strafes
        else -> false
    }

    /** One line of everything worth knowing about the tank right now, for the heartbeat of [com.teamuhi.superbenemies.debug.AiThought]. */
    private fun snapshot(aim: Entity?, hidden: Boolean): String {
        val distance = aim?.let { horizontalDistance(boss.position(), it.position()).roundToInt().toString() } ?: "-"
        return "state=$state ticks=$stateTicks aim=${aim?.name?.string ?: "none"} hidden=${if (hidden) "y" else "n"} " +
                "dist=$distance hp=${(boss.healthFraction() * 100f).roundToInt()}% ammo=[${gunnery.ammoSummary()}] " +
                "formation=$formationPhase pos=${boss.x.roundToInt()},${boss.y.roundToInt()},${boss.z.roundToInt()}"
    }

    /**
     * With `limitedAmmo` and `idleResupplySeconds` above 0: a tank that has patrolled with no target for that long and is
     * short of something (a gun below its full load, or smoke, see [TankGunnery.anySpent]) rolls `idleResupplyChance`
     * percent every [IDLE_ROLL_INTERVAL] ticks to go and rearm, the same drive away and withdraw as an empty gun.
     */
    private fun idleResupply(aim: Entity?, now: Int) {
        if (state != TankState.PATROL || aim != null || boss.isWreck) {
            idleTicks = 0
            return
        }
        idleTicks++
        val wait = settings.idleResupplySeconds.get() * 20
        if (!settings.limitedAmmo.get() || wait <= 0 || idleTicks < wait || now < nextIdleRoll) return
        nextIdleRoll = now + IDLE_ROLL_INTERVAL
        if (!gunnery.anySpent()) {
            boss.thought.think("idleResupply") { "idle, but nothing is spent: staying on patrol" }
            return
        }
        if (random().nextInt(100) >= settings.idleResupplyChance.get()) {
            boss.thought.think("idleResupply") { "idle with a spent load, rolled to stay on patrol" }
            return
        }
        enter(TankState.RETREAT, why = "idle with spent load")
    }

    private fun mayFire(hidden: Boolean, now: Int): Boolean {
        // A ghost aim is only a last seen spot: whatever the state (a flank or an evade can run for seconds with the target
        // out of sight), it is shelled for SUPPRESS_TICKS after the target dropped out of sight and not a tick longer.
        if (hidden && now - hiddenSince >= SUPPRESS_TICKS) {
            boss.thought.think("mayFire") { "holding fire: target out of sight too long" }
            return false
        }
        val fire = when (state) {
            TankState.ENGAGE, TankState.FLANK, TankState.PULL_BACK -> true
            TankState.EVADE -> resumeState != TankState.RETREAT
            // Suppressing fire on the last seen spot, then it is a hunt on the move.
            TankState.SEARCH -> hidden && search.suppressing(now, SUPPRESS_TICKS)
            else -> false
        }
        boss.thought.think("mayFire") { if (fire) "free to fire in $state" else "holding fire: nothing to shoot in $state" }
        return fire
    }

    /**
     * True while the main gun must keep quiet: a heavy tank behind its hide spot has nothing to shoot at but the wall (an
     * armour-piercing round would be asked to pierce it), and one on its way there with the line blocked likewise. A tank
     * rocking back out of sight at a hull-down spot holds it too. The machine guns are not held.
     */
    private fun holdsCannon(): Boolean {
        if (state != TankState.ENGAGE) {
            boss.thought.think("holdsCannon") { "cannon free" }
            return false
        }
        val peeking = peek.holdsCannon(gunnery.lineBlocked)
        val rocking = !peeking && strafe.backingOut && gunnery.lineBlocked
        boss.thought.think("holdsCannon") {
            when {
                peeking -> "holding cannon: waiting behind cover"
                rocking -> "holding cannon: line blocked while backing out of hull-down"
                else -> "cannon free"
            }
        }
        return peeking || rocking
    }

    /**
     * True while the tank hides from its target on purpose (the hide leg and the wait of the peek cycle, the back half of a
     * hull-down rock), so the target being out of sight is no reason to give up the fight for a search. Only for as long as
     * a target can be out of sight before it is lost for good (`breakDetectionSeconds`, less a second).
     */
    private fun hidingOnPurpose(now: Int): Boolean {
        if (!settings.useCover.get()) return false
        if (!peek.hiding && !strafe.rocking) return false
        return hiddenSince < 0 || now - hiddenSince < peek.hideCapTicks()
    }

    // ================================================================================================== persistence

    fun save(tag: CompoundTag) {
        gunnery.saveAmmo(tag)
        targeting.save(tag)
        // A reloaded evade or pull-back resumes what it interrupted; routes and noises are not saved, the rest is derived again.
        val saved = if (state == TankState.EVADE || state == TankState.PULL_BACK) resumeState else state
        tag.putString(TAG_STATE, saved.name)
        if (saved == TankState.SEARCH) search.save(tag)
    }

    fun load(tag: CompoundTag) {
        gunnery.loadAmmo(tag)
        targeting.load(tag)
        var loaded = TankState.entries.firstOrNull { it.name == tag.getString(TAG_STATE) } ?: TankState.PATROL
        if (loaded == TankState.EVADE || loaded == TankState.PULL_BACK || loaded == TankState.INVESTIGATE) loaded = TankState.PATROL
        if (loaded == TankState.FLANK) loaded = TankState.ENGAGE
        if (loaded == TankState.SEARCH && !search.load(tag)) loaded = TankState.PATROL
        state = loaded
        stateTicks = 0
        boss.thought.event("load") { "restored state $loaded (saved ${tag.getString(TAG_STATE)})" }
    }

    // ================================================================================================== helpers

    private fun random() = boss.getRandom()

    private fun debug(message: String, vararg args: Any?) {
        boss.thought.debug(message, *args)
    }

    private fun horizontalDistance(a: Vec3, b: Vec3): Double = FlightMath.horizontalDistance(a, b)

    /** Whether the hull may swim: it floats and `crossWater` (LAV-25 only) is on. */
    private fun canSwim(): Boolean = profile.floats && (settings.crossWater?.get() ?: true)

    /** The profile the planner routes with: a floating hull that must not swim routes like a sinking one. */
    fun routeProfile(): GroundProfile =
        if (canSwim() == profile.floats) profile
        else GroundProfile(
            profile.upStep, canSwim(), profile.crushesFoliage, profile.maxSlope, profile.cellSize, profile.halfWidth, profile.halfLength,
            profile.maxDrop
        )

    /** A live change of `crossWater` rebuilds the planner. */
    private fun refreshPlanner() {
        val swims = canSwim()
        if (swims == plannerSwims) return
        plannerSwims = swims
        planner = GroundRoutePlanner(routeProfile(), boss.thought)
        descentPlanner = GroundRoutePlanner(routeProfile().withDrop(DESCENT_DROP), boss.thought)
    }

    private fun passable(level: Level, point: Vec3): Boolean = GroundTerrain.passable(level, point, routeProfile())

    /** True when another tank boss stands within its separation distance of [point], or is heading for it (see [CrowdSeparation.crowded]). */
    private fun crowded(level: Level, point: Vec3): Boolean = crowd.crowded(level, point)

    /** How far this hull reaches from its centre along the horizontal unit vector ([dx], [dz]); other tanks ask (see [CrowdSeparation.hullReach]). */
    fun hullReach(dx: Double, dz: Double): Double = crowd.hullReach(dx, dz)

    /** The longest way this hull reaches from its centre (over the nose or tail). */
    fun longestReach(): Double = crowd.longestReach()

    /** The states that give way to a tank crowding them; EVADE, RETREAT, FLANK and PULL_BACK do so only when the hulls touch. */
    private fun separationAllowed(): Boolean =
        state == TankState.PATROL || state == TankState.INVESTIGATE || state == TankState.SEARCH || state == TankState.ENGAGE

    /** Whether the ground behind the hull (a hull length and a bit more, dry for a hull that sinks) can be reversed onto. */
    private fun rearClear(level: Level): Boolean {
        val yaw = Math.toRadians(boss.yRot.toDouble())
        val back = Vec3(Math.sin(yaw), 0.0, -Math.cos(yaw))
        val position = boss.position()
        return REAR_PROBES.all { passable(level, position.add(back.scale(it))) }
    }

    /**
     * Drives toward [goal] along a planned route. Holds still while the first route is being searched and after
     * arriving.
     *
     * With [rollOn] the tank does not brake for the end of the route (the caller picks its next goal before it gets there)
     * and, while the route to [goal] is still being searched, drives straight at it when its own ground is passable instead
     * of standing still. Strafing legs are driven this way.
     * @return true once the end of the route has been reached
     */
    private fun goTo(
        level: Level,
        goal: Vec3,
        mode: DriveMode = DriveMode.CRUISE,
        replanShift: Double = GroundRoutePlanner.REPLAN_GOAL_SHIFT,
        rollOn: Boolean = false
    ): Boolean {
        val position = boss.position()
        planner.request(level, position, goal, replanShift)
        planner.tick(level)
        // A long detour (or no route) round a thin wall: shoot a hole through it instead, unless there is a fight to see to.
        if (breach.tick(level, planner, allowed = targeting.aim == null || targeting.hidden)) {
            boss.thought.think("goTo") { "wall breach has the wheel" }
            lookAt = null
            return false
        }
        val waypoint = planner.nextWaypoint(position)
        if (waypoint == null) {
            if (rollOn && !planner.arrived && planner.status != GroundRoutePlanner.Status.NO_ROUTE && passable(level, goal)) {
                boss.thought.think("goTo") { "route not ready, rolling straight at the goal" }
                driver.drive(crowd.steerAround(goal, last = false), mode, stopAtEnd = false)
                lookAt = goal
                return false
            }
            if (planner.status == GroundRoutePlanner.Status.NO_ROUTE || (planner.arrived && planner.partial)) noteRouteTrouble(boss.tickCount)
            // A wheeled hull can only turn by creeping forward, which with no route just pushes it into obstacles: it waits instead.
            boss.thought.think("goTo") {
                when {
                    planner.arrived -> "arrived at the goal"
                    planner.status == GroundRoutePlanner.Status.NO_ROUTE -> if (pivots) "no route: turning toward the goal" else "no route: waiting"
                    else -> "waiting for the route search"
                }
            }
            if (planner.status == GroundRoutePlanner.Status.NO_ROUTE && pivots) driver.turnToward(goal) else driver.hold()
            return planner.arrived
        }
        val last = waypoint === planner.waypoints.lastOrNull()
        boss.thought.think("goTo") { "following the route in $mode" }
        driver.drive(crowd.steerAround(waypoint, last), mode, stopAtEnd = last && !rollOn)
        lookAt = waypoint
        return false
    }

    private fun newGoal() = planner.invalidate()

    /**
     * What the driver learned about the way, passed on to the route planner so the next route knows it: a block the swerve
     * could not get round is marked as blocked and the route searched again (the old one is followed meanwhile); a stuck
     * recovery that began while driving forward marks the cell just ahead of the nose and throws the route away.
     */
    private fun learnFromDriver(level: ServerLevel) {
        // While the tank drives off a perch the descent planner is the one in charge.
        val active = if (descending) descentPlanner else planner
        driver.consumeObstacle()?.let {
            active.markBlocked(level, it.x, it.z)
            // The driver reports every few ticks while it is boxed in: a search needs a few ticks to finish, so not every report restarts it.
            if (boss.tickCount >= nextObstacleReroute) {
                nextObstacleReroute = boss.tickCount + OBSTACLE_REROUTE_TICKS
                active.reroute(level, boss.position())
                if (!descending) noteRouteTrouble(boss.tickCount)
                boss.thought.event("learnFromDriver") { "driver blocked ahead at ${it.x.roundToInt()},${it.z.roundToInt()}: planning round it" }
            }
        }
        if (driver.consumeReplanRequest()) {
            boss.thought.event("learnFromDriver") {
                "driver asks for a new route" + if (driver.stuckDrivingForward) " (stuck driving forward, marking the cell ahead)" else ""
            }
            if (driver.stuckDrivingForward) {
                val forward = FlightMath.dir(boss.yRot, 0f)
                val reach = profile.halfLength + STUCK_MARK_AHEAD
                active.markBlocked(level, boss.x + forward.x * reach, boss.z + forward.z * reach)
            }
            if (!descending) noteRouteTrouble(boss.tickCount)
            active.invalidate()
        }
    }

    private fun towards(point: Vec3): Vec3 =
        FlightMath.horizontal(Vec3(point.x - boss.x, 0.0, point.z - boss.z), FlightMath.dir(boss.yRot, 0f))

    // ================================================================================================== transitions

    /**
     * Switches to [next].
     *
     * A soft transition (see [isSoftTransition]: PATROL, INVESTIGATE and SEARCH among themselves, and ENGAGE to SEARCH) is deferred
     * while the tank has been in its state for less than `decisionCommitSeconds`, unless it is [urgent]; the caller finds out
     * from the result and keeps doing what it did. Every other transition (a target spotted, EVADE, PULL_BACK, RETREAT, CRIPPLED)
     * is never held.
     * @param resume true when returning from an evade: keeps the per-state progress
     * @param urgent true when seeing or losing the target is the reason, so the dwell does not apply
     * @return false when the switch was deferred; true when it happened (or the tank is in [next] already)
     */
    private fun enter(next: TankState, resume: Boolean = false, why: String = "", urgent: Boolean = false): Boolean {
        if (next == state && !resume) return true
        if (!resume && !urgent && isSoftTransition(state, next) && stateTicks < engageCommit.ticks) {
            boss.thought.think("decide.dwell") { "$state -> $next deferred: the plan is held for a moment" }
            return false
        }
        boss.thought.event("state") { "$state -> $next" + if (why.isNotEmpty()) " ($why)" else "" }
        state = next
        stateTicks = 0
        if (resume) return true
        breach.cancel()
        newGoal()
        driver.resetStuck()

        when (next) {
            TankState.PATROL -> {
                patrolGoal = null
                patrolWaitUntil = 0
            }

            TankState.INVESTIGATE -> {}
            TankState.ENGAGE -> {
                blockedTicks = 0
                advancing = false
                snipe.clear()
                peek.drop()
                strafe.reset()
                engageMove = null
                engageCommit.reset()
                engageAimId = -1
                engageRedecide = false
                layStatus = LayStatus.OK
                layBadSince = -1
            }
            TankState.FLANK -> peek.drop()
            TankState.RETREAT -> {
                retreat.reset()
                boss.announce(boss.messageKey("withdraw"), ResupplyNotices.WITHDRAW_STYLE)
            }

            else -> {}
        }
        return true
    }

    /** The states a tank drifts between without anything having changed (see [enter]). */
    private fun isSoftTransition(from: TankState, to: TankState): Boolean {
        fun calm(s: TankState) = s == TankState.PATROL || s == TankState.INVESTIGATE || s == TankState.SEARCH
        return (calm(from) && calm(to)) || (from == TankState.ENGAGE && to == TankState.SEARCH)
    }

    /** Aim-driven transitions: seeing something (or being told where it is) leads to ENGAGE or SEARCH, losing it back. */
    private fun followAim(aim: Entity?, hidden: Boolean) {
        val duration = settings.searchSeconds.get() * 20
        when (state) {
            // A target seen (or told of) is never held back by the dwell time of the state.
            TankState.PATROL, TankState.INVESTIGATE -> if (aim != null) {
                if (hidden) enterSearch(aim.position(), duration, forTarget = true, why = "target hidden, searching", urgent = true)
                else enter(TankState.ENGAGE, why = "target spotted: ${aim.name.string}")
            }

            TankState.SEARCH -> {
                if (aim != null && !hidden) enter(TankState.ENGAGE, why = "target spotted again: ${aim.name.string}")
                else if (aim == null && search.forTarget) enter(TankState.PATROL, why = "target lost", urgent = true)
            }

            TankState.ENGAGE -> when {
                aim == null -> enter(TankState.PATROL, why = "target lost")
                // Hiding behind cover on purpose (the peek cycle, a hull-down rock) is not losing the target.
                hidden -> if (hidingOnPurpose(boss.tickCount)) {
                    boss.thought.think("followAim") { "target out of sight, but hiding on purpose" }
                } else {
                    // Soft: held back until the tank has kept to the fight for a moment (see enter).
                    enterSearch(aim.position(), duration, forTarget = true, why = "target hidden, searching")
                }
            }

            // A flank is run to its end even if the target vanishes into the smoke screen cast to cover it.
            TankState.FLANK -> if (aim == null) enter(TankState.PATROL, why = "target lost during flank")

            else -> {}
        }
    }

    private fun enterSearch(center: Vec3, ticks: Int, forTarget: Boolean, why: String = "", urgent: Boolean = false) {
        if (enter(TankState.SEARCH, why = why, urgent = urgent)) search.start(center, ticks, forTarget)
    }

    private fun enterEvade(bearing: Vec3?, why: String = "") {
        if (state == TankState.CRIPPLED) return
        if (state == TankState.EVADE) {
            evadeDuration = max(evadeDuration, stateTicks + 20) // a second threat extends the break
            boss.thought.event("enterEvade") { "second threat while evading, break extended ($why)" }
            return
        }
        // A pull-back is cut short; the state it interrupted is still the one to resume.
        if (state == TankState.PULL_BACK) {
            pullBack.end()
        } else {
            resumeState = state
            resumeTicks = stateTicks
        }
        evadeSide = if (random().nextBoolean()) 1.0 else -1.0
        evadeDuration = boss.randomBetween(EVADE_MIN, EVADE_MAX)
        evadeBearing = bearing
        state = TankState.EVADE
        stateTicks = 0
        peek.drop()
        breach.cancel()
        newGoal()
        boss.thought.event("state") { "$resumeState -> EVADE" + if (why.isNotEmpty()) " ($why)" else "" }
    }

    private fun enterCrippled() {
        boss.thought.event("state") { "$state -> CRIPPLED (wreck)" }
        state = TankState.CRIPPLED
        stateTicks = 0
        driver.release()
    }

    // ================================================================================================== hearing

    /**
     * DYNAMIC only: while nothing is known about a target, every [LISTEN_INTERVAL] ticks the boss asks [NoiseMap] for the
     * loudest fresh noise it can hear. PATROL and INVESTIGATE turn toward it; a SEARCH moves its centre there (the noise
     * may well be the hunted target). Each noise is heard once.
     *
     * The fire of a vehicle's guns (a cannon, a machine gun) is pinpointed: the shooter is placed within
     * `hearingScatterPercent` of the distance, the turret swings round to it and, when the shooter is a valid target for this
     * tank, it becomes the target at that spot ([TankTargeting.inform]), so the tank shells the place and hunts the shooter
     * like one that dropped out of sight. How far each kind is heard is `cannonHearingPercent` and `machineGunHearingPercent`.
     */
    private fun listen(level: ServerLevel, aim: Entity?) {
        if (settings.targetingMethod.get() != TargetingMethod.DYNAMIC) {
            boss.thought.think("listen") { "not listening: targeting method is not DYNAMIC" }
            return
        }
        if (state != TankState.PATROL && state != TankState.INVESTIGATE && state != TankState.SEARCH) {
            boss.thought.think("listen") { "not listening: busy in $state" }
            return
        }
        if (aim != null && !targeting.hidden) {
            boss.thought.think("listen") { "not listening: target in sight" }
            return
        }
        // A follower keeps its slot: its leader (or its own sight) does the looking.
        if (formationSlot != null && state != TankState.SEARCH) {
            boss.thought.think("listen") { "not listening: the leader does the looking" }
            return
        }
        boss.thought.think("listen") { "listening for noises" }
        if ((boss.tickCount + boss.id) % LISTEN_INTERVAL != 0) return
        val noise = NoiseMap.heard(boss, settings, lastHeardTick + 1) ?: return

        var spot = noise.pos
        if (noise.kind != NoiseKind.OTHER) {
            spot = NoiseMap.pinpoint(
                level, noise, horizontalDistance(boss.position(), noise.pos), settings.hearingScatterPercent.get(), random()
            )
            val shooter = level.getEntity(noise.rootId)?.takeIf { it.isAlive && !it.isRemoved }
            // A pinpointed shooter is urgent: it never waits for the commitment.
            if (shooter != null && targeting.inform(shooter, spot, PINPOINT_HOLD_TICKS)) {
                lastHeardTick = noise.tick
                boss.thought.event("listen") { "pinpointed ${noise.kind} fire of ${shooter.name.string} at ${spot.x.roundToInt()},${spot.z.roundToInt()}" }
                lookAtHeard(spot)
                // Already searching: the search moves to the shooter (a tank that is not takes it up as a hidden target next tick).
                if (state == TankState.SEARCH) {
                    search.hear(spot, settings.searchSeconds.get() * 10)
                    investigateCommit.commit(boss.tickCount)
                }
                return
            }
        }
        // Any other noise waits (it is not used up, so it is heard again) until the last spot has been held long enough.
        if (!mayRedirect(boss.tickCount)) {
            boss.thought.think("decide.investigate") { "heard a noise, but holding the current plan for a moment" }
            return
        }
        lastHeardTick = noise.tick
        boss.thought.event("listen") { "heard a noise (${noise.kind}) at ${spot.x.roundToInt()},${spot.z.roundToInt()}" }
        lookAtHeard(spot)
        investigate(spot)
    }

    /**
     * Whether a new spot to look at may be taken up now (`decisionCommitSeconds`): a tank in INVESTIGATE or SEARCH holds the last
     * one for the commitment, and a patrolling one has to have been on patrol that long (the dwell of [enter]). A noise or smoke
     * that has to wait is not used up.
     */
    private fun mayRedirect(now: Int): Boolean = when (state) {
        TankState.INVESTIGATE, TankState.SEARCH -> investigateCommit.ready(now)
        else -> stateTicks >= investigateCommit.ticks
    }

    /** Turns the turret toward a noise just heard for a moment, whatever else the tank is doing (return fire has the say over it). */
    private fun lookAtHeard(spot: Vec3) {
        heardSpot = spot
        heardLookUntil = boss.tickCount + HEARD_LOOK_TICKS
    }

    /** A noise or a smoke cloud leads to [spot]: a SEARCH moves its centre there, anything else drives over to look. */
    private fun investigate(spot: Vec3) {
        if (state == TankState.SEARCH) {
            boss.thought.event("investigate") { "search centre moved to the new spot" }
            search.hear(spot, settings.searchSeconds.get() * 10)
            investigateCommit.commit(boss.tickCount)
            return
        }
        if (!enter(TankState.INVESTIGATE, why = "going to look at a noise or smoke")) return
        investigateGoal = spot
        investigateCommit.commit(boss.tickCount)
    }

    /**
     * DYNAMIC only: smoke tells that a tank is about. A tank with nothing to do (no target, not in a formation, not answering a
     * teammate's call, patrolling or finishing a search that was no hunt) goes to scout the area of a fresh smoke cloud it can
     * see within `smokeScoutRange` ([SmokeScout]), the way it goes to a noise.
     */
    private fun scoutSmoke(level: ServerLevel, aim: Entity?) {
        if (settings.targetingMethod.get() != TargetingMethod.DYNAMIC || settings.smokeScoutRange.get() <= 0) {
            boss.thought.think("scoutSmoke") { "smoke scouting is off" }
            return
        }
        if (aim != null) {
            boss.thought.think("scoutSmoke") { "not scouting smoke: has a target" }
            return
        }
        if (state != TankState.PATROL && !(state == TankState.SEARCH && !search.forTarget)) {
            boss.thought.think("scoutSmoke") { "not scouting smoke: busy in $state" }
            return
        }
        if (formationSlot != null || rally.destination() != null) {
            boss.thought.think("scoutSmoke") { "not scouting smoke: in a formation or answering a call" }
            return
        }
        boss.thought.think("scoutSmoke") { "watching for smoke" }
        if ((boss.tickCount + boss.id) % SMOKE_SCAN_INTERVAL != 0) return
        // A cloud is scouted once and then used up, so none is looked at while the plan is held.
        if (!mayRedirect(boss.tickCount)) {
            boss.thought.think("decide.investigate") { "smoke about, but holding the current plan for a moment" }
            return
        }
        val cloud = smokeScout.nextCloud(level) ?: return
        boss.thought.event("scoutSmoke") { "smoke at ${cloud.x.roundToInt()},${cloud.z.roundToInt()}, going to scout it" }
        investigate(cloud)
    }

    // ================================================================================================== patrol

    private fun tickPatrol(level: Level) {
        val now = boss.tickCount
        formationSlot?.let {
            // A goal from before the formation is stale by the time the tank is released from it.
            patrolGoal = null
            boss.thought.think("tickPatrol") { "holding its formation slot" }
            follow.follow(level, it)
            return
        }
        if (rally.tick(level)) {
            patrolGoal = null
            boss.thought.think("tickPatrol") { "answering a teammate's call" }
            return
        }
        val goal = patrolGoal
        if (goal == null) {
            boss.thought.think("tickPatrol") { "idle, waiting before the next patrol goal" }
            driver.hold()
            if (now >= patrolWaitUntil) {
                patrolGoal = pickPatrolGoal(level)
                patrolGoalSince = now
                leaderWaited = 0
                newGoal()
                boss.thought.event("tickPatrol") {
                    patrolGoal?.let { "new patrol goal at ${it.x.roundToInt()},${it.z.roundToInt()}" } ?: "no passable patrol goal found, waiting"
                }
            }
            return
        }
        // The leader of a formation lets a follower that strung out catch up, for a while per goal.
        if (leaderWaited < LEADER_WAIT_MAX && formationPhase != TankFormations.Phase.BROKEN && TankFormations.membersStrungOut(boss)) {
            leaderWaited++
            boss.thought.think("tickPatrol") { "leader waiting for a follower that strung out" }
            driver.hold()
            return
        }
        boss.thought.think("tickPatrol") { "driving to the patrol goal" }
        val arrived = goTo(level, goal)
        if (arrived || now - patrolGoalSince > PATROL_TIMEOUT || planner.status == GroundRoutePlanner.Status.NO_ROUTE) {
            boss.thought.event("tickPatrol") {
                when {
                    arrived -> "arrived at the patrol goal"
                    planner.status == GroundRoutePlanner.Status.NO_ROUTE -> "no route to the patrol goal, dropping it"
                    else -> "patrol goal timed out, dropping it"
                }
            }
            patrolGoal = null
            patrolWaitUntil = now + boss.randomBetween(PATROL_WAIT_MIN, PATROL_WAIT_MAX)
        }
    }

    /**
     * A random passable point 40-120 blocks away inside the leash; beyond 80% of the leash the boss heads back toward
     * home instead. A point on a perch (see [ElevationDetection.isPerch]) is never picked. The leader of a formation picks within [LEAD_CONE] degrees either side of the way it faces (the cone
     * widens in the later tries, and the last ones are free), so the column keeps going ahead instead of the leader
     * turning back through its own followers.
     */
    private fun pickPatrolGoal(level: Level): Vec3? {
        val home = boss.homePos
        val leash = settings.leashRange.get().toDouble()
        val position = boss.position()
        val returning = horizontalDistance(position, home) > leash * 0.8
        val leading = !returning && formationPhase != TankFormations.Phase.BROKEN && TankFormations.leadsGroup(boss)
        val forward = FlightMath.dir(boss.yRot, 0f)
        val facing = Math.atan2(forward.z, forward.x)
        val attempts = if (leading) PATROL_ATTEMPTS * 2 else PATROL_ATTEMPTS
        for (attempt in 0 until attempts) {
            val cone = when {
                !leading || attempt >= PATROL_ATTEMPTS * 3 / 2 -> Math.PI
                attempt >= PATROL_ATTEMPTS -> Math.toRadians(LEAD_CONE_WIDE)
                else -> Math.toRadians(LEAD_CONE)
            }
            val angle = if (cone >= Math.PI) random().nextDouble() * Math.PI * 2.0 else facing + (random().nextDouble() * 2.0 - 1.0) * cone
            val point = if (returning) {
                val radius = random().nextDouble() * leash * 0.3
                Vec3(home.x + cos(angle) * radius, position.y, home.z + sin(angle) * radius)
            } else {
                val radius = 40.0 + random().nextDouble() * 80.0
                Vec3(position.x + cos(angle) * radius, position.y, position.z + sin(angle) * radius)
            }
            if (horizontalDistance(point, home) <= leash && passable(level, point) && !crowded(level, point) && !elevation.isPerch(level, point)) return point
        }
        return if (returning) home else null
    }

    // ================================================================================================== investigate

    private fun tickInvestigate(level: Level) {
        formationSlot?.let {
            boss.thought.think("tickInvestigate") { "holding its formation slot" }
            follow.follow(level, it)
            if (stateTicks > INVESTIGATE_TIMEOUT) enter(TankState.PATROL, why = "investigation timed out")
            return
        }
        if (rally.tick(level)) {
            boss.thought.think("tickInvestigate") { "answering a teammate's call" }
            return
        }
        val goal = investigateGoal
        if (goal == null) {
            enter(TankState.PATROL, why = "nothing to investigate")
            return
        }
        boss.thought.think("tickInvestigate") { "driving to the spot to look" }
        val arrived = goTo(level, goal)
        val near = horizontalDistance(boss.position(), goal) < INVESTIGATE_RADIUS
        when {
            planner.status == GroundRoutePlanner.Status.NO_ROUTE && !near -> enter(TankState.PATROL, why = "no route to the spot")
            arrived || near || stateTicks > INVESTIGATE_TIMEOUT -> enterSearch(
                goal, settings.searchSeconds.get() * 10, forTarget = false,
                why = if (arrived || near) "reached the spot, searching" else "investigation timed out, searching"
            )
        }
    }

    // ================================================================================================== search

    /**
     * Drives the search ring ([SearchPattern]). Time up: the boss forgets the target and goes back to patrolling.
     */
    private fun tickSearch(level: Level) {
        if (search.expired(boss.tickCount)) {
            if (search.forTarget) targeting.giveUp()
            enter(TankState.PATROL, why = if (search.forTarget) "search time up, target lost" else "search time up")
            return
        }
        if (rally.tick(level)) {
            boss.thought.think("tickSearch") { "answering a teammate's call" }
            return
        }
        boss.thought.think("tickSearch") { if (search.forTarget) "sweeping for the lost target" else "sweeping the spot it was sent to" }
        search.tick(level)
    }

    // ================================================================================================== engage

    /**
     * One tick of ENGAGE: a follower may hold its slot, a flank may be rolled, and otherwise the tank runs the committed engage
     * move ([decideEngageMove], [runEngageMove]).
     */
    private fun tickEngage(level: Level, aim: Entity) {
        val now = boss.tickCount
        val position = boss.position()
        val target = aim.position()
        val distance = horizontalDistance(position, target)
        val range = settings.engageRange.get().toDouble()
        blockedTicks = if (gunnery.lineBlocked && !gunnery.holdingBarrel) blockedTicks + 1 else 0
        if (settings.useCover.get()) cover.update(level, aim, range)

        // The gun cannot be laid from here: a spot on the ring round the target from which it can (null when the gun can be laid, or none).
        val standOff = if (elevationNeeded(now)) elevation.standOffSpot(level, aim, layStatus) else null

        // A follower holds its slot and lets the turret do the fighting. It breaks formation for a while when its line of
        // fire stays blocked (by the leader in a column, say) so that it can reposition, and when the target is close
        // enough for the normal back-off. A tank that strafes never holds a slot: it keeps moving and firing.
        if (!strafes && standOff == null) formationSlot?.let { slot ->
            if (blockedTicks > EngageBand.BLOCKED_REPOSITION_TICKS && distance > EngageBand.REPOSITION_MIN_DISTANCE) {
                if (now >= formationBreakUntil) boss.thought.event("tickEngage") { "breaking formation: line of fire blocked too long" }
                formationBreakUntil = now + FormationFollow.BREAK_TICKS
            }
            if (now >= formationBreakUntil && distance >= range * EngageBand.BACK_OFF_FACTOR) {
                boss.thought.think("tickEngage") { "holding formation slot, ${aim.name.string} engaged by the turret" }
                if (engageMove != EngageMove.HOLD_SLOT) switchMove(EngageMove.HOLD_SLOT, aim, now, "holding the formation slot", commit = false)
                follow.follow(level, slot)
                return
            }
        }

        if (flank.roll(level, aim, distance, range)) {
            switchMove(EngageMove.FLANK, aim, now, "flank rolled", commit = false)
            return
        }

        // Close in from beyond ADVANCE_START and keep going until inside ADVANCE_STOP: a tank that stops and starts at one
        // distance would creep back and forth as the target shifts.
        if (distance > range * EngageBand.ADVANCE_START) advancing = true else if (distance < range * EngageBand.ADVANCE_STOP) advancing = false

        val move = decideEngageMove(level, aim, distance, range, now, standOff != null)
        runEngageMove(move, level, aim, distance, range, standOff)
    }

    /** True when the tank has a heavy cannon to snipe with (see [snipes]): it peeks or snipes instead of driving about. */
    private fun sniperStance(): Boolean = snipes && gunnery.weapons.any { it.kind == WeaponKind.CANNON && gunnery.enabled(it) }

    /** True when the line of fire has been blocked long enough, and the target is far enough, for the tank to reposition. */
    private fun repositionNeeded(distance: Double): Boolean =
        blockedTicks > EngageBand.BLOCKED_REPOSITION_TICKS && distance > EngageBand.REPOSITION_MIN_DISTANCE

    /** The two sniper moves are one stance: the tank peeks when it has cover and snipes when it has none, which its own cycle sorts out. */
    private fun sameMove(a: EngageMove, b: EngageMove): Boolean =
        a == b || (a.isSniper() && b.isSniper())

    private fun EngageMove.isSniper(): Boolean = this == EngageMove.PEEK || this == EngageMove.SNIPE

    /**
     * What the engage branch would pick right now, from scratch: the ordered list the tank used to run every tick (the gun cannot be
     * laid: stand-off; a sniper: peek / snipe; closing in; repositioning behind a blocked line; backing off or, with no room, circling
     * or turning; circling; turning to the target).
     */
    private fun desireMove(level: Level, distance: Double, range: Double, elevationActive: Boolean): EngageMove = when {
        elevationActive -> EngageMove.ELEVATION
        sniperStance() -> EngageMove.SNIPE
        advancing -> EngageMove.ADVANCE
        repositionNeeded(distance) -> EngageMove.REPOSITION
        distance < range * EngageBand.BACK_OFF_FACTOR ->
            if (rearClear(level)) EngageMove.BACK_OFF else if (strafes) EngageMove.CIRCLE else EngageMove.TURN_HOLD
        strafes -> EngageMove.CIRCLE
        else -> EngageMove.TURN_HOLD
    }

    /**
     * The engage move to run this tick. It is re-decided only every [DECISION_INTERVAL] ticks (staggered by entity id), and a
     * different move replaces the committed one only once `decisionCommitSeconds` have passed since it was chosen. These skip
     * the wait: a new aim, the gun needing a stand-off (or no longer needing one), a move whose body found it cannot go on, a move
     * that reached its end (closing in, repositioning), and a target inside the back-off distance when that is what is wanted.
     * With the option at 0 the tank decides afresh every tick. Each change is one `decide` event.
     */
    private fun decideEngageMove(level: Level, aim: Entity, distance: Double, range: Double, now: Int, elevationActive: Boolean): EngageMove {
        val held = engageMove?.takeIf { it != EngageMove.HOLD_SLOT && it != EngageMove.FLANK }
        val off = engageCommit.ticks <= 0
        val urgent: String? = when {
            held == null -> null
            aim.id != engageAimId -> "new target"
            elevationActive != (held == EngageMove.ELEVATION) ->
                if (elevationActive) "the gun cannot be laid from here" else "the gun can be laid again"
            engageRedecide -> "the move cannot go on"
            held == EngageMove.ADVANCE && !advancing -> "closed in"
            held == EngageMove.REPOSITION && !repositionNeeded(distance) -> "line of fire clear again"
            else -> null
        }
        val closeCheck = held != null && held != EngageMove.BACK_OFF && distance < range * EngageBand.BACK_OFF_FACTOR && now >= nextCloseCheck
        val due = held == null || off || urgent != null || closeCheck || (now + boss.id) % DECISION_INTERVAL == 0
        if (!due) return held!!
        if (closeCheck) nextCloseCheck = now + CLOSE_CHECK_TICKS
        engageRedecide = false

        val desired = desireMove(level, distance, range, elevationActive)
        if (held == null) {
            switchMove(desired, aim, now, "start of the engagement")
            return desired
        }
        if (sameMove(desired, held)) return held
        val tooClose = closeCheck && desired == EngageMove.BACK_OFF
        if (!(off || urgent != null || tooClose || engageCommit.ready(now))) {
            boss.thought.think("decide.hold") { "keeping $held: the decision is held for a moment" }
            return held
        }
        switchMove(desired, aim, now, urgent ?: if (tooClose) "target inside the back-off distance" else "the commitment is over")
        return desired
    }

    /** Takes [next] as the committed move: starts the commitment (unless [commit] is off), drops the sniper's spots when leaving that stance, logs it. */
    private fun switchMove(next: EngageMove, aim: Entity, now: Int, why: String, commit: Boolean = true) {
        val old = engageMove
        if (old != null && old.isSniper() && !next.isSniper()) {
            peek.drop()
            snipe.clear()
        }
        engageMove = next
        engageAimId = aim.id
        if (commit) engageCommit.commit(now)
        boss.thought.event("decide") { "${old ?: "none"} -> $next ($why)" }
    }

    /** Runs the body of [move] for this tick: the branches the tank used to pick between each tick, one per move. */
    private fun runEngageMove(move: EngageMove, level: Level, aim: Entity, distance: Double, range: Double, standOff: Vec3?) {
        val target = aim.position()
        when (move) {
            EngageMove.SNIPE, EngageMove.PEEK -> {
                if (!sniperStance()) {
                    engageRedecide = true
                    driver.hold()
                    return
                }
                // The line is meant to be blocked while hiding and while stepping out: it only counts once the tank is at the peek spot.
                if (peek.awayFromPeek()) blockedTicks = 0
                if (!peek.tick(level, aim, distance, range, blockedTicks)) {
                    engageMove = EngageMove.SNIPE
                    boss.thought.think("tickEngage") { "no cover to peek from, sniping from a firing spot at ${aim.name.string}" }
                    snipe.tick(level, aim, distance, range, blockedTicks)
                } else {
                    engageMove = EngageMove.PEEK
                    boss.thought.think("tickEngage") { "peeking from cover at ${aim.name.string}" }
                }
            }

            EngageMove.ADVANCE, EngageMove.REPOSITION -> {
                val mode = if (distance > range * 2.5 && !boss.recentlyDamaged(EngageBand.SPRINT_HIT_WINDOW)) DriveMode.SPRINT else DriveMode.CRUISE
                boss.thought.think("tickEngage") {
                    if (move == EngageMove.ADVANCE) "closing in on ${aim.name.string} ($mode)" else "repositioning: line to ${aim.name.string} blocked ($mode)"
                }
                goTo(level, target, mode)
                lookAt = null
            }

            // Nowhere to back into: a tank that strafes carries on round the target instead of standing in front of it.
            EngageMove.BACK_OFF ->
                if (rearClear(level)) {
                    boss.thought.think("tickEngage") { "backing off: ${aim.name.string} too close" }
                    driver.backAway(target)
                } else {
                    engageRedecide = true
                    if (strafes) {
                        boss.thought.think("tickEngage") { "target too close, rear blocked: circling ${aim.name.string}" }
                        strafe.tick(level, aim, range, blockedTicks)
                    } else {
                        boss.thought.think("tickEngage") { "target too close, rear blocked: holding" }
                        driver.hold()
                    }
                }

            EngageMove.CIRCLE -> {
                boss.thought.think("tickEngage") { "circling ${aim.name.string} on the engage ring" }
                strafe.tick(level, aim, range, blockedTicks)
            }

            // A wheeled hull turns by creeping forward, so it only turns when well off; the turret does the aiming.
            EngageMove.TURN_HOLD -> {
                val aligned = driver.turnToward(target, facing)
                boss.thought.think("tickEngage") {
                    if (aligned) "holding the engage band, ${aim.name.string} in the turret" else "turning the hull toward ${aim.name.string}"
                }
                if (aligned) driver.hold()
            }

            EngageMove.ELEVATION -> {
                if (standOff == null) {
                    engageRedecide = true
                    driver.hold()
                    return
                }
                boss.thought.think("tickEngage") {
                    "gun cannot ${if (layStatus == LayStatus.TOO_LOW) "depress" else "elevate"} onto ${aim.name.string}: moving to a stand-off spot"
                }
                goTo(level, standOff, DriveMode.CRUISE)
                lookAt = null
            }

            // Not run through here: the slot hold and the flank are decided before the commitment (see tickEngage).
            EngageMove.HOLD_SLOT, EngageMove.FLANK -> {
                engageRedecide = true
                driver.hold()
            }
        }
    }

    // ================================================================================================== elevation

    /**
     * Updates whether the gun can be laid on the aim, in ENGAGE with a target in sight (otherwise it is OK, and no stand-off or
     * descent is asked for), and since when it could not.
     */
    private fun updateLay(aim: Entity?, hidden: Boolean, now: Int) {
        layStatus = if (state == TankState.ENGAGE && aim != null && !hidden) elevation.layStatus(aim) else LayStatus.OK
        if (layStatus == LayStatus.OK) layBadSince = -1 else if (layBadSince < 0) layBadSince = now
    }

    /** True once the gun has failed to lay for [ELEVATION_SUSTAIN_TICKS]: a swing past the limit is no reason to move. */
    private fun elevationNeeded(now: Int): Boolean =
        layStatus != LayStatus.OK && layBadSince >= 0 && now - layBadSince >= ELEVATION_SUSTAIN_TICKS

    /** Notes that the main planner got nowhere (at most once per [TROUBLE_SPACING] ticks); three within [TROUBLE_WINDOW] mean a loop. */
    private fun noteRouteTrouble(now: Int) {
        if (routeTroubles.isNotEmpty() && now - routeTroubles.last() < TROUBLE_SPACING) return
        routeTroubles.addLast(now)
        while (routeTroubles.size > TROUBLE_LOOP) routeTroubles.removeFirst()
    }

    private fun routeLooping(now: Int): Boolean =
        routeTroubles.size >= TROUBLE_LOOP && now - routeTroubles.first() <= TROUBLE_WINDOW

    /**
     * The descent override: true while the tank is stuck on a perch (see [ElevationDetection.perched]) and must drive off it
     * instead of doing what its state asks. It starts when the tank is perched and
     *  - it is in PATROL, INVESTIGATE or SEARCH (nothing is worth the roof), or
     *  - it is in ENGAGE, cannot depress its gun onto the target, and has no stand-off spot it can reach from the perch (none, or
     *    only one on lower ground), or
     *  - the main planner keeps failing ([routeLooping]).
     * It ends when the tank is no longer perched, or its state is one the descent does not belong to (EVADE, PULL_BACK, RETREAT,
     * FLANK). While [descentCooldownUntil] runs after a failed rescue the override stays off.
     */
    private fun mustDescend(level: ServerLevel, aim: Entity?, now: Int): Boolean {
        val eligible = state == TankState.PATROL || state == TankState.INVESTIGATE || state == TankState.SEARCH || state == TankState.ENGAGE
        if (!eligible || boss.isWreck || now < descentCooldownUntil) {
            if (descending) endDescent("not now ($state)")
            return false
        }
        var reason: String? = when {
            descending -> "descending"
            state != TankState.ENGAGE -> "nothing here is worth the perch"
            aim != null && layStatus == LayStatus.TOO_LOW && elevationNeeded(now) -> {
                val spot = elevation.standOffSpot(level, aim, layStatus)
                if (spot == null || spot.y < boss.y - STAND_OFF_LOWER) "no stand-off spot to reach from the perch" else null
            }
            else -> null
        }
        if (reason == null && routeLooping(now)) reason = "the route planner keeps failing here"
        if (reason == null) return false
        if (!elevation.perched(level)) {
            if (descending) endDescent("down from the perch")
            return false
        }
        if (!descending) startDescent(reason, now)
        return true
    }

    private fun startDescent(reason: String, now: Int) {
        descending = true
        descentSince = now
        descentProgressAt = now
        descentBest = Double.MAX_VALUE
        descentLowest = boss.y
        routeTroubles.clear()
        descentPlanner.invalidate()
        newGoal()
        boss.thought.event("elevation.descent") { "perched: driving off to lower ground ($reason)" }
    }

    private fun endDescent(why: String) {
        descending = false
        driver.allowDrop(0.0)
        descentPlanner.invalidate()
        newGoal()
        driver.resetStuck()
        routeTroubles.clear()
        boss.thought.event("elevation.descent") { "descent over ($why)" }
    }

    /**
     * Drives off the perch to [ElevationDetection.descentGoal] with the descent planner (it may step down [DESCENT_DROP] blocks) and a
     * driver whose look-ahead accepts the same drop. With no goal, no route, a route that ends on the perch, or no progress for
     * [DESCENT_STALL_TICKS], the tank is set down on the ground below by the rescue of [DepotRetreat].
     */
    private fun tickDescent(level: ServerLevel, now: Int) {
        val position = boss.position()
        driver.allowDrop(DESCENT_DROP)
        val goal = elevation.descentGoal(level)
        if (goal == null) {
            giveUpDescent(now, "no lower ground within reach")
            return
        }
        boss.thought.think("elevation.descent") { "perched: descending to lower ground" }
        descentPlanner.request(level, position, goal)
        descentPlanner.tick(level)

        val distance = horizontalDistance(position, goal)
        if (distance < descentBest - DESCENT_PROGRESS || boss.y < descentLowest - 1.0) {
            descentBest = minOf(descentBest, distance)
            descentLowest = minOf(descentLowest, boss.y)
            descentProgressAt = now
        }
        if (now - descentProgressAt > DESCENT_STALL_TICKS) {
            giveUpDescent(now, "no progress")
            return
        }
        val waypoint = descentPlanner.nextWaypoint(position)
        if (waypoint == null) {
            when {
                descentPlanner.status == GroundRoutePlanner.Status.NO_ROUTE && now - descentSince > DESCENT_NO_ROUTE_TICKS ->
                    giveUpDescent(now, "no route off the perch")
                descentPlanner.arrived && descentPlanner.partial -> giveUpDescent(now, "the route ends on the perch")
                else -> {
                    boss.thought.think("elevation.descent") { "waiting for the descent route" }
                    driver.hold()
                }
            }
            return
        }
        val last = waypoint === descentPlanner.waypoints.lastOrNull()
        driver.drive(waypoint, DriveMode.CRUISE, stopAtEnd = last)
        lookAt = waypoint
    }

    /** The descent failed: sets the tank down on lower ground ([DepotRetreat.rescueFromPerch]); with none, the override sleeps for [DESCENT_COOLDOWN_TICKS]. */
    private fun giveUpDescent(now: Int, why: String) {
        boss.thought.event("elevation.descent") { "descent failed ($why): setting the tank down on lower ground" }
        val rescued = retreat.rescueFromPerch()
        endDescent(if (rescued) "set down on lower ground" else "no lower ground to be set down on")
        if (!rescued) descentCooldownUntil = now + DESCENT_COOLDOWN_TICKS
    }

    /**
     * Where this tank is heading or holding for now (patrol goal, noise, search point, firing or cover spot, flank point, pull-back shelter),
     * so another tank choosing its own goal can avoid it; null when it has none.
     */
    fun destination(): Vec3? = when (state) {
        TankState.PULL_BACK -> pullBack.spot
        TankState.PATROL -> rally.destination() ?: patrolGoal
        TankState.INVESTIGATE -> rally.destination() ?: investigateGoal
        TankState.SEARCH -> rally.destination() ?: search.currentPoint()
        TankState.ENGAGE -> peek.destination() ?: snipe.spot ?: strafe.goal ?: if (engageMove == EngageMove.ELEVATION) elevation.spot else null
        TankState.FLANK -> flank.goal
        else -> null
    }

    /** The brain's side of the ground behaviours ([StrafeManeuver], [PeekAndShoot] and the rest): its planner, driving, turret look point and debug log. */
    private inner class Moves : GroundMoves {
        override val planner: GroundRoutePlanner
            get() = this@TankBossBrain.planner

        override fun goTo(level: Level, goal: Vec3, mode: DriveMode, replanShift: Double, rollOn: Boolean): Boolean =
            this@TankBossBrain.goTo(level, goal, mode, replanShift, rollOn)

        override fun newGoal() = this@TankBossBrain.newGoal()

        override fun passable(level: Level, point: Vec3): Boolean = this@TankBossBrain.passable(level, point)

        override fun crowded(level: Level, point: Vec3): Boolean = this@TankBossBrain.crowded(level, point)

        override fun rearClear(level: Level): Boolean = this@TankBossBrain.rearClear(level)

        override fun look(at: Vec3?) {
            lookAt = at
        }

        override fun debug(message: String, vararg args: Any?) = this@TankBossBrain.debug(message, *args)
    }

    // ================================================================================================== pull-back

    /** The brain's side of [PullBackRetreat]: its route planner, smoke dispenser and EVADE state. */
    private inner class PullBackHooks : PullBackRetreat.Hooks {
        override fun sprintTo(level: Level, goal: Vec3): Boolean {
            val arrived = goTo(level, goal, DriveMode.SPRINT)
            lookAt = null
            return arrived
        }

        override fun popSmoke(): Boolean = smoke.pop()

        override fun breakAway(from: Vec3) {
            smoke.pop()
            enterEvade(towards(from), "pull-back: breaking away from an aircraft")
        }

        override fun debug(message: String, vararg args: Any?) = this@TankBossBrain.debug(message, *args)
    }

    /**
     * Asks [PullBackRetreat] about the latest hit, in any state that a run to cover may interrupt (not EVADE, a retreat that is
     * already leaving, a run in progress or a wreck), and switches to PULL_BACK when it starts one.
     */
    private fun considerPullBack(level: ServerLevel, aim: Entity?, aimHidden: Boolean) {
        if (state == TankState.EVADE || state == TankState.PULL_BACK || state == TankState.RETREAT || state == TankState.CRIPPLED) {
            boss.thought.think("considerPullBack") { "not considering a pull-back in $state" }
            return
        }
        if (boss.isWreck) return
        if (!pullBack.assess(level, aim, aimHidden, lastAttacker(level))) {
            boss.thought.think("considerPullBack") { "no pull-back needed" }
            return
        }
        resumeState = state
        resumeTicks = stateTicks
        state = TankState.PULL_BACK
        stateTicks = 0
        peek.drop()
        breach.cancel()
        newGoal()
        driver.resetStuck()
        boss.thought.event("state") { "$resumeState -> PULL_BACK (hit hard, running to cover)" }
    }

    /** Runs the retreat to cover and, when it is over, goes back to the state it interrupted. */
    private fun tickPullBack(level: Level) {
        if (!pullBack.tick(level)) {
            boss.thought.think("tickPullBack") { "running to cover" }
            return
        }
        pullBack.end()
        val ticks = resumeTicks
        enter(resumeState, resume = true, why = "pull-back over")
        stateTicks = ticks
        newGoal()
    }

    // ================================================================================================== evade

    /**
     * Breaks 90 degrees off the threat's bearing with a dash, to the side that is passable. The threat is a missile
     * (bearing from the threat monitor) or an aircraft the guns cannot reach (its bearing, set when entering).
     */
    private fun tickEvade(level: Level) {
        val heading = FlightMath.dir(boss.yRot, 0f)
        val bearing = FlightMath.horizontal(evadeBearing ?: threats.threatBearing ?: heading, heading)
        var sideways = Vec3(-bearing.z * evadeSide, 0.0, bearing.x * evadeSide)
        var point = boss.position().add(sideways.scale(EVADE_DASH))
        var swapped = false
        if (!passable(level, point)) {
            evadeSide = -evadeSide
            sideways = sideways.scale(-1.0)
            point = boss.position().add(sideways.scale(EVADE_DASH))
            swapped = true
        }
        if (passable(level, point)) {
            boss.thought.think("tickEvade") { if (swapped) "dashing sideways off the threat (first side blocked)" else "dashing sideways off the threat" }
            driver.drive(point, DriveMode.SPRINT)
        } else {
            boss.thought.think("tickEvade") { "both sides blocked: backing away from the threat" }
            driver.backAway(boss.position().add(bearing), DriveMode.SPRINT)
        }

        if (stateTicks >= evadeDuration) {
            val ticks = resumeTicks
            enter(resumeState, resume = true, why = "evade over")
            stateTicks = ticks
            newGoal()
        }
    }

    // ================================================================================================== water

    /**
     * True while the hull sits in water it cannot (or must not) cross: a sinking tank (any depth), or the LAV-25 with
     * `crossWater` off. The driver then heads for the nearest bank instead of the state's goal.
     */
    private fun mustLeaveWater(level: Level, now: Int): Boolean {
        if (!driver.inWater || canSwim()) {
            wading = false
            boss.thought.think("mustLeaveWater") { if (driver.inWater) "in water, but the hull can swim" else "on dry ground" }
            return false
        }
        if (now >= wadingCheckTick) {
            wadingCheckTick = now + WADING_CHECK_INTERVAL
            val cell = GroundTerrain.sample(level, GroundTerrain.cellOf(boss.x, profile), GroundTerrain.cellOf(boss.z, profile), profile)
            wading = cell.waterDepth > 0
        }
        boss.thought.think("mustLeaveWater") { if (wading) "in water the hull must not cross: heading for the bank" else "in shallows, free to drive on" }
        return wading
    }

    // ================================================================================================== reactions

    /**
     * Reactions to damage, once per hit: return fire, and `smokeWhenHitChance` percent to pop smoke (as part of a flank when
     * fighting and flanking is on). Hits from an aircraft the guns cannot reach, and heavy hits, are [PullBackRetreat]'s.
     */
    private fun reactToHits(level: ServerLevel, aim: Entity?) {
        val hit = boss.lastDamageTick()
        if (hit < 0 || hit == lastHitHandled) return
        lastHitHandled = hit

        val attacker = lastAttacker(level)
        boss.thought.event("reactToHits") { "hit by ${attacker?.name?.string ?: "unknown"}, hp ${(boss.healthFraction() * 100f).roundToInt()}%" }
        returnFire.start(attacker, aim)
        // Every hit is news for the teammates: the call (and the spot this tank now shoots at) goes out this tick.
        rally.shareNow()
        if (!settings.smokeEnabled.get() || boss.isWreck) return

        if (random().nextInt(100) >= settings.smokeWhenHitChance.get()) {
            boss.thought.event("reactToHits") { "no smoke rolled for this hit" }
            return
        }
        if (state == TankState.ENGAGE && settings.flankingEnabled.get() && aim != null && !targeting.hidden &&
            formationPhase != TankFormations.Phase.HALT && flank.ready(boss.tickCount) && flank.start(level, aim)
        ) {
            boss.thought.event("reactToHits") { "answering the hit with a flank" }
            return
        }
        boss.thought.event("reactToHits") { "answering the hit with smoke" }
        smoke.pop()
    }

    private fun lastAttacker(level: ServerLevel): Entity? {
        val id = boss.lastAttackerUUID
        if (id.isNullOrEmpty()) return null
        val uuid = try {
            UUID.fromString(id)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return level.getEntity(uuid)
    }

    private companion object {
        const val TAG_STATE = "State"

        /** One smoke salvo per incoming missile: the tank has few charges and the salvo already covers a wide arc. */
        const val MISSILE_SMOKE_BURSTS = 1

        const val LISTEN_INTERVAL = 10

        /** Ticks between two decisions on the engage move (staggered by entity id), see [decideEngageMove]. */
        const val DECISION_INTERVAL = 10

        /** Ticks between two looks at whether the target has come inside the back-off distance. */
        const val CLOSE_CHECK_TICKS = 5

        /** Ticks the gun has to be out of reach of the target before the tank moves for it (a second). */
        const val ELEVATION_SUSTAIN_TICKS = 20

        /** A stand-off spot this many blocks below the tank is not on its perch: it can only be reached by driving off. */
        const val STAND_OFF_LOWER = 2.0

        // Descent from a perch
        /** The tallest drop (blocks) the descent planner and the driver's look-ahead accept. */
        const val DESCENT_DROP = 6.0
        const val DESCENT_PROGRESS = 2.0
        const val DESCENT_STALL_TICKS = 300
        const val DESCENT_NO_ROUTE_TICKS = 40
        const val DESCENT_COOLDOWN_TICKS = 600

        /** Route troubles (one per [TROUBLE_SPACING] ticks at most) that make a loop when [TROUBLE_LOOP] of them fall within [TROUBLE_WINDOW] ticks. */
        const val TROUBLE_LOOP = 3
        const val TROUBLE_SPACING = 20
        const val TROUBLE_WINDOW = 200

        /** Ticks a tank keeps the shooter it pinpointed by ear as its target without seeing it. */
        const val PINPOINT_HOLD_TICKS = 200

        /** Ticks the turret looks toward a shot just heard. */
        const val HEARD_LOOK_TICKS = 60

        /** Ticks between two looks for a smoke cloud. */
        const val SMOKE_SCAN_INTERVAL = 20

        // Patrol
        const val PATROL_ATTEMPTS = 8

        /** Half-width (degrees) of the cone ahead in which a formation leader picks its patrol goal, and the wider one of its later tries. */
        const val LEAD_CONE = 55.0
        const val LEAD_CONE_WIDE = 100.0
        const val PATROL_TIMEOUT = 1800
        const val PATROL_WAIT_MIN = 120
        const val PATROL_WAIT_MAX = 320

        // Investigate / search
        const val INVESTIGATE_RADIUS = 16.0
        const val INVESTIGATE_TIMEOUT = 1500

        /** Ticks after losing sight in which the boss still shells the last seen spot. */
        const val SUPPRESS_TICKS = 100

        // Idle resupply
        /** Ticks between two rolls of the idle resupply chance. */
        const val IDLE_ROLL_INTERVAL = 200

        const val STATION_RANGE = 70.0

        /** Blocks past the nose where a stuck hull marks its cell as blocked. */
        const val STUCK_MARK_AHEAD = 2.0

        /** Least ticks between two searches started by a block the driver ran into. */
        const val OBSTACLE_REROUTE_TICKS = 20

        /** Longest a leader waits for a straggler per patrol goal, in ticks. */
        const val LEADER_WAIT_MAX = 100

        // Evade
        const val EVADE_MIN = 25
        const val EVADE_MAX = 40
        const val EVADE_DASH = 16.0

        // Water
        const val WADING_CHECK_INTERVAL = 5

        /** Distances (blocks) behind the hull whose ground must be passable before the tank reverses. */
        val REAR_PROBES = doubleArrayOf(4.0, 8.0)
    }
}

/**
 * What a tank in ENGAGE is doing about its target, one at a time and held for `decisionCommitSeconds` (see
 * `TankBossBrain.decideEngageMove`).
 */
internal enum class EngageMove {
    /** A follower in a formation: the slot is held and the turret does the fighting. */
    HOLD_SLOT,

    /** A flank was rolled; the brain is in its FLANK state for it. */
    FLANK,

    /** A sniper steps out of cover for a cannon round and drops back ([PeekAndShoot]). */
    PEEK,

    /** A sniper with no cover to peek from fires from a spot and scoots after each round ([SnipePosition]). */
    SNIPE,

    /** Closing in on a target beyond the engage band. */
    ADVANCE,

    /** Driving at the target because the line of fire stays blocked. */
    REPOSITION,

    /** Reversing from a target that is too close, the front to it. */
    BACK_OFF,

    /** Circling the target on the engage ring ([StrafeManeuver]). */
    CIRCLE,

    /** Standing in the engage band with the hull turned toward the target. */
    TURN_HOLD,

    /** Driving to a stand-off spot from which the gun can be laid on the target ([ElevationDetection]). */
    ELEVATION
}
