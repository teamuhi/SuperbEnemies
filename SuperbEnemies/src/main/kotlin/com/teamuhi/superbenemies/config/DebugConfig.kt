package com.teamuhi.superbenemies.config

import net.minecraftforge.common.ForgeConfigSpec

/**
 * Debug options, written to `config/superbenemies-debug-common.toml`. It is a common config because the AI runs on the
 * server: with [AI_THINKING] on, every AI in the mod prints what it is doing to the console (see `AiThought`). Every
 * option is read live, so none needs a restart.
 */
object DebugConfig {
    val SPEC: ForgeConfigSpec

    val AI_THINKING: ForgeConfigSpec.BooleanValue
    val HEARTBEAT_SECONDS: ForgeConfigSpec.IntValue

    val SHOW_ON_SCREEN: ForgeConfigSpec.BooleanValue
    val SCREEN_RANGE: ForgeConfigSpec.IntValue
    val SCREEN_X: ForgeConfigSpec.IntValue
    val SCREEN_Y: ForgeConfigSpec.IntValue
    val SCREEN_SCALE: ForgeConfigSpec.IntValue
    val SCREEN_MAX_WIDTH: ForgeConfigSpec.IntValue
    val SHOW_VISION: ForgeConfigSpec.BooleanValue

    val THINK_A10: ForgeConfigSpec.BooleanValue
    val THINK_AC130: ForgeConfigSpec.BooleanValue
    val THINK_LAV25: ForgeConfigSpec.BooleanValue
    val THINK_M1A2: ForgeConfigSpec.BooleanValue
    val THINK_BRADLEY: ForgeConfigSpec.BooleanValue
    val THINK_ZTZ99A: ForgeConfigSpec.BooleanValue
    val THINK_DRONE: ForgeConfigSpec.BooleanValue
    val THINK_DUMMY_SOLDIER: ForgeConfigSpec.BooleanValue
    val THINK_AT_SOLDIER: ForgeConfigSpec.BooleanValue
    val THINK_PLAYER_DUMMY: ForgeConfigSpec.BooleanValue

    /** The per-AI filters (every `think*` option) in file order; they only apply while [AI_THINKING] is on. */
    val FILTERS: List<ForgeConfigSpec.BooleanValue>

    /** Every option in file order (grouped by section); the in-game config screen is built from this. */
    val ALL: List<ForgeConfigSpec.ConfigValue<*>>

    /** The options the in-game config screen edits with a slider, and the range of each: the overlay box position, in percent of the free screen space. */
    val SLIDERS: Map<ForgeConfigSpec.ConfigValue<*>, IntRange>

    init {
        val b = ForgeConfigSpec.Builder()

        b.comment("AI thinking: print what every AI in the mod is doing to the console").push("ai_thinking")

        AI_THINKING = b
            .comment(
                "Master switch. Every AI prints a console line (prefixed [AI]) when its behaviour, state or decision changes,",
                "and for one-off events such as a state change, a shot or a route found. Off by default: with it off nothing is",
                "built or printed. Turning it off and on again reprints the current thoughts. Read live."
            )
            .define("aiThinking", false)

        HEARTBEAT_SECONDS = b
            .comment(
                "Every this many seconds each AI also prints a status snapshot (state, target, health, ammo, position),",
                "so a quiet AI still shows what it is up to. 0 = no snapshots. Needs the master switch. Read live."
            )
            .defineInRange("heartbeatSeconds", 5, 0, 300)

        b.pop()

        b.comment("On-screen log: show the thinking lines in a small box on the screen of nearby players").push("ai_thinking_overlay")

        SHOW_ON_SCREEN = b
            .comment(
                "With aiThinking on, also send the thinking lines to players near the AI and show them in a small box on their",
                "screen (top left by default, like the subtitles; see screenX / screenY). Heartbeat lines are not sent. Read live."
            )
            .define("showOnScreen", true)

        SCREEN_RANGE = b
            .comment("Players within this many blocks of an AI (same dimension) get its lines. Read live.")
            .defineInRange("screenRange", 128, 16, 1024)

        SCREEN_X = b
            .comment(
                "Horizontal position of the on-screen log box, in percent of the free space: 0 = flush left, 100 = flush right.",
                "The box never leaves the screen. Client side: read live from each player's own copy of this file."
            )
            .defineInRange("screenX", 0, 0, 100)

        SCREEN_Y = b
            .comment(
                "Vertical position of the on-screen log box, in percent of the free space: 0 = top, 100 = bottom.",
                "The box never leaves the screen. Client side: read live from each player's own copy of this file."
            )
            .defineInRange("screenY", 0, 0, 100)

        SCREEN_SCALE = b
            .comment(
                "Text size of the on-screen log box in percent (100 = the normal GUI text size).",
                "Client side: read live from each player's own copy of this file."
            )
            .defineInRange("screenScale", 75, 25, 300)

        SCREEN_MAX_WIDTH = b
            .comment(
                "Widest the on-screen log box may get, in screen pixels; longer lines are cut with an ellipsis. It is also",
                "limited to the screen width. Client side: read live from each player's own copy of this file."
            )
            .defineInRange("screenMaxWidth", 340, 100, 1000)

        SHOW_VISION = b
            .comment(
                "With aiThinking and showOnScreen on, also draw what every boss sees in the world: a line from the boss to its",
                "target (green = seen, yellow = out of sight but still sensed during the sight-loss grace, red = hidden, aiming",
                "at the last known spot, with a cross there), the tank's sight cone, and a label naming the target and its",
                "category. Shown to players within screenRange. Also adds a 'vision' channel to the log. Read live."
            )
            .define("showVision", true)

        b.pop()

        b.comment("AI thinking filters: which AIs print when the master switch is on").push("ai_thinking_filters")

        THINK_A10 = filter(b, "thinkA10", "the A-10 Warthog boss")
        THINK_AC130 = filter(b, "thinkAc130", "the AC-130 boss")
        THINK_LAV25 = filter(b, "thinkLav25", "the LAV-25 boss")
        THINK_M1A2 = filter(b, "thinkM1a2", "the M1A2 boss")
        THINK_BRADLEY = filter(b, "thinkBradley", "the M2 Bradley boss")
        THINK_ZTZ99A = filter(b, "thinkZtz99a", "the ZTZ-99A boss")
        THINK_DRONE = filter(b, "thinkDrone", "the drone")
        THINK_DUMMY_SOLDIER = filter(b, "thinkDummySoldier", "the dummy (Igla) soldier")
        THINK_AT_SOLDIER = filter(b, "thinkAtSoldier", "the anti-tank soldier (Javelin / RPG) and the launcher soldier")
        THINK_PLAYER_DUMMY = filter(b, "thinkPlayerDummy", "the player dummy")

        b.pop()
        SPEC = b.build()

        FILTERS = listOf(
            THINK_A10, THINK_AC130, THINK_LAV25, THINK_M1A2, THINK_BRADLEY, THINK_ZTZ99A,
            THINK_DRONE, THINK_DUMMY_SOLDIER, THINK_AT_SOLDIER, THINK_PLAYER_DUMMY
        )
        ALL = listOf<ForgeConfigSpec.ConfigValue<*>>(
            AI_THINKING, HEARTBEAT_SECONDS, SHOW_ON_SCREEN, SCREEN_RANGE, SCREEN_X, SCREEN_Y, SCREEN_SCALE, SCREEN_MAX_WIDTH, SHOW_VISION
        ) + FILTERS
        SLIDERS = mapOf<ForgeConfigSpec.ConfigValue<*>, IntRange>(SCREEN_X to 0..100, SCREEN_Y to 0..100)
    }

    private fun filter(b: ForgeConfigSpec.Builder, key: String, who: String): ForgeConfigSpec.BooleanValue = b
        .comment("Print the thinking of $who. Only applies while aiThinking is on. Read live.")
        .define(key, true)
}
