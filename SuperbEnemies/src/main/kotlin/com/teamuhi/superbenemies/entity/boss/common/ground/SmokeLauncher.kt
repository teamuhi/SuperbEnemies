package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.config.TankSettings

/**
 * Smoke is a command to Superb Warfare's own dispenser (`decoyInputDown`): eight smoke grenades in an arc around the
 * turret's direction, so it is popped while the turret faces the threat. It is rate-limited here ([SMOKE_COOLDOWN]) and,
 * with `limitedAmmo`, counted by the vehicle's own decoy count (`smokeCharges`) with its automatic reload blocked ([holdReload]).
 * One instance per brain.
 */
class SmokeLauncher(
    private val boss: TankBossEntity,
    private val settings: TankSettings
) {

    private var nextSmokeTick = 0

    /**
     * Pops a smoke salvo: Superb Warfare's dispenser throws eight grenades in an arc around the turret's direction when
     * `decoyInputDown` is set before its tick. Limited by [SMOKE_COOLDOWN], `smokeEnabled` and, with `limitedAmmo`, the
     * charges left. @return true if the command was given
     */
    fun pop(): Boolean {
        if (!settings.smokeEnabled.get() || boss.isWreck || boss.crippled) {
            boss.thought.think("smoke.pop") { "no smoke: off in the config or wrecked" }
            return false
        }
        val now = boss.tickCount
        if (now < nextSmokeTick) {
            boss.thought.think("smoke.pop") { "no smoke: dispenser cooling down" }
            return false
        }
        if (boss.decoyCount < 1) {
            if (settings.limitedAmmo.get()) {
                boss.thought.think("smoke.pop") { "no smoke: out of charges" }
                return false
            }
            boss.decoyCount = boss.computed().decoyMagazineSize
        }
        boss.decoyInputDown = true
        nextSmokeTick = now + SMOKE_COOLDOWN
        boss.thought.event("smoke.pop") { "popping smoke" + if (settings.limitedAmmo.get()) " (${boss.decoyCount} charges)" else "" }
        return true
    }

    /** With finite ammo the vehicle's own smoke reload (one charge every 25 seconds) is held off; a resupply is the only refill. */
    fun holdReload() {
        if (settings.limitedAmmo.get() && boss.decoyCount <= 0) boss.decoyReloadCoolDown = boss.getDecoyReloadTime()
    }

    private companion object {
        const val SMOKE_COOLDOWN = 200
    }
}
