package com.teamuhi.superbenemies.entity.dummy.ai

import com.teamuhi.superbenemies.entity.boss.common.air.BossAircraft
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedBosses
import com.teamuhi.superbenemies.entity.boss.common.ground.TankBossEntity
import com.teamuhi.superbenemies.entity.dummy.HumanoidDummyEntity
import com.teamuhi.superbenemies.util.TickBudget
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import kotlin.math.cos
import kotlin.math.sin

/** Small helpers shared by the dummy's and the soldiers' goals. */
internal object DummyAi {

    /** Path searches (`createPath` / `moveTo`) all dummies may start per tick. */
    val pathfinds = TickBudget(3)

    /** Idle-scan line-of-sight raycasts all soldiers may start per tick. */
    val sightRays = TickBudget(4)

    /** Floor heights tried around a dummy's own by [standingSpot], nearest first (up to 4 above and 6 below). */
    private val Y_OFFSETS = intArrayOf(0, 1, -1, 2, -2, 3, -3, 4, -4, -5, -6)

    /** A boss within this many blocks makes the dummy look at it and (with `hideFromBosses` for the player dummy) run for cover. */
    const val BOSS_RANGE = 400.0

    /**
     * Component of the world-space horizontal vector ([dx], [dz]) along the facing direction of a mob with yaw
     * [yawDeg]. Minecraft: yaw 0 faces +z, and a positive left input moves towards +x at yaw 0.
     */
    fun localForward(yawDeg: Float, dx: Double, dz: Double): Float {
        val yaw = Math.toRadians(yawDeg.toDouble())
        return (-dx * sin(yaw) + dz * cos(yaw)).toFloat()
    }

    /** Component of ([dx], [dz]) along the mob's left side (see [localForward]). */
    fun localLeft(yawDeg: Float, dx: Double, dz: Double): Float {
        val yaw = Math.toRadians(yawDeg.toDouble())
        return (dx * cos(yaw) + dz * sin(yaw)).toFloat()
    }

    /** The nearest live boss aircraft within [BOSS_RANGE] blocks of [dummy] (any state), or null. */
    fun nearestBoss(dummy: HumanoidDummyEntity, includeCrippled: Boolean): Entity? {
        var best: Entity? = null
        var bestDistance = BOSS_RANGE * BOSS_RANGE
        for (entity in TrackedBosses.near(dummy.level(), dummy.position(), BOSS_RANGE)) {
            val boss = entity as? BossAircraft ?: continue
            if (!includeCrippled && boss.crippled) continue
            val distance = entity.distanceToSqr(dummy)
            if (distance < bestDistance) {
                bestDistance = distance
                best = entity
            }
        }
        return best
    }

    /** The nearest live tank boss within [BOSS_RANGE] blocks of [dummy] (any state unless [includeCrippled] is false), or null. */
    fun nearestTank(dummy: HumanoidDummyEntity, includeCrippled: Boolean): Entity? {
        var best: Entity? = null
        var bestDistance = BOSS_RANGE * BOSS_RANGE
        for (entity in TrackedBosses.near(dummy.level(), dummy.position(), BOSS_RANGE)) {
            val tank = entity as? TankBossEntity ?: continue
            if (!includeCrippled && tank.crippled) continue
            val distance = entity.distanceToSqr(dummy)
            if (distance < bestDistance) {
                bestDistance = distance
                best = entity
            }
        }
        return best
    }

    /**
     * The nearest-to-the-dummy's-height block in column ([x], [z]) with a solid, dry floor under it and two free, dry
     * blocks for the body, trying heights from [Y_OFFSETS] (up to 4 above and 6 below the dummy). Null in an unloaded chunk or when
     * the column has no such block.
     */
    fun standingSpot(dummy: HumanoidDummyEntity, x: Int, z: Int): BlockPos? {
        val level = dummy.level()
        val cursor = BlockPos.MutableBlockPos()
        if (!level.isLoaded(cursor.set(x, dummy.blockPosition().y, z))) return null
        val base = dummy.blockPosition().y
        // Closest to the dummy's own height first: indoors that is the floor it stands on, not the roof above.
        for (offset in Y_OFFSETS) {
            val y = base + offset
            if (y - 1 < level.minBuildHeight || y + 1 >= level.maxBuildHeight) continue
            val floor = level.getBlockState(cursor.set(x, y - 1, z))
            if (floor.getCollisionShape(level, cursor).isEmpty || !floor.fluidState.isEmpty) continue
            val feet = level.getBlockState(cursor.set(x, y, z))
            if (!feet.getCollisionShape(level, cursor).isEmpty || !feet.fluidState.isEmpty) continue
            val head = level.getBlockState(cursor.set(x, y + 1, z))
            if (!head.getCollisionShape(level, cursor).isEmpty || !head.fluidState.isEmpty) continue
            return BlockPos(x, y, z)
        }
        return null
    }
}
