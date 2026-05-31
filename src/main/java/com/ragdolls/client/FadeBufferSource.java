package com.ragdolls.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ragdolls.Ragdolls;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link MultiBufferSource} that fades a corpse out by making its textures genuinely transparent.
 *
 * <p>Vanilla entity render types use an <em>alpha-cutout</em> (a hard {@code discard} with no
 * blending), so just scaling vertex alpha would make the model pop, not dissolve. For a smooth fade
 * we redirect each cutout layer onto a <b>translucent</b> render type that keeps <em>that layer's
 * own texture</em> (so body, armor, cape, ... each fade with their correct texture), then scale
 * every vertex's alpha by the fade factor.</p>
 *
 * <p>The cutout-&gt;translucent mapping is intrinsic to the render type, so it is cached once per
 * distinct type in a shared {@link IdentityHashMap} - after warm-up there is zero per-frame work and
 * no allocation. If the (reflective) texture lookup is ever unavailable the layer is passed through
 * unchanged, so the worst case is the old hard-cutout look, never a crash.</p>
 */
final class FadeBufferSource implements MultiBufferSource {

    /** Cutout render type -&gt; its translucent equivalent (or itself when it must not be redirected). */
    private static final Map<RenderType, RenderType> REMAP = new IdentityHashMap<>();

    // Reflection handles to read a composite render type's texture (official runtime names).
    private static final Field STATE_FIELD;
    private static final Field TEXTURE_STATE_FIELD;
    private static final Method CUTOUT_TEXTURE;

    static {
        Field stateField = null;
        Field textureStateField = null;
        Method cutoutTexture = null;
        try {
            Class<?> composite = Class.forName("net.minecraft.client.renderer.RenderType$CompositeRenderType");
            stateField = composite.getDeclaredField("state");
            stateField.setAccessible(true);
            Class<?> compositeState = Class.forName("net.minecraft.client.renderer.RenderType$CompositeState");
            textureStateField = compositeState.getDeclaredField("textureState");
            textureStateField.setAccessible(true);
            Class<?> emptyTextureState = Class.forName("net.minecraft.client.renderer.RenderStateShard$EmptyTextureStateShard");
            cutoutTexture = emptyTextureState.getDeclaredMethod("cutoutTexture");
            cutoutTexture.setAccessible(true);
        } catch (Throwable t) {
            stateField = null;
            textureStateField = null;
            cutoutTexture = null;
            Ragdolls.LOGGER.warn("Fade: render-type texture lookup unavailable; corpses dissolve without smooth blending", t);
        }
        STATE_FIELD = stateField;
        TEXTURE_STATE_FIELD = textureStateField;
        CUTOUT_TEXTURE = cutoutTexture;
    }

    private final MultiBufferSource delegate;
    private final float alpha;

    FadeBufferSource(MultiBufferSource delegate, float alpha) {
        this.delegate = delegate;
        this.alpha = Mth.clamp(alpha, 0.0f, 1.0f);
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return new FadeVertexConsumer(delegate.getBuffer(translucentEquivalent(type)), alpha);
    }

    /** The translucent equivalent of a cutout layer (same texture), cached per type; never throws. */
    private static RenderType translucentEquivalent(RenderType type) {
        RenderType cached = REMAP.get(type);
        if (cached != null) {
            return cached;
        }
        RenderType result = type;
        try {
            String name = type.toString();
            // Leave effect layers (glint/outline/shadow/glow/already-translucent) on their own type.
            boolean effect = name.contains("glint") || name.contains("outline")
                    || name.contains("shadow") || name.contains("beam")
                    || name.contains("eyes") || name.contains("translucent");
            if (!effect) {
                ResourceLocation tex = textureOf(type);
                if (tex != null) {
                    result = RenderType.entityTranslucent(tex);
                }
            }
        } catch (Throwable ignored) {
            result = type; // fall back to the original type (no smooth blend, but correct texture)
        }
        REMAP.put(type, result);
        return result;
    }

    /** The cutout texture of a composite render type, or null if it is not a textured composite. */
    private static ResourceLocation textureOf(RenderType type) throws ReflectiveOperationException {
        if (STATE_FIELD == null) {
            return null;
        }
        Object state = STATE_FIELD.get(type);            // RenderType$CompositeState (or throws)
        Object textureState = TEXTURE_STATE_FIELD.get(state);
        Object optional = CUTOUT_TEXTURE.invoke(textureState);
        return optional instanceof Optional<?> o ? (ResourceLocation) o.orElse(null) : null;
    }

    /**
     * Forwards every vertex but scales its alpha by the fade factor, plus a small per-region jitter
     * derived from the vertex UV so different parts of the texture lose opacity at slightly
     * different moments (a soft "dissolve" rather than a uniform dim). The bias band is tiny
     * (+-12%) so over a 0.5 s fade it reads as a gentle, non-uniform dissolve, not noise.
     */
    private static final class FadeVertexConsumer implements VertexConsumer {

        private static final float JITTER = 0.12f; // +-12% per-region timing spread

        private final VertexConsumer parent;
        private final float alpha;
        private float u, v; // last UV seen for the current vertex

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
            // Cheap, stable pseudo-random in [-1,1] from the texel coords: regions fade staggered.
            float n = (Mth.sin(u * 91.7f + v * 47.3f) * 43758.5453f);
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
