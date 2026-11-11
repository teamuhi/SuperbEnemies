package com.teamuhi.superbenemies.entity.boss.common.ground

import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedBosses
import com.teamuhi.superbenemies.entity.boss.common.ai.isTeammate
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.IEventBus
import kotlin.math.max

/**
 * Formations of the tank bosses: tanks that list each other as teammates (see `isTeammate`) and are close together drive
 * as a group. The lowest entity id of a group is its leader and drives as usual; the others are followers that hold a slot
 * behind it. The brain asks [slotFor] every tick.
 *
 * Two shapes:
 *  - [Mode.COLUMN], a `---` (the convoy): follower k sits `k * spacing` blocks back along the path the leader drove (a trail
 *    of breadcrumbs), so the group drives in single file and threads through narrow gaps. This is the shape of a group that
 *    is travelling or waiting: the leader has no target.
 *  - [Mode.WEDGE], a `<` with the leader at the tip: follower k sits on the left (odd k) or right (even k) arm, rank
 *    `(k + 1) / 2` rows back. Only while the leader has a target (the group fans out to face it) and every slot, and the
 *    stretch from the leader to it, is passable.
 *
 * The wedge points toward the leader's target. Switching to a wedge takes [WEDGE_CHECKS] clean terrain checks in a row,
 * switching back to a column takes one failed check or the leader losing its target, so the shape does not flicker on rough
 * ground.
 *
 * **Contact.** When a tank of a group meets the enemy ([reportContact]) the whole group first halts ([Phase.HALT]): every
 * member, the leader included, holds still and fires for the leader's `formationHaltSeconds`. Then the formation breaks
 * ([Phase.BROKEN]): nobody holds a slot and the leader stops waiting for stragglers, so each tank fights on its own. Once
 * no member has been in contact for [CONTACT_RESET_TICKS] ticks the group is [Phase.NONE] again and forms up as usual. The
 * contact state belongs to the leader's id and lives outside the groups, so a rebuild (every second) keeps it. 0 seconds
 * means the formation never halts or breaks.
 *
 * Groups are rebuilt lazily, at most once per [CLUSTER_INTERVAL] ticks per level, by whichever tank asks first, so a dead,
 * withdrawn or crippled tank, a changed config and a newcomer are all picked up within a second. Both ways of the
 * teammate relation must hold (A lists B and B lists A) to join a group; a lone tank has no group. Different tank types
 * can share one. The group's spacing is the leader's `formationSpacing`. Nothing is saved: groups form again after a load.
 *
 * Server thread only.
 */
object TankFormations {

    enum class Mode { WEDGE, COLUMN }

    /** Where a group stands in a fight, see [phaseFor]. */
    enum class Phase {
        /** No fight, or `formationHaltSeconds` is 0: the formation works as usual. */
        NONE,

        /** First seconds of contact: every member holds still while its guns work. */
        HALT,

        /** After the halt, until the group has been out of contact for a while: no slots, each tank acts alone. */
        BROKEN
    }

    /** Ticks between two rebuilds of the groups of a level. */
    const val CLUSTER_INTERVAL = 20L

    /** Ticks without contact after which a group that fought is back to normal (the next contact halts it again). */
    const val CONTACT_RESET_TICKS = 200L

    /** A tank joins a group when it is this close (blocks, horizontally) to the leader. */
    const val CLUSTER_RADIUS = 64.0

    /** A follower farther than this from its leader acts on its own (and is dropped at the next rebuild). */
    const val BREAK_DISTANCE = 96.0

    /** Followers per leader. */
    const val MAX_MEMBERS = 5

    /** Slots are recomputed (and the heading smoothed) this often, in ticks. */
    private const val SLOT_INTERVAL = 5L

    /** Clean terrain checks in a row needed to go from a column to a wedge. */
    private const val WEDGE_CHECKS = 2

    /** Blocks the leader has to move for the next breadcrumb. */
    private const val TRAIL_STEP = 3.0

    /** Breadcrumbs kept: enough for [MAX_MEMBERS] followers at the largest spacing (40 blocks). */
    private const val TRAIL_CAPACITY = (MAX_MEMBERS + 1) * 40 / 3

    /** How far back a wedge arm leans per row, as a fraction of the spacing. */
    private const val WEDGE_BACK = 0.8

    /** Horizontal speed (blocks per tick) above which the leader's motion counts as a heading. */
    private const val MOTION_THRESHOLD = 0.03

    /** Weight of the newest heading in the smoothed one. */
    private const val HEADING_BLEND = 0.35

    /** The leader stands still while a follower is farther than this many spacings from its slot. */
    private const val STRING_OUT_SPACINGS = 2.5

    /** Blocks added to the two longest hulls of a group to get the least spacing a group keeps (hulls never touch in a column). */
    private const val MIN_SPACING_GAP = 5.0

    /** A follower swaps slots with the one in front only when it is this many spacings nearer the leader (see [orderMembers]). */
    private const val SWAP_MARGIN = 0.4

    /** A follower aims at the trail this many blocks ahead of where it is on it (see [carrotFor]). */
    private const val CARROT_DISTANCE = 9.0

    /** A follower farther than this from the leader's trail is not pursuing it (the planner brings it back). */
    private const val TRAIL_MAX_OFFSET = 30.0

    /** Degrees the straight-behind column point is swung to either side when that ground is not passable. */
    private val COLUMN_SWING = doubleArrayOf(0.0, 35.0, -35.0, 70.0, -70.0)

    private class Group(var leader: TankBossEntity) {
        var members: List<TankBossEntity> = emptyList()
        var mode = Mode.COLUMN
        var cleanChecks = 0
        var spacing = 14.0
        var heading: Vec3 = Vec3(0.0, 0.0, 1.0)
        var headingSet = false
        /** `gameTime` of the last [refreshSlots], or -1 for never. */
        var slotStamp = -1L
        var slots: List<Vec3?> = emptyList()
        val trail = ArrayDeque<Vec3>()

        fun hasMember(id: Int): Boolean = members.any { it.id == id }
    }

    /**
     * Where a follower in a column steers: [point] on the leader's trail a few blocks ahead of the follower (never nearer the
     * leader than its slot), and [gap], how many blocks along the trail it still is behind its slot (0 when on it or ahead).
     */
    class Carrot(val point: Vec3, val gap: Double)

    /** [slot] 0 is the leader, 1.. the followers in order. */
    private class Assignment(val group: Group, val slot: Int)

    /** When a group met the enemy: `gameTime` of the first contact of this fight and of the latest one. */
    private class Contact(val start: Long, var last: Long)

    private class LevelState {
        /** `gameTime` of the last rebuild, or -1 for never. */
        var stamp = -1L
        val assignments = HashMap<Int, Assignment>()
        val groups = HashMap<Int, Group>()

        /** Contact state per leader id; kept apart from [groups] so a rebuild does not forget a fight in progress. */
        val contacts = HashMap<Int, Contact>()
    }

    private val levels = HashMap<ResourceKey<Level>, LevelState>()

    fun register(bus: IEventBus) {
        bus.addListener<ServerStoppedEvent> { levels.clear() }
    }

    // ================================================================================================== query

    /**
     * Where [tank] should be right now, or null when it has no place to hold: it is a leader or in no group, it left the
     * formation (its leader is gone, crippled or withdrawn, or is farther than [BREAK_DISTANCE]) or `formationEnabled` is
     * off for it. A leader calls this every tick too: that is how its trail of breadcrumbs is laid.
     */
    fun slotFor(tank: TankBossEntity): Vec3? {
        val level = tank.level() as? ServerLevel ?: return null
        val assignment = assignmentOf(level, tank) ?: return null
        val group = assignment.group
        if (assignment.slot == 0) {
            record(group, tank)
            return null
        }
        if (!stillFollowing(tank, group)) return null
        refreshSlots(level, group)
        return group.slots.getOrNull(assignment.slot - 1)
    }

    /**
     * Pure pursuit along the leader's trail for a follower in a column: the point to drive at so the follower retraces the
     * path the leader drove (round the same corners and through the same gaps) instead of routing to its slot afresh. Null
     * when [tank] is not a follower, the group is not a column, the trail is too short to reach its slot, or the tank is more
     * than [TRAIL_MAX_OFFSET] blocks off the trail: it then drives to [slotFor] with the route planner.
     */
    fun carrotFor(tank: TankBossEntity): Carrot? {
        val level = tank.level() as? ServerLevel ?: return null
        val assignment = assignmentOf(level, tank) ?: return null
        val group = assignment.group
        if (assignment.slot == 0 || group.mode != Mode.COLUMN || !stillFollowing(tank, group)) return null

        // The leader's path (carried on straight past a short trail), with arc length from the leader.
        val slotArc = assignment.slot * group.spacing
        val route = pathOf(group, slotArc)
        val path = route.points
        val arc = route.arc

        // Where the follower stands on the path: its nearest point, among the stretches at or behind its slot.
        val position = tank.position()
        var bestOffset = Double.MAX_VALUE
        var followerArc = 0.0
        for (i in 1 until path.size) {
            if (arc[i] < slotArc - group.spacing) continue
            val a = path[i - 1]
            val b = path[i]
            val length = arc[i] - arc[i - 1]
            if (length < 1.0e-6) continue
            val t = (((position.x - a.x) * (b.x - a.x) + (position.z - a.z) * (b.z - a.z)) / (length * length)).coerceIn(0.0, 1.0)
            val offset = FlightMath.horizontalDistance(position, Vec3(a.x + (b.x - a.x) * t, position.y, a.z + (b.z - a.z) * t))
            if (offset < bestOffset) {
                bestOffset = offset
                followerArc = arc[i - 1] + t * length
            }
        }
        if (bestOffset > TRAIL_MAX_OFFSET) return null

        val gap = max(0.0, followerArc - slotArc)
        val carrotArc = max(slotArc, followerArc - CARROT_DISTANCE)
        return Carrot(pointAt(path, arc, carrotArc), gap)
    }

    /** The point [target] blocks of arc length along [path] (whose cumulative lengths are [arc]); the last point beyond its end. */
    private fun pointAt(path: List<Vec3>, arc: DoubleArray, target: Double): Vec3 {
        for (i in 1 until path.size) {
            if (arc[i] < target) continue
            val length = arc[i] - arc[i - 1]
            if (length < 1.0e-6) return path[i]
            return path[i - 1].add(path[i].subtract(path[i - 1]).scale((target - arc[i - 1]) / length))
        }
        return path.last()
    }

    /** The horizontal unit heading of the group [tank] follows, or null when [slotFor] has no place for it. */
    fun headingFor(tank: TankBossEntity): Vec3? {
        val level = tank.level() as? ServerLevel ?: return null
        val assignment = assignmentOf(level, tank) ?: return null
        return if (assignment.slot != 0 && stillFollowing(tank, assignment.group)) assignment.group.heading else null
    }

    /** Blocks between the tanks of the group [tank] is in (the leader's `formationSpacing`). */
    fun spacingFor(tank: TankBossEntity): Double {
        val level = tank.level() as? ServerLevel ?: return tank.settings.formationSpacing.get().toDouble()
        return assignmentOf(level, tank)?.group?.spacing ?: tank.settings.formationSpacing.get().toDouble()
    }

    /** True when [a] and [b] are in the same group (leader and follower, or two followers of one leader). */
    fun sameGroup(a: TankBossEntity, b: TankBossEntity): Boolean {
        val level = a.level() as? ServerLevel ?: return false
        if (b.level() !== a.level()) return false
        val first = assignmentOf(level, a) ?: return false
        val second = assignmentOf(level, b) ?: return false
        return first.group === second.group
    }

    /**
     * Records that [tank] has the enemy in sight right now (the brain calls this every tick while it fights). The first
     * report after [CONTACT_RESET_TICKS] without one starts a new fight, which halts the group. A tank in no group has no
     * contact state; nothing happens.
     */
    fun reportContact(tank: TankBossEntity) {
        val level = tank.level() as? ServerLevel ?: return
        val assignment = assignmentOf(level, tank) ?: return
        val state = levels[level.dimension()] ?: return
        if (assignment.group.leader.settings.formationHaltSeconds.get() <= 0) return
        val now = level.gameTime
        val key = assignment.group.leader.id
        val contact = state.contacts[key]
        if (contact == null || now - contact.last > CONTACT_RESET_TICKS || now < contact.last) {
            state.contacts[key] = Contact(now, now)
            tank.thought.event("formations.contact") { "first contact: its formation halts for ${assignment.group.leader.settings.formationHaltSeconds.get()}s" }
        } else {
            contact.last = now
        }
    }

    /**
     * The phase of the group [tank] belongs to: [Phase.HALT] for the leader's `formationHaltSeconds` after the first contact,
     * then [Phase.BROKEN] until [CONTACT_RESET_TICKS] pass without contact, else [Phase.NONE]. Always [Phase.NONE] for a tank
     * in no group or with `formationHaltSeconds` at 0 (read live).
     */
    fun phaseFor(tank: TankBossEntity): Phase {
        val level = tank.level() as? ServerLevel ?: return Phase.NONE
        val assignment = assignmentOf(level, tank) ?: return Phase.NONE
        val haltTicks = assignment.group.leader.settings.formationHaltSeconds.get() * 20L
        if (haltTicks <= 0L) return Phase.NONE
        val state = levels[level.dimension()] ?: return Phase.NONE
        val contact = state.contacts[assignment.group.leader.id] ?: return Phase.NONE
        val now = level.gameTime
        if (now < contact.last || now - contact.last > CONTACT_RESET_TICKS) return Phase.NONE
        return if (now - contact.start < haltTicks) Phase.HALT else Phase.BROKEN
    }

    /**
     * True when [leader] leads a group and some follower is more than [STRING_OUT_SPACINGS] spacings from its slot: the
     * leader waits for it so the convoy does not string out.
     */
    fun membersStrungOut(leader: TankBossEntity): Boolean {
        val level = leader.level() as? ServerLevel ?: return false
        val assignment = assignmentOf(level, leader) ?: return false
        if (assignment.slot != 0) return false
        val group = assignment.group
        refreshSlots(level, group)
        val limit = group.spacing * STRING_OUT_SPACINGS
        for ((index, member) in group.members.withIndex()) {
            if (!stillFollowing(member, group)) continue
            val slot = group.slots.getOrNull(index) ?: continue
            if (FlightMath.horizontalDistance(member.position(), slot) > limit) return true
        }
        return false
    }

    // ================================================================================================== groups

    private fun assignmentOf(level: ServerLevel, tank: TankBossEntity): Assignment? {
        val state = levels.getOrPut(level.dimension()) { LevelState() }
        val now = level.gameTime
        if (state.stamp < 0L || now - state.stamp >= CLUSTER_INTERVAL || now < state.stamp) {
            state.stamp = now
            recluster(level, state)
        }
        return state.assignments[tank.id]
    }

    /** A tank that may be in a group: alive, not crippled, not away rearming and with formations on. */
    private fun eligible(tank: TankBossEntity): Boolean =
        tank.isAlive && !tank.isRemoved && !tank.crippled && !tank.brain.withdrawn && tank.settings.formationEnabled.get()

    /** Whether [follower] still has a leader to hold a slot behind. */
    private fun stillFollowing(follower: TankBossEntity, group: Group): Boolean {
        val leader = group.leader
        if (!eligible(follower) || !eligible(leader) || leader.level() !== follower.level()) return false
        return FlightMath.horizontalDistance(leader.position(), follower.position()) <= BREAK_DISTANCE
    }

    /** Both bosses list each other as teammates. */
    private fun mutual(a: TankBossEntity, b: TankBossEntity): Boolean = isTeammate(a, b) && isTeammate(b, a)

    private fun recluster(level: ServerLevel, state: LevelState) {
        val candidates = ArrayList<TankBossEntity>()
        for (entity in TrackedBosses.all(level)) {
            if (entity is TankBossEntity && eligible(entity)) candidates.add(entity)
        }
        candidates.sortBy { it.id }

        val assignments = HashMap<Int, Assignment>()
        val groups = HashMap<Int, Group>()
        val taken = HashSet<Int>()
        for (leader in candidates) {
            if (!taken.add(leader.id)) continue
            val previous = state.groups[leader.id]
            val found = ArrayList<TankBossEntity>()
            for (other in candidates) {
                if (found.size >= MAX_MEMBERS) break
                if (other.id in taken || !mutual(leader, other)) continue
                // A tank that is already a follower of this leader is kept up to BREAK_DISTANCE, so the edge does not flap.
                val reach = if (previous?.hasMember(other.id) == true) BREAK_DISTANCE else CLUSTER_RADIUS
                if (FlightMath.horizontalDistance(leader.position(), other.position()) > reach) continue
                found.add(other)
            }
            if (found.isEmpty()) continue
            val members = orderMembers(leader, found, previous)

            val group = previous ?: Group(leader)
            if (previous == null || previous.members.map { it.id } != members.map { it.id }) {
                leader.thought.event("formations.assign") {
                    "leads a formation of ${members.size} follower${if (members.size == 1) "" else "s"}: " + members.joinToString { "#${it.id}" }
                }
            }
            for ((index, member) in members.withIndex()) {
                if (previous?.hasMember(member.id) != true) {
                    member.thought.event("formations.assign") { "joined the formation of #${leader.id} in slot ${index + 1}" }
                }
            }
            group.leader = leader
            group.members = members
            group.spacing = spacingOf(leader, members)
            group.slotStamp = -1L
            updateHeading(group)
            val modeBefore = group.mode
            checkTerrain(level, group)
            if (group.mode != modeBefore) leader.thought.event("formations.mode") { "formation shape $modeBefore -> ${group.mode}" }
            groups[leader.id] = group
            assignments[leader.id] = Assignment(group, 0)
            for ((index, member) in members.withIndex()) {
                taken.add(member.id)
                assignments[member.id] = Assignment(group, index + 1)
            }
        }
        val now = level.gameTime
        state.contacts.values.removeIf { now < it.last || now - it.last > CONTACT_RESET_TICKS }
        for ((id, old) in state.assignments) {
            if (id in assignments) continue
            (level.getEntity(id) as? TankBossEntity)?.thought?.event("formations.assign") {
                if (old.slot == 0) "its formation disbanded" else "left its formation"
            }
        }
        state.assignments.clear()
        state.assignments.putAll(assignments)
        state.groups.clear()
        state.groups.putAll(groups)
    }

    /**
     * The followers in slot order: the tank nearest the leader holds slot 1, the next one slot 2, and so on, so that a group
     * that has been scattered (a fight, a detour) forms up without tanks crossing each other's paths to reach a slot given by
     * their entity id. The order of the last rebuild is kept and two neighbours only swap when the one behind is clearer than
     * [SWAP_MARGIN] times the spacing nearer the leader, so slots do not flip between two tanks at the same distance.
     */
    private fun orderMembers(leader: TankBossEntity, found: List<TankBossEntity>, previous: Group?): List<TankBossEntity> {
        val ordered = ArrayList<TankBossEntity>(found.size)
        previous?.members?.forEach { old -> found.firstOrNull { it.id == old.id }?.let(ordered::add) }
        val origin = leader.position()
        found.filter { fresh -> ordered.none { it.id == fresh.id } }
            .sortedBy { FlightMath.horizontalDistance(origin, it.position()) }
            .forEach(ordered::add)
        val margin = (previous?.spacing ?: leader.settings.formationSpacing.get().toDouble()) * SWAP_MARGIN
        for (i in 1 until ordered.size) {
            var j = i
            while (j > 0 && FlightMath.horizontalDistance(origin, ordered[j].position()) + margin <
                FlightMath.horizontalDistance(origin, ordered[j - 1].position())
            ) {
                val moved = ordered[j]
                ordered[j] = ordered[j - 1]
                ordered[j - 1] = moved
                j--
            }
        }
        return ordered
    }

    /** True when [tank] leads a group of at least one follower: the brain then keeps its goals ahead of the column. */
    fun leadsGroup(tank: TankBossEntity): Boolean {
        val level = tank.level() as? ServerLevel ?: return false
        return assignmentOf(level, tank)?.slot == 0
    }

    /**
     * The leader's `formationSpacing`, but never less than the two longest hulls of the group plus [MIN_SPACING_GAP]: a
     * column tighter than that is a column of tanks that keep giving way to each other (see `CrowdSeparation.separate`) and so
     * never hold their slots.
     */
    private fun spacingOf(leader: TankBossEntity, members: List<TankBossEntity>): Double {
        var longest = leader.brain.longestReach()
        var second = 0.0
        for (member in members) {
            val reach = member.brain.longestReach()
            if (reach > longest) {
                second = longest
                longest = reach
            } else if (reach > second) {
                second = reach
            }
        }
        return max(leader.settings.formationSpacing.get().toDouble(), longest + second + MIN_SPACING_GAP)
    }

    // ================================================================================================== shape

    /** The direction the formation faces: the leader's target, else its motion, else its hull. Blended into [Group.heading]. */
    private fun updateHeading(group: Group) {
        val leader = group.leader
        val position = leader.position()
        val aim = leader.brain.currentAim
        val fallback = FlightMath.dir(leader.yRot, 0f)
        val wanted = when {
            aim != null && aim.level() === leader.level() -> FlightMath.horizontal(aim.position().subtract(position), fallback)
            leader.deltaMovement.horizontalDistance() > MOTION_THRESHOLD -> FlightMath.horizontal(leader.deltaMovement, fallback)
            else -> fallback
        }
        group.heading = if (!group.headingSet) {
            group.headingSet = true
            wanted
        } else {
            FlightMath.horizontal(group.heading.scale(1.0 - HEADING_BLEND).add(wanted.scale(HEADING_BLEND)), wanted)
        }
    }

    /** The point of wedge slot [slot] (1 and up): rank `(slot + 1) / 2` rows back, left of the leader for odd slots. */
    private fun wedgePoint(group: Group, slot: Int): Vec3 {
        val leader = group.leader.position()
        val forward = group.heading
        val rank = (slot + 1) / 2
        val side = if (slot % 2 == 1) -1.0 else 1.0
        val back = rank * group.spacing * WEDGE_BACK
        val across = side * rank * group.spacing
        // The right-hand vector of a heading in Minecraft's axes (facing south, +z, the right hand is west).
        return Vec3(
            leader.x - forward.x * back - forward.z * across,
            leader.y,
            leader.z - forward.z * back + forward.x * across
        )
    }

    /**
     * One terrain check: the wedge needs the leader to have a target and every slot and the stretch from the leader to it to
     * be passable; anything else is a convoy (open ground alone used to make a wedge, so a travelling group never formed a line).
     */
    private fun checkTerrain(level: ServerLevel, group: Group) {
        if (group.leader.brain.currentAim == null) {
            group.cleanChecks = 0
            group.mode = Mode.COLUMN
            return
        }
        val profile = group.leader.brain.routeProfile()
        val origin = group.leader.position()
        var clear = true
        for (slot in 1..group.members.size) {
            val point = wedgePoint(group, slot)
            val middle = origin.add(point.subtract(origin).scale(0.5))
            if (!GroundTerrain.passable(level, point, profile) || !GroundTerrain.passable(level, middle, profile)) {
                clear = false
                break
            }
        }
        if (clear) {
            group.cleanChecks++
            if (group.cleanChecks >= WEDGE_CHECKS) group.mode = Mode.WEDGE
        } else {
            group.cleanChecks = 0
            group.mode = Mode.COLUMN
        }
    }

    /** Recomputes the slot points of [group] every [SLOT_INTERVAL] ticks. */
    private fun refreshSlots(level: ServerLevel, group: Group) {
        val now = level.gameTime
        if (group.slotStamp >= 0L && now >= group.slotStamp && now - group.slotStamp < SLOT_INTERVAL) return
        group.slotStamp = now
        updateHeading(group)
        val profile = group.leader.brain.routeProfile()
        group.slots = List(group.members.size) { index ->
            val slot = index + 1
            // A wedge slot on impassable or unloaded ground (re-checked every refresh, the group-wide check only every
            // CLUSTER_INTERVAL) falls back to the column point of the same rank instead of being snapped by the planner
            // to wherever is nearest, which could be right where another slot snaps to.
            val column = { columnPoint(level, group, slot * group.spacing, profile) }
            when (group.mode) {
                Mode.WEDGE -> wedgeSlot(level, group, slot, profile) ?: column()
                Mode.COLUMN -> column()
            }
        }
    }

    /** The wedge point of [slot] standing on the floor of its terrain cell, or null when that cell cannot be driven on. */
    private fun wedgeSlot(level: ServerLevel, group: Group, slot: Int, profile: GroundProfile): Vec3? {
        val point = wedgePoint(group, slot)
        val cell = GroundTerrain.sample(level, GroundTerrain.cellOf(point.x, profile), GroundTerrain.cellOf(point.z, profile), profile)
        return if (GroundTerrain.passable(cell, profile)) Vec3(point.x, cell.floorY.toDouble(), point.z) else null
    }

    /** [point] standing on the floor of its terrain cell; unchanged when the cell is not loaded. */
    private fun snap(level: ServerLevel, point: Vec3, profile: GroundProfile): Vec3 {
        val cell = GroundTerrain.sample(level, GroundTerrain.cellOf(point.x, profile), GroundTerrain.cellOf(point.z, profile), profile)
        return if (cell.loaded) Vec3(point.x, cell.floorY.toDouble(), point.z) else point
    }

    // ================================================================================================== column

    /** Lays a breadcrumb when the leader has moved [TRAIL_STEP] blocks from the last one. */
    private fun record(group: Group, leader: TankBossEntity) {
        val position = leader.position()
        val last = group.trail.lastOrNull()
        if (last != null && FlightMath.horizontalDistance(last, position) < TRAIL_STEP) return
        group.trail.addLast(position)
        while (group.trail.size > TRAIL_CAPACITY) group.trail.removeFirst()
    }

    /** The leader's path as a polyline, newest first: [points] with the arc length from the leader at each ([arc]). */
    private class Path(val points: List<Vec3>, val arc: DoubleArray, val realLength: Double)

    /**
     * The leader's path: the leader itself, then its breadcrumbs back in time. A trail shorter than [reach] (a group that has
     * only just set off, or just re-formed) is carried on in a straight line past its oldest end, in the direction the
     * leader came from, so the slots of the tail of the column lie on a line that grows into the real trail instead of
     * jumping about with the leader's heading. [Path.realLength] is the length of the part that was really driven.
     */
    private fun pathOf(group: Group, reach: Double): Path {
        val points = ArrayList<Vec3>(group.trail.size + 2)
        points.add(group.leader.position())
        for (index in group.trail.indices.reversed()) points.add(group.trail[index])
        var real = 0.0
        for (i in 1 until points.size) real += FlightMath.horizontalDistance(points[i - 1], points[i])
        if (real < reach) {
            val last = points.last()
            val behind = group.heading.scale(-1.0)
            // The direction of the oldest few crumbs, pointing back in time (smoother than the last segment alone).
            val back = if (points.size >= 2) FlightMath.horizontal(last.subtract(points[max(0, points.size - 4)]), behind) else behind
            points.add(last.add(back.scale(reach - real + 1.0)))
        }
        val arc = DoubleArray(points.size)
        for (i in 1 until points.size) arc[i] = arc[i - 1] + FlightMath.horizontalDistance(points[i - 1], points[i])
        return Path(points, arc, real)
    }

    /**
     * The point [distance] blocks back along the path the leader drove, carried on straight past the oldest breadcrumb when
     * the trail is short (see [pathOf]). That straight part is only used when its ground is passable; otherwise a point
     * behind the leader (straight back, else swung up to 70 degrees to either side) if that is, else null: the follower then
     * acts on its own for now.
     */
    private fun columnPoint(level: ServerLevel, group: Group, distance: Double, profile: GroundProfile): Vec3? {
        val path = pathOf(group, distance)
        val onPath = pointAt(path.points, path.arc, distance)
        if (distance <= path.realLength) return onPath
        if (GroundTerrain.passable(level, onPath, profile)) return snap(level, onPath, profile)
        val origin = group.leader.position()
        for (degrees in COLUMN_SWING) {
            val radians = Math.toRadians(degrees)
            val cos = Math.cos(radians)
            val sin = Math.sin(radians)
            val dir = Vec3(group.heading.x * cos - group.heading.z * sin, 0.0, group.heading.x * sin + group.heading.z * cos)
            val behind = origin.subtract(dir.scale(distance))
            if (GroundTerrain.passable(level, behind, profile)) return snap(level, behind, profile)
        }
        return null
    }
}
