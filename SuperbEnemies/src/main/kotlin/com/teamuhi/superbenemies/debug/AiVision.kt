package com.teamuhi.superbenemies.debug

import com.teamuhi.superbenemies.config.DebugConfig
import com.teamuhi.superbenemies.entity.boss.common.ai.categoryOf
import com.teamuhi.superbenemies.network.AiVisionPacket
import com.teamuhi.superbenemies.network.ModNetwork
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt

/** What a boss makes of its target right now; the colour of the vision line (see `AiVisionRenderer`). */
enum class VisionState {
    /** No target. */
    NONE,

    /** The target is in sight (or the boss uses `STATIC` targeting and always knows). */
    SEEN,

    /** The target broke line of sight but is still tracked at its real position: the sight-loss grace. */
    SENSING,

    /** The target is hidden and the boss aims at the spot it was last known at. */
    GHOST
}

/**
 * The sight cone of a tank (see `TankTargeting`): the horizontal unit direction ([dirX], [dirZ]) the turret points along,
 * the half angle in degrees and the range (blocks) at which it sees farther.
 */
class VisionCone(val dirX: Float, val dirZ: Float, val halfAngleDeg: Float, val range: Float)

/**
 * The "what does the boss see" debug, one report per boss per tick from its targeting code. Two outputs, both off while
 * the AI's thinking is off (they use the same switches as [AiThought]):
 *
 *  - the `vision` log channel: one discrete line, e.g. `seeing Dev (~40m)`, `sensing Dev, 3s left`, `Dev hidden, aiming at
 *    last known spot` or `no target`. Distances are bucketed to 10 blocks and the grace to whole seconds, so the line only
 *    changes when something real does. A change of the held target also prints one `vision.target` event.
 *  - with `showOnScreen` and `showVision` on, an [AiVisionPacket] every [SEND_INTERVAL] ticks (staggered by entity id) to
 *    the players near the boss, drawn in the world by the client `AiVisionRenderer`: a line from the boss to its target, the
 *    tank's sight cone and a label naming the target and its category.
 */
object AiVision {
    /** Ticks between two packets of one boss. */
    const val SEND_INTERVAL = 5

    /** Longest target name sent (longer ones are cut). */
    const val MAX_NAME = 48

    private const val DISTANCE_BUCKET = 10

    /**
     * Reports what [boss] sees. [eye] is where its sight rays start, [target] the real target it holds (null = none; sent
     * even while [aim] is a ghost, so the label tells who is being hunted), [aim] what it aims at (the target or its ghost),
     * [state] how it sees the target and [graceLeftTicks] the sight-loss grace left (only for [VisionState.SENSING]). [cone]
     * is the sight cone, for the tanks only, and only while it is in use.
     */
    fun report(
        boss: Entity,
        thought: AiThought,
        eye: Vec3,
        target: Entity?,
        aim: Entity?,
        state: VisionState,
        graceLeftTicks: Int = 0,
        cone: VisionCone? = null
    ) {
        if (!thought.enabled) return
        val name = target?.name?.string ?: "target"
        val category = target?.let { categoryOf(it)?.name } ?: "OTHER"
        val aimPoint = aim?.boundingBox?.center

        thought.think("vision") {
            when (state) {
                VisionState.NONE -> "no target"
                VisionState.SEEN -> "seeing $name (~${bucket(aimPoint?.let { eye.distanceTo(it) } ?: 0.0)}m)"
                VisionState.SENSING -> "sensing $name, ${(graceLeftTicks + 19) / 20}s left"
                VisionState.GHOST -> "$name hidden, aiming at last known spot"
            }
        }
        val targetId = target?.id ?: -1
        if (targetId != thought.visionTargetId) {
            thought.visionTargetId = targetId
            thought.event("vision.target") { if (target == null) "target dropped" else "now targeting $name [$category]" }
        }

        if (!DebugConfig.SHOW_ON_SCREEN.get() || !DebugConfig.SHOW_VISION.get()) return
        if ((boss.tickCount + boss.id) % SEND_INTERVAL != 0) return
        ModNetwork.sendVision(boss) {
            AiVisionPacket(
                entityId = boss.id,
                label = thought.label.take(AiVisionPacket.MAX_LABEL),
                state = state,
                eye = eye,
                eyeOffset = eye.subtract(boss.position()),
                aim = aimPoint,
                targetId = targetId,
                targetName = if (target == null) "" else name.take(MAX_NAME),
                category = if (target == null) "" else category,
                cone = cone
            )
        }
    }

    /** [distance] rounded to the nearest [DISTANCE_BUCKET] blocks. */
    private fun bucket(distance: Double): Int = (distance / DISTANCE_BUCKET).roundToInt() * DISTANCE_BUCKET
}
