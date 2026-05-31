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
 * blending), so simply scaling vertex alpha would make the model pop out, not dissolve. To get a
 * true fade we route the model through the <b>translucent</b> entity render type (real alpha
 * blending) for its own texture, then scale every vertex's alpha by the fade factor. Any other
 * buffer the renderer asks for (e.g. an outline) is passed through with the same alpha scale.</p>
 */
final class FadeBufferSource implements MultiBufferSource {

    private final MultiBufferSource delegate;
    private final float alpha;
    private final RenderType translucent; // entityTranslucent(texture) - the one we force the body onto

    FadeBufferSource(MultiBufferSource delegate, float alpha, ResourceLocation texture) {
        this.delegate = delegate;
        this.alpha = Mth.clamp(alpha, 0.0f, 1.0f);
        this.translucent = texture != null ? RenderType.entityTranslucent(texture) : null;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        // Redirect the body's solid (cutout) layers onto a translucent type so alpha actually
        // blends; leave glint / outline / shadow / already-translucent layers on their own type
        // (just alpha-scaled) so we do not redraw the body texture in place of an effect.
        RenderType target = (translucent != null && shouldRedirect(type)) ? translucent : type;
        return new FadeVertexConsumer(delegate.getBuffer(target), alpha);
    }

    private boolean shouldRedirect(RenderType type) {
        if (type == translucent) {
            return false;
        }
        String n = type.toString();
        // Skip effect layers - only the plain entity cutout body should be made translucent.
        return !n.contains("glint") && !n.contains("outline") && !n.contains("shadow")
                && !n.contains("translucent") && !n.contains("eyes") && !n.contains("beam");
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
