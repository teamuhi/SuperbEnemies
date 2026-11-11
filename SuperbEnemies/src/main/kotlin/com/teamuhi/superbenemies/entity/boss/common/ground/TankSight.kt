package com.teamuhi.superbenemies.entity.boss.common.ground

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.entity.boss.common.ai.SkyExposure
import com.teamuhi.superbenemies.entity.boss.common.ai.SmokeScreen
import com.teamuhi.superbenemies.entity.boss.common.ai.isTeammate
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

/**
 * Line-of-sight tests of the ground bosses (the counterpart of `SkyExposure` for the planes, which look from above).
 *
 * A target is seen when a ray from the turret to its centre or its head (for a vehicle also its roof and corners, see
 * [canSee]) crosses no sight-blocking block ([SkyExposure.blocksSight]: solid blocks, not glass, leaves or fences) and no
 * smoke cloud ([SmokeScreen.blocksLine]), and it is within the sight range. Rays never load chunks: an unloaded block counts as blocking.
 */
object TankSight {

    /** Distance between two samples along a ray, in blocks. */
    private const val STEP = 1.0

    /** How far below the roof of a vehicle's box the top samples sit, in blocks. */
    private const val TOP_DROP = 0.3

    /** The corner samples of a vehicle sit this fraction of the half extent from its centre. */
    private const val CORNER_INSET = 0.75

    private val CORNER_SIGNS = doubleArrayOf(-1.0, 1.0)

    /** The turret position of [boss], where its sight rays start. */
    fun eyeOf(boss: Entity): Vec3 = Vec3(boss.x, boss.y + boss.bbHeight * 0.9, boss.z)

    /**
     * Whether [boss] sees [target] right now: inside [sightRange] (blocks, 3D) and a ray to one of its sample points is
     * clear. Players and mobs are sampled at the centre and the head. A vehicle, or anything with a rider, is a big box
     * that a wall or a hill can cover in part, so it is also sampled at the top centre and at the four top corners (pulled
     * in by [CORNER_INSET] of the half extent): the target is seen when any one ray is clear. Callers pass the root
     * vehicle of what they want to see, so a player riding a tank is judged by the tank's box and not by a seat low inside it.
     */
    fun canSee(boss: Entity, target: Entity, sightRange: Double): Boolean {
        val level = boss.level()
        if (target.level() !== level) return false
        val eye = eyeOf(boss)
        val box = target.boundingBox
        val centre = box.center
        if (eye.distanceToSqr(centre) > sightRange * sightRange) return false
        if (rayVisible(boss, level, eye, centre)) return true
        val head = Vec3(centre.x, box.maxY - 0.15, centre.z)
        if (head.y > centre.y + 0.2 && rayVisible(boss, level, eye, head)) return true
        if (target !is VehicleEntity && !target.isVehicle) return false

        val top = box.maxY - TOP_DROP
        if (top <= centre.y) return false
        if (rayVisible(boss, level, eye, Vec3(centre.x, top, centre.z))) return true
        val halfX = box.xsize * 0.5 * CORNER_INSET
        val halfZ = box.zsize * 0.5 * CORNER_INSET
        for (sx in CORNER_SIGNS) {
            for (sz in CORNER_SIGNS) {
                if (rayVisible(boss, level, eye, Vec3(centre.x + sx * halfX, top, centre.z + sz * halfZ))) return true
            }
        }
        return false
    }

    /**
     * Smoke clouds the tank bosses launched, mapped to the tank that launched them. A tank pops its smoke toward the
     * threat, so a cloud wall stands between it and its target; without this its own (and its teammates') smoke would blind
     * it to the very target it was covering, which turned every smoke salvo into "target lost, shell the last seen spot".
     * Weak keys: a cloud that burned out and was removed drops out by itself. Server thread only.
     */
    private val launchedSmoke = java.util.WeakHashMap<Entity, Entity>()

    /** Records that [tank] launched [smoke], so its sight (and its teammates') looks through it. */
    fun noteSmoke(smoke: Entity, tank: Entity) {
        (tank as? TankBossEntity)?.thought?.event("sight.noteSmoke") { "launched a smoke cloud" }
        launchedSmoke[smoke] = tank
    }

    /** The tank boss that launched [smoke], or null for a cloud no tank boss made (a player's smoke grenade or vehicle). */
    fun smokeOwner(smoke: Entity): Entity? = launchedSmoke[smoke]

    /** True for a cloud launched by [boss] itself or by a tank it lists as a teammate. */
    private fun friendlySmoke(boss: Entity, smoke: Entity): Boolean {
        val owner = launchedSmoke[smoke] ?: return false
        return owner === boss || isTeammate(boss, owner)
    }

    private fun rayVisible(boss: Entity, level: Level, from: Vec3, to: Vec3): Boolean =
        !SmokeScreen.blocksLine(level, from, to) { friendlySmoke(boss, it) } && solidBlocks(level, from, to, 0) == 0

    /**
     * Counts the sight-blocking blocks on the segment [from]-[to] (each block once; the blocks at both ends are not
     * counted), stopping as soon as the count exceeds [limit]. An unloaded chunk counts as more than [limit].
     * With a limit of 0 it is a plain "is the line clear" test; the armour-piercing shell uses a larger one to see how
     * much wall it would have to get through.
     */
    fun solidBlocks(level: Level, from: Vec3, to: Vec3, limit: Int): Int {
        val delta = to.subtract(from)
        val length = delta.length()
        if (length < 1.5) return 0
        val steps = ceil(length / STEP).toInt()
        val step = delta.scale(1.0 / steps)

        val pos = BlockPos.MutableBlockPos()
        var chunkX = Int.MIN_VALUE
        var chunkZ = Int.MIN_VALUE
        var chunk: net.minecraft.world.level.chunk.LevelChunk? = null
        var lastX = Int.MIN_VALUE
        var lastY = Int.MIN_VALUE
        var lastZ = Int.MIN_VALUE
        var count = 0
        var x = from.x
        var y = from.y
        var z = from.z
        for (i in 1 until steps) {
            x += step.x
            y += step.y
            z += step.z
            val bx = Math.floor(x).toInt()
            val by = Math.floor(y).toInt()
            val bz = Math.floor(z).toInt()
            if (bx == lastX && by == lastY && bz == lastZ) continue
            lastX = bx
            lastY = by
            lastZ = bz
            if (by < level.minBuildHeight || by >= level.maxBuildHeight) continue
            if (chunk == null || chunkX != (bx shr 4) || chunkZ != (bz shr 4)) {
                chunkX = bx shr 4
                chunkZ = bz shr 4
                chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: return limit + 1
            }
            pos.set(bx, by, bz)
            if (SkyExposure.blocksSight(chunk, pos, chunk.getBlockState(pos))) {
                count++
                if (count > limit) return count
            }
        }
        return count
    }
}
