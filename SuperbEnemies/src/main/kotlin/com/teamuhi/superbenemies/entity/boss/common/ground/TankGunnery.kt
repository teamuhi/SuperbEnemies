package com.teamuhi.superbenemies.entity.boss.common.ground

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.config.CannonShell
import com.teamuhi.superbenemies.config.TankSettings
import com.teamuhi.superbenemies.entity.boss.common.BossCrewEntity
import com.teamuhi.superbenemies.entity.boss.common.air.FlightMath
import com.teamuhi.superbenemies.entity.boss.common.ai.TrackedBosses
import com.teamuhi.superbenemies.entity.boss.common.ai.isAirType
import com.teamuhi.superbenemies.entity.boss.common.ai.isTeammate
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a tank gun is for; decides when it fires (see [TankGunnery.wants]). */
enum class WeaponKind {
    /** The main gun: AP / HE shells, fires at everything it may shoot at. */
    CANNON,

    /** Coaxial machine gun: soft targets in the open at short range. */
    COAX,

    /** Wire-guided missile (the LAV-25): armour; the barrel must stay on the target until it lands. */
    MISSILE,

    /** Machine gun on the weapon station: aimed on its own, soft targets and aircraft. */
    HEAVY
}

/** Whether a gun can be laid on a point at all, and if not on which side of its travel the point lies (see [TankGunnery.layStatus]). */
enum class LayStatus {
    /** Within the gun's depression and elevation limits. */
    OK,

    /** The point lies further below the horizon than the gun can depress: the tank stands too high above it. */
    TOO_LOW,

    /** The point lies higher than the gun can elevate. */
    TOO_HIGH
}

/**
 * One gun of a tank boss. [name] is the Superb Warfare weapon name (`vehicleShoot` fires by it, so the gun fires along
 * its own barrel and never needs the crew to look anywhere).
 *
 * @param seat the crew seat that fires it
 * @param fireAngle largest angle (degrees) between the barrel and the firing solution at which it fires
 * @param maxRange longest shot in blocks
 * @param maxElevation highest angle above the horizon (degrees) the gun can elevate to
 * @param maxDepression lowest angle below the horizon (degrees, positive) the gun can depress to; what cover awareness
 * checks so a tank never picks a firing spot on a hill from which it cannot lay the gun on a target in the valley
 * @param interval ticks between two shots, or 0 to derive it from the weapon's rounds per minute
 * @param burstMin burst length range in ticks, 0 = no bursts (single shots paced by [interval] and the reload)
 * @param pauseMin pause between bursts in ticks
 * @param maxPierce cannon only: how many solid blocks an armour-piercing shell may be asked to get through
 */
class TankWeapon(
    val name: String,
    val kind: WeaponKind,
    val seat: Int,
    val fireAngle: Double,
    val maxRange: Double,
    val maxElevation: Double,
    val maxDepression: Double = 8.0,
    val interval: Int = 0,
    val burstMin: Int = 0,
    val burstMax: Int = 0,
    val pauseMin: Int = 0,
    val pauseMax: Int = 0,
    val maxPierce: Int = 0
) {
    /** True for a gun on the weapon station, which turns separately from the turret. */
    val onStation: Boolean get() = kind == WeaponKind.HEAVY

    /** The AI Thinking channel of the reasons this gun holds fire (built once, not per tick). */
    val holdChannel: String = "gunnery.fire/$name"
}

/**
 * Aiming, firing cadence and ammunition of a tank boss's guns. The LAV-25 and the M1A2 differ only in their [weapons]
 * list and a few policies, so this class does the work for both.
 *
 * **Aiming.** Superb Warfare's weapon-name overload of `vehicleShoot` fires along the gun's own barrel
 * (`getShootVec(weaponName)`), so aiming a tank means turning its turret (and, for the M1A2's .50 cal, its weapon
 * station). The brain's `tick` computes the wanted direction (muzzle to the target's lead point, the maths of
 * `Ac130Gunnery.leadPoint`) and stores it; [applyAim] hands it to `turretAutoAimFromVector` / `passengerWeaponAutoAimFormVector`
 * **after** `super.baseTick()` (the engine's own AI aim, which needs a real target UUID, never runs: `aiTurretTargetUUID`
 * stays unset, so a ghost target works too). The turret turns at its data-defined speed, so a gun fires only when its
 * barrel is within [TankWeapon.fireAngle] of its own solution; all barrel guns share the turret, so the turret follows the
 * main gun's solution and the coaxial gun, whose shot is flatter, fires when it happens to line up.
 *
 * **Firing** also needs the gun enabled, rounds left, `GunData.canShoot` (not reloading or overheated), the target within
 * range and a clear line ([TankSight.solidBlocks]). An armour-piercing shell may be fired through up to
 * [TankWeapon.maxPierce] blocks (a hidden target is shelled through its cover); everything else needs a clear line.
 *
 * **Shells.** Superb Warfare's ammo variants are the entries of a weapon's `AmmoType` list; `GunData.changeAmmoConsumer`
 * selects one (index 0 = AP, 1 = HE for both tanks' cannons). With `cannonShell = AUTO` the cannon uses AP against
 * vehicles, hidden targets and targets behind cover and HE against infantry and mobs in the open. Switching unloads the
 * chamber: to AP it is done at once (at most every [AP_SWITCH_TICKS]), to HE only while the gun is empty or reloading
 * (or has not fired for a while) and at most every [SHELL_SWITCH_TICKS].
 *
 * **Ammunition** (`limitedAmmo`) is counted per weapon in [ammo], saved with the boss.
 *
 * All state is per instance.
 */
abstract class TankGunnery(
    protected val boss: TankBossEntity,
    protected val settings: TankSettings,
    val weapons: List<TankWeapon>
) {

    private class WeaponState {
        var nextShot = 0
        var burstEnd = 0
        var pauseUntil = 0
        var lastCall = Int.MIN_VALUE / 2

        /** The [burstEnd] of the burst whose first round was reported, so a burst is reported once (see [shoot]). */
        var reportedBurst = -1
    }

    private class Motion(var id: Int, var last: Vec3, var velocity: Vec3)

    private val states = HashMap<String, WeaponState>()

    /** Rounds left per weapon while `limitedAmmo` is on. A missing entry means a full load. Keyed by weapon name. */
    private val ammo = HashMap<String, Int>()

    // Wanted directions (unit vectors), applied after the vehicle tick.
    private var turretWant: Vec3? = null
    private var stationWant: Vec3? = null

    /** Tick until which the barrel stays on the target for a guided missile in flight (0 = not holding). */
    protected var holdUntil = 0
        private set
    private var holdCheckTick = 0

    private var lastCannonShot = Int.MIN_VALUE / 2

    /** `tickCount` of the last main-cannon round fired (a very negative number before the first). */
    val lastCannonShotTick: Int
        get() = lastCannonShot
    private var nextShellSwitch = 0

    private var lineTick = Int.MIN_VALUE / 2
    private var lineAimId = -1
    private var lineSolid = 0
    private var teammateInLine = false

    private var secondaryMotion: Motion? = null

    /**
     * True when the line of fire of the main gun is blocked: solid blocks between the gun and the aim at the last check, or
     * a teammate boss standing in the way. Both make the brain reposition after a while.
     */
    val lineBlocked: Boolean
        get() = lineSolid > 0 || teammateInLine

    /** True while the barrel is held on the target for a guided missile in flight. */
    val holdingBarrel: Boolean
        get() = boss.tickCount < holdUntil

    /** True for a tank with a gun on the weapon station. */
    val hasStation: Boolean = weapons.any { it.onStation }

    // ================================================================================================== config / ammo

    fun enabled(weapon: TankWeapon): Boolean = settings.weapon(weapon.name)?.enabled?.get() ?: false

    fun anyEnabled(): Boolean = weapons.any { enabled(it) }

    /** Rounds left for [weapon]; effectively unlimited unless the config enables a finite load. */
    fun ammoLeft(weapon: TankWeapon): Int {
        if (!settings.limitedAmmo.get()) return Int.MAX_VALUE
        return ammo.getOrPut(weapon.name) { settings.weapon(weapon.name)?.rounds?.get() ?: 0 }
    }

    fun hasAmmo(weapon: TankWeapon): Boolean = ammoLeft(weapon) > 0

    /** The rounds left per enabled gun and the smoke charges, for the heartbeat of AI Thinking. */
    fun ammoSummary(): String {
        if (!settings.limitedAmmo.get()) return "unlimited"
        return weapons.filter { enabled(it) }.joinToString(" ") { "${it.name}=${ammoLeft(it)}" } + " smoke=${boss.decoyCount}"
    }

    /** True when at least one enabled gun can still fire (always true without `limitedAmmo`, if any gun is enabled). */
    fun hasUsableGun(): Boolean = weapons.any { enabled(it) && hasAmmo(it) }

    /**
     * True when the boss should go and rearm: finite ammo is on and either every enabled gun is empty or the main gun
     * (cannon) is, since a tank without its cannon has no business staying in the fight.
     */
    fun needsResupply(): Boolean {
        if (!settings.limitedAmmo.get() || !anyEnabled()) return false
        val cannon = weapons.firstOrNull { it.kind == WeaponKind.CANNON }
        return !hasUsableGun() || (cannon != null && enabled(cannon) && !hasAmmo(cannon))
    }

    /**
     * True when `limitedAmmo` is on and some enabled gun is below its full load, or (with smoke enabled) the smoke
     * dispenser is below `smokeCharges`: there is something to gain from rearming. A full load, or unlimited ammo, is never
     * "spent". Used by the idle resupply roll of the brain.
     */
    fun anySpent(): Boolean {
        if (!settings.limitedAmmo.get()) return false
        for (weapon in weapons) {
            if (!enabled(weapon)) continue
            val full = settings.weapon(weapon.name)?.rounds?.get() ?: continue
            if (ammoLeft(weapon) < full) return true
        }
        return settings.smokeEnabled.get() && boss.decoyCount < settings.smokeCharges.get()
    }

    private fun consumeAmmo(weapon: TankWeapon) {
        if (!settings.limitedAmmo.get()) return
        ammo[weapon.name] = max(0, ammoLeft(weapon) - 1)
    }

    fun saveAmmo(tag: CompoundTag) {
        if (ammo.isEmpty()) return
        val ammoTag = CompoundTag()
        for ((weapon, left) in ammo) ammoTag.putInt(weapon, left)
        tag.put(TAG_AMMO, ammoTag)
    }

    fun loadAmmo(tag: CompoundTag) {
        ammo.clear()
        if (!tag.contains(TAG_AMMO)) return
        val ammoTag = tag.getCompound(TAG_AMMO)
        for (weapon in ammoTag.allKeys) ammo[weapon] = ammoTag.getInt(weapon)
    }

    private fun stateOf(weapon: TankWeapon): WeaponState = states.getOrPut(weapon.name) { WeaponState() }

    // ================================================================================================== reach

    /** The highest elevation (degrees above the horizon) any enabled gun with rounds left can reach. */
    fun reachElevation(): Double =
        weapons.filter { enabled(it) && hasAmmo(it) }.maxOfOrNull { it.maxElevation } ?: 0.0

    /** Whether some gun could elevate to [entity] from the turret; [margin] degrees of slack. */
    fun canReach(entity: Entity, margin: Double): Boolean =
        elevationBetween(TankSight.eyeOf(boss), entity.boundingBox.center) <= reachElevation() + margin

    /** Angle (degrees, positive above the horizon) of the line [from] -> [to]; the horizontal run counts at least one block. */
    private fun elevationBetween(from: Vec3, to: Vec3): Double =
        Math.toDegrees(atan2(to.y - from.y, max(hypot(to.x - from.x, to.z - from.z), 1.0)))

    /** The main gun: the first enabled cannon that still has rounds, or null when there is none. */
    fun mainGun(): TankWeapon? = weapons.firstOrNull { it.kind == WeaponKind.CANNON && enabled(it) && hasAmmo(it) }

    /**
     * True when the main gun is loaded and may shoot right now (rounds left, not reloading or overheated): the heavy tanks
     * step out of cover when this turns true.
     */
    fun cannonReady(): Boolean {
        val gun = mainGun() ?: return false
        return boss.getGunData(gun.name)?.canShoot(boss.ammoSupplier) == true
    }

    /**
     * Whether [weapon], mounted at [from] (the turret eye of a hull standing at a candidate spot), can be laid on [to]: the
     * angle of the line has to lie between the gun's depression and elevation limits, with [margin] degrees of slack.
     */
    fun canLay(from: Vec3, to: Vec3, weapon: TankWeapon, margin: Double = 0.0): Boolean = layMargin(from, to, weapon) >= margin

    /** Degrees of slack between the line [from] -> [to] and the nearer of [weapon]'s elevation and depression limits (negative: out of reach). */
    fun layMargin(from: Vec3, to: Vec3, weapon: TankWeapon): Double {
        val elevation = elevationBetween(from, to)
        return minOf(weapon.maxElevation - elevation, elevation + weapon.maxDepression)
    }

    /**
     * The gun that decides whether the tank can lay on a target at all: the main gun, else the first enabled barrel gun (the
     * coaxial machine gun or the missile of a tank whose cannon is off or empty). Null when no barrel gun is enabled.
     */
    fun layGun(): TankWeapon? = mainGun() ?: weapons.firstOrNull { !it.onStation && enabled(it) }

    /**
     * Whether [layGun], mounted at [from], can be laid on [to], and when it cannot, which limit is the one in the way. Built on
     * the same maths as [canReach] and [layMargin]; [margin] degrees of slack are given at both limits, so a target right at the
     * edge of the travel is not flagged. [LayStatus.OK] when no barrel gun is enabled (nothing to lay).
     */
    fun layStatus(from: Vec3, to: Vec3, margin: Double = 1.0): LayStatus {
        val gun = layGun() ?: return LayStatus.OK
        val elevation = elevationBetween(from, to)
        return when {
            elevation > gun.maxElevation + margin -> LayStatus.TOO_HIGH
            elevation < -gun.maxDepression - margin -> LayStatus.TOO_LOW
            else -> LayStatus.OK
        }
    }

    // ================================================================================================== per tick

    /**
     * Aims the guns at [aim] (or leaves them looking along [idleLook], by default the hull's heading) and fires those that
     * are on target.
     *
     * @param aimVelocity per-tick velocity of [aim], for lead
     * @param hidden true when [aim] is a ghost (the target is out of sight): only armour-piercing shells are fired at it
     * @param mayFire false in states where the tank must not shoot (patrol, retreat)
     * @param secondary a soft target for the weapon-station gun, or null
     * @param idleLook horizontal direction the turret looks in without a target, or null for the hull's heading
     * @param suppressing true when [aim] is a ghost the boss shoots at on purpose (return fire at an estimated spot): the
     * coaxial and weapon-station machine guns may fire at it and an AUTO cannon loads high explosive, none of which a plain
     * hidden ghost gets
     * @param holdCannon true while the main gun must stay quiet although the rest may fire (a tank waiting behind cover
     * for its gun to reload): the cannon still aims, so it is laid when the tank steps out
     * @param cannonOnly true while the main gun alone works on [aim] (a wall being shot open, see [WallBreach]): the machine
     * guns, the weapon station and the missile neither aim nor fire
     */
    fun tick(
        aim: Entity?, aimVelocity: Vec3, hidden: Boolean, mayFire: Boolean, secondary: Entity?, idleLook: Vec3?,
        suppressing: Boolean = false, holdCannon: Boolean = false, cannonOnly: Boolean = false
    ) {
        val now = boss.tickCount
        turretWant = null
        stationWant = null
        if (boss.crippled || boss.isWreck) return

        val liveAim = aim?.takeIf { it.isAlive && !it.isRemoved }
        val centre = liveAim?.boundingBox?.center
        if (liveAim == null) {
            // Nothing aimed at: the cached line belongs to a target that is gone, so the next aim measures its own at once.
            teammateInLine = false
            lineSolid = 0
            lineAimId = -1
        }
        guideMissile(liveAim, centre, aimVelocity, hidden)
        if (holdUntil != 0) {
            var release = now >= holdUntil || liveAim == null || hidden
            if (!release && now >= holdCheckTick) {
                holdCheckTick = now + MISSILE_CHECK_INTERVAL
                release = !missileInFlight()
            }
            if (release) holdUntil = 0
        }
        val holding = holdUntil != 0

        val barrelGuns = weapons.filter { !it.onStation }
        val primary = barrelGuns.firstOrNull { it.kind != WeaponKind.MISSILE && enabled(it) && hasAmmo(it) } ?: barrelGuns.firstOrNull()
        if (primary != null) {
            val muzzle = boss.getShootPos(primary.name, 1f)
            val dir = if (centre != null) {
                val point = if (holding) centre else leadPoint(primary, muzzle, centre, aimVelocity)
                point.subtract(muzzle)
            } else {
                idleLook ?: FlightMath.dir(boss.yRot, 0f)
            }
            if (finite(dir) && dir.lengthSqr() > 1.0e-6) turretWant = dir.normalize()
            if (liveAim != null && centre != null) updateLine(liveAim, muzzle, centre)
        }
        boss.thought.think("gunnery.tick") {
            when {
                liveAim == null -> "no target, turret looks ahead"
                else -> "aiming ${primary?.name ?: "no gun"} at ${liveAim.name.string}" +
                        (if (hidden) " (last seen spot)" else "") + (if (suppressing) " (suppressing)" else "") +
                        (if (holding) ", barrel held for the missile" else "") +
                        (if (!mayFire) ", fire not allowed here" else "")
            }
        }

        if (mayFire && liveAim != null && centre != null) {
            for (weapon in barrelGuns) {
                if (holding && weapon.kind != WeaponKind.MISSILE && weapon.kind != WeaponKind.COAX) {
                    boss.thought.think(weapon.holdChannel) { "${weapon.name}: holding fire while the missile flies" }
                    continue
                }
                if (holdCannon && weapon.kind == WeaponKind.CANNON) {
                    boss.thought.think(weapon.holdChannel) { "${weapon.name}: held by the brain (waiting behind cover)" }
                    continue
                }
                if (cannonOnly && weapon.kind != WeaponKind.CANNON) continue
                fireBarrel(weapon, liveAim, centre, aimVelocity, hidden, suppressing, holding, now)
            }
        }

        for (weapon in weapons) {
            if (weapon.onStation && !cannonOnly) tickStation(weapon, liveAim, aimVelocity, hidden, suppressing, mayFire, secondary, now)
        }
    }

    /**
     * Hands the stored aim to the vehicle. The entity calls this right after `super.baseTick()`: the shot SBW queues reads
     * the barrel when it runs, and the engine's own turret control (which only knows real targets) has just finished.
     */
    fun applyAim() {
        if (boss.crippled || boss.isWreck) return
        turretWant?.let { boss.turretAutoAimFromVector(it) }
        stationWant?.let { boss.passengerWeaponAutoAimFormVector(it) }
    }

    // ================================================================================================== missile defence

    /**
     * The machine guns that could be laid on [centre] right now: enabled, rounds left, a crew member in the seat, the point
     * within range and within the gun's travel. A barrel gun is left out while the barrel is held for the tank's own guided
     * missile, so the defence never makes that missile lose its target.
     */
    private fun missileGuns(centre: Vec3): List<TankWeapon> = weapons.filter { weapon ->
        if (weapon.kind != WeaponKind.COAX && weapon.kind != WeaponKind.HEAVY) return@filter false
        if (!enabled(weapon) || !hasAmmo(weapon) || (holdingBarrel && !weapon.onStation)) return@filter false
        if (seatedCrew(weapon.seat) == null) return@filter false
        val muzzle = boss.getShootPos(weapon.name, 1f)
        muzzle.distanceTo(centre) <= weapon.maxRange && canLay(muzzle, centre, weapon)
    }

    /** Whether at least one machine gun could be laid on [missile] now (see [fireAtMissile]). */
    fun canShootDown(missile: Entity): Boolean =
        !boss.crippled && !boss.isWreck && missile.isAlive && !missile.isRemoved && missileGuns(missile.boundingBox.center).isNotEmpty()

    /**
     * Lays the machine guns on [missile] and fires those that are on it. The brain calls it **in place of** [tick] on a tick
     * where a guided missile is being shot at: the turret and the weapon station follow the missile (with lead) and the cannon
     * and the tank's own missile stay quiet. Self-defence, so it fires in any state and ignores the usual target policy, but it
     * still needs a clear line and no teammate in front of the muzzle.
     *
     * @return true if at least one round left a barrel on this tick
     */
    fun fireAtMissile(missile: Entity): Boolean {
        turretWant = null
        stationWant = null
        if (boss.crippled || boss.isWreck || !missile.isAlive || missile.isRemoved) return false
        val now = boss.tickCount
        val centre = missile.boundingBox.center
        val velocity = missile.deltaMovement
        var fired = false
        for (weapon in missileGuns(centre)) {
            val muzzle = boss.getShootPos(weapon.name, 1f)
            val wanted = leadPoint(weapon, muzzle, centre, velocity).subtract(muzzle)
            if (!finite(wanted) || wanted.lengthSqr() < 1.0e-6) continue
            if (weapon.onStation) stationWant = wanted.normalize() else turretWant = wanted.normalize()
            if (shootAtMissile(weapon, missile, muzzle, centre, wanted, now)) fired = true
        }
        return fired
    }

    private fun shootAtMissile(weapon: TankWeapon, missile: Entity, muzzle: Vec3, centre: Vec3, wanted: Vec3, now: Int): Boolean {
        val state = stateOf(weapon)
        val data = boss.getGunData(weapon.name) ?: return false
        val crew = seatedCrew(weapon.seat) ?: return false
        when {
            angleBetween(boss.getShootVec(weapon.name, 1f), wanted) > weapon.fireAngle ->
                boss.thought.think(weapon.holdChannel) { "${weapon.name}: turning onto the incoming missile" }
            !data.canShoot(boss.ammoSupplier) ->
                boss.thought.think(weapon.holdChannel) { "${weapon.name}: reloading, cannot engage the missile" }
            now < state.nextShot ->
                boss.thought.think(weapon.holdChannel) { "${weapon.name}: between rounds (engaging the missile)" }
            TankSight.solidBlocks(boss.level(), muzzle, centre, 0) != 0 ->
                boss.thought.think(weapon.holdChannel) { "${weapon.name}: line to the missile blocked by solid blocks" }
            teammateBetween(muzzle, centre) ->
                boss.thought.think(weapon.holdChannel) { "${weapon.name}: holding fire at the missile, a teammate is in the way" }
            else -> {
                shoot(weapon, crew, state, now, missile)
                return true
            }
        }
        return false
    }

    // ================================================================================================== policy

    /**
     * Whether [weapon] should be fired at [aim] now. [distance] is the muzzle-to-target distance. Subclasses add the
     * policy of the guns only they have (the missile). With [suppressing] a ghost aim is shot at on purpose (return fire),
     * so the coaxial gun fires at it too.
     */
    protected open fun wants(weapon: TankWeapon, aim: Entity, hidden: Boolean, suppressing: Boolean, distance: Double): Boolean =
        when (weapon.kind) {
            WeaponKind.CANNON -> distance >= MIN_CANNON_RANGE
            WeaponKind.COAX -> (!hidden || suppressing) && distance <= COAX_RANGE && isSoft(aim)
            WeaponKind.MISSILE, WeaponKind.HEAVY -> false
        }

    /** Called after [weapon] fired at [aim]. */
    protected open fun afterShot(weapon: TankWeapon, aim: Entity, distance: Double, now: Int) {}

    /**
     * Called every tick, before the guns are aimed: steers a missile this boss has in flight. [aim] and [centre] are null
     * when there is no live target; [hidden] is true when the aim is a ghost. Default: nothing to steer.
     */
    protected open fun guideMissile(aim: Entity?, centre: Vec3?, velocity: Vec3, hidden: Boolean) {}

    /** Starts holding the barrel on the target for [ticks] (a guided missile was fired). */
    protected fun holdBarrel(ticks: Int) {
        holdUntil = boss.tickCount + ticks
        holdCheckTick = boss.tickCount + MISSILE_MIN_HOLD
    }

    /** Whether a guided missile of this boss is still in the air; checked every [MISSILE_CHECK_INTERVAL] ticks. */
    protected open fun missileInFlight(): Boolean = true

    /** Soft: not armour. A player, mob or dummy, also one on foot beside a vehicle. */
    protected fun isSoft(aim: Entity): Boolean = aim.rootVehicle !is VehicleEntity

    protected fun isAir(aim: Entity): Boolean {
        val root = aim.rootVehicle
        return root is VehicleEntity && isAirType(root.vehicleType)
    }

    /** The target for the weapon-station gun: an aircraft first, then the soft [secondary], then a soft main target. */
    protected open fun stationTarget(aim: Entity?, hidden: Boolean, suppressing: Boolean, secondary: Entity?): Entity? = when {
        aim != null && !hidden && isAir(aim) -> aim
        secondary != null -> secondary
        aim != null && (!hidden || suppressing) && isSoft(aim) -> aim
        else -> null
    }

    // ================================================================================================== firing

    private fun fireBarrel(
        weapon: TankWeapon, aim: Entity, centre: Vec3, velocity: Vec3, hidden: Boolean, suppressing: Boolean, holding: Boolean,
        now: Int
    ) {
        if (!enabled(weapon)) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: disabled in the config" }
            return
        }
        if (!hasAmmo(weapon)) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: out of ammo" }
            return
        }
        // Hold fire while a teammate stands in the line of fire (no damage immunity: friendly fire is avoided, not prevented).
        if (teammateInLine) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: holding fire, a teammate is in the line of fire" }
            return
        }
        val data = boss.getGunData(weapon.name) ?: return
        val crew = seatedCrew(weapon.seat)
        if (crew == null) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: no crew in seat ${weapon.seat}" }
            return
        }
        val muzzle = boss.getShootPos(weapon.name, 1f)
        val distance = muzzle.distanceTo(centre)
        if (distance > weapon.maxRange) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: ${aim.name.string} out of range" }
            return
        }
        if (!wants(weapon, aim, hidden, suppressing, distance)) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: no use against ${aim.name.string} now (${weapon.kind})" }
            return
        }

        if (weapon.kind == WeaponKind.CANNON) selectShell(weapon, aim, hidden, suppressing, now)

        // A missile steers itself onto the target, so it is launched straight at it with no lead or drop.
        val point = if (weapon.kind == WeaponKind.MISSILE) centre else leadPoint(weapon, muzzle, centre, velocity)
        val wanted = point.subtract(muzzle)
        if (!finite(wanted) || wanted.lengthSqr() < 1.0e-6) return
        val state = stateOf(weapon)
        // The missile leaves along the turret's yaw; what matters is where the barrel (which it rides) points.
        val barrel = if (weapon.kind == WeaponKind.MISSILE) boss.getBarrelVector(1f) else boss.getShootVec(weapon.name, 1f)
        if (angleBetween(barrel, wanted) > weapon.fireAngle) {
            if (now - state.lastCall > BURST_GAP_RESET) state.burstEnd = 0
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: turning onto ${aim.name.string}" }
            return
        }
        if (!data.canShoot(boss.ammoSupplier)) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: reloading" }
            return
        }
        if (now < state.nextShot) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: between rounds" }
            return
        }
        if (!lineAllowed(weapon, data.selectedAmmoType.get())) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: line to ${aim.name.string} blocked by solid blocks" }
            return
        }
        // The cached line is up to LINE_INTERVAL ticks old and the teammates move: look along the barrel once more right
        // before the round leaves, so a tank that drove into the line since the last check is not shot at.
        if (teammateBetween(muzzle, muzzle.add(barrel.normalize().scale(distance)))) {
            teammateInLine = true
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: a teammate crossed the line of fire" }
            return
        }
        if (!cadenceOpen(weapon, state, now)) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: pausing between bursts" }
            return
        }

        shoot(weapon, crew, state, now, aim)
        afterShot(weapon, aim, distance, now)
    }

    private fun tickStation(
        weapon: TankWeapon, aim: Entity?, aimVelocity: Vec3, hidden: Boolean, suppressing: Boolean, mayFire: Boolean,
        secondary: Entity?, now: Int
    ) {
        val target = stationTarget(aim, hidden, suppressing, secondary)
        if (target == null || !target.isAlive || target.isRemoved) {
            secondaryMotion = null
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: no soft target or aircraft" }
            return
        }
        val centre = target.boundingBox.center
        val velocity = if (target === aim) aimVelocity else trackSecondary(target)
        val muzzle = boss.getShootPos(weapon.name, 1f)
        val point = leadPoint(weapon, muzzle, centre, velocity)
        val wanted = point.subtract(muzzle)
        if (!finite(wanted) || wanted.lengthSqr() < 1.0e-6) return
        stationWant = wanted.normalize()

        if (!mayFire || !enabled(weapon) || !hasAmmo(weapon)) {
            boss.thought.think(weapon.holdChannel) {
                "${weapon.name}: tracking ${target.name.string}, " + when {
                    !mayFire -> "fire not allowed here"
                    !enabled(weapon) -> "disabled in the config"
                    else -> "out of ammo"
                }
            }
            return
        }
        val crew = seatedCrew(weapon.seat)
        if (crew == null) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: no crew in seat ${weapon.seat}" }
            return
        }
        if (muzzle.distanceTo(centre) > weapon.maxRange) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: ${target.name.string} out of range" }
            return
        }
        val state = stateOf(weapon)
        if (angleBetween(boss.getShootVec(weapon.name, 1f), wanted) > weapon.fireAngle) {
            if (now - state.lastCall > BURST_GAP_RESET) state.burstEnd = 0
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: turning onto ${target.name.string}" }
            return
        }
        val data = boss.getGunData(weapon.name) ?: return
        if (!data.canShoot(boss.ammoSupplier) || now < state.nextShot) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: ${if (data.canShoot(boss.ammoSupplier)) "between rounds" else "reloading"}" }
            return
        }
        if (TankSight.solidBlocks(boss.level(), muzzle, centre, 0) != 0) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: line to ${target.name.string} blocked by solid blocks" }
            return
        }
        if (teammateBetween(muzzle, centre)) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: holding fire, a teammate is in the way" }
            return
        }
        if (!cadenceOpen(weapon, state, now)) {
            boss.thought.think(weapon.holdChannel) { "${weapon.name}: pausing between bursts" }
            return
        }
        shoot(weapon, crew, state, now, target)
    }

    private fun shoot(weapon: TankWeapon, crew: BossCrewEntity, state: WeaponState, now: Int, target: Entity) {
        // One report per round for a single-shot gun, one per burst for the rest.
        if (weapon.burstMax <= 0 || state.burstEnd != state.reportedBurst) {
            state.reportedBurst = state.burstEnd
            boss.thought.event("gunnery.shoot") {
                val shell = if (weapon.kind == WeaponKind.CANNON) {
                    if (boss.getGunData(weapon.name)?.selectedAmmoType?.get() == HE_INDEX) " HE" else " AP"
                } else ""
                val left = if (settings.limitedAmmo.get()) ", ${ammoLeft(weapon) - 1} left" else ""
                "firing ${weapon.name}$shell at ${target.name.string}$left"
            }
        }
        val uuid: UUID? = null
        val position: Vec3? = null
        boss.vehicleShoot(crew, weapon.name, uuid, position)
        ventGun(weapon)
        consumeAmmo(weapon)
        val interval = if (weapon.interval > 0) weapon.interval
        else max(1, (1200.0 / boss.vehicleWeaponRpm(weapon.name).coerceAtLeast(1)).roundToInt())
        state.nextShot = now + interval
        if (weapon.kind == WeaponKind.CANNON) lastCannonShot = now
    }

    /** Bursts of [TankWeapon.burstMin]..[TankWeapon.burstMax] ticks separated by pauses; single-shot guns are always open. */
    private fun cadenceOpen(weapon: TankWeapon, state: WeaponState, now: Int): Boolean {
        if (weapon.burstMax <= 0) return true
        if (now - state.lastCall > BURST_GAP_RESET) state.burstEnd = 0
        state.lastCall = now
        if (now < state.pauseUntil) return false
        if (state.burstEnd == 0) state.burstEnd = now + between(weapon.burstMin, weapon.burstMax)
        if (now >= state.burstEnd) {
            state.burstEnd = 0
            state.pauseUntil = now + between(weapon.pauseMin, weapon.pauseMax)
            return false
        }
        return true
    }

    /**
     * SBW locks a gun out at 100 heat until it cools below 80, which would silence it for seconds mid-burst. The tank
     * keeps every gun under [HEAT_CAP] so it never overheats.
     */
    private fun ventGun(weapon: TankWeapon) {
        boss.modifyGunData(weapon.name) { data ->
            if (data.heat.get() > HEAT_CAP) data.heat.set(HEAT_CAP)
            if (data.overHeat.get()) data.overHeat.set(false)
        }
    }

    private fun seatedCrew(seat: Int): BossCrewEntity? {
        val crew = boss.crewAt(seat) ?: return null
        if (crew.vehicle !== boss || boss.getSeatIndex(crew) != seat) return null
        return crew
    }

    // ================================================================================================== line of fire

    /**
     * Refreshes the solid-block count between the main gun and the aim every [LINE_INTERVAL] ticks (at once for a new
     * aim). The count is capped a little above the largest pierce depth, which is all anyone needs to know.
     */
    private fun updateLine(aim: Entity, muzzle: Vec3, centre: Vec3) {
        val now = boss.tickCount
        if (aim.id == lineAimId && now - lineTick < LINE_INTERVAL) return
        lineAimId = aim.id
        lineTick = now
        lineSolid = TankSight.solidBlocks(boss.level(), muzzle, centre, LINE_LIMIT)
        teammateInLine = teammateBetween(muzzle, centre)
        boss.thought.think("gunnery.updateLine") {
            "line to ${aim.name.string}: " + when {
                teammateInLine -> "blocked by a teammate"
                lineSolid > 0 -> "blocked by solid blocks"
                else -> "clear"
            }
        }
    }

    /**
     * Whether a teammate boss (see `isTeammate`) is in the way of a shot from [muzzle] to [centre]: its bounding box,
     * inflated by [TEAMMATE_MARGIN], is crossed by the segment before the aim, or already contains the muzzle.
     */
    private fun teammateBetween(muzzle: Vec3, centre: Vec3): Boolean {
        val distance = muzzle.distanceTo(centre)
        for (other in TrackedBosses.near(boss.level(), muzzle, distance)) {
            if (other === boss || !isTeammate(boss, other)) continue
            val box = other.boundingBox.inflate(TEAMMATE_MARGIN)
            if (box.contains(muzzle)) return true
            val hit = box.clip(muzzle, centre).orElse(null) ?: continue
            if (muzzle.distanceTo(hit) < distance) return true
        }
        return false
    }

    /** A shot needs a clear line, except an armour-piercing cannon shell, which may be asked to pierce a few blocks. */
    private fun lineAllowed(weapon: TankWeapon, selectedAmmo: Int): Boolean {
        if (lineSolid == 0) return true
        return weapon.kind == WeaponKind.CANNON && selectedAmmo == AP_INDEX && lineSolid <= weapon.maxPierce
    }

    // ================================================================================================== shells

    private fun selectShell(weapon: TankWeapon, aim: Entity, hidden: Boolean, suppressing: Boolean, now: Int) {
        val desired = when (settings.cannonShell.get()) {
            CannonShell.AP -> AP_INDEX
            CannonShell.HE -> HE_INDEX
            // Return fire at an estimated spot is a soft target in the open until a wall says otherwise: explosive shells.
            CannonShell.AUTO -> if ((hidden && !suppressing) || lineSolid > 0 || !isSoft(aim)) AP_INDEX else HE_INDEX
        }
        val data = boss.getGunData(weapon.name) ?: return
        boss.thought.think("gunnery.selectShell") {
            "cannon wants ${if (desired == AP_INDEX) "AP" else "HE"} (${settings.cannonShell.get()}) for ${aim.name.string}"
        }
        if (data.selectedAmmoType.get() == desired || now < nextShellSwitch) return
        // Switching unloads the chamber and restarts the reload. Armour-piercing is worth that at once (an explosive round
        // does next to nothing to armour); explosive is only picked while the gun is empty / reloading, or idle for a while.
        val idle = data.ammo.get() == 0 || data.reloading() || now - lastCannonShot > SHELL_IDLE_TICKS
        if (desired == HE_INDEX && !idle) {
            boss.thought.think("gunnery.selectShell") { "wants HE but the gun is loaded, keeping the current shell" }
            return
        }
        nextShellSwitch = now + if (desired == AP_INDEX) AP_SWITCH_TICKS else SHELL_SWITCH_TICKS
        boss.thought.event("gunnery.selectShell") { "switching the cannon to ${if (desired == AP_INDEX) "AP" else "HE"}" }
        boss.modifyGunData(weapon.name) { it.changeAmmoConsumer(desired, boss) }
    }

    // ================================================================================================== maths

    /**
     * Point to aim [weapon] at so a round fired now meets [center] (moving at [velocity] per tick): two refinement passes
     * of the time of flight at the muzzle speed, target-velocity lead and compensation for the round's gravity drop.
     * Same maths as `Ac130Gunnery.leadPoint`.
     */
    private fun leadPoint(weapon: TankWeapon, origin: Vec3, center: Vec3, velocity: Vec3): Vec3 {
        val speed = max(boss.getProjectileVelocity(weapon.name).toDouble(), 1.0)
        val gravity = boss.getProjectileGravity(weapon.name).toDouble()

        var t = origin.distanceTo(center) / speed
        repeat(2) { t = origin.distanceTo(center.add(velocity.scale(t))) / speed }
        t = min(t, MAX_LEAD_TICKS)

        // SBW projectiles move pos += v; v.y -= g, so after t ticks the drop is g * t * (t - 1) / 2.
        val drop = max(0.0, gravity * t * (t - 1.0) * 0.5)
        return center.add(velocity.scale(t)).add(0.0, drop, 0.0)
    }

    private fun angleBetween(a: Vec3, b: Vec3): Double {
        val la = a.length()
        val lb = b.length()
        if (la < 1.0e-6 || lb < 1.0e-6) return 180.0
        return Math.toDegrees(acos((a.dot(b) / (la * lb)).coerceIn(-1.0, 1.0)))
    }

    private fun finite(v: Vec3): Boolean = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

    private fun between(low: Int, high: Int): Int = low + boss.getRandom().nextInt(max(1, high - low + 1))

    /** Smoothed per-tick velocity of the weapon-station target, from position differences (a teleport is not motion). */
    private fun trackSecondary(entity: Entity): Vec3 {
        val position = entity.position()
        val motion = secondaryMotion
        if (motion == null || motion.id != entity.id) {
            secondaryMotion = Motion(entity.id, position, Vec3.ZERO)
            return Vec3.ZERO
        }
        val delta = position.subtract(motion.last)
        motion.last = position
        motion.velocity = if (delta.lengthSqr() > TELEPORT_DISTANCE_SQR) Vec3.ZERO
        else motion.velocity.scale(1.0 - VELOCITY_SMOOTHING).add(delta.scale(VELOCITY_SMOOTHING))
        return motion.velocity
    }

    protected companion object {
        const val TAG_AMMO = "Ammo"

        /** Indexes of the cannon's `AmmoType` list in both tanks' vehicle data: 0 = armour-piercing, 1 = high explosive. */
        const val AP_INDEX = 0
        const val HE_INDEX = 1

        const val MIN_CANNON_RANGE = 6.0
        const val COAX_RANGE = 80.0
        const val MAX_LEAD_TICKS = 120.0
        const val BURST_GAP_RESET = 2
        const val HEAT_CAP = 70.0

        const val LINE_INTERVAL = 4
        const val LINE_LIMIT = 8

        /** Blocks added around a teammate's bounding box when testing whether it is in the line of fire. */
        const val TEAMMATE_MARGIN = 1.0
        const val SHELL_SWITCH_TICKS = 60
        const val AP_SWITCH_TICKS = 15
        const val SHELL_IDLE_TICKS = 100

        const val MISSILE_MIN_HOLD = 10
        const val MISSILE_CHECK_INTERVAL = 5

        const val VELOCITY_SMOOTHING = 0.3
        const val TELEPORT_DISTANCE_SQR = 20.0 * 20.0
    }
}
