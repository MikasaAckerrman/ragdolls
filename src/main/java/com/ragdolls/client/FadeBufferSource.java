package com.ragdolls.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * A {@link MultiBufferSource} that fades a corpse out by making its texture genuinely transparent.
 *
 * <p>Vanilla entity render types use an <em>alpha-cutout</em> (a hard {@code discard} with no
 * blending), so simply scaling vertex alpha makes the model pop out at the cutoff instead of
 * dissolving. To get a real fade we redirect the body layers onto the <b>translucent</b> entity
 * render type for the corpse's own texture (real alpha blending), then scale every vertex's alpha by
 * the fade factor, with a tiny per-region jitter so it reads as a soft dissolve.</p>
 *
 * <p>The texture is supplied by the caller (from the entity renderer), so there is no reflection and
 * nothing version-fragile: if it is null we still alpha-scale on the original type (graceful, never
 * a crash).</p>
 */
final class FadeBufferSource implements MultiBufferSource {

    private final MultiBufferSource delegate;
    private final float alpha;
    private final RenderType translucent; // entityTranslucent(corpse texture), or null

    FadeBufferSource(MultiBufferSource delegate, float alpha, ResourceLocation texture) {
        this.delegate = delegate;
        this.alpha = Mth.clamp(alpha, 0.0f, 1.0f);
        this.translucent = texture != null ? RenderType.entityTranslucent(texture) : null;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        RenderType target = (translucent != null && shouldRedirect(type)) ? translucent : type;
        return new FadeVertexConsumer(delegate.getBuffer(target), alpha);
    }

    /** Redirect only the solid body layers; leave effect layers (glint/outline/...) on their own. */
    private boolean shouldRedirect(RenderType type) {
        if (type == translucent) {
            return false;
        }
        String n = type.toString();
        return !n.contains("glint") && !n.contains("outline") && !n.contains("shadow")
                && !n.contains("beam") && !n.contains("eyes") && !n.contains("translucent");
    }

    /**
     * Forwards every vertex but scales its alpha by the fade factor, plus a small per-region jitter
     * from the vertex UV so different parts of the texture lose opacity at slightly different moments
     * (a soft dissolve rather than a uniform dim). The band is tiny (+-12%) so over a 0.5 s fade it
     * reads as a gentle, non-uniform dissolve, not noise.
     */
    private static final class FadeVertexConsumer implements VertexConsumer {

        private static final float JITTER = 0.12f;

        private final VertexConsumer parent;
        private final float alpha;
        private float u, v;

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
            float n = Mth.sin(u * 91.7f + v * 47.3f) * 43758.5453f;
            float bias = ((n - Mth.floor(n)) * 2.0f - 1.0f) * JITTER;
            float a2 = Mth.clamp(alpha * (1.0f + bias), 0.0f, 1.0f);
            parent.setColor(red, green, blue, Mth.clamp((int) (a * a2), 0, 255));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
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
