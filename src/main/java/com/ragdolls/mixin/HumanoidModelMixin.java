package com.ragdolls.mixin;

import com.ragdolls.client.RagdollManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the local player is carrying a corpse (RMB grab), straighten both their arms forward so it
 * looks like they are holding the body out in front of them. Runs after the normal pose is built
 * and only for the grabbing player, so every other humanoid is untouched.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {

    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void ragdolls$carryPose(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                    float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (entity != Minecraft.getInstance().player || !RagdollManager.isGrabbing()) {
            return;
        }
        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        // Arms reach straight out in front (about -80 deg pitch), held slightly inward.
        float pitch = -1.4f;
        model.rightArm.xRot = pitch;
        model.leftArm.xRot = pitch;
        model.rightArm.yRot = 0.0f;
        model.leftArm.yRot = 0.0f;
        model.rightArm.zRot = 0.08f;
        model.leftArm.zRot = -0.08f;
    }
}
