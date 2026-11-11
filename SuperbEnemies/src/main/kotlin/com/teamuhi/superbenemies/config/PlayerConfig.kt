package com.teamuhi.superbenemies.config

import net.minecraftforge.common.ForgeConfigSpec

/**
 * Client config of what the player sees, written to `config/superbenemies-player-client.toml`. It only changes this
 * game's screen, so it is a client config: a dedicated server never reads it. Every option is read live.
 */
object PlayerConfig {
    val SPEC: ForgeConfigSpec

    val SHOW_SPOTTED_ICON: ForgeConfigSpec.BooleanValue

    /** Every option in file order (grouped by section); the in-game config screen is built from this. */
    val ALL: List<ForgeConfigSpec.ConfigValue<*>>

    init {
        val b = ForgeConfigSpec.Builder()

        b.comment("Spotted icon: what the screen shows while a tank boss has the player in sight").push("player_spotted")

        SHOW_SPOTTED_ICON = b
            .comment(
                "Show an eye while a tank boss has you in sight as its target: above the tank when it is on screen, and on a",
                "ring around the crosshair, with an arc, pointing toward it when it is not. Read live."
            )
            .define("showSpottedIcon", true)

        b.pop()
        SPEC = b.build()

        ALL = listOf(SHOW_SPOTTED_ICON)
    }
}
