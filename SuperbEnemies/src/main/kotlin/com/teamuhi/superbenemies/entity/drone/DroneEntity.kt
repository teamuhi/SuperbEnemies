package com.teamuhi.superbenemies.entity.drone

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.CustomExplosion
import com.teamuhi.superbenemies.config.DroneConfig
import com.teamuhi.superbenemies.debug.AiKind
import com.teamuhi.superbenemies.debug.AiThought
import com.teamuhi.superbenemies.debug.runningGoalName
import com.teamuhi.superbenemies.entity.boss.common.ai.SkyExposure
import com.teamuhi.superbenemies.entity.boss.common.ai.TargetChoice
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedVehicles
import com.teamuhi.superbenemies.entity.boss.common.ai.isEnemyTank
import com.teamuhi.superbenemies.entity.drone.ai.DroneApproach
import com.teamuhi.superbenemies.entity.drone.ai.DroneAttackGoal
import com.teamuhi.superbenemies.init.ModItems
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.MoveControl
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.animatable.GeoEntity
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.core.animation.AnimatableManager
import software.bernie.geckolib.util.GeckoLibUtil
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A small, fast, fragile drone (drawn with Superb Warfare's own drone model) that carries a mortar shell: it chases a
 * player (or a tank, by the `playerWeight` / `tankWeight` target weights), tells the other drones where its target is and
 * blows up next to it.
 *
 * It scans for its best target every [SCAN_INTERVAL] ticks ([chaseTarget]); [DroneAttackGoal] then flies at it,
 * weaving about when `evasive` is on. While its target is in sight it [alert]s the other drones each scan: they take that
 * target as their own for `alertSeconds`, wherever it is and whether or not they see it themselves. Within
 * `detonateDistance` of the target's hitbox (and after `armSeconds`) it [detonate]s, which uses Superb Warfare's own
 * explosion with the shell's damage and radius, and the drone is gone. Without a player it drifts around at a gentle pace.
 *
 * It sees shots coming ([dodgeProjectiles]) and sidesteps them with a [dash]; a hit makes it dash as well. The tank bosses
 * shoot at it (see `DroneConfig.TANK_DRONE_WEIGHT`).
 *
 * It is a [PathfinderMob] only to get goals and navigation; flight is its own: [DroneMoveControl] steers the velocity
 * towards the wanted point with a limited acceleration (`agility`), and [travel] just moves and brakes it. It has no gravity
 * and takes no fall damage. Health and armor are read live from [DroneConfig] every [ATTRIBUTE_SYNC_INTERVAL] ticks. It
 * never despawns, drops nothing and gives no XP; sneak + right-click with empty hands picks it up again as a spawn egg.
 * Its label is Superb Warfare's vehicle label, drawn by `DroneLabelOverlay`, not a name tag.
 */
class DroneEntity(type: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(type, level), GeoEntity {

    private val animationCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    /** Console voice for the AI Thinking debug option; the state shown is the goal that is running. */
    val thought: AiThought by lazy { AiThought(this, AiKind.DRONE) { runningGoalName(this) } }

    /** The player or tank being chased, or null. Server only, not saved: a reloaded drone looks for one again. */
    var chaseTarget: Entity? = null
        private set

    /** The target another drone reported, and the game time its report runs out. Server only, not saved. */
    private var alertedTarget: Entity? = null
    private var alertedUntil = 0L

    /** Set by a hit or a dodge: [DroneAttackGoal] reads and clears it to change course at once. */
    var jinkRequested = false

    /** The side of the target this drone dives in from (`fanOut`), set by [DroneAttackGoal]; the other drones read it to spread out. */
    var approach: DroneApproach? = null

    private var lastFlashTick = -FLASH_INTERVAL
    private var nextDodgeTick = 0
    private var attributesSynced = false

    init {
        moveControl = DroneMoveControl(this)
        setNoGravity(true)
        setPersistenceRequired()
    }

    override fun registerGoals() {
        goalSelector.addGoal(1, DroneAttackGoal(this))
        goalSelector.addGoal(2, WaterAvoidingRandomFlyingGoal(this, IDLE_SPEED))
    }

    override fun createNavigation(level: Level): PathNavigation =
        FlyingPathNavigation(this, level).also {
            it.setCanOpenDoors(false)
            it.setCanFloat(true)
            it.setCanPassDoors(true)
        }

    // ================================================================================================== flight

    /** Top speed in blocks per tick. */
    fun topSpeed(): Double = DroneConfig.FLY_SPEED.get() / TICKS_PER_SECOND

    /** Velocity change per tick (blocks per tick squared). */
    fun acceleration(): Double = BASE_ACCELERATION * DroneConfig.AGILITY.get()

    /**
     * Flies for one tick: the velocity [DroneMoveControl] set is applied, then braked while the drone has nowhere to go
     * and while it is faster than its top speed (after a dash). Same shape as the allay's own `travel`.
     */
    override fun travel(travelVector: Vec3) {
        if (isControlledByLocalInstance) {
            move(MoverType.SELF, deltaMovement)
            var velocity = deltaMovement
            if (!moveControl.hasWanted()) velocity = velocity.scale(IDLE_BRAKE)
            if (velocity.lengthSqr() > topSpeed() * topSpeed() * OVERSPEED_MARGIN) velocity = velocity.scale(OVERSPEED_BRAKE)
            deltaMovement = velocity
        }
        calculateEntityAnimation(false)
    }

    /**
     * Throws the drone along [direction] (a unit vector) faster than its top speed; it brakes back by itself. A dash also
     * asks the attack goal for a new weave.
     */
    private fun dash(direction: Vec3) {
        deltaMovement = deltaMovement.add(direction.scale(DASH_SPEED))
        jinkRequested = true
    }

    /** Dashes sideways (and up or down) away from whatever hit it. */
    private fun dashAwayFrom(source: DamageSource) {
        val from = source.sourcePosition ?: source.entity?.position()
        val away = if (from != null) position().subtract(from).multiply(1.0, 0.0, 1.0) else Vec3.ZERO
        val dice = getRandom()
        val direction = if (away.lengthSqr() < 1.0e-4) Vec3(dice.nextDouble() - 0.5, 0.0, dice.nextDouble() - 0.5) else away
        val side = if (dice.nextBoolean()) 1.0 else -1.0
        val unit = direction.normalize()
        dash(Vec3(-unit.z * side, (dice.nextDouble() - 0.4) * 0.6, unit.x * side))
    }

    override fun causeFallDamage(distance: Float, multiplier: Float, source: DamageSource): Boolean = false

    /** A drone has no feet: skimming over the ground makes no footsteps. */
    override fun playStepSound(pos: BlockPos, state: BlockState) {}

    // ================================================================================================== chase target

    /**
     * How much the drone wants [entity] (0 = not at all): `playerWeight` for a player the drone may follow (alive, not a
     * spectator, creative only with `chaseCreative`), `tankWeight` for a tank the bosses would fight too (see
     * [isEnemyTank]: a manned armoured vehicle or an uncrippled tank boss, no wreck). Read live, so a weight set to 0 drops
     * a target that is held already.
     */
    private fun weightOf(entity: Entity): Int = when {
        !entity.isAlive || entity.isRemoved -> 0
        entity is Player -> if (entity.isSpectator || (entity.isCreative && !DroneConfig.CHASE_CREATIVE.get())) 0 else DroneConfig.PLAYER_WEIGHT.get()
        entity is VehicleEntity -> if (isEnemyTank(this, entity)) DroneConfig.TANK_WEIGHT.get() else 0
        else -> 0
    }

    /** Whether the drone sees [entity] right now: in range and, with `requireLineOfSight`, a clear line to it. */
    private fun sees(entity: Entity, range: Double): Boolean =
        distanceToSqr(entity) <= range * range &&
                (!DroneConfig.REQUIRE_LINE_OF_SIGHT.get() || SkyExposure.hasLineOfSight(this, entity))

    /**
     * Picks the best target within `detectionRange` (and in sight, with `requireLineOfSight`): the lowest distance divided by
     * weight, so a weight-5 target counts as five times closer than a weight-1 one. A held target is dropped when it has no
     * weight left or is beyond `chaseRange` (never less than `detectionRange`), and is otherwise only swapped for a candidate
     * that scores clearly better ([SWITCH_FACTOR]), so the drone does not flip between two targets. A target another drone
     * reported ([alert]) is a candidate wherever it is and is not dropped for distance. Then, while the target is in sight,
     * tells the other drones.
     */
    private fun scan(level: ServerLevel) {
        val range = DroneConfig.DETECTION_RANGE.get().toDouble()
        val followRange = max(DroneConfig.CHASE_RANGE.get().toDouble(), range)
        val alerted = alertedTarget(level)
        val previous = chaseTarget
        val held = chaseTarget?.takeIf {
            weightOf(it) > 0 && it.level() === level && (it === alerted || distanceToSqr(it) <= followRange * followRange)
        }
        val best = pickTarget(level, range, alerted)
        chaseTarget = when {
            held == null -> best
            best == null || best === held -> held
            score(best) < score(held) * SWITCH_FACTOR -> best
            else -> held
        }
        val chosen = chaseTarget
        if (chosen !== previous) {
            thought.event("scan") {
                when {
                    chosen == null -> "lost ${previous?.name?.string}: ${if (previous != null && weightOf(previous) <= 0) "no longer wanted" else "out of chase range"}, nothing else in range"
                    previous == null -> "picked ${chosen.name.string}${if (chosen === alerted) " (reported by another drone)" else ""}"
                    else -> "switched ${previous.name.string} -> ${chosen.name.string}${if (held == null) " (old target dropped)" else " (clearly better score)"}"
                }
            }
        } else if (chosen == null) {
            thought.think("scan") { "no target in range" }
        }
        val target = chosen ?: return
        if (DroneConfig.ALERT_DRONES.get() && sees(target, range)) alertDrones(level, target)
    }

    /** The target another drone reported, while the report stands and the target is still wanted and in [level]; else null. */
    private fun alertedTarget(level: ServerLevel): Entity? {
        val target = alertedTarget ?: return null
        if (level.gameTime >= alertedUntil || weightOf(target) <= 0 || target.level() !== level) {
            alertedTarget = null
            return null
        }
        return target
    }

    /**
     * Another drone saw [target]: take it as a candidate until [until] (game time), however far away it is and whether or not
     * this drone sees it. A newer report replaces an older one.
     */
    private fun alert(target: Entity, until: Long) {
        thought.think("alert") { "reported by another drone: ${target.name.string}" }
        alertedTarget = target
        alertedUntil = until
    }

    /**
     * Tells every other drone within `alertRadius` blocks (horizontally; 0 = the whole dimension) of this one where [target] is,
     * for `alertSeconds`. They act on it at their next scan. A drone only passes on what it sees itself, so reports cannot loop.
     */
    private fun alertDrones(level: ServerLevel, target: Entity) {
        val radius = DroneConfig.ALERT_RADIUS.get().toDouble()
        val until = level.gameTime + DroneConfig.ALERT_SECONDS.get() * TICKS_PER_SECOND.toLong()
        thought.think("alertDrones") { "telling the other drones about ${target.name.string}" }
        for (other in TrackedDrones.all(level)) {
            if (other === this || other !is DroneEntity) continue
            if (radius > 0.0) {
                val dx = other.x - x
                val dz = other.z - z
                if (dx * dx + dz * dz > radius * radius) continue
            }
            other.alert(target, until)
        }
    }

    /** The best-scoring target within [range], or null; the [alerted] one counts wherever it is. */
    private fun pickTarget(level: ServerLevel, range: Double, alerted: Entity?): Entity? {
        val candidates = ArrayList<Entity>()
        if (alerted != null) candidates.add(alerted)
        if (DroneConfig.PLAYER_WEIGHT.get() > 0) {
            for (player in level.players()) if (player !== alerted && weightOf(player) > 0 && sees(player, range)) candidates.add(player)
        }
        if (DroneConfig.TANK_WEIGHT.get() > 0) {
            for (vehicle in TrackedVehicles.all(level)) if (vehicle !== alerted && weightOf(vehicle) > 0 && sees(vehicle, range)) candidates.add(vehicle)
        }
        return TargetChoice.bestScore(candidates, ::weightOf, ::distanceOf)
    }

    /** `distance / weight`: lower is better (see [TargetChoice.score]). */
    private fun score(entity: Entity): Double = TargetChoice.score(distanceOf(entity), weightOf(entity))

    private fun distanceOf(entity: Entity): Double = sqrt(distanceToSqr(entity))

    // ================================================================================================== shell

    /** Blows up next to the target once the shell is armed and the target's hitbox is within `detonateDistance`. */
    private fun tryDetonate(level: ServerLevel) {
        val target = chaseTarget?.takeIf { weightOf(it) > 0 } ?: return // a dead or no longer wanted target is not blown up
        val armed = tickCount >= DroneConfig.ARM_SECONDS.get() * TICKS_PER_SECOND
        thought.think("tryDetonate") { if (armed) "shell armed, closing on ${target.name.string}" else "shell not armed yet, holding" }
        if (!armed) return
        val reach = DroneConfig.DETONATE_DISTANCE.get()
        if (target.boundingBox.distanceToSqr(position()) > reach * reach) return
        detonate(level)
    }

    /** The mortar shell goes off where the drone is, with the damage and radius from the config, and the drone is gone. */
    private fun detonate(level: ServerLevel) {
        thought.event("detonate") { "detonating next to ${chaseTarget?.name?.string} at ${blockPosition().toShortString()}" }
        CustomExplosion.Builder(this)
            .attacker(this)
            .damage(DroneConfig.EXPLOSION_DAMAGE.get().toFloat())
            .radius(DroneConfig.EXPLOSION_RADIUS.get().toFloat())
            .position(position())
            .destroyBlock(DroneConfig.DESTROY_BLOCKS.get())
            .explode()
        discard()
    }

    // ================================================================================================== dodging

    /**
     * Looks for a projectile that will pass within [DODGE_MISS_DISTANCE] of the drone in the next [DODGE_LOOKAHEAD_TICKS]
     * ticks and, with `dodgeChance`, dashes across its line of flight, away from where it would pass. One look per tick,
     * and at most one dodge per [DODGE_COOLDOWN] ticks so a stream of bullets does not freeze the drone in a dash loop.
     */
    private fun dodgeProjectiles(level: ServerLevel) {
        if (!DroneConfig.DODGE_PROJECTILES.get() || tickCount < nextDodgeTick) return
        val centre = boundingBox.center
        val hitRadius = bbWidth / 2.0 + DODGE_MISS_DISTANCE
        val box = boundingBox.inflate(DODGE_SCAN_RADIUS)
        for (projectile in level.getEntitiesOfClass(Projectile::class.java, box) { it.owner !== this }) {
            val velocity = projectile.deltaMovement
            val speedSqr = velocity.lengthSqr()
            if (speedSqr < MIN_PROJECTILE_SPEED_SQR) continue // lying in a block or drifting
            val relative = centre.subtract(projectile.position())
            val time = relative.dot(velocity) / speedSqr
            if (time < 0.0 || time > DODGE_LOOKAHEAD_TICKS) continue // already past, or too far to matter yet
            val closest = projectile.position().add(velocity.scale(time))
            val offset = centre.subtract(closest)
            if (offset.lengthSqr() > hitRadius * hitRadius) continue // it misses by itself

            nextDodgeTick = tickCount + DODGE_COOLDOWN
            if (getRandom().nextInt(PERCENT) >= DroneConfig.DODGE_CHANCE.get()) { // did not notice it in time
                thought.event("dodgeProjectiles") { "did not notice incoming ${projectile.type.descriptionId}" }
                return
            }
            thought.event("dodgeProjectiles") { "dodging incoming ${projectile.type.descriptionId}" }
            // Across the line of flight, away from where the shot would pass; any direction when it comes dead centre.
            val along = velocity.scale(1.0 / sqrt(speedSqr))
            var aside = offset.subtract(along.scale(offset.dot(along)))
            if (aside.lengthSqr() < 1.0e-4) {
                aside = along.cross(Vec3(0.0, 1.0, 0.0)).scale(if (getRandom().nextBoolean()) 1.0 else -1.0)
                if (aside.lengthSqr() < 1.0e-4) aside = Vec3(1.0, 0.0, 0.0)
            }
            dash(aside.normalize())
            return
        }
    }

    // ================================================================================================== per-tick upkeep

    override fun customServerAiStep() {
        super.customServerAiStep()
        val level = level() as? ServerLevel ?: return
        thought.heartbeat {
            val target = chaseTarget
            "goal=${runningGoalName(this)} target=${target?.name?.string ?: "none"} dist=${target?.let { "%.0f".format(distanceOf(it)) } ?: "-"} " +
                "hp=${(health / maxHealth * 100f).toInt()}% pos=${blockPosition().toShortString()}"
        }
        dodgeProjectiles(level)
        if ((tickCount + id) % SCAN_INTERVAL == 0) scan(level)
        tryDetonate(level)
        if (isRemoved) return
        if (!attributesSynced || tickCount % ATTRIBUTE_SYNC_INTERVAL == 0) syncAttributes()
        if (DroneConfig.INVULNERABLE.get() && isOnFire) clearFire()
    }

    /**
     * Applies maximum health, armor and armor toughness from [DroneConfig]. Raising the maximum tops up a drone that was full;
     * lowering it clamps the current health.
     */
    private fun syncAttributes() {
        attributesSynced = true
        setBase(Attributes.ARMOR, DroneConfig.ARMOR.get())
        setBase(Attributes.ARMOR_TOUGHNESS, DroneConfig.ARMOR_TOUGHNESS.get())
        val attribute = getAttribute(Attributes.MAX_HEALTH) ?: return
        val wanted = DroneConfig.MAX_HEALTH.get().toDouble()
        if (attribute.baseValue == wanted) return
        val wasFull = health >= maxHealth - FULL_EPSILON
        attribute.baseValue = wanted
        if (wasFull || health > maxHealth) health = maxHealth
    }

    private fun setBase(attribute: Attribute, value: Double) {
        val instance = getAttribute(attribute) ?: return
        if (instance.baseValue != value) instance.baseValue = value
    }

    // ================================================================================================== damage / death

    /**
     * With `invulnerable` on the drone takes nothing but still reacts: the hurt flash is broadcast (at most once per
     * [FLASH_INTERVAL] ticks) and it still dashes. Damage tagged `bypasses_invulnerability` (`/kill`, the void) goes through.
     */
    override fun hurt(source: DamageSource, amount: Float): Boolean {
        if (level().isClientSide || !isAlive) return super.hurt(source, amount)
        if (DroneConfig.INVULNERABLE.get() && !source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            if (tickCount - lastFlashTick >= FLASH_INTERVAL) {
                lastFlashTick = tickCount
                level().broadcastDamageEvent(this, source)
            }
            thought.event("hurt") { "hit by ${source.msgId} while invulnerable${if (DroneConfig.EVASIVE.get()) ", dashing away" else ""}" }
            if (DroneConfig.EVASIVE.get()) dashAwayFrom(source)
            return false
        }
        val before = health
        val result = super.hurt(source, amount)
        if (result && before - health > 0f) {
            val dashes = DroneConfig.EVASIVE.get() && isAlive
            thought.event("hurt") { "took ${"%.1f".format(before - health)} damage from ${source.msgId}${if (dashes) ", dashing away" else ""}" }
            if (dashes) dashAwayFrom(source)
        }
        return result
    }

    // Silent: the vanilla hurt/death grunts do not fit a machine, and the death already has its own bang in [die].
    override fun getHurtSound(source: DamageSource): SoundEvent? = null
    override fun getDeathSound(): SoundEvent? = null

    /**
     * A shot-down drone blows apart at once (puff of smoke and a small bang, but no blast: the shell is a dud) instead of
     * playing the death fall. Announces the kill in chat when `showDeathMessage` is on.
     */
    override fun die(source: DamageSource) {
        val firstDeath = !isRemoved && !dead
        thought.event("die") { "shot down by ${source.msgId}" }
        super.die(source)
        val serverLevel = level() as? ServerLevel ?: return
        if (firstDeath && DroneConfig.SHOW_DEATH_MESSAGE.get()) {
            serverLevel.server.playerList.broadcastSystemMessage(combatTracker.deathMessage, false)
        }
        serverLevel.sendParticles(ParticleTypes.EXPLOSION, x, y + bbHeight / 2.0, z, 1, 0.0, 0.0, 0.0, 0.0)
        serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + bbHeight / 2.0, z, 12, 0.25, 0.2, 0.25, 0.03)
        serverLevel.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 0.6f, 1.6f)
        discard()
    }

    // ================================================================================================== interaction / loot

    /** Sneak + right-click with empty hands removes the drone and returns its spawn egg (not in creative). */
    override fun mobInteract(player: Player, hand: InteractionHand): InteractionResult {
        if (player.isSecondaryUseActive && player.mainHandItem.isEmpty && player.offhandItem.isEmpty) {
            val serverLevel = level() as? ServerLevel ?: return InteractionResult.sidedSuccess(true)
            if (!player.isCreative) {
                val egg = ItemStack(ModItems.DRONE_SPAWN_EGG.get())
                if (!player.addItem(egg)) player.drop(egg, false)
            }
            serverLevel.sendParticles(ParticleTypes.POOF, x, y + bbHeight / 2.0, z, 8, 0.25, 0.2, 0.25, 0.02)
            discard()
            return InteractionResult.sidedSuccess(false)
        }
        return super.mobInteract(player, hand)
    }

    override fun removeWhenFarAway(distanceToClosestPlayer: Double): Boolean = false
    override fun canBeLeashed(player: Player): Boolean = false
    override fun shouldDropLoot(): Boolean = false
    override fun shouldDropExperience(): Boolean = false

    // ================================================================================================== GeckoLib

    // The wings are spun by the renderer's model; there is no animation to play.
    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {}

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = animationCache

    // ================================================================================================== move control

    /**
     * Steers the velocity towards the wanted point: at most `acceleration()` per tick, at most the top speed times the
     * requested speed modifier, and slower than that near the point so the drone settles instead of overshooting. Turns to
     * face its heading. Whatever [travel] does with the velocity is its own business.
     */
    private class DroneMoveControl(private val drone: DroneEntity) : MoveControl(drone) {
        override fun tick() {
            if (operation != Operation.MOVE_TO) return
            val dx = wantedX - drone.x
            val dy = wantedY - drone.y
            val dz = wantedZ - drone.z
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            if (distance < ARRIVE_DISTANCE) {
                operation = Operation.WAIT
                return
            }
            val speed = min(drone.topSpeed() * speedModifier, distance * APPROACH_GAIN)
            val desired = Vec3(dx, dy, dz).scale(speed / distance)
            val current = drone.deltaMovement
            val change = desired.subtract(current)
            val length = change.length()
            val acceleration = drone.acceleration()
            drone.deltaMovement = current.add(if (length > acceleration) change.scale(acceleration / length) else change)

            if (dx * dx + dz * dz > HEADING_EPSILON) {
                val heading = (atan2(dz, dx) * Mth.RAD_TO_DEG).toFloat() - 90f
                drone.yRot = rotlerp(drone.yRot, heading, TURN_SPEED)
            }
        }
    }

    companion object {
        private const val TICKS_PER_SECOND = 20.0
        private const val PERCENT = 100
        private const val SCAN_INTERVAL = 10
        private const val ATTRIBUTE_SYNC_INTERVAL = 20
        private const val FULL_EPSILON = 0.01f

        /** Minimum ticks between two broadcast hurt flashes (the client's own hurt animation lasts 10 ticks). */
        private const val FLASH_INTERVAL = 10

        /** A new target replaces the held one only when its score is below this fraction of the held one's. */
        private const val SWITCH_FACTOR = 0.7

        /** Speed modifier of the idle wandering (a quarter of the top speed). */
        private const val IDLE_SPEED = 0.25

        /** Blocks per tick squared at `agility` 1.0: the top speed of 14 blocks per second is reached in about 8 ticks. */
        private const val BASE_ACCELERATION = 0.09

        /** Velocity kept per tick while the drone has nowhere to go. */
        private const val IDLE_BRAKE = 0.85

        /** Above top speed squared times this the drone is braked by [OVERSPEED_BRAKE] per tick (a dash decays in a few ticks). */
        private const val OVERSPEED_MARGIN = 1.1
        private const val OVERSPEED_BRAKE = 0.9

        /** Speed of a dash (dodge or hit), blocks per tick. */
        private const val DASH_SPEED = 1.1

        /** Projectiles are looked for this far (blocks) around the drone, and this many ticks ahead. */
        private const val DODGE_SCAN_RADIUS = 24.0
        private const val DODGE_LOOKAHEAD_TICKS = 12.0

        /** A shot that would pass within this much (blocks) of the hitbox counts as a hit. */
        private const val DODGE_MISS_DISTANCE = 0.6
        private const val DODGE_COOLDOWN = 6

        /** Projectiles slower than this (blocks per tick, squared) are stuck or drifting, not shots. */
        private const val MIN_PROJECTILE_SPEED_SQR = 0.04

        private const val ARRIVE_DISTANCE = 0.25
        private const val APPROACH_GAIN = 0.35
        private const val HEADING_EPSILON = 1.0e-4
        private const val TURN_SPEED = 40f

        /** Fragile and quick: the settings overwrite health and armor as soon as the entity spawns. */
        fun createAttributes(): AttributeSupplier.Builder =
            Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 6.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.ARMOR, 0.0)
                .add(Attributes.ARMOR_TOUGHNESS, 0.0)
    }
}
