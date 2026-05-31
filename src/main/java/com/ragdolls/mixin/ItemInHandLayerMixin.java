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
 * Keeps the held item with the arm that holds it when a corpse is torn apart (gore):
 * <ul>
 *   <li>On the body, the item of a torn-off arm is not drawn - it flew off with the arm.</li>
 *   <li>On a detached limb chunk, only that chunk's own arm draws its item.</li>
 * </ul>
 * Runs only while a ragdoll is being rendered (one null-check otherwise), so live entities are
 * untouched.
 */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void ragdolls$keepItemWithArm(LivingEntity entity, ItemStack stack, ItemDisplayContext displayContext,
                                          HumanoidArm arm, PoseStack poseStack, MultiBufferSource buffer,
                                          int combinedLight, CallbackInfo ci) {
        LimbSkeleton skeleton = RagdollRenderContext.current();
        if (skeleton == null) {
            return;
        }
        LimbSkeleton.Limb hand = (arm == HumanoidArm.RIGHT) ? LimbSkeleton.Limb.RIGHT_ARM : LimbSkeleton.Limb.LEFT_ARM;
        LimbSkeleton.Limb solo = RagdollRenderContext.solo();
        if (solo != null) {
            if (hand != solo) {
                ci.cancel(); // a flying chunk draws only its own arm's item
            }
        } else if (skeleton.isTorn(hand)) {
            ci.cancel(); // body: this arm (and its item) tore off and flew away
        }
    }
}
