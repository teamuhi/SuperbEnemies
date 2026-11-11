package com.teamuhi.superbenemies.entity.soldier

import com.teamuhi.superbenemies.debug.AiThought
import net.minecraft.nbt.CompoundTag

/**
 * The ammo options a [LauncherAmmo] reads, so one state machine serves every launcher soldier, each with its own config
 * file. Every getter reads the config live (nothing is cached).
 */
interface AmmoSettings {
    /** Every reload is free and the soldier never runs dry. */
    fun unlimited(): Boolean

    /** Rounds carried, including the one in the launcher. */
    fun capacity(): Int

    /** Ticks it takes to load the next round after one was fired. */
    fun reloadTicks(): Int

    /** Seconds after the last round is gone until the soldier is resupplied with a full load; 0 = never. */
    fun resupplySeconds(): Int
}

/**
 * The ammo of a one-shot launcher soldier: the round in the launcher ([loaded], 0 or 1), the rest ([reserve]), the reload
 * timer and the resupply countdown after the last round is gone. The settings are asked for on every use (a lambda), so a
 * soldier whose settings depend on its weapon can build this before its own fields exist.
 *
 * Timers are absolute ticks of the owner's `tickCount` (reset by a reload of the chunk); only the counts and the
 * remaining resupply time are saved ([save], [load]).
 */
class LauncherAmmo(private val settings: () -> AmmoSettings, private val thought: () -> AiThought? = { null }) {

    /** The round in the launcher (0 or 1). */
    var loaded = 0
        private set

    /** Rounds carried besides the loaded one. */
    var reserve = 0
        private set

    /** False until [init] ran or a save was [load]ed. */
    var ready = false
        private set

    /** Tick at which the next round is loaded, or [NONE] when no reload is running. */
    private var reloadUntil = NONE

    /** Tick at which the empty soldier is resupplied, or [NONE]. */
    private var resupplyAt = NONE

    /** First ammo setup: the capacity counts the loaded round. */
    fun init() {
        ready = true
        loaded = 1
        reserve = (settings().capacity() - 1).coerceAtLeast(0)
        reloadUntil = NONE
        resupplyAt = NONE
    }

    /** True when a round is loaded or can still be loaded (unlimited ammo, reserve), or one is [inFlight]. */
    fun hasAny(inFlight: Boolean): Boolean = loaded > 0 || reserve > 0 || inFlight || settings().unlimited()

    /** The launcher was fired: it is empty until the reload is done. */
    fun consume() {
        loaded = 0
    }

    /**
     * Reload timer, resupply timer, and clamping when the config shrank the capacity. [inFlight] is true while a round the
     * soldier still has to guide is flying: nothing is reloaded then.
     */
    fun tick(now: Int, inFlight: Boolean) {
        if (!ready) init()
        val config = settings()
        val unlimited = config.unlimited()

        if (!unlimited && now % CLAMP_INTERVAL == 0) {
            val cap = config.capacity()
            if (loaded > cap) loaded = cap
            if (loaded + reserve > cap) reserve = (cap - loaded).coerceAtLeast(0)
        }

        if (loaded > 0 || inFlight) {
            reloadUntil = NONE
            resupplyAt = NONE
            return
        }

        if (unlimited || reserve > 0) {
            resupplyAt = NONE
            if (reloadUntil == NONE) {
                reloadUntil = now + config.reloadTicks()
                thought()?.event("reload") {
                    "launcher empty, reloading (${if (unlimited) "unlimited ammo" else "$reserve in reserve"}, ${config.reloadTicks()} ticks)"
                }
            }
            if (now >= reloadUntil) {
                loaded = 1
                if (!unlimited) reserve--
                reloadUntil = NONE
                thought()?.event("reload") { "reloaded, ${if (unlimited) "unlimited ammo" else "$reserve left in reserve"}" }
            }
            return
        }

        // Completely empty: resupply after the configured delay (0 = never).
        reloadUntil = NONE
        val seconds = config.resupplySeconds()
        if (seconds <= 0) {
            thought()?.think("resupply") { "out of rounds, resupply is switched off" }
            resupplyAt = NONE
        } else if (resupplyAt == NONE) {
            resupplyAt = now + seconds * 20
            thought()?.event("resupply") { "out of rounds, resupply in $seconds s" }
        } else if (now >= resupplyAt) {
            reserve = config.capacity() // the reload above puts the first one in the launcher
            resupplyAt = NONE
            thought()?.event("resupply") { "resupplied, ${config.capacity()} rounds" }
        }
    }

    /** Writes the counts and the time left until the resupply ([now] is the owner's tick count). Nothing before [ready]. */
    fun save(tag: CompoundTag, now: Int) {
        if (!ready) return
        tag.putInt(TAG_LOADED, loaded)
        tag.putInt(TAG_RESERVE, reserve)
        tag.putInt(TAG_RESUPPLY_IN, if (resupplyAt == NONE) NONE else (resupplyAt - now).coerceAtLeast(0))
    }

    /** Reads what [save] wrote (a tag without the counts leaves the ammo untouched, so the first tick sets it up). */
    fun load(tag: CompoundTag) {
        if (!tag.contains(TAG_LOADED)) return
        loaded = tag.getInt(TAG_LOADED).coerceIn(0, 1)
        reserve = tag.getInt(TAG_RESERVE).coerceAtLeast(0)
        val resupplyIn = tag.getInt(TAG_RESUPPLY_IN)
        resupplyAt = if (resupplyIn >= 0) resupplyIn else NONE // the tick count of a freshly loaded entity is 0
        ready = true
    }

    private companion object {
        // The same keys the Igla soldier always used, so existing soldiers keep their ammo.
        const val TAG_LOADED = "MissileLoaded"
        const val TAG_RESERVE = "MissileReserve"
        const val TAG_RESUPPLY_IN = "ResupplyIn"

        const val NONE = -1
        const val CLAMP_INTERVAL = 20
    }
}
