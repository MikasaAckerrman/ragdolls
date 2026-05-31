package com.ragdolls.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ragdolls.client.LimbSkeleton;
import com.ragdolls.client.RagdollRenderContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When a corpse's arm has been torn off (gore), do not draw the item it was holding: the item left
 * with the arm, so rendering it on the body would leave it floating in mid-air. Runs only while a
 * ragdoll is being rendered (one null-check otherwise), so live entities are untouched.
 */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void ragdolls$skipTornHand(LivingEntity entity, ItemStack stack, ItemDisplayContext displayContext,
                                       HumanoidArm arm, PoseStack poseStack, MultiBufferSource buffer,
                                       int combinedLight, CallbackInfo ci) {
        LimbSkeleton skeleton = RagdollRenderContext.current();
        if (skeleton == null) {
            return;
        }
        LimbSkeleton.Limb hand = (arm == HumanoidArm.RIGHT) ? LimbSkeleton.Limb.RIGHT_ARM : LimbSkeleton.Limb.LEFT_ARM;
        if (skeleton.isTorn(hand)) {
            ci.cancel();
        }
    }
}
