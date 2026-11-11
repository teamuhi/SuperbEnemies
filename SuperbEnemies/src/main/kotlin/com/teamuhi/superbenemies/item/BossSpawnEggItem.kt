package com.teamuhi.superbenemies.item

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModSounds
import com.teamuhi.superbenemies.client.BossCrateItemRenderer
import com.teamuhi.superbenemies.entity.boss.common.air.BossAircraft
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import com.teamuhi.superbenemies.entity.boss.common.ground.GroundProfile
import com.teamuhi.superbenemies.entity.boss.common.ground.GroundTerrain
import com.teamuhi.superbenemies.entity.boss.common.ground.SpawnVerdict
import com.teamuhi.superbenemies.init.ModEntities
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.Difficulty
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.client.extensions.common.IClientItemExtensions
import net.minecraftforge.registries.ForgeRegistries
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.math.max

/** Where a [BossSpawnEggItem] puts its boss. */
enum class SpawnMode {
    /** The boss appears already airborne, high above the clicked position (the planes). */
    AIR,

    /** The boss is placed on the clicked surface and drives (the tanks). */
    GROUND
}

/**
 * Spawns a boss vehicle (the A-10, the AC-130 or a tank). This is a plain [Item] rather than a vanilla spawn egg because
 * the vanilla `SpawnEggItem` only accepts `Mob` entity types and the bosses are vehicles. It is drawn as a Superb Warfare
 * supply crate, and using it does not spawn the boss at once: it checks the spot, then sets down a [BossCrateEntity] that
 * plays Superb Warfare's crate opening animation and releases the boss when the lid is off.
 *
 * [SpawnMode.AIR]: the crate stands on the terrain at the clicked column; when it opens the boss appears [SPAWN_ALTITUDE]
 * blocks above the clicked position or the terrain below it (whichever is higher), facing the player's look direction, and
 * is launched with [BossAircraft.prepareLaunch]. It needs sky.
 *
 * [SpawnMode.GROUND]: the boss is placed on the clicked surface (the egg also reaches water with a right-click in the
 * air, for a boss that floats) and gets its home from [BossVehicle.prepareGround]. So it does not appear on top of the
 * player it is moved forward along the look direction, one hull length plus [GROUND_FORWARD_MARGIN] blocks if that spot is
 * free and then ever shorter distances, preferring a spot at least [PLAYER_GAP] blocks clear of the player. The spot is refused when it is within
 * [GROUND_BORDER_MARGIN] blocks of the world border, in a dimension without open terrain (a ceiling), on lava or another
 * hazard, in a fluid (unless the boss floats, see [BossVehicle.groundProfile]), or when the hull does not fit
 * ([GroundTerrain.checkSpawn]). No sky or altitude checks.
 *
 * @param type the boss entity type; the entity it creates must implement [BossAircraft] for AIR and [BossVehicle] for GROUND
 * @param clearanceHalfWidth half the width of the box that must be free of blocks around the spawn point: roughly the
 *        wingspan / 2 plus a margin for aircraft (6 for the A-10, 16 for the 28 block wide AC-130), the hull radius for
 *        a ground boss
 * @param mode where the boss is put; AIR keeps the behaviour the aircraft eggs always had
 */
class BossSpawnEggItem(
    properties: Properties,
    private val type: Supplier<out EntityType<out VehicleEntity>>,
    private val clearanceHalfWidth: Double,
    private val mode: SpawnMode = SpawnMode.AIR
) : Item(properties) {

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        if (level !is ServerLevel) return InteractionResult.SUCCESS
        val clicked = context.clickedPos
        val surface = surfacePoint(clicked, context.clickedFace, context.clickLocation)
        return if (trySpawn(level, context.player, clicked, surface, context.itemInHand)) {
            InteractionResult.CONSUME
        } else {
            InteractionResult.FAIL
        }
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level !is ServerLevel) return InteractionResultHolder.success(stack)

        // A ground boss may float, so its click can land on the water surface.
        val fluid = if (mode == SpawnMode.GROUND) ClipContext.Fluid.SOURCE_ONLY else ClipContext.Fluid.NONE
        val hit = getPlayerPOVHitResult(level, player, fluid)
        val base = if (hit.type == HitResult.Type.BLOCK) hit.blockPos else player.blockPosition()
        val surface = if (hit.type == HitResult.Type.BLOCK) surfacePoint(base, hit.direction, hit.location) else null
        return if (trySpawn(level, player, base, surface, stack)) {
            InteractionResultHolder.consume(stack)
        } else {
            InteractionResultHolder.fail(stack)
        }
    }

    /** The exact spot a ground boss stands at for a click on [face] of [clicked]: the surface for the top face, else beside it. */
    private fun surfacePoint(clicked: BlockPos, face: Direction, location: Vec3): Vec3 =
        if (face == Direction.UP) Vec3(clicked.x + 0.5, location.y, clicked.z + 0.5)
        else clicked.relative(face).let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }

    private fun trySpawn(level: ServerLevel, player: Player?, base: BlockPos, surface: Vec3?, stack: ItemStack): Boolean {
        fun refuse(key: String): Boolean {
            player?.displayClientMessage(
                Component.translatable("message.superbenemies.spawn_refused.$key").withStyle(ChatFormatting.RED),
                true
            )
            return false
        }

        if (level.difficulty == Difficulty.PEACEFUL) return refuse("peaceful")
        return when (mode) {
            SpawnMode.AIR -> spawnAir(level, player, base, stack, ::refuse)
            SpawnMode.GROUND -> spawnGround(level, player, base, surface, stack, ::refuse)
        }
    }

    // ================================================================================================== air

    private fun spawnAir(
        level: ServerLevel, player: Player?, base: BlockPos, stack: ItemStack, refuse: (String) -> Boolean
    ): Boolean {
        // Nether-style dimensions have a bedrock ceiling: there is no sky to fly in.
        if (level.dimensionType().hasCeiling()) return refuse("ceiling")

        val x = base.x + 0.5
        val z = base.z + 0.5
        if (!level.worldBorder.isWithinBounds(x, z, BORDER_MARGIN)) return refuse("border")

        val terrainY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.x, base.z)
        // Clicking deep underground: the heightmap surface is far above, so the sky is not reachable.
        if (terrainY - base.y > MAX_COVER) return refuse("no_sky")

        val spawnY = max(base.y, terrainY) + SPAWN_ALTITUDE
        if (spawnY + HEADROOM > level.maxBuildHeight) return refuse("too_high")

        // The airframe plus a safety margin must be free of blocks.
        val bossType = type.get()
        val half = clearanceHalfWidth
        // Vertical extent from the type's hitbox (at least the A-10's 7 blocks), so the tall AC-130 is covered too.
        val height = max(bossType.height.toDouble() + 2.0, MIN_CLEARANCE_HEIGHT)
        val clearance = AABB(x - half, spawnY - 2.0, z - half, x + half, spawnY - 2.0 + height, z + half)
        if (!level.noCollision(clearance)) return refuse("obstructed")

        // Misconfigured registration: the boss must be an aircraft. The probe is thrown away; the crate makes the real one.
        val probe = bossType.create(level) ?: return refuse("failed")
        val aircraft = probe is BossAircraft
        probe.discard()
        if (!aircraft) return refuse("failed")

        val yaw = player?.yRot ?: level.random.nextFloat() * 360f
        // The crate stands on the terrain (or on the clicked block when that is higher); the plane leaves it from the sky.
        val crateY = max(base.y + 1.0, terrainY.toDouble())
        return placeCrate(
            level, player, stack, Vec3(x, crateY, z), Vec3(x, spawnY, z), Vec3(x, terrainY.toDouble(), z), yaw, refuse
        )
    }

    // ================================================================================================== ground

    private fun spawnGround(
        level: ServerLevel, player: Player?, base: BlockPos, surface: Vec3?, stack: ItemStack, refuse: (String) -> Boolean
    ): Boolean {
        // The route finder and the terrain sampling read the surface heightmap: a bedrock ceiling makes that useless.
        if (level.dimensionType().hasCeiling()) return refuse("ground_ceiling")

        val clicked = surface ?: Vec3(base.x + 0.5, base.y + 1.0, base.z + 0.5)
        if (!level.worldBorder.isWithinBounds(clicked.x, clicked.z, GROUND_BORDER_MARGIN)) return refuse("border")

        val bossType = type.get()
        val boss = bossType.create(level) ?: return refuse("failed")
        if (boss !is BossVehicle || !boss.groundBoss) {
            boss.discard()
            return refuse("failed")
        }

        val profile = boss.groundProfile()
        boss.discard() // only a probe for its profile; the crate makes the real boss
        val half = max(clearanceHalfWidth, bossType.width / 2.0)
        val height = bossType.height.toDouble() + GROUND_HEADROOM
        val yaw = player?.yRot ?: level.random.nextFloat() * 360f
        val radians = Math.toRadians(yaw.toDouble())
        val forward = Vec3(-Math.sin(radians), 0.0, Math.cos(radians))

        // Furthest first: the first spot that fits and is clear of the player wins; failing that, the first that fits.
        var chosen: Vec3? = null
        var crowded: Vec3? = null
        var clickedRefusal = "ground_obstructed"
        for (fraction in FORWARD_FRACTIONS) {
            val offset = (half * 2.0 + GROUND_FORWARD_MARGIN) * fraction
            val candidate = if (offset == 0.0) clicked else forwardPoint(level, clicked, forward, offset, profile) ?: continue
            if (!level.worldBorder.isWithinBounds(candidate.x, candidate.z, GROUND_BORDER_MARGIN)) continue
            val verdict = GroundTerrain.checkSpawn(
                level, candidate.x, candidate.y + GROUND_LIFT, candidate.z, half, height, profile
            )
            if (verdict != SpawnVerdict.OK) {
                if (offset == 0.0) clickedRefusal = refusalKey(verdict)
                continue
            }
            val gap = if (player == null) Double.MAX_VALUE else Math.hypot(candidate.x - player.x, candidate.z - player.z)
            if (gap >= half + PLAYER_GAP) {
                chosen = candidate
                break
            }
            if (crowded == null) crowded = candidate
        }
        val point = chosen ?: crowded
        if (point == null) return refuse(clickedRefusal)

        // The crate stands where the boss will be set down (the hull is lifted a little off the floor, the crate is not).
        return placeCrate(
            level, player, stack, point, Vec3(point.x, point.y + GROUND_LIFT, point.z), point, Mth.wrapDegrees(yaw), refuse
        )
    }

    /** The ground [distance] blocks from [from] along [direction], or null when that chunk is not loaded. */
    private fun forwardPoint(level: ServerLevel, from: Vec3, direction: Vec3, distance: Double, profile: GroundProfile): Vec3? {
        val x = from.x + direction.x * distance
        val z = from.z + direction.z * distance
        val y = GroundTerrain.spawnY(level, Mth.floor(x), Mth.floor(z), profile) ?: return null
        return Vec3(x, y, z)
    }

    private fun refusalKey(verdict: SpawnVerdict): String = when (verdict) {
        SpawnVerdict.OK -> "failed"
        SpawnVerdict.UNLOADED -> "unloaded"
        SpawnVerdict.OBSTRUCTED -> "ground_obstructed"
        SpawnVerdict.FLUID -> "water"
        SpawnVerdict.LAVA -> "hazard"
    }

    /**
     * Sets down the crate that releases the boss: [crateAt] is where it stands, [spawn] and [home] are what the boss gets
     * when it is let out (position, and the point it takes as home for `prepareLaunch` / `prepareGround`). Uses up the egg
     * only once the crate is in the world.
     */
    private fun placeCrate(
        level: ServerLevel, player: Player?, stack: ItemStack,
        crateAt: Vec3, spawn: Vec3, home: Vec3, yaw: Float, refuse: (String) -> Boolean
    ): Boolean {
        val bossKey = ForgeRegistries.ENTITY_TYPES.getKey(type.get()) ?: return refuse("failed")
        val crate = ModEntities.BOSS_CRATE.get().create(level) ?: return refuse("failed")
        crate.moveTo(crateAt.x, crateAt.y, crateAt.z, yaw, 0f)
        crate.configure(bossKey, mode, spawn, home, ForgeRegistries.ITEMS.getKey(this))
        if (!level.addFreshEntity(crate)) return refuse("failed")

        level.playSound(null, crateAt.x, crateAt.y, crateAt.z, ModSounds.OPEN.get(), SoundSource.BLOCKS, 1f, 1f)
        finish(level, player, stack, crateAt)
        return true
    }

    private fun finish(level: ServerLevel, player: Player?, stack: ItemStack, at: Vec3) {
        level.gameEvent(player, GameEvent.ENTITY_PLACE, at)
        if (player != null) {
            player.cooldowns.addCooldown(this, COOLDOWN_TICKS)
            if (!player.abilities.instabuild) stack.shrink(1)
        }
    }

    /** The egg is drawn as Superb Warfare's crate (its item models are `builtin/entity` with the container's transforms). */
    override fun initializeClient(consumer: Consumer<IClientItemExtensions>) {
        super.initializeClient(consumer)
        consumer.accept(object : IClientItemExtensions {
            private var renderer: BlockEntityWithoutLevelRenderer? = null

            override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer {
                val minecraft = Minecraft.getInstance()
                return renderer ?: BossCrateItemRenderer(minecraft.blockEntityRenderDispatcher, minecraft.entityModels)
                    .also { renderer = it }
            }
        })
    }

    private companion object {
        const val SPAWN_ALTITUDE = 60.0
        const val HEADROOM = 40.0
        const val MIN_CLEARANCE_HEIGHT = 7.0
        const val MAX_COVER = 60
        const val BORDER_MARGIN = 64.0
        const val COOLDOWN_TICKS = 40

        /** Ground bosses may stand closer to the world border than the planes may circle. */
        const val GROUND_BORDER_MARGIN = 16.0

        /** The hull is set down this far above the clicked surface so it does not start inside the floor. */
        const val GROUND_LIFT = 0.1

        /** Extra free height above the hull for the clearance box (turret, antennas). */
        const val GROUND_HEADROOM = 0.5

        /** A ground boss is put one hull length plus this many blocks ahead of the click, along the player's look direction. */
        const val GROUND_FORWARD_MARGIN = 6.0

        /** Blocks between the hull edge and the player a forward spot should leave. */
        const val PLAYER_GAP = 3.0

        /** Shares of the full forward distance to try, furthest first; 0 is the clicked spot itself. */
        val FORWARD_FRACTIONS = doubleArrayOf(1.0, 0.75, 0.5, 0.25, 0.0)
    }
}
