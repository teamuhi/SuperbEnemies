package com.teamuhi.superbenemies.entity.boss.a10

import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.A10Entity
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.BossConfig
import com.teamuhi.superbenemies.debug.AiKind
import com.teamuhi.superbenemies.debug.AiThought
import com.teamuhi.superbenemies.config.BossKind
import com.teamuhi.superbenemies.config.TeammateSettings
import com.teamuhi.superbenemies.entity.boss.a10.ai.A10BossBrain
import com.teamuhi.superbenemies.entity.boss.common.air.BossAircraft
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.entity.boss.common.BossLifecycle
import com.teamuhi.superbenemies.entity.boss.common.BossLifecycleSettings
import com.teamuhi.superbenemies.init.ModEntities
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.BossEvent
import net.minecraft.world.Difficulty
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import com.atsuishio.superbwarfare.init.ModItems as SbwItems

/**
 * The Entity-type parameter of [A10Entity] is invariant (`EntityType<A10Entity>`), so a subclass registered under its
 * own type has to pass it through an unchecked cast. Safe: the type is only ever used to build instances of this class.
 */
@Suppress("UNCHECKED_CAST")
private fun asA10Type(type: EntityType<*>): EntityType<A10Entity> = type as EntityType<A10Entity>

/**
 * "The Plane That Burns the Sky": Superb Warfare's A-10 flown by a hidden AI pilot.
 *
 *  - Ammunition: a creative ammo box lives in the vehicle inventory, which makes the weapons and the flare dispenser
 *    draw from an unlimited supply (weapons still use their normal magazine / reload timers).
 *  - Fuel: [consumeEnergy] does nothing and the energy store is topped up.
 *  - Health: finite, shown in a boss bar that empties at 10% (where SBW's aircraft engine switches to its crash
 *    branch). Below that the plane falls, the wreck explodes on impact and the loot / XP is dropped. The bar, the
 *    crash and the payout live in the shared [BossLifecycle].
 *  - Players cannot board, loot, repair or tow it.
 *
 * Control inputs are written by [A10BossBrain] **before** `super.baseTick()` so they are in place when SBW's
 * `travel()` runs the aircraft engine in the same tick.
 */
class A10WarthogBossEntity(type: EntityType<A10WarthogBossEntity>, level: Level) : A10Entity(asA10Type(type), level),
    BossAircraft {

    val brain = A10BossBrain(this)

    private val lifecycle = BossLifecycle(
        this,
        BossLifecycleSettings(
            lootTable = LOOT_TABLE,
            titleKey = "boss.${SuperbEnemies.MOD_ID}.a10_warthog.title",
            crippledMessageKey = "message.${SuperbEnemies.MOD_ID}.a10_boss.crippled",
            defeatedMessageKey = "message.${SuperbEnemies.MOD_ID}.a10_boss.defeated",
            barColor = BossEvent.BossBarColor.RED,
            xpReward = { BossConfig.XP_REWARD.get() },
            bossBarRange = { BossConfig.BOSS_BAR_RANGE.get() },
            globalAggro = { BossConfig.GLOBAL_AGGRO.get() }
        )
    )

    /** The point on the ground the boss circles when no player is in range. */
    var homePos: Vec3 = Vec3.ZERO

    /**
     * True once health has dropped to 10% of the maximum (set by the lifecycle, read by the brain). From then on the
     * plane is lost: the brain releases the controls and SBW's aircraft engine runs its crash branch.
     */
    override val crippled: Boolean
        get() = lifecycle.crippled

    private var homeSet = false
    private var initialised = false
    private var lastDamagedTick = -1

    /** The hidden pilot, or null while it is missing (it is re-created automatically). */
    val pilot: BossCrewEntity?
        get() = firstPassenger as? BossCrewEntity

    /** Remaining health as a fraction of the maximum, 0..1 (can be slightly outside while the wreck burns down). */
    override fun healthFraction(): Float {
        val max = getMaxHealth()
        return if (max > 0f) health / max else 0f
    }

    /** Chat message to the players around the boss (same reach as the crippled / defeated broadcasts). */
    override fun announce(translationKey: String, style: Style, alsoNear: Vec3?) =
        lifecycle.broadcast(translationKey, style, alsoNear)

    /** Health fraction a replacement boss starts with; negative = full health. Saved until the first tick used it. */
    private var restoredHealth = -1f

    override fun restoreHealthFraction(fraction: Float) {
        restoredHealth = fraction
    }

    override fun isCrew(entity: Entity?): Boolean = entity != null && entity === pilot

    override fun isCrewUuid(uuid: String?): Boolean = uuid != null && uuid == pilot?.stringUUID

    override fun defensiveFlares(): Boolean = BossConfig.DEFENSIVE_FLARES.get()

    override fun flareMissChance(): Double = BossConfig.FLARE_MISS_CHANCE.get() / 100.0

    override fun limitedAmmo(): Boolean = BossConfig.LIMITED_AMMO.get()

    override val bossKind: BossKind
        get() = BossKind.A10

    override val thought: AiThought by lazy { AiThought(this, AiKind.A10) { brain.state.name } }

    override fun teammates(): TeammateSettings = BossConfig.TEAMMATES

    override fun flareAmmoLoad(): Int = BossConfig.FLARE_COUNT.get()

    /** Unit vector of the current heading in the horizontal plane (Minecraft yaw convention). */
    private fun horizontalLook(): Vec3 {
        val yaw = yRot * Mth.DEG_TO_RAD
        return Vec3(-Mth.sin(yaw).toDouble(), 0.0, Mth.cos(yaw).toDouble())
    }

    // ================================================================================================== spawning

    /** Called by the spawn egg before the entity is added: sets the home point and puts the plane in the air. */
    override fun prepareLaunch(home: Vec3) {
        thought.event("entity.prepareLaunch") { "launched into the air, home ${home.x.toInt()}, ${home.y.toInt()}, ${home.z.toInt()}" }
        homePos = home
        homeSet = true
        deltaMovement = horizontalLook().scale(LAUNCH_SPEED)
        engineStart = true
        engineStartOver = true
        gearUp = true
        synchedGearRot = 1f
        power = LAUNCH_POWER
    }

    /** Tells the brain where the fight was, so a replacement that finds nobody circles there, not over the old spawn. */
    override fun rememberFight(anchor: Vec3) = brain.rememberFight(anchor)

    /**
     * Puts the aircraft at [spot] in open air (the unstuck teleport, see `StuckWatch`): same heading, wings level, nose on
     * the horizon, and launch speed and power so it flies on instead of stalling.
     */
    fun relaunch(spot: Vec3) {
        thought.event("entity.relaunch") { "relaunched in open air: wings level, launch speed and power" }
        moveTo(spot.x, spot.y, spot.z, yRot, 0f)
        setZRot(0f)
        deltaMovement = horizontalLook().scale(LAUNCH_SPEED)
        power = LAUNCH_POWER
    }

    /** One-time setup on the first server tick; also covers `/summon`, which bypasses the spawn egg. */
    private fun firstTickSetup() {
        initialised = true

        if (!homeSet) {
            val ground = level().getHeight(Heightmap.Types.MOTION_BLOCKING, blockPosition().x, blockPosition().z)
            homePos = Vec3(x, ground.toDouble(), z)
            homeSet = true
        }

        applyVehicleOverride()
        health = if (restoredHealth > 0f) restoredHealth * getMaxHealth() else getMaxHealth()
        restoredHealth = -1f
        applyWeaponTuning()
        if (BossConfig.LIMITED_AMMO.get()) decoyCount = BossConfig.FLARE_COUNT.get()

        if (hasEnergyStorage()) energy = maxEnergy
        engineStart = true
        engineStartOver = true
        gearUp = true
        synchedGearRot = 1f
        if (power < LAUNCH_POWER) power = LAUNCH_POWER
        if (deltaMovement.horizontalDistanceSqr() < 0.25) {
            deltaMovement = horizontalLook().scale(LAUNCH_SPEED)
        }
    }

    /**
     * Applies the config's health and block-damage settings through SBW's per-vehicle `Override` JSON.
     * That override merges top-level keys of the vehicle data, so `DestroyInfo` has to be written out completely.
     */
    private fun applyVehicleOverride() {
        val parts = ArrayList<String>()

        val wantedHealth = BossConfig.MAX_HEALTH.get().toFloat()
        if (computed().maxHealth != wantedHealth) parts += "\"MaxHealth\":$wantedHealth"

        if (!BossConfig.BLOCK_DAMAGE.get()) {
            val d = computed().destroyInfo
            parts += "\"DestroyInfo\":{" +
                    "\"CrashPassengers\":${d.crashPassengers}," +
                    "\"ExplodePassengers\":${d.explodePassengers}," +
                    "\"ExplodeBlocks\":false," +
                    "\"ExplosionDamage\":${d.explosionDamage}," +
                    "\"ExplosionRadius\":${d.explosionRadius}," +
                    "\"SympatheticDetonation\":${d.sympatheticDetonation}," +
                    "\"SympatheticDetonationForce\":${d.sympatheticDetonationForce}," +
                    "\"SympatheticDetonationChance\":${d.sympatheticDetonationChance}," +
                    "\"NoWreck\":${d.noWreck}}"
        }

        if (parts.isNotEmpty()) this.override = "{" + parts.joinToString(",") + "}"
    }

    /**
     * Scales the weapons' damage by the config multipliers and disables block damage if configured. Uses the gun's own
     * property override string (the mechanism SBW uses for per-instance weapon tweaks).
     */
    private fun applyWeaponTuning() {
        val noBlocks = !BossConfig.BLOCK_DAMAGE.get()
        val multipliers = mapOf(
            "Cannon" to BossConfig.CANNON_DAMAGE_MULT.get(),
            "Rocket" to BossConfig.ROCKET_DAMAGE_MULT.get(),
            "Bomb" to BossConfig.BOMB_DAMAGE_MULT.get(),
            "Missile" to BossConfig.MISSILE_DAMAGE_MULT.get()
        )
        for ((weapon, multiplier) in multipliers) {
            if (multiplier == 1.0 && !noBlocks) continue
            modifyGunData(weapon) { data ->
                val parts = ArrayList<String>()
                if (multiplier != 1.0) {
                    parts += "\"Damage\":${data.get(GunProp.DAMAGE).toDouble() * multiplier}"
                    parts += "\"ExplosionDamage\":${data.get(GunProp.EXPLOSION_DAMAGE).toDouble() * multiplier}"
                }
                if (noBlocks) parts += "\"ExplosionDestroy\":false"
                data.propertyOverrideString.set("{" + parts.joinToString(",") + "}")
            }
        }
    }

    // ================================================================================================== upkeep

    /** Re-creates the pilot / ammo box if missing (chunk reloads, `/kill`) and keeps fuel and flares topped up. */
    private fun maintain() {
        if (isWreck) return

        if (pilot?.isAlive != true) spawnPilot()

        if (!hasCreativeAmmoBoxCached() && inventory.slots > 0) {
            inventory.setStackInSlot(0, ItemStack(SbwItems.CREATIVE_AMMO_BOX.get()))
        }
        if (hasEnergyStorage() && energy < maxEnergy / 2) energy = maxEnergy
        if (BossConfig.LIMITED_AMMO.get()) {
            decoyCount = minOf(decoyCount, BossConfig.FLARE_COUNT.get())
        } else if (decoyCount < 2) {
            decoyCount = computed().decoyMagazineSize
        }
    }

    private fun spawnPilot() {
        val level = level()
        val newPilot = ModEntities.BOSS_PILOT.get().create(level) ?: return
        newPilot.moveTo(x, y, z, yRot, 0f)
        // Kill messages for the boss's weapons credit this name.
        newPilot.customName = Component.translatable("entity.${SuperbEnemies.MOD_ID}.a10_warthog_boss")
        if (level.addFreshEntity(newPilot)) newPilot.startRiding(this, true)
    }

    // ================================================================================================== tick

    /**
     * The boss never presses the gear key, and Superb Warfare drops the gear whenever the aircraft touches the ground (and
     * never raises it again by itself), so it is held up on both sides before and after the engine tick. A crippled
     * aircraft and a wreck are left to Superb Warfare.
     */
    private fun holdGearUp() {
        if (crippled || isWreck) return
        gearUp = true
        synchedGearRot = 1f
    }

    override fun baseTick() {
        val server = !level().isClientSide
        if (server) {
            if (level().difficulty == Difficulty.PEACEFUL) {
                discard()
                return
            }
            if (!initialised) firstTickSetup()
            if (tickCount % MAINTAIN_INTERVAL == 0) maintain()
            lifecycle.checkCripple()
            // The AI writes its control inputs first so SBW's engine sees them during this tick's travel().
            if (!lifecycle.defeated) brain.tick()
            // Derived every tick, so a boss reloaded mid-resupply comes back with its bar still hidden.
            lifecycle.barHidden = brain.withdrawn
            lifecycle.tickWounded()
        }

        holdGearUp()
        super.baseTick()
        holdGearUp()

        if (server) {
            if (tickCount % CHUNK_TICKET_INTERVAL == 0) keepChunkLoaded(position())
            lifecycle.tickAfter()
        }
    }

    override fun startSeenByPlayer(player: ServerPlayer) {
        super.startSeenByPlayer(player)
        lifecycle.startSeenBy(player)
    }

    override fun stopSeenByPlayer(player: ServerPlayer) {
        super.stopSeenByPlayer(player)
        lifecycle.stopSeenBy(player)
    }

    // ================================================================================================== SBW overrides

    /** True while the plane should pass through blocks: the config toggle, except for a crippled or wrecked plane. */
    private fun noclip(): Boolean = BossConfig.IGNORE_BLOCK_COLLISIONS.get() && !crippled && !isWreck

    /**
     * With `ignoreBlockCollisions` the plane moves by its velocity alone: SBW's collision resolution, crash damage,
     * bounce and speed loss are all skipped. Ground contact is cleared so nothing treats it as landed.
     */
    override fun move(movementType: MoverType, movement: Vec3) {
        if (!noclip()) {
            super.move(movementType, movement)
            return
        }
        if (movement.lengthSqr() > 1.0E-7) setPos(x + movement.x, y + movement.y, z + movement.z)
        horizontalCollision = false
        verticalCollision = false
        verticalCollisionBelow = false
        setOnGround(false)
    }

    /** SBW smashes blocks in front of a fast vehicle; a plane that ghosts through terrain should not carve it up. */
    override fun collideBlocks() {
        if (noclip()) return
        super.collideBlocks()
    }

    /** Unlimited fuel. */
    override fun consumeEnergy(amount: Int) {}

    /** Only the boss's own pilot may sit in the (single) seat. */
    override fun canAddPassenger(pPassenger: Entity): Boolean = pPassenger is BossCrewEntity && passengers.isEmpty()

    /** No boarding, no crowbar, no keys, no inventory. */
    override fun interact(player: Player, hand: InteractionHand): InteractionResult = InteractionResult.PASS

    override fun hasMenu(): Boolean = false
    override fun openCustomInventoryScreen(player: Player) {}

    /** No passive regeneration. */
    override fun heal(pHealAmount: Float) {}
    override fun repairAmount(): Float = 0f

    /** The wreck never leaves SBW's vehicle wreckage loot; the boss drops its own loot table instead. */
    override fun generateWreckageLoot() {}

    /**
     * The boss's own bombs and shells must not hurt it. SBW's friendly-fire check exempts explosion damage
     * (`CUSTOM_EXPLOSION`) even from the driver, so filter it here. Crash damage (`VEHICLE_STRIKE` with the plane itself
     * as the direct entity) counts unless `collisionDamage` is off, except for a crippled plane or a wreck; a ram by
     * another vehicle always counts.
     */
    override fun hurt(source: DamageSource, amount: Float): Boolean {
        val attacker = source.entity
        if ((attacker === this || isCrew(attacker)) && !source.`is`(ModDamageTypes.VEHICLE_STRIKE)) {
            return false
        }
        // Skipping here also skips SBW's `crash` flag, so a scrape or bounce is harmless.
        if (!BossConfig.COLLISION_DAMAGE.get() && !crippled && !isWreck &&
            source.`is`(ModDamageTypes.VEHICLE_STRIKE) && source.directEntity === this
        ) {
            return false
        }
        val hurt = super.hurt(source, amount)
        if (hurt && amount > 0f) lastDamagedTick = tickCount
        return hurt
    }

    /** True when the boss took damage within the last [ticks] ticks. */
    override fun recentlyDamaged(ticks: Int): Boolean = lastDamagedTick >= 0 && tickCount - lastDamagedTick <= ticks

    override fun remove(reason: RemovalReason) {
        if (!level().isClientSide) {
            lifecycle.onRemove(reason)
            // The creative ammo box is an implementation detail: SBW would otherwise drop it as an item.
            val items = inventory.getItems()
            for (i in 0 until items.size) items[i] = ItemStack.EMPTY
        }
        super.remove(reason)
    }

    // ================================================================================================== persistence

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)
        compound.putBoolean(TAG_INITIALISED, initialised)
        if (restoredHealth > 0f) compound.putFloat(TAG_RESTORED_HEALTH, restoredHealth)
        lifecycle.save(compound)
        if (homeSet) {
            compound.putDouble(TAG_HOME_X, homePos.x)
            compound.putDouble(TAG_HOME_Y, homePos.y)
            compound.putDouble(TAG_HOME_Z, homePos.z)
        }
        val brainTag = CompoundTag()
        brain.save(brainTag)
        compound.put(TAG_BRAIN, brainTag)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        initialised = compound.getBoolean(TAG_INITIALISED)
        restoredHealth = if (compound.contains(TAG_RESTORED_HEALTH)) compound.getFloat(TAG_RESTORED_HEALTH) else -1f
        lifecycle.load(compound)
        if (compound.contains(TAG_HOME_X)) {
            homePos = Vec3(compound.getDouble(TAG_HOME_X), compound.getDouble(TAG_HOME_Y), compound.getDouble(TAG_HOME_Z))
            homeSet = true
        }
        if (compound.contains(TAG_BRAIN)) brain.load(compound.getCompound(TAG_BRAIN))
    }

    private companion object {
        val LOOT_TABLE = ResourceLocation(SuperbEnemies.MOD_ID, "entities/a10_warthog_boss")

        const val TAG_INITIALISED = "BossInitialised"
        const val TAG_RESTORED_HEALTH = "BossRestoredHealth"
        const val TAG_HOME_X = "BossHomeX"
        const val TAG_HOME_Y = "BossHomeY"
        const val TAG_HOME_Z = "BossHomeZ"
        const val TAG_BRAIN = "BossBrain"

        const val LAUNCH_SPEED = 2.2
        const val LAUNCH_POWER = 1.8f
        const val MAINTAIN_INTERVAL = 10
        /** Below the 5 tick timeout of SBW's POST_TELEPORT ticket, so the chunk never drops out between renewals. */
        const val CHUNK_TICKET_INTERVAL = 4
    }
}
