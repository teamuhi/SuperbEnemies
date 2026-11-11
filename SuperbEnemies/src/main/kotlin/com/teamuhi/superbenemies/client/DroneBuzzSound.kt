package com.teamuhi.superbenemies.client

import com.teamuhi.superbenemies.config.DroneConfig
import com.teamuhi.superbenemies.entity.drone.DroneEntity
import com.atsuishio.superbwarfare.init.ModSounds
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth

/**
 * The drone's engine buzz: a client-only loop of Superb Warfare's `superbwarfare:drone_engine` that follows the drone.
 *
 * Volume and pitch rise with the drone's speed (half volume at rest, full at top speed) and `engineVolume` scales it.
 * The loop stops by itself when the drone is removed, dies or changes level. [onDroneJoin] is called from a client
 * `EntityJoinLevelEvent` listener and starts the loop once per drone.
 */
private const val BASE_VOLUME = 0.6f
private const val PITCH_MIN = 0.9f
private const val PITCH_RANGE = 0.3f

/** Playing buzz per drone entity id, so a drone that re-joins the level does not get a second loop. Client thread only. */
private val playing = HashMap<Int, DroneBuzzSound>()

/**
 * Starts the engine buzz for [drone] on the client when `engineVolume` is above 0 and no buzz is already playing for it.
 * Does nothing on the server, outside the client's current level or for a drone that is already gone.
 */
fun onDroneJoin(drone: DroneEntity) {
    val level = drone.level()
    if (!level.isClientSide) return
    if (DroneConfig.ENGINE_VOLUME.get() <= 0) return
    val mc = Minecraft.getInstance()
    if (mc.level !== level || drone.isRemoved || !drone.isAlive) return

    val existing = playing[drone.id]
    if (existing != null) {
        if (existing.drone === drone && !existing.isStopped && mc.soundManager.isActive(existing)) return
        playing.remove(drone.id)
    }
    val sound = DroneBuzzSound(drone)
    playing[drone.id] = sound
    mc.soundManager.play(sound)
}

/** The looping engine sound of one drone. Use [onDroneJoin] rather than creating it directly. */
class DroneBuzzSound internal constructor(internal val drone: DroneEntity) : AbstractTickableSoundInstance(
    ModSounds.DRONE_ENGINE.get(), SoundSource.HOSTILE, drone.level().random
) {

    init {
        looping = true
        delay = 0
        x = drone.x
        y = drone.y
        z = drone.z
        update()
    }

    override fun tick() {
        val mc = Minecraft.getInstance()
        if (drone.isRemoved || !drone.isAlive || mc.level == null || drone.level() !== mc.level) {
            finish()
            return
        }
        x = drone.x
        y = drone.y
        z = drone.z
        update()
    }

    /** Volume and pitch from the speed ratio (0 to 1 of the top speed) and the live `engineVolume`. */
    private fun update() {
        val top = drone.topSpeed()
        val ratio = if (top > 0.0) Mth.clamp(drone.deltaMovement.length() / top, 0.0, 1.0).toFloat() else 0f
        volume = BASE_VOLUME * DroneConfig.ENGINE_VOLUME.get() / 100f * (0.5f + 0.5f * ratio)
        pitch = PITCH_MIN + PITCH_RANGE * ratio
    }

    private fun finish() {
        stop()
        if (playing[drone.id] === this) playing.remove(drone.id)
    }
}
