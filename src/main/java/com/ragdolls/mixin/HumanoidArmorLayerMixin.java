package com.ragdolls.mixin;

import com.ragdolls.client.LimbSkeleton;
import com.ragdolls.client.RagdollRenderContext;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When a corpse has had a limb torn off (gore), hide the matching piece of armor so it does not
 * float where the missing limb used to be. {@code setPartVisibility} has just enabled the parts for
 * the slot being drawn; we simply switch the torn ones back off. Runs only while a ragdoll is being
 * rendered (one null-check otherwise), so live entities are untouched.
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {

    @Inject(method = "setPartVisibility", at = @At("TAIL"))
    private void ragdolls$hideTornArmor(HumanoidModel<?> model, EquipmentSlot slot, CallbackInfo ci) {
        LimbSkeleton skeleton = RagdollRenderContext.current();
        if (skeleton == null) {
            return;
        }
        if (skeleton.isTorn(LimbSkeleton.Limb.HEAD)) {
            model.head.visible = false;
            model.hat.visible = false;
        }
        if (skeleton.isTorn(LimbSkeleton.Limb.BODY)) {
            model.body.visible = false;
        }
        if (skeleton.isTorn(LimbSkeleton.Limb.RIGHT_ARM)) {
            model.rightArm.visible = false;
        }
        if (skeleton.isTorn(LimbSkeleton.Limb.LEFT_ARM)) {
            model.leftArm.visible = false;
        }
        if (skeleton.isTorn(LimbSkeleton.Limb.RIGHT_LEG)) {
            model.rightLeg.visible = false;
        }
        if (skeleton.isTorn(LimbSkeleton.Limb.LEFT_LEG)) {
            model.leftLeg.visible = false;
        }
    }
}
