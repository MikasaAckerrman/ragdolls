package com.ragdolls.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ragdolls.Config;
import com.ragdolls.Ragdolls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.List;

/**
 * A single torn-off limb (arm / leg / head) flying away from a corpse as its own rigid chunk. It is
 * drawn by re-using the entity's own renderer with a "solo" visibility mask (see
 * {@link RagdollRenderContext}), so the limb keeps its skin, its armor piece and - for an arm - the
 * item it was holding, all for free. Physics are a cut-down version of the corpse's: gravity, drag,
 * a tumble and a bounce off the ground.
 *
 * <p>Owned by its {@link Ragdoll}: it ticks and renders with the corpse and disappears with it.</p>
 */
final class FlyingLimb {

    private static final double GRAVITY = 0.08;     // real weight, matching the body (was floaty)
    private static final double DRAG = 0.99;
    private static final double GROUND_BOUNCE = 0.30;
    private static final double GROUND_FRICTION = 0.72;
    private static final double SPIN_DRAG = 0.985;
    private static final double HALF_BOX = 0.18; // small collision box for the chunk

    private final LivingEntity entity;
    private final LimbSkeleton skeleton;
    private final LimbSkeleton.Limb role;
    private final double halfHeight;

    private double cx, cy, cz;     // chunk centre (world)
    private double pcx, pcy, pcz;  // previous centre (render interpolation)
    private double vx, vy, vz;

    private final Quaternionf rot = new Quaternionf();
    private final Quaternionf prevRot = new Quaternionf();
    private final float spinX, spinY, spinZ;
    private float spinSpeed;

    private boolean resting = false;
    private boolean renderErrorLogged = false;

    private int age = 0;
    private final int maxLife;     // ticks before this chunk fades away on its own (~6s)
    private final int fadeTicks;   // length of the transparent tail-fade

    FlyingLimb(LivingEntity entity, LimbSkeleton skeleton, LimbSkeleton.Limb role,
               double halfHeight, Vec3 centre, Vec3 velocity, RandomSource random) {
        this.entity = entity;
        this.skeleton = skeleton;
        this.role = role;
        this.halfHeight = halfHeight;
        this.maxLife = Config.flyingLimbTicks();
        this.fadeTicks = Math.min(Config.fadeTicks(), maxLife);
        this.cx = this.pcx = centre.x;
        this.cy = this.pcy = centre.y;
        this.cz = this.pcz = centre.z;
        this.vx = velocity.x;
        this.vy = velocity.y;
        this.vz = velocity.z;

        // Random tumble axis and speed so each chunk spins its own way.
        Vec3 axis = new Vec3(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5);
        if (axis.lengthSqr() < 1.0e-4) {
            axis = new Vec3(1.0, 0.0, 0.0);
        }
        axis = axis.normalize();
        this.spinX = (float) axis.x;
        this.spinY = (float) axis.y;
        this.spinZ = (float) axis.z;
        this.spinSpeed = (float) (0.3 + random.nextDouble() * 0.4);
    }

    void tick(Level level) {
        this.pcx = cx;
        this.pcy = cy;
        this.pcz = cz;
        this.prevRot.set(rot);
        age++;

        if (resting) {
            return; // settled on the ground; holds its pose at zero cost
        }

        vy -= GRAVITY;
        vx *= DRAG;
        vy *= DRAG;
        vz *= DRAG;

        Vec3 wanted = new Vec3(vx, vy, vz);
        AABB box = new AABB(cx - HALF_BOX, cy - HALF_BOX, cz - HALF_BOX,
                cx + HALF_BOX, cy + HALF_BOX, cz + HALF_BOX);
        Vec3 moved = Entity.collideBoundingBox(null, wanted, box, level, List.of());

        boolean hitX = moved.x != wanted.x;
        boolean hitY = moved.y != wanted.y;
        boolean hitZ = moved.z != wanted.z;
        cx += moved.x;
        cy += moved.y;
        cz += moved.z;

        if (spinSpeed != 0.0f) {
            rot.premul(new Quaternionf().fromAxisAngleRad(spinX, spinY, spinZ, spinSpeed));
            spinSpeed *= SPIN_DRAG;
        }

        if (hitX) {
            vx = -vx * GROUND_BOUNCE;
        }
        if (hitZ) {
            vz = -vz * GROUND_BOUNCE;
        }
        if (hitY && wanted.y < 0.0) {
            vy = -vy * GROUND_BOUNCE;
            vx *= GROUND_FRICTION;
            vz *= GROUND_FRICTION;
            spinSpeed *= 0.5f;
            // Come to rest once it is barely moving on the ground.
            if (Math.abs(vy) < 0.04 && (vx * vx + vz * vz) < 0.0009 && Math.abs(spinSpeed) < 0.04f) {
                resting = true;
                spinSpeed = 0.0f;
                vx = vy = vz = 0.0;
            }
        } else if (hitY) {
            vy = 0.0;
        }
    }

    /** Done once it has lived out its lifetime; the corpse then drops it from its list. */
    boolean isFinished() {
        return age >= maxLife;
    }

    /** Smooth transparent tail-fade over the final {@link #fadeTicks} of the chunk's life. */
    private float fadeAlpha(float partialTick) {
        if (fadeTicks <= 0) {
            return 1.0f;
        }
        float remaining = maxLife - (age + partialTick);
        return remaining < fadeTicks ? Mth.clamp(remaining / fadeTicks, 0.0f, 1.0f) : 1.0f;
    }

    void render(Minecraft mc, PoseStack pose, MultiBufferSource buffers, Vec3 cam, float partialTick, float corpseAlpha) {
        float alpha = Math.min(corpseAlpha, fadeAlpha(partialTick));
        if (alpha <= 0.02f) {
            return;
        }
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        @SuppressWarnings("unchecked")
        EntityRenderer<Entity> renderer = (EntityRenderer<Entity>) dispatcher.getRenderer(entity);
        if (renderer == null) {
            return;
        }

        double rx = Mth.lerp(partialTick, pcx, cx);
        double ry = Mth.lerp(partialTick, pcy, cy);
        double rz = Mth.lerp(partialTick, pcz, cz);

        double maxDist = Config.maxRenderDistance();
        if (maxDist > 0.0) {
            double dx = rx - cam.x, dy = ry - cam.y, dz = rz - cam.z;
            if (dx * dx + dy * dy + dz * dz > maxDist * maxDist) {
                return;
            }
        }

        Quaternionf orientation = new Quaternionf(prevRot).slerp(rot, partialTick);
        int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(rx, ry, rz));
        MultiBufferSource source = alpha < 0.999f ? new FadeBufferSource(buffers, alpha) : buffers;

        // Freeze the renderer's own rotation/animation state so the model draws undeformed; our
        // quaternion then tumbles the whole (masked) model as one rigid chunk about its centre.
        float oldYBodyRot = entity.yBodyRot;
        float oldYBodyRotO = entity.yBodyRotO;
        float oldYRot = entity.getYRot();
        float oldYRotO = entity.yRotO;
        float oldXRot = entity.getXRot();
        float oldXRotO = entity.xRotO;
        float oldYHeadRot = entity.yHeadRot;
        float oldYHeadRotO = entity.yHeadRotO;
        int oldDeathTime = entity.deathTime;
        int oldHurtTime = entity.hurtTime;

        entity.yBodyRot = entity.yBodyRotO = 0.0f;
        entity.setYRot(0.0f);
        entity.yRotO = 0.0f;
        entity.setXRot(0.0f);
        entity.xRotO = 0.0f;
        entity.yHeadRot = entity.yHeadRotO = 0.0f;
        entity.deathTime = 0;
        entity.hurtTime = 0;

        dispatcher.setRenderShadow(false);
        pose.pushPose();
        try {
            pose.translate(rx - cam.x, ry - cam.y, rz - cam.z);
            pose.mulPose(orientation);
            pose.translate(0.0, -halfHeight, 0.0); // model centre -> chunk centre

            RagdollRenderContext.setSolo(skeleton, role);
            renderer.render(entity, 0.0f, partialTick, pose, source, light);
        } catch (Exception e) {
            if (!renderErrorLogged) {
                renderErrorLogged = true;
                Ragdolls.LOGGER.warn("Flying limb render failed for entity type {} (render disabled)",
                        entity.getType(), e);
            }
        } finally {
            pose.popPose();
            dispatcher.setRenderShadow(true);
            RagdollRenderContext.clear();
            if (skeleton != null) {
                skeleton.restore();
            }
            entity.yBodyRot = oldYBodyRot;
            entity.yBodyRotO = oldYBodyRotO;
            entity.setYRot(oldYRot);
            entity.yRotO = oldYRotO;
            entity.setXRot(oldXRot);
            entity.xRotO = oldXRotO;
            entity.yHeadRot = oldYHeadRot;
            entity.yHeadRotO = oldYHeadRotO;
            entity.deathTime = oldDeathTime;
            entity.hurtTime = oldHurtTime;
        }
    }
}
