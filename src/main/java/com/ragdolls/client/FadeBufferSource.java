package com.ragdolls.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;

/**
 * A {@link MultiBufferSource} that multiplies the alpha of every vertex by a factor, used to fade a
 * corpse out smoothly before it is removed. Vertices are still written to the real buffers, so the
 * normal {@code endBatch()} flush handles drawing.
 */
final class FadeBufferSource implements MultiBufferSource {

    private final MultiBufferSource delegate;
    private final float alpha;

    FadeBufferSource(MultiBufferSource delegate, float alpha) {
        this.delegate = delegate;
        this.alpha = Mth.clamp(alpha, 0.0f, 1.0f);
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return new FadeVertexConsumer(delegate.getBuffer(type), alpha);
    }

    /** Forwards every vertex unchanged except for a scaled alpha channel. */
    private static final class FadeVertexConsumer implements VertexConsumer {

        private final VertexConsumer parent;
        private final float alpha;

        FadeVertexConsumer(VertexConsumer parent, float alpha) {
            this.parent = parent;
            this.alpha = alpha;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            parent.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int a) {
            parent.setColor(red, green, blue, Mth.clamp((int) (a * alpha), 0, 255));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            parent.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            parent.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            parent.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            parent.setNormal(x, y, z);
            return this;
        }
    }
}
