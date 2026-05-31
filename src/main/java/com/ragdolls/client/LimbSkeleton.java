package com.ragdolls.client;

import com.ragdolls.mixin.AgeableListModelAccessor;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * A lightweight per-corpse "physics skeleton". Each captured {@link ModelPart} (arm, leg, head,
 * tail, ...) gets an angular spring-damper that makes it sag under gravity and lag behind the body
 * as it tumbles, then settle when the body comes to rest - giving the floppy rag-doll feel without
 * any real per-joint collision solver.
 *
 * <p>Optimised for many corpses: fixed float arrays (no per-tick allocation), a hard cap on bones,
 * and a "settled" flag that stops all work once a limb stops moving.</p>
 */
public final class LimbSkeleton {

    private static final int MAX_BONES = 24;

    private static final float SPRING = 0.34f;      // pull back toward the natural pose
    private static final float DAMP = 0.55f;        // velocity damping (higher = less jitter)
    private static final float GRAV_SAG = 0.008f;   // constant downward droop on pitch
    private static final float MAX_ANGLE = 0.45f;   // clamp (~26 deg) so limbs stay attached-looking
    private static final float SETTLE_EPS = 0.0006f;
    private static final float WAKE_SPEED = 0.01f;  // body motion above this re-energises limbs

    private final ModelPart[] bones;
    private final float[] ox, oy, oz;     // current angular offset per bone (rad)
    private final float[] vx, vy, vz;     // angular velocity per bone
    private final float[] pox, poy, poz;  // previous offset (for render interpolation)
    private final float[] sx, sy, sz;     // saved part rotation, to restore after rendering

    private boolean settled = false;
    private boolean applied = false;

    private LimbSkeleton(ModelPart[] bones) {
        this.bones = bones;
        int n = bones.length;
        this.ox = new float[n];
        this.oy = new float[n];
        this.oz = new float[n];
        this.vx = new float[n];
        this.vy = new float[n];
        this.vz = new float[n];
        this.pox = new float[n];
        this.poy = new float[n];
        this.poz = new float[n];
        this.sx = new float[n];
        this.sy = new float[n];
        this.sz = new float[n];
    }

    /**
     * Collect the model's limb parts. Returns null (rigid corpse, zero overhead) for models we
     * cannot introspect - e.g. GeckoLib mobs, which use their own bone system.
     */
    public static LimbSkeleton capture(EntityModel<?> model) {
        List<ModelPart> parts = new ArrayList<>();
        try {
            if (model instanceof HierarchicalModel<?> hierarchical) {
                ModelPart root = hierarchical.root();
                root.getAllParts().forEach(part -> {
                    if (part != root && parts.size() < MAX_BONES) {
                        parts.add(part);
                    }
                });
            } else if (model instanceof AgeableListModelAccessor accessor) {
                accessor.ragdolls$headParts().forEach(p -> add(parts, p));
                accessor.ragdolls$bodyParts().forEach(p -> add(parts, p));
            }
        } catch (Throwable ignored) {
            return null;
        }
        return parts.isEmpty() ? null : new LimbSkeleton(parts.toArray(new ModelPart[0]));
    }

    private static void add(List<ModelPart> parts, ModelPart part) {
        if (parts.size() < MAX_BONES) {
            parts.add(part);
        }
    }

    /** True once every limb has stopped moving (used to decide when the corpse may freeze). */
    public boolean isSettled() {
        return settled;
    }

    /** Snap the interpolation anchor to the current pose so frozen limbs hold perfectly still. */
    public void freezePose() {
        System.arraycopy(ox, 0, pox, 0, ox.length);
        System.arraycopy(oy, 0, poy, 0, oy.length);
        System.arraycopy(oz, 0, poz, 0, oz.length);
    }

    /** Advance the spring-damper one tick. {@code bodySpeed}/{@code bodySpin} drive limb sway. */
    public void tick(float bodySpeed, float bodySpin, float floppiness) {
        for (int i = 0; i < bones.length; i++) {
            pox[i] = ox[i];
            poy[i] = oy[i];
            poz[i] = oz[i];
        }

        boolean bodyStill = bodySpeed < WAKE_SPEED && bodySpin < WAKE_SPEED;
        if (settled && bodyStill) {
            return; // fully asleep: no work until the body moves again
        }

        float kick = (bodySpin * 0.4f + bodySpeed * 1.0f) * floppiness;
        float sag = GRAV_SAG * floppiness;
        float maxMag = 0.0f;

        for (int i = 0; i < bones.length; i++) {
            float phase = ((i & 1) == 0) ? 1.0f : -1.0f;

            float ax = -SPRING * ox[i] - DAMP * vx[i] + sag + phase * kick * 0.25f;
            float ay = -SPRING * oy[i] - DAMP * vy[i] + phase * kick * 0.15f;
            float az = -SPRING * oz[i] - DAMP * vz[i] + phase * kick * 0.5f;

            vx[i] += ax;
            vy[i] += ay;
            vz[i] += az;

            ox[i] = Mth.clamp(ox[i] + vx[i], -MAX_ANGLE, MAX_ANGLE);
            oy[i] = Mth.clamp(oy[i] + vy[i], -MAX_ANGLE, MAX_ANGLE);
            oz[i] = Mth.clamp(oz[i] + vz[i], -MAX_ANGLE, MAX_ANGLE);

            maxMag = Math.max(maxMag, Math.abs(vx[i]) + Math.abs(vy[i]) + Math.abs(vz[i]));
        }

        settled = bodyStill && maxMag < SETTLE_EPS;
    }

    /**
     * Add the interpolated limb offsets to the live model parts (called from the renderer mixin).
     * The original rotations are saved so {@link #restore()} can undo them after rendering - the
     * model instance is shared across all entities of this type, so it must be left untouched.
     */
    public void apply(float partialTick) {
        if (applied) {
            return; // re-entrancy guard: a nested entity render must not double-save/offset
        }
        for (int i = 0; i < bones.length; i++) {
            ModelPart part = bones[i];
            sx[i] = part.xRot;
            sy[i] = part.yRot;
            sz[i] = part.zRot;
        }
        applied = true;
        for (int i = 0; i < bones.length; i++) {
            ModelPart part = bones[i];
            part.xRot += Mth.lerp(partialTick, pox[i], ox[i]);
            part.yRot += Mth.lerp(partialTick, poy[i], oy[i]);
            part.zRot += Mth.lerp(partialTick, poz[i], oz[i]);
        }
    }

    /** Undo {@link #apply}, restoring the shared model to its post-{@code setupAnim} pose. */
    public void restore() {
        if (!applied) {
            return;
        }
        applied = false;
        for (int i = 0; i < bones.length; i++) {
            ModelPart part = bones[i];
            part.xRot = sx[i];
            part.yRot = sy[i];
            part.zRot = sz[i];
        }
    }
}
