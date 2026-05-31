package com.ragdolls.client;

/**
 * Hands the limb skeleton of the corpse currently being drawn to the renderer mixin.
 *
 * <p>All level rendering happens on a single thread, so a plain static field is both correct and
 * cheaper than a {@link ThreadLocal}: the mixin only pays one null-check per entity render.</p>
 */
public final class RagdollRenderContext {

    private static LimbSkeleton current;

    private RagdollRenderContext() {}

    static void set(LimbSkeleton skeleton) {
        current = skeleton;
    }

    static void clear() {
        current = null;
    }

    /** The skeleton for the corpse being rendered right now, or null for a normal entity. */
    public static LimbSkeleton current() {
        return current;
    }
}
