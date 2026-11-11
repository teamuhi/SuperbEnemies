package com.teamuhi.superbenemies.entity.boss.ac130.ai

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.Ac130Config
import com.teamuhi.superbenemies.entity.boss.ac130.Ac130BossEntity
import com.teamuhi.superbenemies.entity.boss.ac130.Ac130Gun
import com.teamuhi.superbenemies.entity.boss.common.ResupplyNotices
import com.teamuhi.superbenemies.entity.boss.common.air.AirResupply
import com.teamuhi.superbenemies.entity.boss.common.air.FlightController
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.air.StuckWatch
import com.teamuhi.superbenemies.entity.boss.common.air.TerrainGuard
import com.teamuhi.superbenemies.entity.boss.common.ai.ThreatMonitor
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The AC-130 boss's decision making: the glue between targeting, the flight controller, terrain guard, threat monitor
 * and the side-gun gunnery.
 *
 * `tick()` runs on the server **before** `VehicleEntity.baseTick`, so control inputs written here are in place when
 * SBW's aircraft engine consumes them; `applyGunnerAim()` runs **after** it (see [Ac130Gunnery]).
 *
 * The gunship never dives. Its attack is a pylon turn: SBW's loiter autopilot circles the target counter-clockwise with
 * the aircraft's LEFT side toward the centre, which is exactly where the three gun seats look. The brain only chooses
 * the circle (smoothed centre on the target, cruise altitude, an orbit radius adapted so the target sits in the middle
 * of the guns' depression windows) and lets the gunners shoot.
 *
 * While orbiting the guns fire in one of three patterns ([Ac130FireMode]): at the main target (normal), at one target
 * each (split fire) or all at one fixed spot (focus fire); the pattern is rolled every 15-25 seconds.
 *
 * Flow: INGRESS -> ORBIT (guns fire) -> occasionally TAUNT -> INGRESS -> ORBIT ... EVADE interrupts any state and
 * returns to it; RESUPPLY replaces the orbit while every enabled gun is out of ammunition; CRIPPLED is terminal.
 *
 * All state is per instance (no statics), so several bosses can fly at once.
 */
class Ac130BossBrain(private val boss: Ac130BossEntity) {

    private val flight = FlightController(boss)
    private val guard = TerrainGuard(boss)
    private val threats = ThreatMonitor(boss)
    private val stuck = StuckWatch(boss)
    private val targeting = Ac130Targeting(boss)
    private val gunnery = Ac130Gunnery(boss)

    /** The idle roll and the flight to the depot (see [AirResupply]); this plane's options and helpers plug in here. */
    private val resupply = AirResupply(boss, AirResupply.Settings(
        limitedAmmo = { Ac130Config.LIMITED_AMMO.get() },
        idleResupplySeconds = { Ac130Config.IDLE_RESUPPLY_SECONDS.get() },
        idleResupplyChance = { Ac130Config.IDLE_RESUPPLY_CHANCE.get() },
        resupplySeconds = { Ac130Config.RESUPPLY_SECONDS.get() },
        globalAggro = { Ac130Config.GLOBAL_AGGRO.get() },
        aggroRange = { Ac130Config.AGGRO_RANGE.get() },
        bossBarRange = { Ac130Config.BOSS_BAR_RANGE.get() },
        loseTargetFactor = Ac130Targeting.LOSE_TARGET_FACTOR,
        clearanceHalfWidth = RESUPPLY_CLEARANCE_HALF_WIDTH,
        maxClimb = RESUPPLY_MAX_CLIMB,
        huntingMessage = MSG_HUNTING
    ), object : AirResupply.Hooks {
        override val homePos: Vec3 get() = boss.homePos
        override val aim: Entity? get() = targeting.aim
        override val stateTicks: Int get() = this@Ac130BossBrain.stateTicks

        override fun fallbackAnchor(): Vec3? = targeting.lastKnown.anchor ?: idleCentre
        override fun anySpent(): Boolean = gunnery.anySpent()
        override fun fly(dir: Vec3, throttle: Float) = this@Ac130BossBrain.fly(dir, throttle)
        override fun cruiseAltitude(): Double = this@Ac130BossBrain.cruiseAltitude()
        override fun awayFrom(entity: Entity): Vec3 = this@Ac130BossBrain.awayFrom(entity)
        override fun currentHeading(): Vec3 = this@Ac130BossBrain.currentHeading()
        override fun towards(point: Vec3): Vec3 = this@Ac130BossBrain.towards(point)
        override fun enterResupply() = this@Ac130BossBrain.enter(Ac130State.RESUPPLY, why = "idle with spent guns")
    })

    var state: Ac130State = Ac130State.INGRESS
        private set
    private var stateTicks = 0

    /** True while the boss is away rearming, including an evade flown on the way; the boss bar is hidden meanwhile. */
    val withdrawn: Boolean
        get() = state == Ac130State.RESUPPLY || (state == Ac130State.EVADE && resumeState == Ac130State.RESUPPLY)

    // --- orbit ---
    /** Adaptive orbit radius; negative until first used so the config is only read once the server runs. */
    private var radius = -1.0
    private var orbitCentre: Vec3? = null
    private var nextRadiusAdaptTick = 0
    private var nextTauntRollTick = 0

    // --- fire patterns (during ORBIT) ---
    private var fireMode = Ac130FireMode.NORMAL
    private var modeEndTick = 0
    private var nextModeRollTick = 0

    /** Focus fire: the fixed spot every gun shoots at. */
    private var focusPoint: Vec3? = null

    /** Split fire: one order per gun, where the orbit is centred, and the angle of the spots used without enough targets. */
    private var splitPlan: Ac130FirePlan? = null
    private var splitCentre: Vec3? = null
    private var splitSpotAngle = 0.0
    private var nextSplitRefreshTick = 0

    // --- evade ---
    private var resumeState = Ac130State.INGRESS
    private var resumeTicks = 0
    private var evadeSide = 1.0
    private var evadeDuration = 35

    // --- idle ---
    // With no target the boss loiters over `targeting.lastKnown.anchor` (where it last had one), not over its home point,
    // which may be far from where the fight moved to.

    /** Where the fight was when this boss replaced one that left to rearm; used until it has had a target of its own. */
    private var idleCentre: Vec3? = null

    // --- taunt ---
    private var tauntPhase = TauntPhase.SETUP
    private var tauntPassed = false
    private var flaresDropped = 0
    private var nextTauntFlareTick = 0

    private enum class TauntPhase { SETUP, RUN }

    // ================================================================================================== main loop

    fun tick() {
        val level = boss.level() as? ServerLevel ?: return

        boss.thought.heartbeat { snapshot() }

        if (boss.crippled) {
            if (state != Ac130State.CRIPPLED) enterCrippled()
            flight.release()
            return
        }

        if (Ac130Config.UNSTUCK_TELEPORT.get()) {
            unstuck(level)
        } else {
            stuck.reset()
        }

        stateTicks++
        targeting.update(level, radiusNow())
        val target = targeting.aim
        target?.let { targeting.lastKnown.anchorAt(it.position()) }
        resupply.countIdle(state == Ac130State.INGRESS && target == null)

        if (threats.tick()) enterEvade()

        // The target is gone: stop attacking and loiter where it was (INGRESS without a target).
        if (target == null && state.needsTarget()) enter(Ac130State.INGRESS, why = "target lost")

        // Finite ammunition: with every enabled gun empty, go and rearm.
        if ((state == Ac130State.INGRESS || state == Ac130State.ORBIT) && Ac130Config.LIMITED_AMMO.get() &&
            gunnery.anyEnabled() && !gunnery.hasUsableGun()
        ) {
            enter(Ac130State.RESUPPLY, why = "every enabled gun is out of ammo")
        }

        when (state) {
            Ac130State.INGRESS -> tickIngress(target)
            Ac130State.ORBIT -> if (target != null) tickOrbit(target)
            Ac130State.TAUNT -> if (target != null) tickTaunt(target)
            Ac130State.RESUPPLY -> resupply.tick()
            Ac130State.EVADE -> tickEvade()
            Ac130State.CRIPPLED -> {}
        }

        // Aim every tick; fire only while circling, flying in or breaking away (the taunt and the rearm never shoot).
        val mayFire = state == Ac130State.INGRESS || state == Ac130State.ORBIT || state == Ac130State.EVADE
        gunnery.tick(
            target, targeting.aimVelocity, mayFire, firePlan()
        ) {
            targeting.snapToAnotherTarget(level, radiusNow())
        }
        threats.applyFlareInput()

        safetyNet(target)
    }

    /** The heartbeat line: state, target, range, cover, health, ammunition, fire mode and position. */
    private fun snapshot(): String {
        val victim = targeting.target
        val range = victim?.let { FlightMath.horizontalDistance(it.position(), boss.position()).roundToInt().toString() } ?: "-"
        return "state=$state ticks=$stateTicks target=${victim?.name?.string ?: "none"} dist=$range " +
                "hidden=${if (targeting.lastKnown.hidden) "y" else "n"} hp=${(boss.healthFraction() * 100).roundToInt()}% " +
                "ammo=[${gunnery.ammoSummary()}] fire=$fireMode radius=${radiusNow().roundToInt()} " +
                "pos=${boss.x.roundToInt()},${boss.y.roundToInt()},${boss.z.roundToInt()}"
    }

    /**
     * Re-applies the gunners' aim against the current seat frame. The entity calls this right after `baseTick`: SBW's
     * seat shot is queued and reads the gunner's look direction later, and `positionRider` has just turned the gunners
     * with the vehicle.
     */
    fun applyGunnerAim() {
        if (boss.crippled) return
        gunnery.applyAim()
    }

    private fun Ac130State.needsTarget(): Boolean = this == Ac130State.ORBIT || this == Ac130State.TAUNT

    // ================================================================================================== persistence

    fun save(tag: CompoundTag) {
        gunnery.saveAmmo(tag)
        targeting.save(tag)
        // A reloaded evade restarts at INGRESS, except mid-resupply: the boss would rejoin silently with empty guns.
        val saved = if (state == Ac130State.EVADE && resumeState == Ac130State.RESUPPLY) Ac130State.RESUPPLY else state
        tag.putString(TAG_STATE, saved.name)
    }

    fun load(tag: CompoundTag) {
        gunnery.loadAmmo(tag)
        targeting.load(tag)
        state = Ac130State.entries.firstOrNull { it.name == tag.getString(TAG_STATE) } ?: Ac130State.INGRESS
        // A reloaded evade restarts from the beginning; the other states resume (and re-derive their working data).
        if (state == Ac130State.EVADE) state = Ac130State.INGRESS
        stateTicks = 0
        boss.thought.event("load") { "restored state $state" }
    }

    // ================================================================================================== helpers

    private fun randomBetween(min: Int, max: Int): Int = min + boss.getRandom().nextInt(max - min + 1)

    private fun referenceY(aim: Entity? = targeting.aim): Double = aim?.y ?: idleSpot().y

    /** The spot circled with no target: the last target, else the fight a replacement came back to, else home. */
    private fun idleSpot(): Vec3 = targeting.lastKnown.anchor ?: idleCentre ?: boss.homePos

    /** Called for a replacement boss: the fight was around [anchor], so circle there until a target turns up. */
    fun rememberFight(anchor: Vec3) {
        idleCentre = anchor
    }

    private fun cruiseAltitude(): Double =
        min(referenceY() + Ac130Config.CRUISE_HEIGHT.get().toDouble(), boss.level().maxBuildHeight + 60.0)

    private fun baseRadius(): Double = Ac130Config.ORBIT_RADIUS.get().toDouble()

    /** The current orbit radius, always inside 0.6..1.8 x the configured radius (the config can change mid-orbit). */
    private fun radiusNow(): Double {
        val base = baseRadius()
        val current = if (radius < 0.0) base else radius
        return current.coerceIn(base * MIN_RADIUS_FACTOR, base * MAX_RADIUS_FACTOR)
    }

    private fun currentHeading(): Vec3 = FlightMath.horizontal(boss.deltaMovement, FlightMath.dir(boss.yRot, 0f))

    private fun awayFrom(entity: Entity): Vec3 =
        FlightMath.horizontal(Vec3(boss.x - entity.x, 0.0, boss.z - entity.z), currentHeading())

    private fun towards(point: Vec3): Vec3 =
        FlightMath.horizontal(Vec3(point.x - boss.x, 0.0, point.z - boss.z), currentHeading())

    /** Flies toward [dir] after the terrain guard has raised its pitch if the ground is coming up. */
    private fun fly(dir: Vec3, throttle: Float, maxBank: Float = MAX_BANK, guarded: Boolean = true) {
        val wanted = if (guarded) guard.adjustDesired(dir, referenceY() + UNKNOWN_TERRAIN_OFFSET) else dir
        flight.steer(wanted, throttle, maxBank)
    }

    /** Throttle trim for the SBW-flown orbit: its own throttle logic tends to settle below the lift speed. */
    private fun trimOrbitThrottle(cruise: Double) {
        val altitudeError = cruise - boss.y
        val wanted = (ORBIT_THROTTLE + altitudeError * 0.004).coerceIn(ORBIT_THROTTLE_MIN, ORBIT_THROTTLE_MAX)
        boss.power = Mth.lerp(0.1f, boss.power, wanted.toFloat())
    }

    /** Flies a broad, shallow-bank pylon turn while holding the requested altitude. */
    private fun flyPylon(centre: Vec3, altitude: Double, radius: Double) {
        val offset = FlightMath.horizontal(
            Vec3(boss.x - centre.x, 0.0, boss.z - centre.z),
            currentHeading()
        )
        val distance = FlightMath.horizontalDistance(boss.position(), centre)
        // The left-side guns must face the centre: at +X the aircraft therefore flies toward -Z.
        val tangent = Vec3(offset.z, 0.0, -offset.x)
        // Keep the tangent dominant: an uncapped correction turns the gunship through the target when it falls inside
        // the ring, especially while the heavy aircraft is still carrying lateral momentum.
        val radialCorrection = offset.scale(
            ((radius - distance) * PYLON_RADIAL_GAIN).coerceIn(-PYLON_MAX_RADIAL, PYLON_MAX_RADIAL)
        )
        val desired = tangent.add(radialCorrection)
        val altitudeCorrection = ((altitude - boss.y) * PYLON_PITCH_GAIN).coerceIn(-PYLON_MAX_DOWN, PYLON_MAX_UP)
        fly(
            FlightMath.tilted(desired, altitudeCorrection),
            ORBIT_THROTTLE.toFloat(),
            maxBank = PYLON_MAX_BANK
        )
    }

    /** Releases one flare burst this tick (unlimited: the dispenser is refilled instead of waiting for SBW's reload). */
    private fun releaseFlare() {
        if (boss.isWreck || boss.crippled) return
        if (boss.decoyCount < 1) {
            if (Ac130Config.LIMITED_AMMO.get()) {
                boss.thought.think("releaseFlare") { "flares empty, none released" }
                return
            }
            boss.decoyCount = boss.computed().decoyMagazineSize
        }
        boss.decoyInputDown = true
    }

    // ================================================================================================== transitions

    /**
     * Called every tick. The gunship stayed on one spot for `unstuckSeconds`: it is put in open air (see [StuckWatch])
     * and starts over from INGRESS, unless it was on its way to rearm, which carries on. Nothing is free: tried again
     * after another window.
     */
    private fun unstuck(level: ServerLevel) {
        val spot = stuck.rescueSpot(level, Ac130Config.UNSTUCK_SECONDS.get()) ?: return
        boss.thought.event("unstuck") { "stuck at ${boss.position()}, moved to $spot" }
        boss.relaunch(spot)
        flight.release()
        if (!withdrawn) enter(Ac130State.INGRESS, why = "stuck, relaunched")
    }

    /** @param resume true when returning from an evade: keeps the per-state progress */
    private fun enter(next: Ac130State, resume: Boolean = false, why: String = "") {
        if (next == state && !resume) return
        boss.thought.event("state") { "$state -> $next" + if (why.isNotEmpty()) " ($why)" else "" }
        state = next
        stateTicks = 0
        // Only the orbit (and the idle/climbing loiters inside INGRESS and RESUPPLY) use SBW's autopilot; each
        // re-enables it itself every tick.
        if (next != Ac130State.ORBIT) boss.loiterActive = false
        if (next != Ac130State.ORBIT && next != Ac130State.EVADE) clearFireMode()
        if (resume) return

        when (next) {
            Ac130State.ORBIT -> {
                orbitCentre = null
                nextRadiusAdaptTick = 0
                nextModeRollTick = randomBetween(MODE_FIRST_ROLL_MIN, MODE_FIRST_ROLL_MAX)
                nextTauntRollTick = randomBetween(TAUNT_ROLL_MIN, TAUNT_ROLL_MAX)
            }

            Ac130State.RESUPPLY -> {
                resupply.reset()
                boss.announce(MSG_WITHDRAW, ResupplyNotices.WITHDRAW_STYLE)
            }

            Ac130State.TAUNT -> {
                tauntPhase = TauntPhase.SETUP
                tauntPassed = false
                flaresDropped = 0
                nextTauntFlareTick = 0
            }

            else -> {}
        }
    }

    private fun enterEvade() {
        if (state == Ac130State.CRIPPLED) return
        if (state == Ac130State.EVADE) {
            evadeDuration = max(evadeDuration, stateTicks + 25) // a second missile extends the break
            boss.thought.event("enterEvade") { "another missile: evade extended to $evadeDuration ticks" }
            return
        }
        resumeState = state
        resumeTicks = stateTicks
        evadeSide = if (boss.getRandom().nextBoolean()) 1.0 else -1.0
        evadeDuration = randomBetween(EVADE_MIN, EVADE_MAX)
        boss.thought.event("enterEvade") {
            "evading from $resumeState to the ${if (evadeSide > 0) "right" else "left"} for $evadeDuration ticks"
        }
        enter(Ac130State.EVADE, why = "missile inbound")
    }

    private fun enterCrippled() {
        boss.thought.event("enterCrippled") { "$state -> CRIPPLED (health gone, crashing)" }
        state = Ac130State.CRIPPLED
        stateTicks = 0
        boss.loiterActive = false
        flight.release()
    }

    // ================================================================================================== ingress

    /**
     * Fly to the target at cruise altitude. Inside [INGRESS_CIRCLE_FACTOR] x radius, if the plane is still too low, it
     * circles the target while it climbs instead of overflying it. With no target it loiters over the last target (or
     * the fight its replacement came back to, or home).
     */
    private fun tickIngress(target: Entity?) {
        val cruise = cruiseAltitude()

        if (target == null) {
            boss.thought.think("tickIngress") {
                "no target, circling ${if (targeting.lastKnown.anchor != null) "where the last target was seen" else if (idleCentre != null) "the fight it came back to" else "home"}"
            }
            flyPylon(idleSpot(), cruise, baseRadius())
            resupply.rollIdle()
            return
        }

        val r = radiusNow()
        val distance = FlightMath.horizontalDistance(boss.position(), target.position())
        val altitudeError = cruise - boss.y
        val altitudeReady = altitudeError < INGRESS_ARRIVAL_MARGIN

        if (distance < r * INGRESS_CIRCLE_FACTOR) {
            if (altitudeReady || stateTicks > INGRESS_TIMEOUT) {
                enter(Ac130State.ORBIT, why = if (altitudeReady) "at the circle and altitude" else "climb timed out, orbiting low")
                return
            }
            boss.thought.think("tickIngress") { "circling ${target.name.string} while climbing to altitude" }
            flyPylon(target.position(), cruise, r)
            return
        }

        boss.thought.think("tickIngress") { "flying in on ${target.name.string}" }
        val heading = if (distance < 40.0) currentHeading() else towards(target.position())
        fly(FlightMath.tilted(heading, (altitudeError * 0.1).coerceIn(-4.0, 18.0)), INGRESS_THROTTLE)
    }

    // ================================================================================================== orbit

    private fun tickOrbit(target: Entity) {
        val cruise = cruiseAltitude()
        val r = radiusNow()

        // Too far from the target to ever bring the guns to bear (it ran, or teleported): fly in again.
        if (FlightMath.horizontalDistance(boss.position(), target.position()) > r * ORBIT_LOSE_FACTOR) {
            enter(Ac130State.INGRESS, why = "target too far from the orbit, flying in again")
            return
        }
        // The autopilot sheds altitude in tight banked turns: climb back instead of orbiting low.
        if (boss.y < cruise - ALTITUDE_RECOVERY && stateTicks > 60) {
            enter(Ac130State.INGRESS, why = "orbit sank, climbing back")
            return
        }
        boss.thought.think("tickOrbit") { "orbiting ${target.name.string}, fire mode $fireMode" }

        manageFireMode(target)
        val centre = followTarget(orbitFocus(target), r)
        adaptRadius(target)
        // Split victims can occupy a wide cluster. Move farther out so their angles remain inside the
        // left-side gun windows instead of forcing the airframe through the cluster.
        val adapted = if (fireMode == Ac130FireMode.SPLIT) {
            max(radiusNow(), baseRadius() * SPLIT_STANDOFF_FACTOR)
        } else {
            radiusNow()
        }
        flyPylon(centre, cruise, adapted)

        // A rare show-off pass, never when there is nothing to shoot it down with and never replacing the guns.
        if (stateTicks >= nextTauntRollTick) {
            nextTauntRollTick = stateTicks + randomBetween(TAUNT_ROLL_MIN, TAUNT_ROLL_MAX)
            if (fireMode == Ac130FireMode.NORMAL && Ac130Config.TAUNT_ENABLED.get() && gunnery.anyEnabled() &&
                boss.getRandom().nextInt(TAUNT_WEIGHT + ORBIT_CONTINUE_WEIGHT) < TAUNT_WEIGHT
            ) {
                enter(Ac130State.TAUNT, why = "taunt roll won")
            }
        }
    }

    /**
     * Orbit centre: eases toward [position] (lerp [CENTRE_LERP] per tick) and snaps once the target is more than
     * [CENTRE_SNAP_FACTOR] x radius off it. The side guns only cover +-15 degrees around the plane's left wing, so a
     * centre that trails a running target swings it out of every gun's arc.
     */
    private fun followTarget(position: Vec3, r: Double): Vec3 {
        val current = orbitCentre
        val centre = when {
            current == null -> position
            FlightMath.horizontalDistance(current, position) > r * CENTRE_SNAP_FACTOR -> position
            else -> current.lerp(position, CENTRE_LERP)
        }
        orbitCentre = centre
        return centre
    }

    /**
     * Every [RADIUS_ADAPT_INTERVAL] ticks, moves the radius toward the value that puts the target in the middle of the
     * enabled guns' depression windows ([Ac130Gunnery.suggestedRadius]): wider when the target sits too steeply below
     * the guns, tighter when it is too shallow. Eased ([RADIUS_GAIN]) because the aircraft takes a while to follow.
     */
    private fun adaptRadius(target: Entity) {
        val now = boss.tickCount
        if (now < nextRadiusAdaptTick) return
        nextRadiusAdaptTick = now + RADIUS_ADAPT_INTERVAL

        val wanted = gunnery.suggestedRadius(target) ?: return
        val base = baseRadius()
        val goal = (wanted * ORBIT_TIGHTEN).coerceIn(base * MIN_RADIUS_FACTOR, base * MAX_RADIUS_FACTOR)
        val before = radiusNow()
        radius = Mth.lerp(RADIUS_GAIN, before, goal)

        if (boss.thought.enabled && abs(radius - before) > 1.0 && boss.tickCount % 40 < RADIUS_ADAPT_INTERVAL) {
            boss.thought.event("adaptRadius") {
                "orbit radius ${"%.1f".format(before)} -> ${"%.1f".format(radius)} (suggested ${"%.1f".format(wanted)}, configured ${"%.1f".format(base)})"
            }
        }
    }

    // ================================================================================================== fire patterns

    /**
     * Rolls and ends the fire patterns while orbiting. Every [MODE_ROLL_MIN]..[MODE_ROLL_MAX] ticks a roll starts split
     * fire (`splitFireChance` percent) or focus fire (`focusFireChance` percent, checked after it); both need two
     * usable guns, so a single remaining gun (disabled, spent, lost) just fires normally. A running pattern ends on
     * time or as soon as only one gun is left.
     */
    private fun manageFireMode(target: Entity) {
        val now = boss.tickCount
        if (fireMode != Ac130FireMode.NORMAL) {
            if (now >= modeEndTick || gunnery.readyGuns().size < 2) {
                boss.thought.event("manageFireMode") {
                    "$fireMode fire over: ${if (now >= modeEndTick) "time is up" else "fewer than two usable guns"}"
                }
                endFireMode()
            } else if (fireMode == Ac130FireMode.SPLIT && now >= nextSplitRefreshTick) {
                (boss.level() as? ServerLevel)?.let { refreshSplit(it, target) }
            }
            return
        }
        if (stateTicks < nextModeRollTick) return
        nextModeRollTick = stateTicks + randomBetween(MODE_ROLL_MIN, MODE_ROLL_MAX)
        if (gunnery.readyGuns().size < 2) {
            boss.thought.think("manageFireMode") { "normal fire only: fewer than two usable guns" }
            return
        }

        val split = Ac130Config.SPLIT_FIRE_CHANCE.get()
        val focus = Ac130Config.FOCUS_FIRE_CHANCE.get()
        val roll = boss.getRandom().nextInt(100)
        boss.thought.event("manageFireMode") {
            "fire mode roll $roll (split below $split, focus below ${split + focus}) -> " +
                    when {
                        roll < split -> "SPLIT"
                        roll < split + focus -> "FOCUS"
                        else -> "NORMAL"
                    }
        }
        when {
            roll < split -> startSplit(target)
            roll < split + focus -> startFocus(target)
        }
    }

    private fun startFocus(target: Entity) {
        fireMode = Ac130FireMode.FOCUS
        modeEndTick = boss.tickCount + randomBetween(FOCUS_MIN, FOCUS_MAX)
        focusPoint = target.boundingBox.center
        boss.thought.event("startFocus") { "focus fire: every gun on ${target.name.string}'s spot for ${modeEndTick - boss.tickCount} ticks" }
    }

    private fun startSplit(target: Entity) {
        fireMode = Ac130FireMode.SPLIT
        modeEndTick = boss.tickCount + randomBetween(SPLIT_MIN, SPLIT_MAX)
        splitSpotAngle = boss.getRandom().nextDouble() * Math.PI * 2.0
        boss.thought.event("startSplit") { "split fire: one gun per victim for ${modeEndTick - boss.tickCount} ticks" }
        (boss.level() as? ServerLevel)?.let { refreshSplit(it, target) }
    }

    /**
     * One gun per victim: the main target and the nearest others (see [Ac130Targeting.extraTargets]). A gun without a
     * victim of its own shoots a spot on a ring of [SPLIT_SPOT_RADIUS] blocks around the main target instead. The
     * orbit is centred on the victims' average position so every gun can reach its own.
     */
    private fun refreshSplit(level: ServerLevel, primary: Entity) {
        nextSplitRefreshTick = boss.tickCount + SPLIT_REFRESH_TICKS
        val guns = gunnery.readyGuns()
        val victims = listOf(primary) + targeting.extraTargets(level, primary, SPLIT_CLUSTER_RADIUS, guns.size - 1)

        val orders = EnumMap<Ac130Gun, GunAim>(Ac130Gun::class.java)
        for ((index, gun) in guns.withIndex()) {
            orders[gun] = if (index < victims.size) {
                GunAim(victims[index], Vec3.ZERO)
            } else {
                val angle = splitSpotAngle + index * (Math.PI * 2.0 / guns.size)
                GunAim(primary, Vec3(cos(angle) * SPLIT_SPOT_RADIUS, 0.0, sin(angle) * SPLIT_SPOT_RADIUS))
            }
        }
        splitPlan = Ac130FirePlan(orders)
        boss.thought.think("refreshSplit") {
            "split fire on ${victims.joinToString { it.name.string }}" +
                    if (victims.size < guns.size) ", spare guns on spots around ${primary.name.string}" else ""
        }
        splitCentre = Vec3(victims.sumOf { it.x } / victims.size, primary.y, victims.sumOf { it.z } / victims.size)
    }

    private fun endFireMode() {
        clearFireMode()
        nextModeRollTick = stateTicks + randomBetween(MODE_ROLL_MIN, MODE_ROLL_MAX)
    }

    private fun clearFireMode() {
        fireMode = Ac130FireMode.NORMAL
        focusPoint = null
        splitPlan = null
        splitCentre = null
    }

    /** The point the orbit is centred on: the focus spot, the split victims' average, or the main target. */
    private fun orbitFocus(target: Entity): Vec3 = when (fireMode) {
        Ac130FireMode.FOCUS -> focusPoint ?: target.position()
        Ac130FireMode.SPLIT -> splitCentre ?: target.position()
        Ac130FireMode.NORMAL -> target.position()
    }

    /** The gun orders of the running pattern, or null for normal fire (and once the pattern's time is up). */
    private fun firePlan(): Ac130FirePlan? {
        if (fireMode == Ac130FireMode.NORMAL || boss.tickCount >= modeEndTick) return null
        return when (fireMode) {
            Ac130FireMode.SPLIT -> splitPlan
            Ac130FireMode.FOCUS -> focusPoint?.let { point ->
                Ac130FirePlan(gunnery.readyGuns().associateWith { GunAim(null, point) }, FOCUS_SCATTER_SCALE)
            }

            Ac130FireMode.NORMAL -> null
        }
    }

    // ================================================================================================== taunt

    /**
     * "Angel flares": one straight low pass over the target at ground + [TAUNT_PASS_HEIGHT], dropping flare bursts in
     * pairs within [TAUNT_FLARE_RANGE] blocks. Like the A-10's taunt it first opens the distance so the pass is a
     * straight run. It fires no guns ([tick] passes `mayFire = false`).
     */
    private fun tickTaunt(target: Entity) {
        val ground = max(target.y, guard.terrainAt(target.x, target.z, target.y))
        val altitudeError = ground + TAUNT_PASS_HEIGHT - boss.y
        val tilt = (altitudeError * 0.1).coerceIn(-6.0, 10.0)
        val distance = FlightMath.horizontalDistance(boss.position(), target.position())
        boss.thought.think("tickTaunt") { "taunt flyby over ${target.name.string}: ${tauntPhase.name.lowercase()}" }

        if (stateTicks > TAUNT_TIMEOUT) {
            enter(Ac130State.INGRESS, why = "taunt timed out")
            return
        }

        when (tauntPhase) {
            TauntPhase.SETUP -> {
                if (distance < TAUNT_RUN_IN_DISTANCE) {
                    fly(FlightMath.tilted(awayFrom(target), tilt), 2.4f)
                } else {
                    tauntPhase = TauntPhase.RUN
                }
            }

            TauntPhase.RUN -> {
                val toTarget = towards(target.position())
                val heading = currentHeading()
                val ahead = (target.x - boss.x) * heading.x + (target.z - boss.z) * heading.z
                if (!tauntPassed && ahead < 0.0 && distance < TAUNT_PASSED_DISTANCE) tauntPassed = true

                // After the pass keep going straight; turning back to the target would end the flyby in a circle.
                fly(FlightMath.tilted(if (tauntPassed) heading else toTarget, tilt), 2.2f, maxBank = TAUNT_MAX_BANK)

                val now = boss.tickCount
                if (distance < TAUNT_FLARE_RANGE && flaresDropped < TAUNT_MAX_FLARES && now >= nextTauntFlareTick &&
                    !boss.recentlyDamaged(TAUNT_NO_FLARE_TICKS)
                ) {
                    if (flaresDropped == 0) boss.thought.event("tickTaunt") { "dropping angel flares" }
                    releaseFlare()
                    flaresDropped++
                    // Two bursts back to back, then a gap: a pair every TAUNT_PAIR_INTERVAL ticks.
                    nextTauntFlareTick = now + if (flaresDropped % 2 == 1) 1 else TAUNT_PAIR_INTERVAL - 1
                }

                if (tauntPassed) enter(Ac130State.INGRESS, why = "flyby done")
            }
        }
    }

    // ================================================================================================== evade

    private fun tickEvade() {
        // Break 90 degrees off the missile's bearing, nose slightly down if there is height to spare.
        val bearing = FlightMath.horizontal(threats.threatBearing ?: currentHeading(), currentHeading())
        val sideways = Vec3(-bearing.z * evadeSide, 0.0, bearing.x * evadeSide)
        val spare = boss.y - guard.floorAhead(referenceY() + UNKNOWN_TERRAIN_OFFSET)
        val pitch = if (spare > 120.0) -6.0 else 3.0
        boss.thought.think("tickEvade") { "breaking away from a missile, will resume $resumeState" }
        fly(FlightMath.tilted(sideways, pitch), 3.0f, maxBank = EVADE_MAX_BANK)

        if (stateTicks >= evadeDuration) {
            val ticks = resumeTicks
            enter(resumeState, resume = true, why = "evade over")
            stateTicks = ticks
        }
    }

    // ================================================================================================== terrain guard

    /**
     * Runs after the state logic and overrides the controls when ground clearance runs out. The flown states already
     * steer through [TerrainGuard.adjustDesired]; this catches what that cannot: the orbit and the loiters (flown by
     * SBW's autopilot, which has its own avoidance and is only overridden when it is losing, with a latch).
     */
    private fun safetyNet(target: Entity?) {
        if (state == Ac130State.CRIPPLED) return

        val v = boss.deltaMovement
        val descent = max(0.0, -v.y) * GUARD_DESCENT_FACTOR
        val floor = guard.floorAhead(referenceY(target) + UNKNOWN_TERRAIN_OFFSET)
        val clearance = boss.y - floor
        val buffer = GUARD_BASE_BUFFER + descent

        boss.thought.think("safetyNet") {
            if (clearance < buffer) "terrain safety net pulling up" else "terrain safety net idle"
        }
        if (clearance < buffer) {
            val urgency = (1.0 - clearance / buffer).coerceIn(0.0, 1.0)
            val detour = target?.let {
                val offset = FlightMath.horizontal(
                    Vec3(boss.x - it.x, 0.0, boss.z - it.z),
                    currentHeading()
                )
                // Keep the pylon turn moving sideways around a tall structure instead of climbing straight into it.
                Vec3(offset.z, 0.0, -offset.x)
            } ?: currentHeading()
            flight.steer(
                FlightMath.tilted(detour, 18.0 + 22.0 * urgency),
                (2.6 + 0.4 * urgency).toFloat(),
                maxBank = MAX_BANK
            )
        }
    }

    private companion object {
        const val TAG_STATE = "State"

        const val MSG_WITHDRAW = "message.${SuperbEnemies.MOD_ID}.ac130_boss.withdraw"
        const val MSG_HUNTING = "message.${SuperbEnemies.MOD_ID}.ac130_boss.hunting"

        // Orbit
        const val MIN_RADIUS_FACTOR = 0.45
        /** Keep the gun-suggested standoff distance; tightening it made the heavy gunship cut through targets. */
        const val ORBIT_TIGHTEN = 1.0
        const val MAX_RADIUS_FACTOR = 1.8
        const val RADIUS_ADAPT_INTERVAL = 5
        const val RADIUS_GAIN = 0.1
        const val CENTRE_LERP = 0.2
        const val CENTRE_SNAP_FACTOR = 0.4
        const val ORBIT_LOSE_FACTOR = 3.0
        const val INGRESS_CIRCLE_FACTOR = 1.8
        const val INGRESS_THROTTLE = 2.8f

        // Banking (degrees); the vehicle data allows 65
        const val MAX_BANK = 65f
        const val EVADE_MAX_BANK = 75f
        const val TAUNT_MAX_BANK = 45f
        const val INGRESS_ARRIVAL_MARGIN = 20.0
        const val INGRESS_TIMEOUT = 1500
        const val ALTITUDE_RECOVERY = 40.0
        const val ORBIT_THROTTLE = 1.8
        const val ORBIT_THROTTLE_MIN = 1.6
        const val ORBIT_THROTTLE_MAX = 2.8
        const val PYLON_RADIAL_GAIN = 0.01
        const val PYLON_MAX_RADIAL = 0.65
        const val PYLON_PITCH_GAIN = 0.08
        const val PYLON_MAX_UP = 12.0
        const val PYLON_MAX_DOWN = 6.0
        const val PYLON_MAX_BANK = 28f

        // Fire patterns: frequent rolls keep the gunship changing attack pressure while it circles.
        const val MODE_FIRST_ROLL_MIN = 60
        const val MODE_FIRST_ROLL_MAX = 120
        const val MODE_ROLL_MIN = 100
        const val MODE_ROLL_MAX = 180
        const val SPLIT_MIN = 160
        const val SPLIT_MAX = 240
        const val FOCUS_MIN = 120
        const val FOCUS_MAX = 180
        const val SPLIT_CLUSTER_RADIUS = 70.0
        const val SPLIT_REFRESH_TICKS = 60
        const val SPLIT_SPOT_RADIUS = 14.0
        const val SPLIT_STANDOFF_FACTOR = 1.45
        const val FOCUS_SCATTER_SCALE = 0.35

        // Taunt
        const val TAUNT_ROLL_MIN = 400
        const val TAUNT_ROLL_MAX = 600
        const val TAUNT_WEIGHT = 6
        const val ORBIT_CONTINUE_WEIGHT = 30
        const val TAUNT_PASS_HEIGHT = 50.0
        const val TAUNT_RUN_IN_DISTANCE = 250.0
        const val TAUNT_PASSED_DISTANCE = 200.0
        const val TAUNT_FLARE_RANGE = 90.0
        const val TAUNT_PAIR_INTERVAL = 4
        const val TAUNT_MAX_FLARES = 16
        const val TAUNT_NO_FLARE_TICKS = 200
        const val TAUNT_TIMEOUT = 700

        // Missile breaks are short enough for the side guns to keep engaging while the gunship jinks.
        const val EVADE_MIN = 20
        const val EVADE_MAX = 28

        // Resupply (the shared values are in AirResupply)
        const val RESUPPLY_CLEARANCE_HALF_WIDTH = 16.0
        const val RESUPPLY_MAX_CLIMB = 16.0

        // Terrain guard
        /** Unloaded chunks are beyond the active fight area; do not treat them as phantom skyscrapers. */
        const val UNKNOWN_TERRAIN_OFFSET = -10.0
        const val GUARD_BASE_BUFFER = 12.0
        const val GUARD_DESCENT_FACTOR = 14.0
    }
}
