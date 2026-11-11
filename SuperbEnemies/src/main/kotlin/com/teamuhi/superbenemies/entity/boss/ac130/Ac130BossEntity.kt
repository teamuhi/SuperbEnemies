package com.teamuhi.superbenemies.entity.boss.ac130

import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.Ac130hEntity
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.Ac130Config
import com.teamuhi.superbenemies.debug.AiKind
import com.teamuhi.superbenemies.debug.AiThought
import com.teamuhi.superbenemies.config.BossKind
import com.teamuhi.superbenemies.config.TeammateSettings
import com.teamuhi.superbenemies.entity.boss.ac130.ai.Ac130BossBrain
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
 * The Entity-type parameter of [Ac130hEntity] is invariant (`EntityType<Ac130hEntity>`), so a subclass registered under
 * its own type has to pass it through an unchecked cast. Safe: the type is only ever used to build instances of this class.
 */
@Suppress("UNCHECKED_CAST")
private fun asAc130Type(type: EntityType<*>): EntityType<Ac130hEntity> = type as EntityType<Ac130hEntity>

/**
 * "Angel of Death": Superb Warfare's AC-130H gunship flown and gunned by four hidden AI crew members.
 *
 *  - Crew: seat 0 is the pilot, seats 1-3 are the gunners of the [Ac130Gun]s (M61, Bofors, M102). All four are
 *    [BossCrewEntity] instances that the boss re-creates and re-seats whenever one goes missing.
 *  - Ammunition: a creative ammo box lives in the vehicle inventory, so the guns draw from an unlimited supply (they
 *    still use their normal magazine / reload timers). The brain's own limited-ammo mode sits on top of that.
 *  - Fuel: [consumeEnergy] does nothing and the energy store is topped up.
 *  - Health: finite, shown in a boss bar that empties at 10% (where SBW's aircraft engine switches to its crash
 *    branch). The bar, the crash and the payout live in the shared [BossLifecycle].
 *  - Players cannot board, loot, repair or tow it.
 *
 * Tick order matters. [Ac130BossBrain.tick] writes the flight controls **before** `super.baseTick()` so they are in
 * place when SBW's `travel()` runs the aircraft engine. [Ac130BossBrain.applyGunnerAim] runs **after** it, because
 * `positionRider` re-applies the vehicle's yaw change to every rider during the tick and SBW reads the gunner's
 * rotation for its (deferred) shots afterwards.
 */
class Ac130BossEntity(type: EntityType<Ac130BossEntity>, level: Level) : Ac130hEntity(asAc130Type(type), level),
    BossAircraft {

    val brain = Ac130BossBrain(this)

    private val lifecycle = BossLifecycle(
        this,
        BossLifecycleSettings(
            lootTable = LOOT_TABLE,
            titleKey = "boss.${SuperbEnemies.MOD_ID}.ac130_angel_of_death.title",
            crippledMessageKey = "message.${SuperbEnemies.MOD_ID}.ac130_boss.crippled",
            defeatedMessageKey = "message.${SuperbEnemies.MOD_ID}.ac130_boss.defeated",
            barColor = BossEvent.BossBarColor.PURPLE,
            xpReward = { Ac130Config.XP_REWARD.get() },
            bossBarRange = { Ac130Config.BOSS_BAR_RANGE.get() },
            globalAggro = { Ac130Config.GLOBAL_AGGRO.get() },
            // The AC-130 is about 30 blocks long: start the fire at the tail, not at the middle of the fuselage.
            trailBack = 14.0,
            trailUp = 4.0
        )
    )

    /** The point on the ground the boss circles when no player is in range. */
    var homePos: Vec3 = Vec3.ZERO

    /**
     * True once health has dropped to 10% of the maximum (set by the lifecycle, read by the brain). From then on the
     * gunship is lost: the brain releases the controls and SBW's aircraft engine runs its crash branch.
     */
    override val crippled: Boolean
        get() = lifecycle.crippled

    /** True once the loot / XP has been paid out (the wreck is about to disappear). */
    val defeated: Boolean
        get() = lifecycle.defeated

    private var homeSet = false
    private var initialised = false
    private var lastDamagedTick = -1

    /** The hidden pilot (seat 0), or null while it is missing (it is re-created automatically). */
    val pilot: BossCrewEntity?
        get() = crewAt(PILOT_SEAT)

    /** The crew member sitting in [gun]'s seat, or null while it is missing (it is re-created automatically). */
    fun gunner(gun: Ac130Gun): BossCrewEntity? = crewAt(gun.seat)

    private fun crewAt(seat: Int): BossCrewEntity? = (getNthEntity(seat) as? BossCrewEntity)?.takeIf { it.isAlive }

    /** Remaining health as a fraction of the maximum, 0..1 (can be slightly outside while the wreck burns down). */
    override fun healthFraction(): Float {
        val max = getMaxHealth()
        return if (max > 0f) health / max else 0f
    }

    /** Chat message to the players around the boss, and around [alsoNear] when given (same reach as the crippled / defeated broadcasts). */
    override fun announce(translationKey: String, style: Style, alsoNear: Vec3?) =
        lifecycle.broadcast(translationKey, style, alsoNear)

    /** Health fraction a replacement boss starts with; negative = full health. Saved until the first tick used it. */
    private var restoredHealth = -1f

    override fun restoreHealthFraction(fraction: Float) {
        restoredHealth = fraction
    }

    /** True for any [BossCrewEntity] that is currently a passenger of this boss. */
    override fun isCrew(entity: Entity?): Boolean = entity is BossCrewEntity && entity.vehicle === this

    override fun isCrewUuid(uuid: String?): Boolean {
        if (uuid == null) return false
        for (passenger in passengers) {
            if (passenger is BossCrewEntity && passenger.stringUUID == uuid) return true
        }
        return false
    }

    override fun defensiveFlares(): Boolean = Ac130Config.DEFENSIVE_FLARES.get()

    override fun flareMissChance(): Double = Ac130Config.FLARE_MISS_CHANCE.get() / 100.0

    override fun limitedAmmo(): Boolean = Ac130Config.LIMITED_AMMO.get()

    override val bossKind: BossKind
        get() = BossKind.AC130

    override val thought: AiThought by lazy { AiThought(this, AiKind.AC130) { brain.state.name } }

    override fun teammates(): TeammateSettings = Ac130Config.TEAMMATES

    override fun flareAmmoLoad(): Int = Ac130Config.FLARE_COUNT.get()

    /** Unit vector of the current heading in the horizontal plane (Minecraft yaw convention). */
    private fun horizontalLook(): Vec3 {
        val yaw = yRot * Mth.DEG_TO_RAD
        return Vec3(-Mth.sin(yaw).toDouble(), 0.0, Mth.cos(yaw).toDouble())
    }

    // ================================================================================================== spawning

    /** Called by the spawn egg before the entity is added: sets the home point and puts the gunship in the air. */
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
        if (Ac130Config.LIMITED_AMMO.get()) decoyCount = Ac130Config.FLARE_COUNT.get()

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

        val wantedHealth = Ac130Config.MAX_HEALTH.get().toFloat()
        if (computed().maxHealth != wantedHealth) parts += "\"MaxHealth\":$wantedHealth"

        if (!Ac130Config.BLOCK_DAMAGE.get()) {
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
        val noBlocks = !Ac130Config.BLOCK_DAMAGE.get()
        val multipliers = mapOf(
            Ac130Gun.M61.weaponName to Ac130Config.CANNON_DAMAGE_MULT.get(),
            Ac130Gun.BOFORS.weaponName to Ac130Config.BOFORS_DAMAGE_MULT.get(),
            Ac130Gun.M102.weaponName to Ac130Config.HOWITZER_DAMAGE_MULT.get()
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

    /**
     * Re-creates missing crew / the ammo box (chunk reloads, `/kill`) and keeps fuel and flares topped up.
     * Crew that sit in a seat beyond the crew seats, or are dead but not yet removed, are discarded.
     */
    private fun maintain() {
        if (isWreck) return

        // Ascending seat order: a new crew member mounts into the lowest free seat, which is then the wanted one.
        for (seat in 0 until CREW_SIZE) {
            val occupant = getNthEntity(seat)
            if (occupant is BossCrewEntity && occupant.isAlive) continue
            if (occupant != null) occupant.discard() // dead, removed or foreign: free the seat for a fresh member
            spawnCrew(seat)
        }
        for (i in CREW_SIZE until maxPassengers) {
            val extra = getNthEntity(i)
            if (extra != null) extra.discard()
        }

        if (!hasCreativeAmmoBoxCached() && inventory.slots > 0) {
            inventory.setStackInSlot(0, ItemStack(SbwItems.CREATIVE_AMMO_BOX.get()))
        }
        if (hasEnergyStorage() && energy < maxEnergy / 2) energy = maxEnergy
        if (Ac130Config.LIMITED_AMMO.get()) {
            decoyCount = minOf(decoyCount, Ac130Config.FLARE_COUNT.get())
        } else if (decoyCount < 2) {
            decoyCount = computed().decoyMagazineSize
        }
    }

    /** Creates one crew member and puts it in [wanted]: mount first, then [changeSeat] if SBW chose another seat. */
    private fun spawnCrew(wanted: Int) {
        val level = level()
        val member = ModEntities.BOSS_PILOT.get().create(level) ?: return
        member.moveTo(x, y, z, yRot, 0f)
        // Kill messages for the boss's weapons credit this name.
        member.customName = Component.translatable("entity.${SuperbEnemies.MOD_ID}.ac130_angel_of_death")
        if (!level.addFreshEntity(member)) return
        if (!member.startRiding(this, true)) {
            member.discard() // no free seat (should not happen): never leave an unmounted member behind
            return
        }
        if (getSeatIndex(member) != wanted && !changeSeat(member, wanted)) {
            // The wanted seat is taken by someone else; the next maintain() sorts it out.
            if (getSeatIndex(member) !in 0 until CREW_SIZE) member.discard()
        }
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
            // The gunners' rotation is read when SBW runs the (deferred) shot, so it has to be final after the tick.
            if (!lifecycle.defeated && !lifecycle.crippled) brain.applyGunnerAim()
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

    /** True while the gunship should pass through blocks: the config toggle, except for a crippled or wrecked one. */
    private fun noclip(): Boolean = Ac130Config.IGNORE_BLOCK_COLLISIONS.get() && !crippled && !isWreck

    /**
     * With `ignoreBlockCollisions` the gunship moves by its velocity alone: SBW's collision resolution, crash damage,
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

    /** SBW smashes blocks in front of a fast vehicle; a gunship that ghosts through terrain should not carve it up. */
    override fun collideBlocks() {
        if (noclip()) return
        super.collideBlocks()
    }

    /** Unlimited fuel. */
    override fun consumeEnergy(amount: Int) {}

    /** Only the boss's own crew may board, up to the four crew seats. */
    override fun canAddPassenger(pPassenger: Entity): Boolean =
        pPassenger is BossCrewEntity && passengers.size < CREW_SIZE

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
     * The boss's own shells must not hurt it. SBW's friendly-fire check exempts explosion damage (`CUSTOM_EXPLOSION`)
     * even from the crew, so filter it here. Crash damage (`VEHICLE_STRIKE` with the plane itself as the direct entity)
     * counts unless `collisionDamage` is off, except for a crippled plane or a wreck; a ram by another vehicle always
     * counts.
     */
    override fun hurt(source: DamageSource, amount: Float): Boolean {
        val attacker = source.entity
        if ((attacker === this || isCrew(attacker)) && !source.`is`(ModDamageTypes.VEHICLE_STRIKE)) {
            return false
        }
        // Skipping here also skips SBW's `crash` flag, so a scrape or bounce is harmless.
        if (!Ac130Config.COLLISION_DAMAGE.get() && !crippled && !isWreck &&
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
        val LOOT_TABLE = ResourceLocation(SuperbEnemies.MOD_ID, "entities/ac130_angel_of_death")

        const val TAG_INITIALISED = "BossInitialised"
        const val TAG_RESTORED_HEALTH = "BossRestoredHealth"
        const val TAG_HOME_X = "BossHomeX"
        const val TAG_HOME_Y = "BossHomeY"
        const val TAG_HOME_Z = "BossHomeZ"
        const val TAG_BRAIN = "BossBrain"

        /** Pilot plus the three gunners; SBW's AC-130 has further (unused) seats which stay empty. */
        const val CREW_SIZE = 4
        const val PILOT_SEAT = 0

        // The AC-130 is slower and heavier than the A-10.
        const val LAUNCH_SPEED = 1.6
        const val LAUNCH_POWER = 1.6f
        const val MAINTAIN_INTERVAL = 10
        /** Below the 5 tick timeout of SBW's POST_TELEPORT ticket, so the chunk never drops out between renewals. */
        const val CHUNK_TICKET_INTERVAL = 4
    }
}
