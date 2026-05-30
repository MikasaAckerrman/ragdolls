package com.ragdolls.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ragdolls.Config;
import com.ragdolls.Ragdolls;
import com.ragdolls.network.DeathPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.List;

/**
 * A single rigid-body corpse. The entire entity model is kept intact ("glued") and treated as one
 * solid object with a position, linear velocity, an orientation quaternion and a spin axis/speed.
 *
 * <p>Collision against the world (floors, walls, ceilings, ledges) is delegated to Minecraft's own
 * swept block-collision routine, so it is exactly as accurate and as cheap as vanilla entity
 * movement. Lava burns the corpse away; water makes it float and drift with the current.</p>
 *
 * <p>The simulation is intentionally a tiny custom rigid body rather than a physics-engine
 * dependency: one moving box per corpse, frozen the moment it comes to rest, which keeps the cost
 * negligible and avoids native libraries / mod conflicts.</p>
 */
public final class Ragdoll {

    private static final double GRAVITY = 0.045;
    private static final double LINEAR_DRAG = 0.985;
    private static final double GROUND_BOUNCE = 0.22;
    private static final double GROUND_FRICTION = 0.55;
    private static final double WALL_BOUNCE = 0.30;

    private static final double WATER_DRAG = 0.82;
    private static final double WATER_BUOYANCY = 0.028;
    private static final double WATER_CURRENT = 0.9;
    private static final double FLUID_MAX_SPEED = 0.25;

    private static final int BURN_TICKS = 30; // how fast lava consumes a corpse (~1.5s)

    private final LivingEntity entity;
    private final double bbWidth;
    private final double bbHeight;
    private final double halfHeight;

    private double x, y, z;       // current feet position (world)
    private double px, py, pz;    // previous feet position (for render interpolation)
    private double vx, vy, vz;    // linear velocity per tick

    private final Quaternionf rot = new Quaternionf();
    private final Quaternionf prevRot = new Quaternionf();

    private float spinX, spinY, spinZ; // normalized world-space spin axis
    private float spinSpeed;            // radians per tick (signed)

    private int age = 0;
    private int maxAgeTicks;
    private int fadeTicks;
    private boolean resting = false;
    private boolean burning = false;
    private boolean renderErrorLogged = false;

    public Ragdoll(LivingEntity entity, DeathPayload payload) {
        this.entity = entity;
        this.bbWidth = Math.max(0.2, entity.getBbWidth());
        this.bbHeight = Math.max(0.2, entity.getBbHeight());
        this.halfHeight = this.bbHeight * 0.5;

        this.maxAgeTicks = Config.lifetimeTicks();
        this.fadeTicks = Math.min(Config.fadeTicks(), maxAgeTicks);

        this.x = this.px = entity.getX();
        this.y = this.py = entity.getY();
        this.z = this.pz = entity.getZ();

        Vec3 dir = new Vec3(payload.dirX(), 0.0, payload.dirZ());
        if (dir.lengthSqr() < 1.0e-4) {
            dir = new Vec3(0.0, 0.0, 1.0);
        }
        dir = dir.normalize();

        double power = 0.25 + payload.strength() * 0.45;
        this.vx = dir.x * power;
        this.vz = dir.z * power;
        this.vy = 0.18 + payload.strength() * 0.25 + (payload.critical() ? 0.15 : 0.0);

        // Spin axis is horizontal and perpendicular to the push -> the body tumbles head over heels
        // in the direction it is thrown.
        Vec3 axis = new Vec3(0.0, 1.0, 0.0).cross(dir);
        if (axis.lengthSqr() < 1.0e-4) {
            axis = new Vec3(1.0, 0.0, 0.0);
        }
        axis = axis.normalize();
        this.spinX = (float) axis.x;
        this.spinY = (float) axis.y;
        this.spinZ = (float) axis.z;

        // A hit above the centre of mass flips the body forward, a low hit flips it backward.
        double lever = (payload.hitHeight() - 0.5) * 2.0; // -1 .. 1
        double sign = lever >= 0.0 ? 1.0 : -1.0;
        this.spinSpeed = (float) (sign * (0.12 + payload.strength() * 0.25
                + Math.abs(lever) * 0.10 + (payload.critical() ? 0.10 : 0.0)));
    }

    public void tick(Level level) {
        this.px = x;
        this.py = y;
        this.pz = z;
        this.prevRot.set(rot);
        age++;

        // Once a corpse is asleep on solid ground we stop simulating it entirely (no block lookups,
        // no allocations) until it expires. This is the main performance guard.
        if (resting) {
            return;
        }

        BlockPos comPos = BlockPos.containing(x, y + halfHeight, z);
        FluidState fluid = level.getFluidState(comPos);
        boolean inLava = fluid.is(FluidTags.LAVA);
        boolean inWater = fluid.is(FluidTags.WATER);

        if (inLava && Config.burnInLava()) {
            startBurning();
        }
        if (burning) {
            spawnBurnParticles(level);
        }

        boolean inFluid = (inWater && Config.floatInWater()) || (inLava && Config.burnInLava());

        if (inFluid) {
            vy += WATER_BUOYANCY;
            if (inWater && Config.floatInWater()) {
                Vec3 flow = fluid.getFlow(level, comPos);
                vx += flow.x * WATER_CURRENT;
                vz += flow.z * WATER_CURRENT;
            }
            vx *= WATER_DRAG;
            vy *= WATER_DRAG;
            vz *= WATER_DRAG;
            vx = Mth.clamp(vx, -FLUID_MAX_SPEED, FLUID_MAX_SPEED);
            vy = Mth.clamp(vy, -FLUID_MAX_SPEED, FLUID_MAX_SPEED);
            vz = Mth.clamp(vz, -FLUID_MAX_SPEED, FLUID_MAX_SPEED);
        } else {
            vy -= GRAVITY;
            vx *= LINEAR_DRAG;
            vy *= LINEAR_DRAG;
            vz *= LINEAR_DRAG;
        }

        // Swept collision against the world. Box is the entity's footprint centred on the corpse,
        // so it rests on floors and ledges and is stopped by walls just like a live entity.
        Vec3 wanted = new Vec3(vx, vy, vz);
        AABB box = AABB.ofSize(new Vec3(x, y + halfHeight, z), bbWidth, bbHeight, bbWidth);
        Vec3 moved = Entity.collideBoundingBox(null, wanted, box, level, List.of());

        boolean hitX = moved.x != wanted.x;
        boolean hitY = moved.y != wanted.y;
        boolean hitZ = moved.z != wanted.z;
        boolean landed = hitY && wanted.y < 0.0;

        x += moved.x;
        y += moved.y;
        z += moved.z;

        if (spinSpeed != 0.0f) {
            rot.premul(new Quaternionf().fromAxisAngleRad(spinX, spinY, spinZ, spinSpeed));
        }

        if (hitX) {
            vx = -vx * WALL_BOUNCE;
        }
        if (hitZ) {
            vz = -vz * WALL_BOUNCE;
        }
        if (hitY) {
            if (wanted.y < 0.0) {
                vy = -vy * GROUND_BOUNCE;
                vx *= GROUND_FRICTION;
                vz *= GROUND_FRICTION;
                spinSpeed *= 0.4f;
            } else {
                vy = 0.0; // bumped a ceiling
            }
        }

        // Come to rest only on solid ground (never while floating), once nearly motionless.
        double horizontal = Math.sqrt(vx * vx + vz * vz);
        if (!inFluid && landed && Math.abs(vy) < 0.06 && horizontal < 0.02 && Math.abs(spinSpeed) < 0.02) {
            resting = true;
            spinSpeed = 0.0f;
            vx = vy = vz = 0.0;
        }
    }

    private void startBurning() {
        if (!burning) {
            burning = true;
            maxAgeTicks = Math.min(maxAgeTicks, age + BURN_TICKS);
            fadeTicks = Math.min(Math.max(fadeTicks, BURN_TICKS / 2), maxAgeTicks);
        }
    }

    private void spawnBurnParticles(Level level) {
        var random = level.getRandom();
        for (int i = 0; i < 2; i++) {
            double ox = (random.nextDouble() - 0.5) * bbWidth;
            double oy = random.nextDouble() * bbHeight;
            double oz = (random.nextDouble() - 0.5) * bbWidth;
            level.addParticle(ParticleTypes.FLAME, x + ox, y + oy, z + oz, 0.0, 0.02, 0.0);
            level.addParticle(ParticleTypes.LARGE_SMOKE, x + ox, y + oy + 0.2, z + oz, 0.0, 0.03, 0.0);
        }
    }

    public void render(Minecraft mc, PoseStack pose, MultiBufferSource buffers, Vec3 cam, float partialTick) {
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        @SuppressWarnings("unchecked")
        EntityRenderer<Entity> renderer = (EntityRenderer<Entity>) dispatcher.getRenderer(entity);
        if (renderer == null) {
            return;
        }

        float scale = fadeScale(partialTick);
        if (scale <= 0.0f) {
            return;
        }

        double rx = Mth.lerp(partialTick, px, x);
        double ry = Mth.lerp(partialTick, py, y);
        double rz = Mth.lerp(partialTick, pz, z);

        // Distance culling (cheap, keeps far-away corpses from costing draw calls).
        double maxDist = Config.maxRenderDistance();
        if (maxDist > 0.0) {
            double dx = rx - cam.x, dy = (ry + halfHeight) - cam.y, dz = rz - cam.z;
            if (dx * dx + dy * dy + dz * dz > maxDist * maxDist) {
                return;
            }
        }

        Quaternionf orientation = new Quaternionf(prevRot).slerp(rot, partialTick);
        int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(rx, ry + halfHeight, rz));

        // Freeze every state the renderer would use to rotate/animate the model so it draws upright
        // and undeformed; our quaternion then orients the whole body as one rigid piece.
        float oldYBodyRot = entity.yBodyRot;
        float oldYBodyRotO = entity.yBodyRotO;
        float oldYRot = entity.getYRot();
        float oldYRotO = entity.yRotO;
        float oldXRot = entity.getXRot();
        float oldXRotO = entity.xRotO;
        float oldYHeadRot = entity.yHeadRot;
        float oldYHeadRotO = entity.yHeadRotO;
        int oldDeathTime = entity.deathTime;

        entity.yBodyRot = entity.yBodyRotO = 0.0f;
        entity.setYRot(0.0f);
        entity.yRotO = 0.0f;
        entity.setXRot(0.0f);
        entity.xRotO = 0.0f;
        entity.yHeadRot = entity.yHeadRotO = 0.0f;
        entity.deathTime = 0;

        dispatcher.setRenderShadow(false);
        pose.pushPose();
        try {
            pose.translate(rx - cam.x, ry - cam.y, rz - cam.z);
            // Rotate (and fade-scale) about the body's centre of mass for a natural tumble.
            pose.translate(0.0, halfHeight, 0.0);
            pose.mulPose(orientation);
            if (scale != 1.0f) {
                pose.scale(scale, scale, scale);
            }
            pose.translate(0.0, -halfHeight, 0.0);

            renderer.render(entity, 0.0f, partialTick, pose, buffers, light);
        } catch (Exception e) {
            // A foreign renderer may dislike being driven for a removed entity; never crash the game.
            // Log once per corpse (WARN) so issues are diagnosable without spamming every frame.
            if (!renderErrorLogged) {
                renderErrorLogged = true;
                Ragdolls.LOGGER.warn("Ragdoll render failed for entity type {} (will keep simulating, render disabled)",
                        entity.getType(), e);
            }
        } finally {
            pose.popPose();
            dispatcher.setRenderShadow(true);

            entity.yBodyRot = oldYBodyRot;
            entity.yBodyRotO = oldYBodyRotO;
            entity.setYRot(oldYRot);
            entity.yRotO = oldYRotO;
            entity.setXRot(oldXRot);
            entity.xRotO = oldXRotO;
            entity.yHeadRot = oldYHeadRot;
            entity.yHeadRotO = oldYHeadRotO;
            entity.deathTime = oldDeathTime;
        }
    }

    /** 1.0 for most of the life, easing down to 0.0 over the last {@link #fadeTicks} ticks. */
    private float fadeScale(float partialTick) {
        if (fadeTicks <= 0) {
            return age >= maxAgeTicks ? 0.0f : 1.0f;
        }
        float remaining = maxAgeTicks - (age + partialTick);
        if (remaining >= fadeTicks) {
            return 1.0f;
        }
        return Mth.clamp(remaining / fadeTicks, 0.0f, 1.0f);
    }

    public boolean isFinished() {
        return age >= maxAgeTicks || entity.level() != Minecraft.getInstance().level;
    }
}
