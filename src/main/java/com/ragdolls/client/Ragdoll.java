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
import org.joml.Vector3f;

import java.util.List;

/**
 * A single rigid-body corpse. The whole entity model is kept intact ("glued") and treated as one
 * solid object with a position, linear velocity, an orientation quaternion and a spin axis/speed.
 *
 * <p>Collision uses Minecraft's own swept block collision, so the corpse is stopped by floors,
 * walls, ceilings and rests on ledges exactly like a live entity. Lava burns it away; water makes
 * it float and drift with the current. When it settles on solid ground it stops simulating until
 * either it expires or the block beneath it disappears, in which case it wakes and keeps falling.</p>
 *
 * <p>The launch impulse is derived from the actual killing damage (so a hit does not fling a body
 * across the map), shaped by where it was hit and what killed it. It is a deliberately tiny custom
 * rigid body, not a physics engine, to stay dependency-free and mod-compatible.</p>
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
    private static final int RESTDROP_RAMP_TICKS = 4; // smooth settle so a lying body is not popped down

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
    private boolean consumed = false;
    private boolean renderErrorLogged = false;

    // When the corpse settles, its model is dropped so the body actually lies on the ground instead
    // of hovering at centre-of-mass height. Ramped in over a few ticks to avoid a visible pop.
    private double restDropTarget = 0.0;
    private int restStartAge = -1;

    public Ragdoll(LivingEntity entity, DeathPayload payload) {
        this.entity = entity;
        this.bbWidth = Math.max(0.2, entity.getBbWidth());
        this.bbHeight = Math.max(0.2, entity.getBbHeight());
        this.halfHeight = this.bbHeight * 0.5;

        this.maxAgeTicks = Config.lifetimeTicks();
        this.fadeTicks = Math.min(Config.fadeTicks(), maxAgeTicks);
        this.burning = payload.onFire();

        this.x = this.px = entity.getX();
        this.y = this.py = entity.getY();
        this.z = this.pz = entity.getZ();

        Vec3 dir = new Vec3(payload.dirX(), 0.0, payload.dirZ());
        if (dir.lengthSqr() < 1.0e-4) {
            dir = new Vec3(0.0, 0.0, 1.0);
        }
        dir = dir.normalize();

        float damage = payload.damage();
        int cause = payload.cause();
        double kb = Config.knockbackMultiplier();

        // Realistic horizontal travel distance (blocks): a strong netherite-crit (~15 dmg) lands
        // about a metre away; bigger hits throw further, capped so nothing flies across the map.
        double dist = Mth.clamp(0.5 + Math.max(0.0, damage - 5.0) * 0.06, 0.35, 6.0);
        double vyPop = 0.10 + Math.min(damage * 0.004, 0.10);
        double spinScale = 1.0;
        switch (cause) {
            case DeathPayload.CAUSE_EXPLOSION -> {
                dist *= 1.25;
                vyPop = 0.24 + Math.min(damage * 0.005, 0.16);
                spinScale = 1.6;
            }
            case DeathPayload.CAUSE_PROJECTILE -> vyPop = 0.07; // flatter push along the shot
            case DeathPayload.CAUSE_FALL -> {
                dist *= 0.35;
                vyPop = 0.04;
                spinScale = 1.3;
            }
            default -> { }
        }
        dist *= kb;

        double horizVel = Mth.clamp(dist * 0.085, 0.0, 0.7);
        this.vx = dir.x * horizVel;
        this.vz = dir.z * horizVel;
        this.vy = vyPop + (payload.critical() ? 0.03 : 0.0);

        // Spin axis is horizontal and perpendicular to the push -> the body tumbles in the direction
        // it is thrown. A hit high on the body (head) topples it forward, a low hit (legs) backward.
        Vec3 axis = new Vec3(0.0, 1.0, 0.0).cross(dir);
        if (axis.lengthSqr() < 1.0e-4) {
            axis = new Vec3(1.0, 0.0, 0.0);
        }
        axis = axis.normalize();
        this.spinX = (float) axis.x;
        this.spinY = (float) axis.y;
        this.spinZ = (float) axis.z;

        double lever = (payload.hitHeight() - 0.5) * 2.0; // -1 (feet) .. +1 (head)
        double sign = lever >= 0.0 ? 1.0 : -1.0;
        this.spinSpeed = (float) Mth.clamp(
                sign * (0.10 + Math.min(damage * 0.008, 0.18) + Math.abs(lever) * 0.08) * spinScale,
                -0.6, 0.6);
    }

    public void tick(Level level) {
        this.px = x;
        this.py = y;
        this.pz = z;
        this.prevRot.set(rot);
        age++;

        // Settled on solid ground: behave like a static dead entity (no simulation cost). Every so
        // often check the block underneath; if its support vanished, wake up and keep falling.
        if (resting) {
            if (age % 10 == 0 && !isSupported(level)) {
                resting = false;
                restStartAge = -1;
                restDropTarget = 0.0;
            } else {
                return;
            }
        }

        BlockPos comPos = BlockPos.containing(x, y + halfHeight, z);
        FluidState fluid = level.getFluidState(comPos);
        boolean inLava = fluid.is(FluidTags.LAVA);
        boolean inWater = fluid.is(FluidTags.WATER);

        if (inLava && Config.burnInLava()) {
            igniteConsume();
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
            restDropTarget = computeRestDrop(rot);
            restStartAge = age;
        }
    }

    /**
     * How far to lower the model so its lowest point touches the ground for the current orientation.
     * For an upright body this is 0; for one lying flat it is roughly (halfHeight - bodyWidth/2).
     */
    private double computeRestDrop(Quaternionf q) {
        double ax = Math.abs(new Vector3f(1.0f, 0.0f, 0.0f).rotate(q).y());
        double ay = Math.abs(new Vector3f(0.0f, 1.0f, 0.0f).rotate(q).y());
        double az = Math.abs(new Vector3f(0.0f, 0.0f, 1.0f).rotate(q).y());
        double verticalHalfExtent = ax * (bbWidth * 0.5) + ay * halfHeight + az * (bbWidth * 0.5);
        return Math.max(0.0, halfHeight - verticalHalfExtent);
    }

    /** True while there is a collidable block directly beneath the corpse's footprint. */
    private boolean isSupported(Level level) {
        AABB probe = new AABB(
                x - bbWidth * 0.5, y - 0.08, z - bbWidth * 0.5,
                x + bbWidth * 0.5, y + 0.02, z + bbWidth * 0.5);
        return level.getBlockCollisions(null, probe).iterator().hasNext();
    }

    private void igniteConsume() {
        burning = true;
        if (!consumed) {
            consumed = true;
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

        float alpha = fadeAlpha(partialTick);
        if (alpha <= 0.02f) {
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

        // Lower a settled body so it rests on the ground rather than hovering at its hitbox centre.
        double drop = 0.0;
        if (resting && restStartAge >= 0) {
            float t = Mth.clamp((age + partialTick - restStartAge) / (float) RESTDROP_RAMP_TICKS, 0.0f, 1.0f);
            drop = restDropTarget * t;
        }

        // While fading, route rendering through a buffer source that scales vertex alpha so the
        // corpse turns transparent before it is removed.
        MultiBufferSource source = alpha < 0.999f ? new FadeBufferSource(buffers, alpha) : buffers;

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
            pose.translate(rx - cam.x, (ry - drop) - cam.y, rz - cam.z);
            // Rotate about the body's centre of mass for a natural tumble.
            pose.translate(0.0, halfHeight, 0.0);
            pose.mulPose(orientation);
            pose.translate(0.0, -halfHeight, 0.0);

            renderer.render(entity, 0.0f, partialTick, pose, source, light);
        } catch (Exception e) {
            // A foreign renderer may dislike being driven for a removed entity; never crash the game.
            // Log once per corpse (WARN) so issues are diagnosable without spamming every frame.
            if (!renderErrorLogged) {
                renderErrorLogged = true;
                Ragdolls.LOGGER.warn("Ragdoll render failed for entity type {} (render disabled, still simulating)",
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
    private float fadeAlpha(float partialTick) {
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
