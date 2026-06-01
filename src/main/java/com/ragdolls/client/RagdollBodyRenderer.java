package com.ragdolls.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ragdolls.entity.RagdollBodyEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * No-op renderer for the invisible collision body. It exists only so the entity type has a
 * registered renderer; the body is never drawn (the visible corpse is drawn by the owning Ragdoll).
 */
public final class RagdollBodyRenderer extends EntityRenderer<RagdollBodyEntity> {

    public RagdollBodyRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(RagdollBodyEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // Intentionally draws nothing.
    }

    @Override
    public ResourceLocation getTextureLocation(RagdollBodyEntity entity) {
        return ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    }
}
