package com.ragdolls.client;

import com.ragdolls.mixin.AgeableListModelAccessor;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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

    private static final float SPRING = 0.34f;      // pull back toward the rest pose
    private static final float DAMP = 0.55f;        // velocity damping (higher = less jitter)
    private static final float GRAV_DRAPE = 0.30f;  // how far limbs sag toward world-down at rest
    private static final float MAX_ANGLE = 0.45f;   // clamp (~26 deg) so limbs stay attached-looking
    private static final float SETTLE_EPS = 0.0006f;
    private static final float WAKE_SPEED = 0.01f;  // body motion above this re-energises limbs

    private final ModelPart[] bones;
    private final float[] ox, oy, oz;     // current angular offset per bone (rad)
    private final float[] vx, vy, vz;     // angular velocity per bone
    private final float[] pox, poy, poz;  // previous offset (for render interpolation)
    private final float[] sx, sy, sz;     // saved part rotation, to restore after rendering
    private final boolean[] svis;         // saved part visibility, to restore after rendering
    private final boolean[] torn;         // limbs that have been torn off (hidden)
    private int tornCount = 0;

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
        this.svis = new boolean[n];
        this.torn = new boolean[n];
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

    public int boneCount() {
        return bones.length;
    }

    /** Tear off a random still-attached limb (it becomes hidden). Returns false if none are left. */
    public boolean tearRandom(net.minecraft.util.RandomSource random) {
        int remaining = bones.length - tornCount;
        if (remaining <= 0) {
            return false;
        }
        int pick = random.nextInt(remaining);
        for (int i = 0; i < bones.length; i++) {
            if (!torn[i] && pick-- == 0) {
                torn[i] = true;
                tornCount++;
                return true;
            }
        }
        return false;
    }

    /** Snap the interpolation anchor to the current pose so frozen limbs hold perfectly still. */
    public void freezePose() {
        System.arraycopy(ox, 0, pox, 0, ox.length);
        System.arraycopy(oy, 0, poy, 0, oy.length);
        System.arraycopy(oz, 0, poz, 0, oz.length);
    }

    /**
     * Advance the spring-damper one tick. The {@code orientation} of the body lets the limbs sag
     * toward the real world-down (so a corpse lying on its side/back drapes its limbs to match it
     * instead of snapping back to the upright pose); {@code bodySpeed}/{@code bodySpin} drive sway.
     */
    public void tick(Quaternionf orientation, float bodySpeed, float bodySpin, float floppiness) {
        for (int i = 0; i < bones.length; i++) {
            pox[i] = ox[i];
            poy[i] = oy[i];
            poz[i] = oz[i];
        }

        boolean bodyStill = bodySpeed < WAKE_SPEED && bodySpin < WAKE_SPEED;
        if (settled && bodyStill) {
            return; // fully asleep: no work until the body moves again
        }

        // World-down expressed in the model's local frame: when the body is upright this is
        // (0,-1,0) and adds no droop; as it tumbles onto its side/back the horizontal components
        // grow, pulling the limbs to hang toward the actual ground for the body's final pose.
        Vector3f down = orientation.transformInverse(new Vector3f(0.0f, -1.0f, 0.0f));
        float drape = GRAV_DRAPE * floppiness;
        float tgtPitch = Mth.clamp(down.z * drape, -MAX_ANGLE, MAX_ANGLE);
        float tgtRoll = Mth.clamp(-down.x * drape, -MAX_ANGLE, MAX_ANGLE);

        float kick = (bodySpin * 0.4f + bodySpeed * 1.0f) * floppiness;
        float maxMag = 0.0f;

        for (int i = 0; i < bones.length; i++) {
            float phase = ((i & 1) == 0) ? 1.0f : -1.0f;

            // Spring toward the gravity-draped target (not zero) so the settled pose matches the
            // resting body instead of snapping back to the default standing pose.
            float ax = -SPRING * (ox[i] - tgtPitch) - DAMP * vx[i] + phase * kick * 0.25f;
            float ay = -SPRING * oy[i] - DAMP * vy[i] + phase * kick * 0.15f;
            float az = -SPRING * (oz[i] - tgtRoll) - DAMP * vz[i] + phase * kick * 0.5f;

            vx[i] += ax;
            vy[i] += ay;
            vz[i] += az;

            ox[i] = Mth.clamp(ox[i] + vx[i], -MAX_ANGLE, MAX_ANGLE);
            oy[i] = Mth.clamp(oy[i] + vy[i], -MAX_ANGLE, MAX_ANGLE);
            oz[i] = Mth.clamp(oz[i] + vz[i], -MAX_ANGLE, MAX_ANGLE);

            maxMag = Math.max(maxMag, Math.abs(vx[i]) + Math.abs(vy[i]) + Math.abs(vz[i]));
        }

        // "Settled" = limbs have stopped moving (they are now resting at the draped target). While
        // the body is still toppling, the target keeps shifting, so this stays false until it lies
        // flat - which is exactly when the corpse is allowed to freeze.
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
            svis[i] = part.visible;
            if (torn[i]) {
                part.visible = false; // a torn-off limb is no longer drawn on the body
            }
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
            part.visible = svis[i];
        }
    }
}
