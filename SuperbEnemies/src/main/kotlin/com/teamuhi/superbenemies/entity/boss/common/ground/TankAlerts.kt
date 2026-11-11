package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedBosses
import com.teamuhi.superbenemies.entity.boss.common.ai.isTeammate
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.IEventBus

/**
 * The call-out of a tank under attack: where the enemy is, for every teammate tank to hear.
 *
 * A tank that was hit by something other than a teammate ([TankBossBrain] decides) files a report here every
 * [TankBossBrain] share interval while the attack goes on. A report holds the place of the enemy (the attacker's real
 * position when the attacker is known, else the best estimate the victim has), the attacker's entity id and where the victim
 * stands. Every tank that lists the victim as a teammate ([isTeammate], a one-sided relation like the rest) and is within
 * [RANGE] blocks of it finds the report with [alertFor]: it adopts the enemy as its target (see `TankTargeting.inform`), shoots
 * at the spot the victim shoots back at ([ReturnFire.join]) and drives to a spot beside the victim so that the teammates end up
 * close together, facing the enemy ([rankOf] spreads them).
 * A report lapses [HOLD_TICKS] ticks after it was last refreshed, or when the victim is gone, crippled or away rearming.
 *
 * Nothing is saved: a loaded world starts without reports. Server thread only.
 */
object TankAlerts {

    /** Tanks farther than this (blocks, horizontally) from the victim do not hear its call. */
    const val RANGE = 400.0

    /** Ticks a report stays valid after the victim last refreshed it. */
    const val HOLD_TICKS = 400L

    /**
     * One tank's call: [enemyId] is the attacker (or the target it sees), [enemyPos] where it is. [firePoint] is the exact spot
     * the victim is shooting back at while its return fire runs (null when it is not), so that the teammates ([ReturnFire.join])
     * put their shells on the same place. [located] is false when nothing told the victim where the shot came from: [enemyPos]
     * is then only the way it faces, good for turning toward but not for shooting at.
     */
    class Alert(val victimId: Int) {
        var enemyId = -1
        var enemyPos: Vec3 = Vec3.ZERO
        var firePoint: Vec3? = null
        var located = true
        var victimPos: Vec3 = Vec3.ZERO
        var stamp = 0L
    }

    private class LevelState {
        val alerts = HashMap<Int, Alert>()
    }

    private val levels = HashMap<ResourceKey<Level>, LevelState>()

    fun register(bus: IEventBus) {
        bus.addListener<ServerStoppedEvent> { levels.clear() }
    }

    /**
     * Files (or refreshes) the call of [victim]: the enemy [enemy] (null when unknown) stands at [position] ([located] false: that
     * is a guess), and [firePoint] is the spot the victim shoots back at, if it does.
     */
    fun report(victim: TankBossEntity, enemy: Entity?, position: Vec3, firePoint: Vec3?, located: Boolean) {
        val level = victim.level() as? ServerLevel ?: return
        val state = levels.getOrPut(level.dimension()) { LevelState() }
        if (victim.id !in state.alerts) {
            victim.thought.event("alerts.report") { "filed a call for help about ${enemy?.name?.string ?: "an unseen enemy"}" + if (located) "" else " (position guessed)" }
        }
        val alert = state.alerts.getOrPut(victim.id) { Alert(victim.id) }
        alert.enemyId = enemy?.id ?: -1
        alert.enemyPos = position
        alert.firePoint = firePoint
        alert.located = located
        alert.victimPos = victim.position()
        alert.stamp = level.gameTime
    }

    /** Takes back the call of [victim] (it is gone, or the fight is over). */
    fun clear(victim: TankBossEntity) {
        val level = victim.level() as? ServerLevel ?: return
        if (levels[level.dimension()]?.alerts?.remove(victim.id) != null) victim.thought.event("alerts.clear") { "took back its call for help" }
    }

    /**
     * The freshest report of a teammate of [tank] that [tank] can hear, or null. Reports of victims that are gone, crippled,
     * away rearming or out of [RANGE] are skipped; stale ones are dropped.
     */
    fun alertFor(tank: TankBossEntity): Alert? {
        val level = tank.level() as? ServerLevel ?: return null
        val state = levels[level.dimension()] ?: return null
        if (state.alerts.isEmpty()) return null
        val now = level.gameTime
        var best: Alert? = null
        val iterator = state.alerts.values.iterator()
        while (iterator.hasNext()) {
            val alert = iterator.next()
            if (now < alert.stamp || now - alert.stamp > HOLD_TICKS) {
                iterator.remove()
                continue
            }
            if (alert.victimId == tank.id) continue
            val victim = level.getEntity(alert.victimId) as? TankBossEntity
            if (victim == null || !victim.isAlive || victim.isRemoved || victim.crippled || victim.brain.withdrawn) {
                iterator.remove()
                continue
            }
            if (!isTeammate(tank, victim)) continue
            if (FlightMath.horizontalDistance(tank.position(), victim.position()) > RANGE) continue
            if (best == null || alert.stamp > best.stamp) best = alert
        }
        return best
    }

    /**
     * The place of [tank] among the tanks that heard [alert], 0 and up in order of entity id. It decides which spot beside
     * the victim the tank takes, so that the teammates do not all drive to the same point.
     */
    fun rankOf(tank: TankBossEntity, alert: Alert): Int {
        val level = tank.level() as? ServerLevel ?: return 0
        val victim = level.getEntity(alert.victimId) as? TankBossEntity ?: return 0
        var rank = 0
        for (entity in TrackedBosses.all(level)) {
            if (entity !is TankBossEntity || entity === tank || entity === victim || entity.id > tank.id) continue
            if (!entity.isAlive || entity.isRemoved || entity.crippled || entity.brain.withdrawn) continue
            if (isTeammate(entity, victim)) rank++
        }
        return rank
    }
}
