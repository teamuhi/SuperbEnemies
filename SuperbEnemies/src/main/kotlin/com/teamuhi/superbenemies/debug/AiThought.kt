package com.teamuhi.superbenemies.debug

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.BossKind
import com.teamuhi.superbenemies.config.DebugConfig
import com.teamuhi.superbenemies.network.ModNetwork
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraftforge.common.ForgeConfigSpec
import org.apache.logging.log4j.message.ParameterizedMessage

/**
 * The kinds of AI that can print their thinking, one per filter of [DebugConfig]. [label] is the tag in the console line;
 * [enabled] is the master switch and this kind's filter together, both read live.
 */
enum class AiKind(val label: String, private val toggle: () -> ForgeConfigSpec.BooleanValue) {
    A10("A-10", { DebugConfig.THINK_A10 }),
    AC130("AC-130", { DebugConfig.THINK_AC130 }),
    LAV25("LAV-25", { DebugConfig.THINK_LAV25 }),
    M1A2("M1A2", { DebugConfig.THINK_M1A2 }),
    BRADLEY("Bradley", { DebugConfig.THINK_BRADLEY }),
    ZTZ99A("ZTZ-99A", { DebugConfig.THINK_ZTZ99A }),
    DRONE("Drone", { DebugConfig.THINK_DRONE }),
    DUMMY_SOLDIER("Dummy Soldier", { DebugConfig.THINK_DUMMY_SOLDIER }),
    AT_SOLDIER("AT Soldier", { DebugConfig.THINK_AT_SOLDIER }),
    PLAYER_DUMMY("Player Dummy", { DebugConfig.THINK_PLAYER_DUMMY });

    /** True when the master switch and this kind's filter are both on. */
    fun enabled(): Boolean = DebugConfig.AI_THINKING.get() && toggle().get()

    companion object {
        /** The AI kind of the boss vehicle [kind]. */
        fun of(kind: BossKind): AiKind = when (kind) {
            BossKind.A10 -> A10
            BossKind.AC130 -> AC130
            BossKind.LAV25 -> LAV25
            BossKind.M1A2 -> M1A2
            BossKind.BRADLEY -> BRADLEY
            BossKind.ZTZ99A -> ZTZ99A
        }
    }
}

/**
 * The console voice of one AI entity, created once per entity (`val thought`). Every AI function reports through it:
 * [think] for what it is doing right now (printed only when the text changes), [event] for one-off happenings (always
 * printed), [debug] for log4j-style `{}` messages and [heartbeat] for the periodic status snapshot. Nothing is built or
 * printed while [enabled] is false, which is the normal case, so a call costs one config read.
 *
 * With `showOnScreen` on, every line except the heartbeat is also sent to the players near the AI (see `ModNetwork`).
 *
 * Line format: `[AI][M1A2 #412|ENGAGE] tickEngage: backing off, target inside 0.45x range` (kind label, entity id, the
 * AI's own state from [stateOf], the channel, the text).
 *
 * Message rule: a [think] text holds only discrete facts (the decision, the reason, the target's name). Never put a raw
 * continuous number in it (a distance, an angle, a position), or the text changes every tick and floods the console.
 * Round such numbers into buckets, or move them to the [heartbeat] or an [event].
 */
class AiThought(
    private val owner: Entity,
    private val kind: AiKind,
    private val stateOf: () -> String = { "" }
) {
    /** The tag of this AI in the console line and on the vision labels (e.g. `A-10`). */
    val label: String get() = kind.label

    /** Entity id of the target `AiVision` last announced (-1 = none), so only a change prints the `vision.target` event. */
    internal var visionTargetId = -1

    /** The last text printed by [think] on each channel, so an unchanged thought is not printed again. */
    private val last = HashMap<String, String>()

    /** Whether the previous [enabled] check was on; the dedupe memory is cleared when it flips off. */
    private var wasOn = false

    /**
     * True when this entity should print: server side only, master switch on and this kind's filter on. Turning it off
     * clears the dedupe memory, so turning it back on reprints the current thoughts.
     */
    val enabled: Boolean
        get() {
            if (owner.level().isClientSide) return false
            val on = kind.enabled()
            if (on != wasOn) {
                wasOn = on
                if (!on) {
                    last.clear()
                    visionTargetId = -1
                }
            }
            return on
        }

    /** Prints [message] on [channel] (use the calling function's name) only when it differs from the last text on that channel. */
    inline fun think(channel: String, message: () -> String) {
        if (enabled) emitIfChanged(channel, message())
    }

    /** Prints [message] on [channel] every time: for one-off events (state change, shot, hit, route found, target picked). */
    inline fun event(channel: String, message: () -> String) {
        if (enabled) emit(channel, message())
    }

    /** An [event] on the `debug` channel whose [format] uses log4j `{}` placeholders filled from [args]. */
    fun debug(format: String, vararg args: Any?) {
        if (enabled) emit("debug", ParameterizedMessage.format(format, args))
    }

    /**
     * Prints [snapshot] about every `heartbeatSeconds` seconds (never when that is 0). The entity id staggers the entities
     * so they do not all print on the same tick.
     */
    inline fun heartbeat(snapshot: () -> String) {
        if (heartbeatDue()) emit("heartbeat", snapshot())
    }

    /** True on this entity's heartbeat tick. */
    @PublishedApi
    internal fun heartbeatDue(): Boolean {
        if (!enabled) return false
        val seconds = DebugConfig.HEARTBEAT_SECONDS.get()
        return seconds > 0 && (owner.tickCount + owner.id) % (seconds * 20) == 0
    }

    @PublishedApi
    internal fun emitIfChanged(channel: String, text: String) {
        if (last.put(channel, text) == text) return
        emit(channel, text)
    }

    @PublishedApi
    internal fun emit(channel: String, text: String) {
        val state = stateOf()
        SuperbEnemies.LOGGER.info("[AI][{} #{}|{}] {}: {}", kind.label, owner.id, state, channel, text)
        if (channel != "heartbeat" && DebugConfig.SHOW_ON_SCREEN.get()) ModNetwork.sendThought(owner, kind.label, state, channel, text)
    }
}

/** The class name of the goal [mob] is running (the first running one), or an empty string: the state shown for goal-driven mobs. */
fun runningGoalName(mob: Mob): String =
    mob.goalSelector.runningGoals.findFirst().map { it.goal.javaClass.simpleName }.orElse("")
