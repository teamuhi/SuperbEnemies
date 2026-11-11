package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath.horizontalDistance
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.max

/**
 * Team call ([TankAlerts]): a tank that is under attack files the position of the enemy every [ALERT_SHARE_INTERVAL] ticks
 * ([share]). Every tank that lists it as a teammate adopts that enemy as its target (even from beyond its own aggro range,
 * [poll]), shells the reported spot, and while it sees no target of its own drives to a spot beside the attacked tank, facing
 * the enemy, so the teammates end up close together ([tick]). One instance per brain.
 *
 * @param facingTolerance the heading error (degrees) under which the hull counts as facing the enemy
 * @param isWithdrawn whether the boss is away rearming; it neither calls nor answers then
 */
class TeamRally(
    private val boss: TankBossEntity,
    private val settings: TankSettings,
    private val targeting: TankTargeting,
    private val returnFire: ReturnFire,
    private val driver: GroundDriver,
    private val moves: GroundMoves,
    private val facingTolerance: Float,
    private val isWithdrawn: () -> Boolean
) {

    /** The call of a teammate under attack that this tank hears, refreshed every [ALERT_POLL_INTERVAL] ticks; null when there is none. */
    private var alert: TankAlerts.Alert? = null
    private var nextAlertPoll = 0
    private var nextAlertShare = 0

    /** The spot beside the attacked teammate this tank drives to, and when it is picked again. */
    private var rallyPoint: Vec3? = null
    private var rallyRepick = 0
    private var rallyHolding = false
    private var rallyStalled = 0
    private var rallyGiveUpUntil = 0

    /** Every [ALERT_POLL_INTERVAL] ticks: hears the call of a teammate under attack and takes the enemy it names as its target. */
    fun poll(level: ServerLevel, now: Int) {
        if (now < nextAlertPoll) return
        nextAlertPoll = now + ALERT_POLL_INTERVAL
        val heard = if (boss.isWreck || isWithdrawn()) null else TankAlerts.alertFor(boss)
        alert = heard
        if (heard == null) {
            rallyPoint = null
            rallyHolding = false
            boss.thought.think("rally.poll") { "no teammate is calling for help" }
            return
        }
        val enemy = level.getEntity(heard.enemyId)?.takeIf { it.isAlive && !it.isRemoved }
        boss.thought.think("rally.poll") { "a teammate is under attack by ${enemy?.name?.string ?: "an unseen enemy"}" }
        if (enemy != null) targeting.inform(enemy, heard.enemyPos, ALERT_INFORM_TICKS)
        joinFire(level, heard, enemy)
    }

    /**
     * Shoots at the spot the attacked teammate shoots back at, while its call is fresh: [TankAlerts.Alert.firePoint], else the
     * place it reported for the enemy when that is a real fix. A call that is no longer refreshed (the hits stopped) is not
     * shot at, only driven to.
     */
    private fun joinFire(level: ServerLevel, heard: TankAlerts.Alert, enemy: Entity?) {
        if (level.gameTime - heard.stamp > ALERT_FIRE_TICKS) return
        val spot = heard.firePoint ?: if (heard.located) heard.enemyPos.add(0.0, ReturnFire.HEIGHT, 0.0) else return
        returnFire.join(spot, enemy)
    }

    /** The next [share] files the call at once (the tank was just hit), instead of waiting for its interval. */
    fun shareNow() {
        nextAlertShare = 0
    }

    /**
     * Under attack (hit by something that is no teammate within [ALERT_HOLD_TICKS] ticks): files the call for the teammates
     * every [ALERT_SHARE_INTERVAL] ticks (at once on a hit, see [shareNow]). The position is where the enemy really is: its
     * vehicle when the [attacker] is known, else the target the guns are on while it is in sight, else the best estimate the hit
     * gave ([TankBossEntity.lastHitOrigin]); with none of those the call only says which way the tank faces. When this tank
     * shoots back ([ReturnFire]) the call carries that exact spot too, which the teammates' guns take over, so the whole group
     * fires at one place. The return-fire spot follows the refreshed fix of a known attacker.
     */
    fun share(level: ServerLevel, aim: Entity?, now: Int, attacker: () -> Entity?) {
        if (boss.isWreck || isWithdrawn() || !boss.recentlyDamaged(ALERT_HOLD_TICKS)) {
            boss.thought.think("rally.share") { "not calling: not under attack" }
            return
        }
        if (now < nextAlertShare) return
        nextAlertShare = now + ALERT_SHARE_INTERVAL
        val caller = attacker()?.takeIf { it.isAlive && !it.isRemoved }
        val seen = if (aim != null && !targeting.hidden) aim else null
        val fix = caller?.rootVehicle?.position() ?: seen?.position() ?: boss.lastHitOrigin
        if (caller != null && fix != null) returnFire.follow(caller, fix)
        val where = fix ?: boss.position().add(FlightMath.dir(boss.yRot, 0f).scale(ALERT_GUESS_DISTANCE))
        boss.thought.think("rally.share") {
            "calling teammates: enemy ${(caller ?: seen)?.name?.string ?: "unknown"}" + if (fix != null) " located" else ", position guessed"
        }
        TankAlerts.report(boss, caller ?: seen, where, returnFire.point, located = fix != null)
    }

    /** Where this tank drives to for a teammate's call, for the tanks that avoid its destination. */
    fun destination(): Vec3? = if (alert != null) rallyPoint else null

    /**
     * Answers a teammate's call while no target of its own is in sight: drives to a spot beside the attacked tank (see
     * [pickRally]) and, there, turns toward the reported enemy and waits for it to show. A tank that sees a target does not
     * come here (its state is ENGAGE or FLANK). A spot that cannot be reached for [RALLY_STALL_TICKS] ticks is dropped for
     * [RALLY_GIVE_UP_TICKS] ticks, then the tank goes back to its own search or patrol.
     * @return true when this tick's driving was taken
     */
    fun tick(level: Level): Boolean {
        val heard = alert ?: return false
        val now = boss.tickCount
        if (now < rallyGiveUpUntil) {
            boss.thought.think("rally.tick") { "ignoring the call for a while (the rally spot was unreachable)" }
            return false
        }
        if (targeting.aim != null && !targeting.hidden) return false

        var spot = rallyPoint
        if (spot == null || now >= rallyRepick) {
            rallyRepick = now + RALLY_REPICK_TICKS
            val picked = pickRally(level, heard)
            // A spot that barely moved is kept, so the route and the hold do not restart at every repick.
            if (picked == null || spot == null || horizontalDistance(picked, spot) >= RALLY_KEEP_RADIUS) {
                spot = picked
                rallyPoint = picked
                boss.thought.event("rally.pick") { picked?.let { "rally spot beside the attacked teammate at ${it.x.toInt()},${it.z.toInt()}" } ?: "no rally spot found, acting alone" }
            }
        }
        if (spot == null) return false

        val distance = horizontalDistance(boss.position(), spot)
        if (distance <= RALLY_ARRIVE_RADIUS || (rallyHolding && distance <= RALLY_RESUME_RADIUS)) {
            rallyHolding = true
            rallyStalled = 0
            boss.thought.think("rally.tick") { "at the rally spot, waiting for the enemy to show" }
            if (driver.turnToward(heard.enemyPos, facingTolerance)) driver.hold()
            return true
        }
        rallyHolding = false
        val planner = moves.planner
        planner.request(level, boss.position(), spot, FormationFollow.REPLAN_SHIFT)
        planner.tick(level)
        if (planner.status == GroundRoutePlanner.Status.NO_ROUTE || planner.arrived) {
            if (++rallyStalled > RALLY_STALL_TICKS) {
                rallyStalled = 0
                rallyPoint = null
                rallyGiveUpUntil = now + RALLY_GIVE_UP_TICKS
                boss.thought.event("rally.tick") { "cannot reach the rally spot, acting alone for a while" }
                moves.newGoal()
                return false
            }
        } else if (planner.hasRoute) {
            rallyStalled = 0
        }
        val mode = if (distance > RALLY_SPRINT_DISTANCE) DriveMode.SPRINT else DriveMode.CRUISE
        boss.thought.think("rally.tick") { "driving to the rally spot ($mode)" }
        moves.goTo(level, spot, mode, FormationFollow.REPLAN_SHIFT)
        return true
    }

    /**
     * The spot of this tank beside the attacked teammate: the tanks that heard the call take places in order of entity id
     * ([TankAlerts.rankOf]), alternately to the left and to the right of the victim, seen from the victim toward the enemy,
     * one `formationSpacing` (at least [RALLY_MIN_SPACING] blocks) further out for each pair and a little back, so they stand
     * in a loose line abreast facing the enemy. A spot that is not passable or is crowded is pulled in toward the victim;
     * if the whole side is blocked the other side is tried. Null: no place found (the tank acts alone).
     */
    private fun pickRally(level: Level, heard: TankAlerts.Alert): Vec3? {
        val victim = (level as? ServerLevel)?.getEntity(heard.victimId)?.position() ?: heard.victimPos
        val toEnemy = FlightMath.horizontal(
            Vec3(heard.enemyPos.x - victim.x, 0.0, heard.enemyPos.z - victim.z),
            FlightMath.dir(boss.yRot, 0f)
        )
        val right = Vec3(-toEnemy.z, 0.0, toEnemy.x)
        val rank = TankAlerts.rankOf(boss, heard)
        val spacing = max(RALLY_MIN_SPACING, settings.formationSpacing.get().toDouble())
        val step = (rank / 2 + 1).toDouble()
        val firstSide = if (rank % 2 == 0) 1.0 else -1.0
        for (side in doubleArrayOf(firstSide, -firstSide)) {
            for (scale in RALLY_SCALES) {
                val across = side * step * spacing * scale
                val back = step * spacing * RALLY_BACK * scale
                val point = Vec3(
                    victim.x + right.x * across - toEnemy.x * back,
                    victim.y,
                    victim.z + right.z * across - toEnemy.z * back
                )
                if (moves.passable(level, point) && !moves.crowded(level, point)) return point
            }
        }
        return null
    }

    private companion object {
        const val ALERT_POLL_INTERVAL = 5

        /** A tank hit within this many ticks is still under attack and keeps calling. */
        const val ALERT_HOLD_TICKS = 100

        /** Ticks between two refreshes of the call (the enemy's position moves with it). */
        const val ALERT_SHARE_INTERVAL = 10

        /** A tank that heard the call keeps the named enemy as its target this long without seeing it, in ticks. */
        const val ALERT_INFORM_TICKS = 200

        /** A call older than this (ticks since the victim last refreshed it) is no longer shot at. */
        const val ALERT_FIRE_TICKS = 60L

        /** How far ahead (blocks) a call with no fix on the enemy points: the way the victim faces. */
        const val ALERT_GUESS_DISTANCE = 40.0

        /** Smallest distance (blocks) between the tanks that rally beside the attacked one. */
        const val RALLY_MIN_SPACING = 12.0

        /** How far back (as a fraction of the spacing) each step of the rally line leans, away from the enemy. */
        const val RALLY_BACK = 0.3

        /** The spot is tried at these fractions of the full distance from the victim before the other side is tried. */
        val RALLY_SCALES = doubleArrayOf(1.0, 0.75, 0.5)
        const val RALLY_REPICK_TICKS = 40
        const val RALLY_KEEP_RADIUS = 6.0
        const val RALLY_ARRIVE_RADIUS = 6.0
        const val RALLY_RESUME_RADIUS = 10.0
        const val RALLY_SPRINT_DISTANCE = 50.0
        const val RALLY_STALL_TICKS = 100
        const val RALLY_GIVE_UP_TICKS = 400
    }
}
