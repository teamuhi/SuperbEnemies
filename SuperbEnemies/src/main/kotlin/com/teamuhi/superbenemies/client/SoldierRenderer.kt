package com.teamuhi.superbenemies.client

import com.teamuhi.superbenemies.SuperbEnemies
import com.teamuhi.superbenemies.entity.soldier.LauncherSoldierEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.model.HumanoidModel.ArmPose
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.HumanoidArm
import java.util.concurrent.ConcurrentHashMap

/**
 * Draws a launcher soldier (the Dummy Soldier and the Javelin and RPG soldiers): the shared dummy renderer plus an optional
 * custom [skin] and arm poses for the held launcher.
 *
 * A [net.minecraft.client.renderer.entity.HumanoidMobRenderer] never asks the held item for an arm pose (only the player
 * renderer does), so [prepareModel] sets them: both arms raised while the soldier aims down sights, the two-handed carry
 * while it sprints, otherwise the main arm holds the item. The launcher itself is drawn by Superb Warfare's item renderer.
 *
 * The skins `assets/superbenemies/textures/entity/dummy_soldier.png` (Dummy Soldier) and `at_soldier.png` (both anti-tank
 * soldiers) are optional. Whether each exists is looked up once and cached (a texture lookup per frame would hit the
 * resource manager); [invalidateSkinCache] clears the cache on every resource reload. Without the file the soldier uses the
 * default Steve or Alex skin, like the player dummy.
 */
class SoldierRenderer<T : LauncherSoldierEntity>(
    context: EntityRendererProvider.Context,
    private val skin: ResourceLocation
) : HumanoidDummyRenderer<T>(context) {

    override fun textureFor(entity: T): ResourceLocation =
        if (hasCustomSkin(skin)) skin else super.textureFor(entity)

    /** A custom skin uses the wide (Steve) arm layout; the default skin keeps its UUID-based choice. */
    override fun usesSlimModel(entity: T): Boolean =
        !hasCustomSkin(skin) && super.usesSlimModel(entity)

    override fun prepareModel(entity: T, model: PlayerModel<T>) {
        val main: ArmPose
        val off: ArmPose
        when {
            entity.aiming -> {
                main = ArmPose.BOW_AND_ARROW
                off = ArmPose.BOW_AND_ARROW
            }
            entity.isSprinting -> {
                main = ArmPose.CROSSBOW_CHARGE
                off = ArmPose.CROSSBOW_CHARGE
            }
            else -> {
                main = ArmPose.ITEM
                off = ArmPose.EMPTY
            }
        }
        if (entity.mainArm == HumanoidArm.LEFT) {
            model.leftArmPose = main
            model.rightArmPose = off
        } else {
            model.rightArmPose = main
            model.leftArmPose = off
        }
    }

    companion object {
        val DUMMY_SKIN = ResourceLocation(SuperbEnemies.MOD_ID, "textures/entity/dummy_soldier.png")
        val AT_SKIN = ResourceLocation(SuperbEnemies.MOD_ID, "textures/entity/at_soldier.png")

        /** Skin -> whether the file exists. Written by the render thread and the reload listener, hence concurrent. */
        private val presence = ConcurrentHashMap<ResourceLocation, Boolean>()

        private fun hasCustomSkin(skin: ResourceLocation): Boolean =
            presence.getOrPut(skin) { Minecraft.getInstance().resourceManager.getResource(skin).isPresent }

        /** Forgets the cached lookups; called when resource packs reload so an added or removed skin is noticed. */
        fun invalidateSkinCache() {
            presence.clear()
        }
    }
}
