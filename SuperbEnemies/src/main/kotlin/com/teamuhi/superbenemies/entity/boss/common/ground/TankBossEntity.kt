package com.teamuhi.superbenemies.entity.boss.common.ground

import com.atsuishio.superbwarfare.data.gun.AmmoConsumer
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.projectile.SmokeDecoyEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.config.TeammateSettings
import com.teamuhi.superbenemies.debug.AiKind
import com.teamuhi.superbenemies.debug.AiThought
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.entity.boss.common.BossLifecycle
import com.teamuhi.superbenemies.entity.boss.common.BossLifecycleSettings
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import com.teamuhi.superbenemies.entity.boss.common.ai.isTeammate
import com.teamuhi.superbenemies.init.ModEntities
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import net.minecraft.world.Difficulty
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import com.atsuishio.superbwarfare.init.ModItems as SbwItems

/**
 * What the two ground bosses (the LAV-25 and the M1A2) have in common: Superb Warfare's vehicle driven and gunned by
 * hidden AI crew, with finite health in a boss bar, a creative ammo box in the vehicle inventory (so the guns draw from an
 * unlimited supply but keep their reload timers), unlimited fuel, and no way for a player to board, loot, repair or tow it.
 * It mirrors the plane bosses (`Ac130BossEntity`); the subclasses only name their config, crew and brain.
 *
 *  - Crew: [crewSeats] [BossCrewEntity] instances, seat 0 is the driver and turret gunner (Superb Warfare reads the engine
 *    input and the turret from it), a further seat is a commander on the weapon station. The boss re-creates and re-seats
 *    any that goes missing.
 *  - Ground: [groundBoss] is true, so the spawn egg and `BossRespawns` place it on the surface and call [prepareGround] with
 *    its home point instead of launching it. A knocked-out tank is wrecked at once and the lifecycle pays out.
 *  - Wreck: the vehicle data of both bosses sets `SympatheticDetonationChance` to 1, so a wreck always throws its turret;
 *    the lifecycle waits for that to pay out the loot.
 *
 * Tick order matters. `brain.tick()` writes the driving inputs **before** `super.baseTick()` so they are in place when SBW's
 * engine runs; `brain.applyAim()` runs **after** it, because the engine's own AI turret control (which needs a real target
 * entity) has just run and the shot SBW queues reads the barrel when it fires.
 */
abstract class TankBossEntity(type: EntityType<*>, level: Level) : VehicleEntity(type, level), BossVehicle {

    /** The config options of this boss. */
    abstract val settings: TankSettings

    /** Registry path of the entity, also the stem of its lang keys (`entity.superbenemies.<key>`, `boss.superbenemies.<key>.title`...). */
    abstract val bossKey: String

    /** Number of hidden crew members (seats 0 until this). */
    abstract val crewSeats: Int

    /** Half the width of the free box the spawn egg and the respawn demand around the boss. */
    abstract val clearanceHalfWidth: Double

    abstract val barColor: BossEvent.BossBarColor

    /** How far behind / above the origin the wreck's fire trail starts. */
    abstract val trailBack: Double
    abstract val trailUp: Double

    /** Creates the brain. Called once, on the first server tick (the subclass is fully constructed by then). */
    protected abstract fun createBrain(): TankBossBrain

    private val brainLazy = lazy { createBrain() }

    val brain: TankBossBrain get() = brainLazy.value

    /** Console voice for AI Thinking; the state shown is the brain's, or empty while the brain is still being created. */
    override val thought: AiThought by lazy {
        AiThought(this, AiKind.of(bossKind)) { if (brainLazy.isInitialized()) brain.state.name else "" }
    }

    private val lifecycle: BossLifecycle<TankBossEntity> by lazy {
        BossLifecycle(
            this,
            BossLifecycleSettings(
                lootTable = ResourceLocation(SuperbEnemies.MOD_ID, "entities/$bossKey"),
                titleKey = "boss.${SuperbEnemies.MOD_ID}.$bossKey.title",
                crippledMessageKey = messageKey("crippled"),
                defeatedMessageKey = messageKey("defeated"),
                barColor = barColor,
                xpReward = { settings.xpReward.get() },
                bossBarRange = { settings.bossBarRange.get() },
                globalAggro = { settings.globalAggro.get() },
                trailBack = trailBack,
                trailUp = trailUp,
                canCripple = false
            )
        )
    }

    /** Lang key of one of this boss's chat messages (`defeated`, `crippled`, `withdraw`, `hunting`). */
    fun messageKey(name: String): String = "message.${SuperbEnemies.MOD_ID}.$bossKey.$name"

    /** The point on the ground the boss patrols around when it has nothing to fight. */
    var homePos: Vec3 = Vec3.ZERO

    override val groundBoss: Boolean
        get() = true

    /**
     * True once the tank is out of action, which for a tank is once its wreck is declared (it fights to the last point of
     * health; there is no crippled phase at 10%). The brain then releases the controls and the other bosses stop treating it as a target.
     */
    override val crippled: Boolean
        get() = lifecycle.crippled || isWreck

    /** True once the loot / XP has been paid out (the wreck is about to disappear). */
    val defeated: Boolean
        get() = lifecycle.defeated

    private var homeSet = false
    private var initialised = false
    private var lastDamagedTick = -1

    /** (tick, health lost) of the latest attacks, oldest first; not saved (a reload starts a fresh tick counter). */
    private val damageLog = ArrayDeque<Pair<Int, Float>>()

    /**
     * Where the last hit that counts as an attack came from (the attacker's vehicle, else the projectile, else the damage
     * source's own position), or null when the source gave no place. Set together with [lastDamageTick]; the brain turns
     * it into return fire.
     */
    var lastHitOrigin: Vec3? = null
        private set

    /** The crew member sitting in [seat], or null while it is missing (it is re-created automatically). */
    fun crewAt(seat: Int): BossCrewEntity? = (getNthEntity(seat) as? BossCrewEntity)?.takeIf { it.isAlive }

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

    /** For a tank: smoke against guided missiles (the shared threat monitor drives the smoke dispenser). */
    override fun defensiveFlares(): Boolean = settings.smokeEnabled.get() && settings.smokeAgainstMissiles.get()

    override fun flareMissChance(): Double = MISSILE_MISS_CHANCE

    override fun limitedAmmo(): Boolean = settings.limitedAmmo.get()

    override fun teammates(): TeammateSettings = settings.teammates

    // ================================================================================================== spawning

    /** Called by the spawn egg and `BossRespawns` before the entity is added: sets the home point. */
    override fun prepareGround(home: Vec3) {
        homePos = home
        homeSet = true
    }

    /**
     * Puts the tank on its wheels / tracks at [spot] (the stuck rescue, see [GroundRescue]): same heading, level, at rest.
     * The crew rides along with the vehicle.
     */
    fun setDownAt(spot: Vec3) {
        moveTo(spot.x, spot.y, spot.z, yRot, 0f)
        setZRot(0f)
        deltaMovement = Vec3.ZERO
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
        if (settings.limitedAmmo.get()) decoyCount = settings.smokeCharges.get()
        if (hasEnergyStorage()) energy = maxEnergy
        thought.event("entity.firstTickSetup") {
            "set up at ${x.toInt()},${y.toInt()},${z.toInt()}, hp ${health.toInt()}/${getMaxHealth().toInt()}, limited ammo ${settings.limitedAmmo.get()}"
        }
    }

    /**
     * Applies the config's health and block-damage settings through SBW's per-vehicle `Override` JSON.
     * That override merges top-level keys of the vehicle data, so `DestroyInfo` has to be written out completely.
     */
    private fun applyVehicleOverride() {
        val parts = ArrayList<String>()

        val wantedHealth = settings.maxHealth.get().toFloat()
        if (computed().maxHealth != wantedHealth) parts += "\"MaxHealth\":$wantedHealth"

        if (!settings.blockDamage.get()) {
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
     *
     * The override string is applied before a gun's selected ammo type, and an ammo type's own `Override` (the HE shell's
     * lower direct damage and bigger explosion) wins over it, so a multiplier on `Damage` alone would only reach the AP
     * shell. The ammo list is therefore rewritten with the same multiplier applied inside each type's override
     * ([scaledAmmoTypes]).
     */
    private fun applyWeaponTuning() {
        val noBlocks = !settings.blockDamage.get()
        for (weapon in settings.weapons) {
            val multiplier = weapon.damageMultiplier.get()
            if (multiplier == 1.0 && !noBlocks) continue
            modifyGunData(weapon.name) { data ->
                val parts = ArrayList<String>()
                if (multiplier != 1.0) {
                    parts += "\"Damage\":${data.get(GunProp.DAMAGE).toDouble() * multiplier}"
                    parts += "\"ExplosionDamage\":${data.get(GunProp.EXPLOSION_DAMAGE).toDouble() * multiplier}"
                    scaledAmmoTypes(data, multiplier)?.let { parts += "\"AmmoType\":$it" }
                }
                if (noBlocks) parts += "\"ExplosionDestroy\":false"
                data.propertyOverrideString.set("{" + parts.joinToString(",") + "}")
            }
        }
    }

    /**
     * The gun's `AmmoType` list as JSON with every type's `Damage` / `ExplosionDamage` override multiplied by [multiplier],
     * or null when the gun has a single ammo type (or the list cannot be rebuilt: the plain multiplier then applies alone).
     */
    private fun scaledAmmoTypes(data: GunData, multiplier: Double): String? = try {
        val consumers: List<AmmoConsumer> = data.get(GunProp.AMMO_CONSUMER)
        if (consumers.size < 2) {
            null
        } else {
            val array = JsonArray()
            for (consumer in consumers) {
                val entry = JsonObject()
                entry.addProperty("Ammo", consumer.ammo)
                entry.addProperty("AmmoSlot", consumer.ammoSlot)
                entry.addProperty("ShouldUnload", consumer.shouldUnload)
                val override = consumer.override?.deepCopy()
                if (override != null) {
                    for (key in SCALED_KEYS) {
                        val value = override.get(key)
                        if (value != null && value.isJsonPrimitive) override.addProperty(key, value.asDouble * multiplier)
                    }
                    entry.add("Override", override)
                }
                array.add(entry)
            }
            array.toString()
        }
    } catch (e: Exception) {
        SuperbEnemies.LOGGER.warn("[{}] could not scale the ammo types of a weapon: {}", settings.logName, e.toString())
        null
    }

    // ================================================================================================== upkeep

    /**
     * Re-creates missing crew / the ammo box (chunk reloads, `/kill`) and keeps fuel and smoke topped up.
     * Crew that sit in a seat beyond the crew seats, or are dead but not yet removed, are discarded.
     */
    private fun maintain() {
        if (isWreck) return

        // Ascending seat order: a new crew member mounts into the lowest free seat, which is then the wanted one.
        for (seat in 0 until crewSeats) {
            val occupant = getNthEntity(seat)
            if (occupant is BossCrewEntity && occupant.isAlive) continue
            if (occupant != null) occupant.discard() // dead, removed or foreign: free the seat for a fresh member
            thought.event("entity.maintain") { "crew seat $seat is empty${if (occupant != null) " (discarding the occupant)" else ""}, spawning a new member" }
            spawnCrew(seat)
        }
        for (i in crewSeats until maxPassengers) {
            val extra = getNthEntity(i)
            if (extra != null) extra.discard()
        }

        if (!hasCreativeAmmoBoxCached() && inventory.slots > 0) {
            inventory.setStackInSlot(0, ItemStack(SbwItems.CREATIVE_AMMO_BOX.get()))
        }
        if (hasEnergyStorage() && energy < maxEnergy / 2) energy = maxEnergy
        if (settings.limitedAmmo.get()) {
            decoyCount = minOf(decoyCount, settings.smokeCharges.get())
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
        member.customName = Component.translatable("entity.${SuperbEnemies.MOD_ID}.$bossKey")
        if (!level.addFreshEntity(member)) return
        if (!member.startRiding(this, true)) {
            member.discard() // no free seat (should not happen): never leave an unmounted member behind
            return
        }
        if (getSeatIndex(member) != wanted && !changeSeat(member, wanted)) {
            // The wanted seat is taken by someone else; the next maintain() sorts it out.
            if (getSeatIndex(member) !in 0 until crewSeats) member.discard()
        }
    }

    // ================================================================================================== tick

    override fun baseTick() {
        val server = !level().isClientSide
        if (server) {
            if (level().difficulty == Difficulty.PEACEFUL) {
                thought.event("entity.baseTick") { "peaceful difficulty, removing the boss" }
                discard()
                return
            }
            if (!initialised) firstTickSetup()
            if (tickCount % MAINTAIN_INTERVAL == 0) maintain()
            lifecycle.checkCripple()
            // The AI writes its control inputs first so SBW's engine sees them during this tick's travel().
            // A wreck that has just paid out still gets this call: the brain lets go of the controls (see crippled).
            if (!lifecycle.defeated || crippled) brain.tick()
            // Derived every tick, so a boss reloaded mid-resupply comes back with its bar still hidden.
            lifecycle.barHidden = brain.withdrawn
            lifecycle.tickWounded()
        }

        // A smoke command written by the brain is consumed by the engine during the super call below.
        val popping = server && decoyInputDown
        super.baseTick()

        if (server) {
            if (popping) markLaunchedSmoke()
            // The engine's own turret control has just run; the boss's aim goes last so the queued shots read it.
            if (!lifecycle.defeated && !lifecycle.crippled) brain.applyAim()
            publishTurret()
            if (tickCount % CHUNK_TICKET_INTERVAL == 0) keepChunkLoaded(position())
            lifecycle.tickAfter()
        } else {
            receiveTurret()
        }
    }

    /** Tells [TankSight] which smoke clouds this tank just launched (they appear this tick, unticked), see [TankSight.noteSmoke]. */
    private fun markLaunchedSmoke() {
        for (smoke in level().getEntitiesOfClass(SmokeDecoyEntity::class.java, boundingBox.inflate(SMOKE_SCAN)) { it.tickCount <= 1 }) {
            TankSight.noteSmoke(smoke, this)
        }
    }

    /**
     * Superb Warfare never sends the turret angles to clients: a client turns a turret itself, from the controlling
     * player's view or from a real target UUID, and the boss's aim (a lead point, a ghost, an idle look) is neither. So
     * the server's angles ride on the entity data and the client adopts them each tick.
     */
    private fun publishTurret() {
        with(entityData) {
            set(SYNC_TURRET_Y, turretYRot)
            set(SYNC_TURRET_X, turretXRot)
            set(SYNC_GUN_Y, gunYRot)
            set(SYNC_GUN_X, gunXRot)
        }
    }

    /** Client side: takes the server's angles; the `...O` values were set to the previous angles at the start of the tick. */
    private fun receiveTurret() {
        val turretY = entityData.get(SYNC_TURRET_Y)
        val turretX = entityData.get(SYNC_TURRET_X)
        val gunY = entityData.get(SYNC_GUN_Y)
        val gunX = entityData.get(SYNC_GUN_X)
        // The engine wraps a yaw past +-360 by 720 on both ends of the lerp; a jump that large must not be interpolated.
        if (abs(turretY - turretYRot) > WRAP_JUMP) turretYRotO = turretY
        if (abs(gunY - gunYRot) > WRAP_JUMP) gunYRotO = gunY
        turretYRot = turretY
        turretXRot = turretX
        gunYRot = gunY
        gunXRot = gunX
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

    override fun defineSynchedData() {
        super.defineSynchedData()
        with(entityData) {
            define(SYNC_TURRET_Y, 0f)
            define(SYNC_TURRET_X, 0f)
            define(SYNC_GUN_Y, 0f)
            define(SYNC_GUN_X, 0f)
            define(SYNC_SPOTTED, -1)
        }
    }

    /**
     * Entity id of the vehicle (or entity) this tank has in sight as its target right now, -1 when it sees none. Synced so
     * that the client of a spotted player can show the eye icon (see `SpottedOverlay`).
     */
    var spottedId: Int
        get() = entityData.get(SYNC_SPOTTED)
        set(value) {
            if (entityData.get(SYNC_SPOTTED) != value) entityData.set(SYNC_SPOTTED, value)
        }

    /** Unlimited fuel. */
    override fun consumeEnergy(amount: Int) {}

    /** Only the boss's own crew may board, up to the crew seats. */
    override fun canAddPassenger(pPassenger: Entity): Boolean =
        pPassenger is BossCrewEntity && passengers.size < crewSeats

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
     * even from the crew, so filter it here. Collision damage still counts.
     */
    override fun hurt(source: DamageSource, amount: Float): Boolean {
        val attacker = source.entity
        if ((attacker === this || isCrew(attacker)) && !source.`is`(ModDamageTypes.VEHICLE_STRIKE)) {
            thought.think("entity.hurt") { "ignoring damage from its own shells or crew" }
            return false
        }
        val before = health
        val hurt = super.hurt(source, amount)
        // A hit from a teammate's round is friendly fire, not an attack: it must not trigger cover, smoke or a flank
        // against the enemy the tank is fighting (nor count as that enemy revealing itself).
        if (hurt && amount > 0f && (attacker == null || !isTeammate(this, attacker))) {
            lastDamagedTick = tickCount
            lastHitOrigin = attacker?.rootVehicle?.position() ?: source.directEntity?.position() ?: source.sourcePosition
            val dealt = before - health
            if (dealt > 0f) {
                damageLog.addLast(tickCount to dealt)
                while (damageLog.size > DAMAGE_LOG_MAX) damageLog.removeFirst()
            }
            thought.event("entity.hurt") {
                "took ${"%.1f".format(dealt)} from ${attacker?.name?.string ?: source.msgId}, hp ${(healthFraction() * 100f).toInt()}%"
            }
        } else if (hurt && amount > 0f) {
            thought.think("entity.hurt") { "friendly fire from ${attacker?.name?.string}, not treated as an attack" }
        }
        return hurt
    }

    /** Health lost to attacks (no friendly fire) within the last [ticks] ticks; the pull-back retreat compares it with the maximum health. */
    fun damageTaken(ticks: Int): Float {
        var sum = 0f
        for ((tick, dealt) in damageLog) if (tickCount - tick <= ticks) sum += dealt
        return sum
    }

    /** True when the boss took damage within the last [ticks] ticks. */
    override fun recentlyDamaged(ticks: Int): Boolean = lastDamagedTick >= 0 && tickCount - lastDamagedTick <= ticks

    /** The tick of the last hit (`tickCount`), or -1 if none yet; lets the brain react once per hit. */
    fun lastDamageTick(): Int = lastDamagedTick

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
        val SYNC_TURRET_Y: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(TankBossEntity::class.java, EntityDataSerializers.FLOAT)
        val SYNC_TURRET_X: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(TankBossEntity::class.java, EntityDataSerializers.FLOAT)
        val SYNC_GUN_Y: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(TankBossEntity::class.java, EntityDataSerializers.FLOAT)
        val SYNC_GUN_X: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(TankBossEntity::class.java, EntityDataSerializers.FLOAT)
        val SYNC_SPOTTED: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(TankBossEntity::class.java, EntityDataSerializers.INT)

        /** Degrees; a yaw change larger than this in one tick is the engine's 720 degree wrap, not a real swing. */
        const val WRAP_JUMP = 300f

        const val TAG_INITIALISED = "BossInitialised"
        const val TAG_RESTORED_HEALTH = "BossRestoredHealth"
        const val TAG_HOME_X = "BossHomeX"
        const val TAG_HOME_Y = "BossHomeY"
        const val TAG_HOME_Z = "BossHomeZ"
        const val TAG_BRAIN = "BossBrain"

        /** The damage values of a gun's ammo type that the damage multiplier scales. */
        val SCALED_KEYS = listOf("Damage", "ExplosionDamage")

        /** Chance that the crew misses an incoming guided missile (a tank crew sees less than a pilot). */
        const val MISSILE_MISS_CHANCE = 0.15

        const val MAINTAIN_INTERVAL = 10

        /** Most hits kept in [damageLog] (a gun at full rate lands a few per second; the window the log is read over is 3 seconds). */
        const val DAMAGE_LOG_MAX = 64

        /** Blocks around the hull in which a smoke cloud that appears in the tick of a salvo counts as this tank's. */
        const val SMOKE_SCAN = 3.0

        /** Below the 5 tick timeout of SBW's POST_TELEPORT ticket, so the chunk never drops out between renewals. */
        const val CHUNK_TICKET_INTERVAL = 4
    }
}
