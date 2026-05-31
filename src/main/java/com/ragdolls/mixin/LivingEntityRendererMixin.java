package com.ragdolls.mixin;

import com.ragdolls.client.LimbSkeleton;
import com.ragdolls.client.RagdollRenderContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After the model has been posed by {@code setupAnim}, but before it (and its armor/item layers)
 * are drawn, add the corpse's procedural limb offsets. Runs only when a ragdoll is being rendered
 * (a single null-check otherwise), so live entities are unaffected.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(
            method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER))
    private void ragdolls$applyLimbs(LivingEntity entity, float entityYaw, float partialTick,
                                     PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                     CallbackInfo ci) {
        LimbSkeleton skeleton = RagdollRenderContext.current();
        if (skeleton != null) {
            try {
                skeleton.apply(partialTick, RagdollRenderContext.solo());
            } catch (Throwable ignored) {
                // Never let limb posing break entity rendering.
            }
        }
    }
}
