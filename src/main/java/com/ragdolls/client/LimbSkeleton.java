package com.ragdolls.client;

import com.ragdolls.mixin.AgeableListModelAccessor;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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

    /**
     * Anatomical role of a captured bone. Known only for humanoids (where it lets us hide the
     * matching armor piece / held item when a limb is torn off); everything else is {@link #OTHER}.
     */
    public enum Limb { HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG, OTHER }

    private static final int MAX_BONES = 24;

    private static final float SPRING = 0.22f;      // softer -> a visible pendulum swing
    private static final float DAMP = 0.40f;        // underdamped (limbs swing) but still settles
    private static final float MAX_ANGLE = 1.30f;   // ~75 deg: limbs really dangle, full range
    private static final float SETTLE_EPS = 0.0009f;
    private static final float WAKE_SPEED = 0.01f;  // body motion above this re-energises limbs

    private final ModelPart[] bones;
    private final Limb[] role;             // anatomical role per bone (for gore: armor/item hiding)
    private final float[] ox, oy, oz;     // current angular offset per bone (rad)
    private final float[] vx, vy, vz;     // angular velocity per bone
    private final float[] pox, poy, poz;  // previous offset (for render interpolation)
    private final float[] sx, sy, sz;     // saved part rotation, to restore after rendering
    private final boolean[] svis;         // saved part visibility, to restore after rendering
    private final boolean[] torn;         // limbs that have been torn off (hidden)
    private int tornCount = 0;

    private boolean settled = false;
    private boolean applied = false;
    private boolean droppedRightItem = false; // held item fell to the ground (do not draw in hand)
    private boolean droppedLeftItem = false;
    private final Vector3f tmpDown = new Vector3f(); // reused each tick (no per-tick allocation)

    private LimbSkeleton(ModelPart[] bones, Limb[] role) {
        this.bones = bones;
        this.role = role;
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
        List<Limb> roles = new ArrayList<>();
        try {
            if (model instanceof HumanoidModel<?> h) {
                // Known anatomy -> we can hide the matching armor/held item when a limb is torn.
                addRole(parts, roles, h.head, Limb.HEAD);
                addRole(parts, roles, h.hat, Limb.HEAD);
                addRole(parts, roles, h.body, Limb.BODY);
                addRole(parts, roles, h.rightArm, Limb.RIGHT_ARM);
                addRole(parts, roles, h.leftArm, Limb.LEFT_ARM);
                addRole(parts, roles, h.rightLeg, Limb.RIGHT_LEG);
                addRole(parts, roles, h.leftLeg, Limb.LEFT_LEG);
            } else if (model instanceof HierarchicalModel<?> hierarchical) {
                ModelPart root = hierarchical.root();
                root.getAllParts().forEach(part -> {
                    if (part != root && parts.size() < MAX_BONES) {
                        parts.add(part);
                        roles.add(Limb.OTHER);
                    }
                });
            } else if (model instanceof AgeableListModelAccessor accessor) {
                accessor.ragdolls$headParts().forEach(p -> add(parts, roles, p));
                accessor.ragdolls$bodyParts().forEach(p -> add(parts, roles, p));
            }
        } catch (Throwable ignored) {
            return null;
        }
        return parts.isEmpty() ? null
                : new LimbSkeleton(parts.toArray(new ModelPart[0]), roles.toArray(new Limb[0]));
    }

    private static void addRole(List<ModelPart> parts, List<Limb> roles, ModelPart part, Limb r) {
        if (part != null && parts.size() < MAX_BONES) {
            parts.add(part);
            roles.add(r);
        }
    }

    private static void add(List<ModelPart> parts, List<Limb> roles, ModelPart part) {
        if (parts.size() < MAX_BONES) {
            parts.add(part);
            roles.add(Limb.OTHER);
        }
    }

    /** True once every limb has stopped moving (used to decide when the corpse may freeze). */
    public boolean isSettled() {
        return settled;
    }

    /**
     * Tear off a random still-attached limb (it becomes hidden). Returns the anatomical role of the
     * limb that was torn, or {@code null} if none remained.
     */
    public Limb tearRandom(RandomSource random) {
        int remaining = bones.length - tornCount;
        if (remaining <= 0) {
            return null;
        }
        int pick = random.nextInt(remaining);
        for (int i = 0; i < bones.length; i++) {
            if (!torn[i] && pick-- == 0) {
                torn[i] = true;
                tornCount++;
                return role[i];
            }
        }
        return null;
    }

    /** True if a torn-off bone has the given anatomical role (drives armor/held-item hiding). */
    public boolean isTorn(Limb limb) {
        for (int i = 0; i < bones.length; i++) {
            if (torn[i] && role[i] == limb) {
                return true;
            }
        }
        return false;
    }

    /** Mark an arm's held item as dropped to the ground, so it is no longer drawn in that hand. */
    public void markItemDropped(Limb arm) {
        if (arm == Limb.RIGHT_ARM) {
            droppedRightItem = true;
        } else if (arm == Limb.LEFT_ARM) {
            droppedLeftItem = true;
        }
    }

    /** True if the item this arm was holding has dropped to the ground. */
    public boolean isItemDropped(Limb arm) {
        return arm == Limb.RIGHT_ARM ? droppedRightItem : arm == Limb.LEFT_ARM && droppedLeftItem;
    }

    /** True if this model has identifiable arms (humanoid) - i.e. hand-item drops make sense. */
    public boolean hasArms() {
        for (Limb r : role) {
            if (r == Limb.RIGHT_ARM || r == Limb.LEFT_ARM) {
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

        // World-down expressed in the body's local frame. A limb at rest points along local -Y
        // (straight down in model space); we solve the pitch (about X) and roll (about Z) that swing
        // that rest direction onto local world-down, so the limb ALWAYS hangs toward the real ground
        // no matter how the body is facing - upright, on its side, or fully upside-down. (Computed
        // once per tick; per-bone we only scale by role and clamp, so this stays cheap.)
        tmpDown.set(0.0f, -1.0f, 0.0f);
        orientation.transformInverse(tmpDown);
        float lx = Mth.clamp(tmpDown.x, -1.0f, 1.0f);
        float ly = tmpDown.y;
        float lz = tmpDown.z;
        float hangRoll = (float) Math.asin(lx);             // sideways sag
        float hangPitch = (float) Math.atan2(-lz, -ly);     // forward/back sag (handles flip via ly)
        float kick = (bodySpin * 0.5f + bodySpeed * 1.2f) * floppiness;
        float maxMag = 0.0f;

        for (int i = 0; i < bones.length; i++) {
            float gain = gainFor(role[i]) * Math.min(1.0f, floppiness);
            float limit = limitFor(role[i]);
            float tgtPitch = Mth.clamp(hangPitch * gain, -limit, limit);
            float tgtRoll = Mth.clamp(hangRoll * gain, -limit, limit);
            float phase = ((i & 1) == 0) ? 1.0f : -1.0f;

            // Spring toward the gravity-hang target (not zero), with a low-damped swing so the limbs
            // visibly dangle and lag the body instead of being a rigid doll.
            float ax = -SPRING * (ox[i] - tgtPitch) - DAMP * vx[i] + phase * kick * 0.30f;
            float ay = -SPRING * oy[i] - DAMP * vy[i] + phase * kick * 0.12f;
            float az = -SPRING * (oz[i] - tgtRoll) - DAMP * vz[i] + phase * kick * 0.40f;

            vx[i] += ax;
            vy[i] += ay;
            vz[i] += az;

            ox[i] = Mth.clamp(ox[i] + vx[i], -limit, limit);
            oy[i] = Mth.clamp(oy[i] + vy[i], -limit, limit);
            oz[i] = Mth.clamp(oz[i] + vz[i], -limit, limit);

            maxMag = Math.max(maxMag, Math.abs(vx[i]) + Math.abs(vy[i]) + Math.abs(vz[i]));
        }

        // "Settled" = limbs have stopped moving (resting at their gravity-hang). While the body is
        // still toppling, the target keeps shifting, so this stays false until it lies flat - which
        // is exactly when the corpse is allowed to freeze.
        settled = bodyStill && maxMag < SETTLE_EPS;
    }

    /**
     * How far a limb of this role may swing toward gravity (radians). The torso stays rigid (it is
     * the body the whole ragdoll already rotates as one); arms hang the most, legs less, the head
     * lolls a little; unknown (non-humanoid) parts get a mild sag since we cannot tell which is the
     * torso.
     */
    private static float gainFor(Limb r) {
        return switch (r) {
            case BODY -> 0.0f;
            case HEAD -> 0.45f;
            case RIGHT_ARM, LEFT_ARM -> 1.15f;
            case RIGHT_LEG, LEFT_LEG -> 0.80f;
            default -> 0.45f;
        };
    }

    /**
     * Per-role swing limit (radians). Limbs we positively identify (arms/legs/head of a humanoid)
     * may swing far and really dangle; the torso is locked; everything we cannot identify - which on
     * a quadruped/bird model includes the body itself - is kept to a small, safe wobble so those
     * models do not visibly come apart or jitter.
     */
    private static float limitFor(Limb r) {
        return switch (r) {
            case BODY -> 0.0f;
            case HEAD -> 0.6f;
            case RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG -> MAX_ANGLE;
            default -> 0.25f; // unknown parts (incl. non-humanoid torsos): gentle, no falling apart
        };
    }

    /**
     * Add the interpolated limb offsets to the live model parts (called from the renderer mixin).
     * The original rotations are saved so {@link #restore()} can undo them after rendering - the
     * model instance is shared across all entities of this type, so it must be left untouched.
     */
    public void apply(float partialTick, Limb soloRole) {
        if (applied) {
            return; // re-entrancy guard: a nested entity render must not double-save/offset
        }
        for (int i = 0; i < bones.length; i++) {
            ModelPart part = bones[i];
            sx[i] = part.xRot;
            sy[i] = part.yRot;
            sz[i] = part.zRot;
            svis[i] = part.visible;
            if (soloRole != null) {
                part.visible = (role[i] == soloRole); // detached chunk: draw only this one limb
            } else if (torn[i]) {
                part.visible = false; // body: a torn-off limb is no longer drawn here
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
