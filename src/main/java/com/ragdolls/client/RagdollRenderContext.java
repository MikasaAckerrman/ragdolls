package com.ragdolls.client;

/**
 * Hands the limb skeleton of the corpse currently being drawn to the renderer mixin, plus an
 * optional "solo" role: when set, only the bone with that role (and its armor / held item) is drawn
 * - used to render a torn-off limb flying away as its own chunk.
 *
 * <p>All level rendering happens on a single thread, so plain static fields are both correct and
 * cheaper than {@link ThreadLocal}: the mixins only pay one null-check per entity render.</p>
 */
public final class RagdollRenderContext {

    private static LimbSkeleton current;
    private static LimbSkeleton.Limb solo;

    private RagdollRenderContext() {}

    static void set(LimbSkeleton skeleton) {
        current = skeleton;
        solo = null;
    }

    /** Render only the bone with role {@code soloRole} (a detached, flying limb chunk). */
    static void setSolo(LimbSkeleton skeleton, LimbSkeleton.Limb soloRole) {
        current = skeleton;
        solo = soloRole;
    }

    static void clear() {
        current = null;
        solo = null;
    }

    /** The skeleton for the corpse being rendered right now, or null for a normal entity. */
    public static LimbSkeleton current() {
        return current;
    }

    /** Non-null while rendering a single detached limb: the only role that should be drawn. */
    public static LimbSkeleton.Limb solo() {
        return solo;
    }
}
