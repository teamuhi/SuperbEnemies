package com.teamuhi.superbenemies.client

import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ContainerObjectSelectionList
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.language.I18n
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraftforge.common.ForgeConfigSpec
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * One config file shown by [BossConfigScreen] as a tab: its [title], its [spec] and the options to list, in file order.
 * A section header is drawn whenever the first path element changes. [requires] maps an option to the on/off option of
 * the same page it depends on; while that toggle is off (as currently edited) the option is greyed out and not editable.
 * [sliders] lists the integer options shown as a slider over the given range instead of a text box (Forge's own range
 * class is private, so the spec cannot be asked); the spec still validates the value.
 */
data class ConfigPage(
    val title: Component,
    val spec: ForgeConfigSpec,
    val values: List<ForgeConfigSpec.ConfigValue<*>>,
    val requires: Map<ForgeConfigSpec.ConfigValue<*>, ForgeConfigSpec.ConfigValue<Boolean>> = emptyMap(),
    val disabledWhenEnabled: Map<ForgeConfigSpec.ConfigValue<*>, ForgeConfigSpec.ConfigValue<Boolean>> = emptyMap(),
    val sliders: Map<ForgeConfigSpec.ConfigValue<*>, IntRange> = emptyMap()
)

/**
 * A set of related [ConfigPage]s (the aircraft, the armoured bosses...), shown as one collapsible heading in the sidebar of
 * [BossConfigScreen]. A new mob is one more page in a group (or a new group): the sidebar scrolls, so it never runs out of room.
 */
data class ConfigGroup(val title: Component, val pages: List<ConfigPage>)

/**
 * In-game editor for the mod's config files, opened from the Mods screen. Forge 1.20.1 has no automatic config screen,
 * so this builds one from the specs: an on/off button per boolean, a cycle button per enum, a slider per small integer
 * range (the 0 to 5 target weights) and a validated text box per other number (range shown in the tooltip).
 *
 * Layout: a scrollable sidebar on the left lists every [ConfigGroup] as a collapsible heading with its pages indented
 * under it; the panel on the right shows the open page (breadcrumb, Reset Tab, search box, option list). The search box
 * looks through every page at once, so an option can be found without knowing which mob it belongs to; opening a page
 * from the sidebar clears it. Each option has a reset button; edited options turn yellow with a dot, pages and groups get
 * a `*` (unsaved) or `!` (invalid) marker and the unsaved count is shown above the buttons. Done is disabled while any
 * value on any page is invalid and saves every spec whose values changed; Cancel and Esc ask before throwing away
 * unsaved edits.
 *
 * The selected page, collapsed groups, search text, scroll positions and drafts are fields, so a window resize (which
 * rebuilds the widgets) keeps them.
 * Edits the local copy of the common config only: on a dedicated server the server's own file is what counts.
 */
class BossConfigScreen(
    private val parent: Screen?,
    private val groups: List<ConfigGroup>,
    /** Options that must always hold the same value (a boss's teammate toggle for another boss and that boss's toggle back). */
    private val mirrors: Map<ForgeConfigSpec.ConfigValue<*>, ForgeConfigSpec.ConfigValue<*>> = emptyMap()
) : Screen(Component.translatable("screen.superbenemies.config.title")) {

    private val pages = groups.flatMap { it.pages }

    /** Index in [pages] of the first page of each group. */
    private val groupStart: IntArray = IntArray(groups.size).also { starts ->
        var next = 0
        for ((index, group) in groups.withIndex()) {
            starts[index] = next
            next += group.pages.size
        }
    }

    private val values = pages.flatMap { it.values }

    /** The spec each option belongs to, so range checks and saves go to the right file. */
    private val pageOf = HashMap<ForgeConfigSpec.ConfigValue<*>, ConfigPage>().also { map ->
        for (page in pages) for (value in page.values) map[value] = page
    }

    /** Text of every option (all tabs), kept across window resizes and tab switches. */
    private val drafts = HashMap<ForgeConfigSpec.ConfigValue<*>, String>().also { map ->
        for (value in values) map[value] = value.get().toString()
    }

    private var selectedTab = 0
    private var search = ""

    /** Groups folded up in the sidebar. */
    private val collapsed = BooleanArray(groups.size)

    /** Per page: has unsaved edits / holds an invalid value. Refreshed by [refreshStatus] so rendering stays cheap. */
    private val pageDirty = BooleanArray(pages.size)
    private val pageInvalid = BooleanArray(pages.size)
    private var unsavedTotal = 0
    private var invalidTotal = 0

    private var list: PanelList? = null
    private var sidebar: PanelList? = null
    private var searchBox: EditBox? = null
    private var doneButton: Button? = null
    private var resetTabButton: Button? = null

    /** Geometry of the two panels, set by [init]. */
    private var sidebarLeft = 0
    private var sidebarRight = 0
    private var contentLeft = 0
    private var contentRight = 0
    private var panelBottom = 0
    private var listTop = LIST_TOP

    /** Tooltip of the option label under the mouse this frame, drawn last so the list's scissor cannot clip it. */
    private var labelTooltip: Tooltip? = null

    override fun init() {
        selectedTab = selectedTab.coerceIn(0, pages.lastIndex)
        val previousScroll = list?.scrollAmount ?: 0.0
        val previousSidebarScroll = sidebar?.scrollAmount ?: 0.0

        val sidebarWidth = (width * SIDEBAR_FRACTION).toInt().coerceIn(SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH)
        sidebarLeft = SIDE_MARGIN
        sidebarRight = sidebarLeft + sidebarWidth
        contentLeft = sidebarRight + PANEL_GAP
        contentRight = width - SIDE_MARGIN
        panelBottom = height - LIST_BOTTOM_MARGIN
        val contentWidth = contentRight - contentLeft
        listTop = SEARCH_Y + LIST_TOP_GAP

        // Content header: Reset Tab on the right (the breadcrumb is drawn on the left), then the search box.
        resetTabButton = addRenderableWidget(
            Button.builder(Component.translatable("screen.superbenemies.config.reset_tab")) { resetTab() }
                .bounds(contentRight - RESET_TAB_WIDTH, HEADER_Y, RESET_TAB_WIDTH, 20).build()
        )
        val box = EditBox(
            font, contentLeft, SEARCH_Y, contentWidth, 20,
            Component.translatable("screen.superbenemies.config.search")
        )
        box.setMaxLength(MAX_SEARCH_LENGTH)
        box.setHint(Component.translatable("screen.superbenemies.config.search").withStyle(ChatFormatting.GRAY))
        box.value = search
        box.setResponder { text ->
            search = text
            populate(0.0)
        }
        searchBox = addRenderableWidget(box)

        // Bottom row: Done / Cancel.
        val buttonWidth = minOf(BUTTON_WIDTH, (width - 2 * SIDE_MARGIN - BUTTON_GAP) / 2)
        val buttonsLeft = width / 2 - (2 * buttonWidth + BUTTON_GAP) / 2
        val buttonsTop = height - BUTTONS_BOTTOM_OFFSET
        doneButton = addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) {
                save()
                leave()
            }.bounds(buttonsLeft, buttonsTop, buttonWidth, 20).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(buttonsLeft + buttonWidth + BUTTON_GAP, buttonsTop, buttonWidth, 20).build()
        )

        val newSidebar = PanelList(sidebarLeft, sidebarWidth, PANEL_TOP, panelBottom, SIDEBAR_ITEM_HEIGHT, sidebarWidth - 14)
        sidebar = newSidebar
        addWidget(newSidebar)
        populateSidebar(previousSidebarScroll)

        val newList = PanelList(
            contentLeft, contentWidth, listTop, panelBottom, ROW_HEIGHT, minOf(ROW_WIDTH, contentWidth - 20)
        )
        list = newList
        addWidget(newList)
        populate(previousScroll)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics)
        labelTooltip = null
        renderPanel(graphics, sidebarLeft, PANEL_TOP, sidebarRight, panelBottom)
        renderPanel(graphics, contentLeft, listTop, contentRight, panelBottom)
        sidebar?.render(graphics, mouseX, mouseY, partialTick)
        list?.render(graphics, mouseX, mouseY, partialTick)
        graphics.drawCenteredString(font, title, width / 2, TITLE_Y, 0xFFFFFF)
        renderBreadcrumb(graphics)
        renderStatusLine(graphics)
        super.render(graphics, mouseX, mouseY, partialTick)
        labelTooltip?.let { graphics.renderTooltip(font, it.toCharSequence(minecraft!!), mouseX, mouseY) }
    }

    /** Translucent dark box with a thin border; reads on both the dirt background and the dimmed in-world one. */
    private fun renderPanel(graphics: GuiGraphics, left: Int, top: Int, right: Int, bottom: Int) {
        graphics.fill(left, top, right, bottom, PANEL_COLOR)
        graphics.renderOutline(left - 1, top - 1, right - left + 2, bottom - top + 2, PANEL_BORDER_COLOR)
    }

    /** "Group > Page" above the search box, or a note while the search box is looking through every page. */
    private fun renderBreadcrumb(graphics: GuiGraphics) {
        val available = contentRight - contentLeft - RESET_TAB_WIDTH - 8
        val y = HEADER_Y + 6
        if (search.isNotBlank()) {
            graphics.drawString(font, fit(Component.translatable("screen.superbenemies.config.searching").string, available), contentLeft, y, GOLD_COLOR)
            return
        }
        val prefix = groups[groupOf(selectedTab)].title.string + BREADCRUMB_SEPARATOR
        val prefixWidth = font.width(prefix)
        graphics.drawString(font, fit(prefix, available), contentLeft, y, MUTED_COLOR)
        if (prefixWidth < available) {
            graphics.drawString(font, fit(pages[selectedTab].title.string, available - prefixWidth), contentLeft + prefixWidth, y, 0xFFFFFF)
        }
    }

    /** Invalid values first (they block Done), then the unsaved count, otherwise the usual note. */
    private fun renderStatusLine(graphics: GuiGraphics) {
        val (text, color) = when {
            invalidTotal > 0 -> Component.translatable("screen.superbenemies.config.invalid", invalidTotal) to ERROR_COLOR
            unsavedTotal > 0 -> unsavedText(unsavedTotal) to GOLD_COLOR
            else -> Component.translatable("screen.superbenemies.config.note") to MUTED_COLOR
        }
        graphics.drawCenteredString(font, text, width / 2, height - STATUS_BOTTOM_OFFSET, color)
    }

    /** [text] cut to [maxWidth] pixels with a trailing ellipsis. */
    private fun fit(text: String, maxWidth: Int): String =
        if (font.width(text) <= maxWidth) text
        else font.plainSubstrByWidth(text, (maxWidth - font.width(ELLIPSIS)).coerceAtLeast(0)) + ELLIPSIS

    /** The [ConfigHotkey] closes the screen again (asking first about unsaved edits), unless the search box is being typed in. */
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (ConfigHotkey.KEY.matches(keyCode, scanCode) && searchBox?.isFocused != true) {
            onClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    /** Esc and Cancel: leave straight away, or ask first when there is something to lose. */
    override fun onClose() {
        val unsaved = unsavedTotal
        if (unsaved == 0) {
            leave()
            return
        }
        // Denying returns to this same instance, so tab, search, scroll and drafts are all still there.
        minecraft?.setScreen(
            ConfirmScreen(
                { discard -> if (discard) leave() else minecraft?.setScreen(this) },
                Component.translatable("screen.superbenemies.config.discard.title"),
                Component.translatable("screen.superbenemies.config.discard.message", unsavedText(unsaved))
            )
        )
    }

    /** Back to the screen that opened this one, without asking. */
    private fun leave() {
        minecraft?.setScreen(parent)
    }

    // ------------------------------------------------------------------------------------------------ pages

    /** The group that holds page [pageIndex]. */
    private fun groupOf(pageIndex: Int): Int {
        var group = 0
        for (index in groupStart.indices) if (groupStart[index] <= pageIndex) group = index
        return group
    }

    /** Indices in [pages] of the pages of [group]. */
    private fun pagesOf(group: Int): IntRange = groupStart[group] until groupStart[group] + groups[group].pages.size

    private fun groupDirty(group: Int): Boolean = pagesOf(group).any { pageDirty[it] }

    private fun groupInvalid(group: Int): Boolean = pagesOf(group).any { pageInvalid[it] }

    /** Opens [index]. Leaving a search behind: the box is cleared, which refills the list for the new page. */
    private fun selectTab(index: Int) {
        if (index == selectedTab && search.isEmpty()) return
        selectedTab = index
        val box = searchBox
        if (search.isNotEmpty() && box != null) {
            box.value = "" // the responder repopulates
        } else {
            populate(0.0)
        }
    }

    private fun toggleGroup(group: Int) {
        collapsed[group] = !collapsed[group]
        populateSidebar(sidebar?.scrollAmount ?: 0.0)
    }

    /** The sidebar: a heading per group and, unless it is folded up, its pages under it. */
    private fun populateSidebar(scroll: Double) {
        val target = sidebar ?: return
        target.clearAll()
        for (group in groups.indices) {
            target.addRow(GroupRow(group))
            if (collapsed[group]) continue
            for (page in pagesOf(group)) target.addRow(PageRow(page))
        }
        target.setScrollAmount(scroll)
    }

    /**
     * Re-evaluates everything that depends on the drafts: the unsaved and invalid counts, the markers of every page and
     * group, Done and Reset Tab. Cheap enough per keystroke; the sidebar and the status line only read the results.
     */
    private fun refreshStatus() {
        unsavedTotal = 0
        invalidTotal = 0
        for ((index, page) in pages.withIndex()) {
            var dirty = 0
            var invalid = 0
            for (value in page.values) {
                val parsed = parse(value, drafts[value] ?: continue)
                if (parsed == null) invalid++
                if (parsed != value.get()) dirty++
            }
            pageDirty[index] = dirty > 0
            pageInvalid[index] = invalid > 0
            unsavedTotal += dirty
            invalidTotal += invalid
        }
        doneButton?.active = invalidTotal == 0
        // While searching the list spans every page, so "this tab" is not what is on screen.
        resetTabButton?.active = search.isBlank() && pages[selectedTab].values.any { !isDefault(it) }
    }

    /** Sets every option of the open page to its default. Not saved until Done. */
    private fun resetTab() {
        for (value in pages[selectedTab].values) setDraft(value, defaultText(value))
        populate(list?.scrollAmount ?: 0.0)
    }

    /** Sets the draft of [value] and of its mirrored option on the other page, if it has one. Does not refresh the rows. */
    private fun setDraft(value: ForgeConfigSpec.ConfigValue<*>, text: String) {
        drafts[value] = text
        mirrors[value]?.let { drafts[it] = text }
    }

    // ------------------------------------------------------------------------------------------------ values

    private fun specOf(value: ForgeConfigSpec.ConfigValue<*>): ForgeConfigSpec.ValueSpec =
        pageOf.getValue(value).spec.spec.get<ForgeConfigSpec.ValueSpec>(value.path)

    /** The typed value for [text], or null when it is not a valid number / is outside the option's range. */
    private fun parse(value: ForgeConfigSpec.ConfigValue<*>, text: String): Any? = when (value.get()) {
        is Boolean -> text.toBooleanStrictOrNull()
        // ValueSpec.test applies the option's own range check.
        is Int -> text.trim().toIntOrNull()?.takeIf { specOf(value).test(it) }
        is Double -> text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && specOf(value).test(it) }
        is Enum<*> -> enumConstants(value).firstOrNull { it.name == text }
        else -> null
    }

    /** Every constant of an enum-valued option, in declaration order. */
    private fun enumConstants(value: ForgeConfigSpec.ConfigValue<*>): List<Enum<*>> =
        (value.get() as Enum<*>).declaringJavaClass.enumConstants.toList()

    /** "PLAYERS_AND_MOBS" -> "Players And Mobs". */
    private fun enumLabel(constant: Enum<*>): String =
        constant.name.lowercase().split('_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    /** The option's default as draft text (enum defaults may be stored as the constant itself). */
    private fun defaultText(value: ForgeConfigSpec.ConfigValue<*>): String =
        when (val default = specOf(value).default) {
            is Enum<*> -> default.name
            else -> default.toString()
        }

    /** True when the draft parses to the default value (so "12" and "12.0" both count for a double default). */
    private fun isDefault(value: ForgeConfigSpec.ConfigValue<*>): Boolean {
        val parsed = parse(value, drafts[value] ?: return true) ?: return false
        return parsed == parse(value, defaultText(value))
    }

    /** True when the draft differs from the value currently in the config (an invalid draft counts as an edit). */
    private fun isDirty(value: ForgeConfigSpec.ConfigValue<*>): Boolean {
        val draft = drafts[value] ?: return false
        return parse(value, draft) != value.get()
    }

    /** False while the toggle this option depends on ([ConfigPage.requires]) is switched off in the drafts. */
    private fun isAvailable(value: ForgeConfigSpec.ConfigValue<*>): Boolean {
        val page = pageOf.getValue(value)
        val requiredToggle = page.requires[value]
        if (requiredToggle != null && !(drafts[requiredToggle]?.toBooleanStrictOrNull() ?: requiredToggle.get())) return false
        val disabledToggle = page.disabledWhenEnabled[value]
        if (disabledToggle != null && (drafts[disabledToggle]?.toBooleanStrictOrNull() ?: disabledToggle.get())) return false
        return true
    }

    private fun unsavedText(count: Int): Component =
        if (count == 1) Component.translatable("screen.superbenemies.config.unsaved_one")
        else Component.translatable("screen.superbenemies.config.unsaved", count)

    private fun save() {
        for (page in pages) {
            var changed = false
            for (value in page.values) {
                val parsed = parse(value, drafts[value] ?: continue) ?: continue
                @Suppress("UNCHECKED_CAST")
                val typed = value as ForgeConfigSpec.ConfigValue<Any>
                if (typed.get() != parsed) {
                    typed.set(parsed)
                    changed = true
                }
            }
            // Only rewrite the files that were actually edited.
            if (changed) page.spec.save()
        }
    }

    /** "cruiseHeightAboveTarget" -> "Cruise Height Above Target". */
    private fun humanize(key: String): String =
        key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

    /**
     * The label shown for an option: the `screen.superbenemies.config.option.<key>` translation when the lang file has
     * one (the teammate toggles, which are named after a vehicle), otherwise the humanized key.
     */
    private fun labelOf(value: ForgeConfigSpec.ConfigValue<*>): String {
        val key = value.path.last()
        val translationKey = "screen.superbenemies.config.option.$key"
        return if (I18n.exists(translationKey)) I18n.get(translationKey) else humanize(key)
    }

    /** Case-insensitive match on the shown label or the raw key; an empty query matches everything. */
    private fun matches(value: ForgeConfigSpec.ConfigValue<*>, query: String): Boolean {
        if (query.isEmpty()) return true
        val key = value.path.last()
        return labelOf(value).lowercase().contains(query) || key.lowercase().contains(query)
    }

    // ------------------------------------------------------------------------------------------------ list

    /**
     * Refills the list with the open page, or with every page's matches while [search] is not empty; a section header only
     * appears when a row under it matches. Rebuilds the row widgets from the drafts, so call it after any draft was
     * changed from outside a widget.
     */
    private fun populate(scroll: Double) {
        val target = list ?: return
        target.clearAll()
        val query = search.trim().lowercase()
        val rows = ArrayList<Row>()
        var matched = 0
        for (page in if (query.isEmpty()) listOf(pages[selectedTab]) else pages) {
            var section: String? = null
            var header: Row? = null
            for (value in page.values) {
                val name = value.path.first()
                if (name != section) {
                    section = name
                    header = HeaderRow(Component.translatable("screen.superbenemies.config.section.$name"))
                }
                if (!matches(value, query)) continue
                header?.let {
                    rows += it
                    header = null
                }
                rows += OptionRow(value)
                matched++
            }
        }
        when {
            matched == 0 -> rows += NoticeRow(Component.translatable("screen.superbenemies.config.no_results"))
            query.isNotEmpty() -> rows.add(0, NoticeRow(resultsText(matched)))
        }
        for (row in rows) target.addRow(row)
        target.setScrollAmount(scroll)
        refreshStatus()
    }

    private fun resultsText(count: Int): Component =
        if (count == 1) Component.translatable("screen.superbenemies.config.results_one")
        else Component.translatable("screen.superbenemies.config.results", count)

    private abstract class Row : ContainerObjectSelectionList.Entry<Row>()

    /**
     * A scrollable box of [Row]s placed at [left] with its own [panelWidth], used for both the sidebar and the option list.
     * The panel background is drawn by [BossConfigScreen.renderPanel] instead of the list's dirt texture.
     */
    private inner class PanelList(
        private val left: Int,
        private val panelWidth: Int,
        top: Int,
        bottom: Int,
        itemHeight: Int,
        private val entryWidth: Int
    ) : ContainerObjectSelectionList<Row>(
        this@BossConfigScreen.minecraft!!, panelWidth, this@BossConfigScreen.height, top, bottom, itemHeight
    ) {
        init {
            setLeftPos(left)
            setRenderBackground(false)
            setRenderTopAndBottom(false)
        }

        fun addRow(row: Row) {
            addEntry(row)
        }

        fun clearAll() {
            clearEntries()
        }

        fun isEmpty(): Boolean = children().isEmpty()

        override fun getRowWidth(): Int = entryWidth

        override fun getScrollbarPosition(): Int = left + panelWidth - SCROLLBAR_WIDTH
    }

    /** Sidebar heading of a group: fold arrow, title and the group's change markers; a click folds or unfolds it. */
    private inner class GroupRow(private val group: Int) : Row() {
        private val title = groups[group].title.string

        override fun render(
            graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int,
            mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float
        ) {
            if (hovered) graphics.fill(left, top - 2, left + width, top + height + 2, HOVER_COLOR)
            val marks = drawMarkers(graphics, left + width - 4, top + 4, groupDirty(group), groupInvalid(group))
            val arrow = if (collapsed[group]) "▶ " else "▼ "
            graphics.drawString(font, fit(arrow + title, width - 8 - marks), left + 4, top + 4, GOLD_COLOR)
        }

        override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
            if (button != 0) return false
            playClick()
            toggleGroup(group)
            return true
        }

        override fun children(): List<GuiEventListener> = emptyList()
        override fun narratables(): List<NarratableEntry> = emptyList()
    }

    /** Sidebar entry of one page, indented under its group; the open page is highlighted. */
    private inner class PageRow(private val page: Int) : Row() {
        private val title = pages[page].title.string

        override fun render(
            graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int,
            mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float
        ) {
            val selected = page == selectedTab && search.isBlank()
            if (selected) {
                graphics.fill(left, top - 2, left + width, top + height + 2, SELECTED_COLOR)
                graphics.fill(left, top - 2, left + 2, top + height + 2, ACCENT_COLOR)
            } else if (hovered) {
                graphics.fill(left, top - 2, left + width, top + height + 2, HOVER_COLOR)
            }
            val marks = drawMarkers(graphics, left + width - 4, top + 4, pageDirty[page], pageInvalid[page])
            val color = if (selected || hovered) 0xFFFFFF else VALID_COLOR
            graphics.drawString(font, fit(title, width - PAGE_INDENT - 4 - marks), left + PAGE_INDENT, top + 4, color)
        }

        override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
            if (button != 0) return false
            playClick()
            selectTab(page)
            return true
        }

        override fun children(): List<GuiEventListener> = emptyList()
        override fun narratables(): List<NarratableEntry> = emptyList()
    }

    /** Draws `*` (unsaved) and `!` (invalid) ending at [right]; returns the width taken so the title can make room. */
    private fun drawMarkers(graphics: GuiGraphics, right: Int, y: Int, dirty: Boolean, invalid: Boolean): Int {
        var x = right
        if (invalid) {
            x -= font.width("!")
            graphics.drawString(font, "!", x, y, ERROR_COLOR)
            x -= MARKER_GAP
        }
        if (dirty) {
            x -= font.width("*")
            graphics.drawString(font, "*", x, y, GOLD_COLOR)
            x -= MARKER_GAP
        }
        return right - x
    }

    private fun playClick() {
        minecraft?.soundManager?.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    /** Section title in gold with a thin divider line on each side. */
    private inner class HeaderRow(private val text: Component) : Row() {
        override fun render(
            graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int,
            mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float
        ) {
            val centre = left + width / 2
            val half = font.width(text) / 2
            graphics.drawCenteredString(font, text, centre, top + 8, GOLD_COLOR)
            val lineY = top + 12
            graphics.hLine(left, centre - half - DIVIDER_GAP, lineY, DIVIDER_COLOR)
            graphics.hLine(centre + half + DIVIDER_GAP, left + width, lineY, DIVIDER_COLOR)
        }

        override fun children(): List<GuiEventListener> = emptyList()
        override fun narratables(): List<NarratableEntry> = emptyList()
    }

    /** A single grey centered line, used for the empty-search result. */
    private inner class NoticeRow(private val text: Component) : Row() {
        override fun render(
            graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int,
            mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float
        ) {
            graphics.drawCenteredString(font, text, left + width / 2, top + 8, MUTED_COLOR)
        }

        override fun children(): List<GuiEventListener> = emptyList()
        override fun narratables(): List<NarratableEntry> = emptyList()
    }

    private inner class OptionRow(private val value: ForgeConfigSpec.ConfigValue<*>) : Row() {
        private val name = labelOf(value)
        private val label = Component.literal(name)
        private val widget: AbstractWidget = createWidget()

        /** Sets the draft back to the option's default and rebuilds the rows so the control shows it. */
        private val resetButton: Button = Button.builder(Component.literal("↺")) {
            setDraft(value, defaultText(value))
            populate(list?.scrollAmount ?: 0.0)
        }.size(RESET_SIZE, RESET_SIZE).tooltip(Tooltip.create(Component.translatable("screen.superbenemies.config.reset")))
            .build()

        private val tooltip: Tooltip

        init {
            // The spec comment already ends with the "Range: a ~ b" line for numeric options.
            val spec = specOf(value)
            val text = listOfNotNull(
                spec.comment?.takeIf { it.isNotBlank() }?.trim(),
                "Default: ${spec.default}"
            ).joinToString("\n")
            tooltip = Tooltip.create(Component.literal(text))
            widget.setTooltip(tooltip)
        }

        private fun createWidget(): AbstractWidget {
            val draft = drafts[value] ?: value.get().toString()
            if (value.get() is Boolean) {
                return CycleButton.onOffBuilder(draft.toBooleanStrictOrNull() ?: true)
                    .displayOnlyValue()
                    .create(0, 0, CONTROL_WIDTH, 20, label) { _, enabled ->
                        setDraft(value, enabled.toString())
                        // While searching, the mirrored toggle may be on screen too: rebuild so it shows the new value.
                        if (value in mirrors && search.isNotBlank()) populate(list?.scrollAmount ?: 0.0) else refreshStatus()
                    }
            }
            if (value.get() is Enum<*>) {
                val constants = enumConstants(value)
                val initial = constants.firstOrNull { it.name == draft } ?: value.get() as Enum<*>
                return CycleButton.builder<Enum<*>> { Component.literal(enumLabel(it)) }
                    .withValues(constants)
                    .withInitialValue(initial)
                    .displayOnlyValue()
                    .create(0, 0, CONTROL_WIDTH, 20, label) { _, selected ->
                        drafts[value] = selected.name
                        refreshStatus()
                    }
            }
            val sliderRange = pageOf.getValue(value).sliders[value]
            if (sliderRange != null) {
                val initial = draft.trim().toIntOrNull()?.coerceIn(sliderRange.first, sliderRange.last) ?: sliderRange.first
                return IntSlider(value, sliderRange.first, sliderRange.last, initial, label)
            }
            val box = EditBox(font, 0, 0, CONTROL_WIDTH, 20, label)
            box.setMaxLength(MAX_NUMBER_LENGTH)
            box.value = draft
            box.setTextColor(if (parse(value, draft) != null) VALID_COLOR else INVALID_COLOR)
            box.setResponder { text ->
                drafts[value] = text
                box.setTextColor(if (parse(value, text) != null) VALID_COLOR else INVALID_COLOR)
                refreshStatus()
            }
            return box
        }

        override fun render(
            graphics: GuiGraphics, index: Int, top: Int, left: Int, width: Int, height: Int,
            mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float
        ) {
            resetButton.x = left + width - RESET_SIZE
            resetButton.y = top
            widget.x = resetButton.x - ELEMENT_GAP - CONTROL_WIDTH
            widget.y = top

            // Greyed out (and not editable) while the option it depends on is off.
            val available = isAvailable(value)
            widget.active = available
            (widget as? EditBox)?.setEditable(available)

            val invalid = parse(value, drafts[value] ?: "") == null
            val dirty = invalid || isDirty(value)
            val labelWidth = widget.x - left - ELEMENT_GAP
            val shown = fit((if (dirty) "● " else "") + name, labelWidth)
            val labelColor = when {
                !available -> MUTED_COLOR
                invalid -> ERROR_COLOR
                dirty -> EDITED_COLOR
                else -> 0xFFFFFF
            }
            graphics.drawString(font, shown, left, top + 6, labelColor)
            if (mouseX in left until left + labelWidth && mouseY in top until top + 20 &&
                mouseY in this@BossConfigScreen.listTop until this@BossConfigScreen.panelBottom
            ) {
                labelTooltip = tooltip
            }

            resetButton.active = !isDefault(value)
            widget.render(graphics, mouseX, mouseY, partialTick)
            resetButton.render(graphics, mouseX, mouseY, partialTick)
        }

        override fun children(): List<GuiEventListener> = listOf(widget, resetButton)
        override fun narratables(): List<NarratableEntry> = listOf(widget, resetButton)
    }

    /**
     * Slider for an integer option with a small range: it snaps to whole numbers [min]..[max] and writes the number into
     * the option's draft, so [parse], [isDirty] and the reset button treat it like a typed value. Shows the number, and
     * "Off" for 0 when the range starts at 0.
     */
    private inner class IntSlider(
        private val option: ForgeConfigSpec.ConfigValue<*>,
        private val min: Int,
        private val max: Int,
        initial: Int,
        label: Component
    ) : AbstractSliderButton(0, 0, CONTROL_WIDTH, 20, label, fractionOf(initial, min, max)) {

        init {
            updateMessage()
        }

        private fun selected(): Int = (min + (value * (max - min)).roundToInt()).coerceIn(min, max)

        override fun updateMessage() {
            val n = selected()
            message = if (n == 0 && min == 0) Component.translatable("screen.superbenemies.config.slider_off")
            else Component.literal(n.toString())
        }

        override fun applyValue() {
            val n = selected()
            value = fractionOf(n, min, max)
            drafts[option] = n.toString()
            refreshStatus()
        }

        /** Left / right step by one whole number instead of one pixel of the track. */
        override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
            val step = when (keyCode) {
                GLFW.GLFW_KEY_LEFT -> -1
                GLFW.GLFW_KEY_RIGHT -> 1
                else -> return super.keyPressed(keyCode, scanCode, modifiers)
            }
            val n = (selected() + step).coerceIn(min, max)
            if (n != selected()) {
                value = fractionOf(n, min, max)
                applyValue()
                updateMessage()
            }
            return true
        }
    }

    private companion object {
        /** Slider position (0..1) of [n] within [min]..[max]. */
        fun fractionOf(n: Int, min: Int, max: Int): Double = if (max > min) (n - min).toDouble() / (max - min) else 0.0

        const val TITLE_Y = 8
        const val SIDE_MARGIN = 10
        const val PANEL_TOP = 24
        const val PANEL_GAP = 8
        const val SIDEBAR_FRACTION = 0.27
        const val SIDEBAR_MIN_WIDTH = 96
        const val SIDEBAR_MAX_WIDTH = 150
        const val SIDEBAR_ITEM_HEIGHT = 20
        const val PAGE_INDENT = 12
        const val MARKER_GAP = 3
        const val SCROLLBAR_WIDTH = 6
        const val BREADCRUMB_SEPARATOR = " › "

        /** y of the breadcrumb / Reset Tab row and of the search box under it. */
        const val HEADER_Y = 24
        const val RESET_TAB_WIDTH = 80
        const val SEARCH_Y = 48
        const val MAX_SEARCH_LENGTH = 40
        const val LIST_TOP = 74

        /** Gap from the top of the search box to the top of the option list. */
        const val LIST_TOP_GAP = 26
        const val LIST_BOTTOM_MARGIN = 52
        const val STATUS_BOTTOM_OFFSET = 43
        const val BUTTONS_BOTTOM_OFFSET = 28
        const val BUTTON_WIDTH = 150
        const val BUTTON_GAP = 5
        const val ROW_HEIGHT = 24
        const val ROW_WIDTH = 330
        const val CONTROL_WIDTH = 100
        const val RESET_SIZE = 20
        const val ELEMENT_GAP = 4
        const val DIVIDER_GAP = 6
        const val MAX_NUMBER_LENGTH = 16
        const val ELLIPSIS = "…"

        // Minecraft has no light/dark theme: these read on both the dimmed in-world background and the dirt one.
        const val VALID_COLOR = 0xE0E0E0
        const val INVALID_COLOR = 0xFF5555
        const val ERROR_COLOR = 0xFF5555
        const val GOLD_COLOR = 0xFFD966
        const val EDITED_COLOR = 0xFFFF55
        const val MUTED_COLOR = 0xA0A0A0
        const val DIVIDER_COLOR = 0x80FFD966.toInt()
        const val PANEL_COLOR = 0x66000000
        const val PANEL_BORDER_COLOR = 0x55FFFFFF
        const val HOVER_COLOR = 0x33FFFFFF
        const val SELECTED_COLOR = 0x55FFD966
        const val ACCENT_COLOR = 0xFFFFD966.toInt()
    }
}
