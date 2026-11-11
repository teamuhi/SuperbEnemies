package com.teamuhi.superbenemies.client

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.entity.boss.common.BossVehicle
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

/**
 * Fades a boss aircraft's engine out instead of cutting it.
 *
 * Superb Warfare's engine sound is audible out to 768 blocks but the bosses are only tracked by the client out to 512,
 * and its sound instance stops the moment the entity leaves the client level, so a distant plane used to go silent at
 * about a third of its volume. When a healthy boss leaves the client level this plays a continuation of the same
 * loop at its last position and velocity and fades it to nothing. Superb Warfare itself is not touched.
 *
 * Only for the aircraft (the ground bosses are heard well inside the range they are tracked at). Not used for a crippled boss or a wreck (the crash would keep droning after the explosion).
 */
@Mod.EventBusSubscriber(modid = SuperbEnemies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = [Dist.CLIENT])
object BossEngineFade {

    /** 3 seconds: long enough to read as a fade, short enough that the ghost does not outlive the plane noticeably. */
    private const val FADE_TICKS = 60

    /** Superb Warfare multiplies the engine volume by its own 0..3 fade-in counter; at full fade-in the factor is 3. */
    private const val SBW_FADE_FACTOR = 3f

    @SubscribeEvent
    fun onEntityLeave(event: EntityLeaveLevelEvent) {
        val level = event.level
        if (!level.isClientSide) return
        val entity = event.entity
        if (entity !is VehicleEntity || entity !is BossVehicle) return
        // The tanks' engines are only audible out to a short range, well inside the tracking range: nothing to fade.
        if (entity.groundBoss) return

        val mc = Minecraft.getInstance()
        // Disconnecting or changing dimension clears every entity; the sound manager stops its sounds anyway.
        if (mc.level !== level || mc.player == null) return
        if (entity.crippled || entity.isWreck || entity.health <= 0f) return
        if (!entity.engineRunning()) return
        if (mc.player?.vehicle === entity) return

        val sound = entity.getEngineSound() ?: return
        val volume = entity.getEngineSoundVolume() * SBW_FADE_FACTOR
        if (volume <= 0f) return

        mc.soundManager.play(FadingEngineSound(sound, entity.position(), entity.deltaMovement, enginePitch(entity.power), volume))
    }

    /** Same curve as Superb Warfare's `EngineSound.getPitch` for fixed-wing aircraft. */
    private fun enginePitch(power: Float): Float {
        val pitch = when {
            power < 0.5f -> 0.6f + power * 0.4f
            power <= 1f -> 0.8f + (power - 0.5f) * 0.4f
            else -> minOf(power, 1.5f)
        }
        return Mth.abs(pitch)
    }

    private class FadingEngineSound(
        sound: SoundEvent,
        start: Vec3,
        private val velocity: Vec3,
        pitch: Float,
        private val startVolume: Float
    ) : AbstractTickableSoundInstance(sound, SoundSource.AMBIENT, Minecraft.getInstance().level!!.random) {

        private var age = 0

        init {
            looping = true
            delay = 0
            volume = startVolume
            this.pitch = pitch
            x = start.x
            y = start.y
            z = start.z
        }

        override fun tick() {
            if (age >= FADE_TICKS || Minecraft.getInstance().level == null) {
                stop()
                return
            }
            age++
            // Smoothstep so the tail does not end in a step: slow start, slow finish.
            val t = age.toFloat() / FADE_TICKS
            volume = startVolume * (1f - t * t * (3f - 2f * t))
            x += velocity.x
            y += velocity.y
            z += velocity.z
        }
    }
}
