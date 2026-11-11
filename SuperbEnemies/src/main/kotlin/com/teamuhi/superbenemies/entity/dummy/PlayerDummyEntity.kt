package com.teamuhi.superbenemies.entity.dummy

import com.teamuhi.superbenemies.config.DummyConfig
import com.teamuhi.superbenemies.debug.AiKind
import com.teamuhi.superbenemies.entity.dummy.ai.DummyAi
import com.teamuhi.superbenemies.entity.dummy.ai.DummyHideGoal
import com.teamuhi.superbenemies.entity.dummy.ai.DummyLookGoal
import com.teamuhi.superbenemies.entity.dummy.ai.DummyRoamGoal
import com.teamuhi.superbenemies.entity.dummy.ai.DummyStrafeGoal
import com.teamuhi.superbenemies.init.ModItems
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * A player-looking test target, spawned from its egg, mainly so the bosses can be tested without being in survival.
 *
 * The body (navigation, anchor, health tag, invulnerability, pickup) is [HumanoidDummyEntity]; this class only picks the
 * goals and the settings. Behaviour lives in four goals, all of which read [DummyConfig] live (through
 * [PlayerDummySettings]): [DummyHideGoal] (cover from the bosses), [DummyStrafeGoal] and [DummyRoamGoal] (movement:
 * strafing / roaming, one per movement mode) and [DummyLookGoal] (facing the nearest threat).
 */
class PlayerDummyEntity(type: EntityType<out PathfinderMob>, level: Level) : HumanoidDummyEntity(type, level) {

    override val settings: DummySettings = PlayerDummySettings

    override val aiKind: AiKind get() = AiKind.PLAYER_DUMMY

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, DummyHideGoal(this))
        goalSelector.addGoal(2, DummyStrafeGoal(this))
        goalSelector.addGoal(2, DummyRoamGoal(this)) // mutually exclusive with the strafe goal: each checks the movement mode
        goalSelector.addGoal(3, DummyLookGoal(this))
    }

    override fun pickupItem(): ItemStack = ItemStack(ModItems.PLAYER_DUMMY_SPAWN_EGG.get())

    override fun coverEnabled(): Boolean = DummyConfig.HIDE_FROM_BOSSES.get()

    /** With `hideFromBosses` on: whenever a live, uncrippled boss is within [DummyAi.BOSS_RANGE] blocks. */
    override fun wantsCover(): Boolean {
        val wants = coverEnabled() && DummyAi.nearestBoss(this, includeCrippled = false) != null
        thought.think("wantsCover") { if (wants) "yes: a boss is near" else if (!coverEnabled()) "no: hiding is switched off" else "no: no boss near" }
        return wants
    }

    companion object {
        /** Vanilla player walk speed (0.1), 20 health, and a 32 block follow range. */
        fun createAttributes(): AttributeSupplier.Builder = createDummyAttributes()
    }
}
