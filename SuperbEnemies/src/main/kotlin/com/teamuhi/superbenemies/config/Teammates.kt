package com.teamuhi.superbenemies.config

import net.minecraftforge.common.ForgeConfigSpec

/**
 * The six boss vehicles, as the rows of every boss's Teammates table. [key] is the option name in the config files (and
 * the suffix of the `screen.superbenemies.config.option.<key>` label in the lang file).
 */
enum class BossKind(val key: String) {
    A10("a10Warthog"),
    AC130("ac130"),
    LAV25("lav25"),
    M1A2("m1a2"),
    BRADLEY("bradley"),
    ZTZ99A("ztz99a")
}

/**
 * The Teammates table of one boss: one on/off option per [BossKind], read live. A boss never targets a vehicle of a type
 * that is switched on here (see `isTeammate`), and a tank boss may form up with a tank of such a type (see `TankFormations`).
 */
class TeammateSettings(val toggles: Map<BossKind, ForgeConfigSpec.BooleanValue>) {
    /** True when bosses of [kind] are teammates of the boss that owns this table. */
    fun allows(kind: BossKind): Boolean = toggles[kind]?.get() ?: false
}

/**
 * Pairs every teammate toggle with its counterpart in the other boss's table (A's "B" toggle with B's "A" toggle), both
 * ways, so the config screen can keep the two in step. A boss's toggle for its own kind has no counterpart.
 */
fun mirroredToggles(tables: Map<BossKind, TeammateSettings>): Map<ForgeConfigSpec.ConfigValue<*>, ForgeConfigSpec.ConfigValue<*>> {
    val mirrors = HashMap<ForgeConfigSpec.ConfigValue<*>, ForgeConfigSpec.ConfigValue<*>>()
    for ((owner, table) in tables) {
        for ((kind, toggle) in table.toggles) {
            if (kind == owner) continue
            val counterpart = tables[kind]?.toggles?.get(owner) ?: continue
            mirrors[toggle] = counterpart
        }
    }
    return mirrors
}

/**
 * Starts the `<sectionPrefix>_teammates` section of [b] with one option per [BossKind] and returns them as a table. Only
 * [self] is on by default: a boss does not fight its own kind. The section is left open (no `pop`), so a tank config can
 * add its formation options to it before closing it.
 *
 * The relation is one-sided: boss A listing boss B only stops A from targeting B (and A from retaliating against B). It
 * takes both bosses listing each other for the tanks to form up.
 */
fun defineTeammates(b: ForgeConfigSpec.Builder, sectionPrefix: String, self: BossKind): TeammateSettings {
    b.comment(
        "Bosses of the types switched on here are this boss's teammates: they are never targeted (nor retaliated against),",
        "and tanks that list each other may form up. A relation only counts for the boss that lists it."
    ).push("${sectionPrefix}_teammates")
    val toggles = LinkedHashMap<BossKind, ForgeConfigSpec.BooleanValue>()
    for (kind in BossKind.values()) {
        toggles[kind] = b
            .comment("Bosses of this type are teammates: never targeted, and (tanks) may form up together.")
            .define(kind.key, kind == self)
    }
    return TeammateSettings(toggles)
}
