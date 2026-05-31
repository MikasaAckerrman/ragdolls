package com.ragdolls.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ragdolls.Config;
import com.ragdolls.Ragdolls;
import com.ragdolls.entity.RagdollBodyEntity;
import com.ragdolls.network.DeathPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.model.EntityModel;
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
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * A single rigid-body corpse with optional floppy limbs.
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li><b>Physics</b> - thrown by the killing blow, tumbles, collides with the world (floors,
 *       walls, ledges), burns in lava, floats in water.</li>
 *   <li><b>Freeze</b> - once it has lain motionless on solid ground for {@code settleSeconds} it
 *       drops its physics (to cost nothing) but keeps the exact pose it ended up in.</li>
 *   <li><b>Fade</b> - it dissolves smoothly (vertex alpha) and is then removed from memory. This is
 *       triggered by its lifetime expiring, the support beneath a frozen corpse disappearing, or
 *       being evicted when the corpse cap is exceeded.</li>
 * </ol>
 *
 * <p>Entities whose model exposes no vanilla parts (e.g. GeckoLib mobs) cannot be articulated, so
 * instead of a rigid ragdoll they simply die with a graceful fade-out in place.</p>
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

    private static final int BURN_TICKS = 30;          // how fast lava consumes a corpse (~1.5s)
    private static final int RESTDROP_RAMP_TICKS = 4;  // smooth settle so a lying body is not popped down

    private final LivingEntity entity;
    private final double bbWidth;
    private final double bbHeight;
    private final double halfHeight;
    private final LimbSkeleton skeleton;
    private final boolean fadeOnly;     // non-articulable model -> graceful dissolve, no tumble
    private RagdollBodyEntity body;     // optional real collision body (mod-physics compatibility)
    private boolean collideErrorLogged = false;

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
    private boolean frozen = false;
    private boolean burning = false;
    private boolean consumed = false;
    private boolean renderErrorLogged = false;

    // Forced fade-out (lifetime / support loss / eviction / graceful death).
    private int fadeStartAge = -1;
    private int fadeDurTicks = 1;

    // When the corpse settles, its model is dropped so the body lies on the ground instead of
    // hovering at centre-of-mass height. Ramped in over a few ticks to avoid a visible pop.
    private double restDropTarget = 0.0;
    private int restStartAge = -1;

    public Ragdoll(LivingEntity entity, DeathPayload payload, EntityModel<?> model) {
        this.entity = entity;
        this.bbWidth = Math.max(0.2, entity.getBbWidth());
        this.bbHeight = Math.max(0.2, entity.getBbHeight());
        this.halfHeight = this.bbHeight * 0.5;
        this.skeleton = LimbSkeleton.capture(model); // null if the model has no usable parts
        this.fadeOnly = (this.skeleton == null);

        // Player corpses persist (forever unless burned); when the player respawns the client world
        // is rebuilt and the corpse is dropped automatically. Mob corpses use the configured life.
        this.maxAgeTicks = (entity instanceof Player) ? Integer.MAX_VALUE / 2 : Config.lifetimeTicks();
        this.fadeTicks = Math.min(Config.fadeTicks(), maxAgeTicks);
        this.burning = payload.onFire();

        this.x = this.px = entity.getX();
        this.y = this.py = entity.getY();
        this.z = this.pz = entity.getZ();

        if (fadeOnly) {
            // Mobs we cannot articulate just die with a clean dissolve where they fell.
            startFade(Math.max(fadeTicks, 16));
            return;
        }

        // Optional real collision body so physics mods carry the corpse on their contraptions.
        if (Config.useEntityCollision() && entity.level() instanceof ClientLevel clientLevel) {
            try {
                RagdollBodyEntity b = new RagdollBodyEntity(Ragdolls.RAGDOLL_BODY.get(), clientLevel);
                b.setBodySize((float) bbWidth, (float) bbHeight);
                b.setId(RagdollBodyEntity.nextClientId());
                b.setPos(x, y, z);
                clientLevel.addEntity(b);
                this.body = b;
            } catch (Throwable t) {
                this.body = null;
                Ragdolls.LOGGER.warn("Failed to create collision body; using built-in collision", t);
            }
        }

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

        // Limbs keep simulating until the body freezes (then they hold their final pose) or it
        // starts dissolving.
        if (skeleton != null && Config.enableLimbs() && !frozen && !isFadingOut()) {
            float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
            skeleton.tick(speed, Math.abs(spinSpeed), (float) Config.limbFloppiness());
        }

        // While dissolving, white "crumbling" motes drift up off the body.
        if (isFadingOut() || (fadeTicks > 0 && maxAgeTicks - age <= fadeTicks)) {
            spawnFadeParticles(level);
        }

        if (isFadingOut()) {
            return; // dissolving in place; removal handled by isFinished()
        }

        if (frozen) {
            // Wake up (regain physics) if pushed by the player, or if the ground beneath disappears
            // so the corpse falls again. The lifetime/disappear timer keeps running regardless.
            boolean wake = tryPush(level);
            if (!wake && age % 5 == 0 && !isSupported(level)) {
                wake = true;
            }
            if (wake) {
                unfreeze();
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

        // Move + collide against the world (and, with useEntityCollision, mod physics contraptions).
        Vec3 wanted = new Vec3(vx, vy, vz);
        Vec3 moved = collideMove(level, wanted);

        boolean hitX = moved.x != wanted.x;
        boolean hitY = moved.y != wanted.y;
        boolean hitZ = moved.z != wanted.z;

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

        // Freeze the instant the body is motionless on solid ground AND its limbs have stopped: it
        // then holds its exact pose at zero cost until something wakes it (push / lost support).
        boolean onGround = hitY && wanted.y < 0.0;
        double horizontal = Math.sqrt(vx * vx + vz * vz);
        boolean still = !inFluid && onGround
                && Math.abs(vy) < 0.06 && horizontal < 0.02 && Math.abs(spinSpeed) < 0.02;
        if (still) {
            // Stop residual rotation/creep so a still body is truly motionless.
            spinSpeed = 0.0f;
            vx = 0.0;
            vz = 0.0;
            if (restStartAge < 0) {
                restStartAge = age;
                restDropTarget = computeRestDrop(rot);
            }
            if (skeleton == null || !Config.enableLimbs() || skeleton.isSettled()) {
                freeze();
            }
        } else {
            restStartAge = -1;
            restDropTarget = 0.0;
        }
    }

    /** Drop physics but keep the current pose. */
    private void freeze() {
        frozen = true;
        spinSpeed = 0.0f;
        vx = vy = vz = 0.0;
        if (restStartAge < 0) {
            restStartAge = age;
            restDropTarget = computeRestDrop(rot);
        }
    }

    /** Resume physics (the corpse was pushed or lost its support). */
    private void unfreeze() {
        frozen = false;
        restStartAge = -1;
        restDropTarget = 0.0;
    }

    /**
     * If the local player is walking into a frozen corpse, give it a shove so it wakes and slides.
     * Purely cosmetic and client-side - enough to "kick" a body around.
     */
    private boolean tryPush(Level level) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        Vec3 pv = player.getDeltaMovement();
        if (pv.x * pv.x + pv.z * pv.z < 0.0016) { // player barely moving (~0.04/tick)
            return false;
        }
        AABB corpseBox = AABB.ofSize(new Vec3(x, y + halfHeight, z), bbWidth + 0.3, bbHeight, bbWidth + 0.3);
        if (!player.getBoundingBox().intersects(corpseBox)) {
            return false;
        }
        vx = pv.x * 0.8;
        vz = pv.z * 0.8;
        vy = 0.06;
        return true;
    }

    /** Begin a smooth dissolve; the corpse is removed once it completes. */
    public void startFade(int durationTicks) {
        if (fadeStartAge < 0) {
            fadeStartAge = age;
            fadeDurTicks = Math.max(1, durationTicks);
        }
    }

    public boolean isFadingOut() {
        return fadeStartAge >= 0;
    }

    /** True if this corpse belongs to the given entity (used to fade the player's corpse on respawn). */
    public boolean isFor(Entity owner) {
        return entity == owner;
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

    /**
     * Resolve one tick of movement. With a collision body the corpse follows the body's position
     * (so mod contraptions can carry it); otherwise it uses Minecraft's swept block collision.
     */
    private Vec3 collideMove(Level level, Vec3 wanted) {
        RagdollBodyEntity b = this.body;
        if (b != null) {
            try {
                b.setDeltaMovement(wanted.x, wanted.y, wanted.z);
                b.move(MoverType.SELF, b.getDeltaMovement());
                return new Vec3(b.getX() - x, b.getY() - y, b.getZ() - z);
            } catch (Throwable t) {
                if (!collideErrorLogged) {
                    collideErrorLogged = true;
                    Ragdolls.LOGGER.warn("Entity collision failed; falling back to built-in collision", t);
                }
                disposeBody();
            }
        }
        AABB box = AABB.ofSize(new Vec3(x, y + halfHeight, z), bbWidth, bbHeight, bbWidth);
        return Entity.collideBoundingBox(null, wanted, box, level, List.of());
    }

    /** Remove the collision body from the world; called when the corpse is discarded. */
    public void dispose() {
        disposeBody();
    }

    private void disposeBody() {
        RagdollBodyEntity b = this.body;
        if (b != null) {
            try {
                if (b.level() instanceof ClientLevel clientLevel) {
                    clientLevel.removeEntity(b.getId(), Entity.RemovalReason.DISCARDED);
                }
            } catch (Throwable ignored) {
                // best effort
            }
            this.body = null;
        }
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

    /** White, bright motes drifting upward - the corpse "crumbling" away as it dissolves. */
    private void spawnFadeParticles(Level level) {
        var random = level.getRandom();
        for (int i = 0; i < 2; i++) {
            double ox = (random.nextDouble() - 0.5) * bbWidth;
            double oy = random.nextDouble() * bbHeight;
            double oz = (random.nextDouble() - 0.5) * bbWidth;
            double upward = 0.04 + random.nextDouble() * 0.06;
            level.addParticle(ParticleTypes.END_ROD,
                    x + ox, y + oy, z + oz,
                    (random.nextDouble() - 0.5) * 0.02, upward, (random.nextDouble() - 0.5) * 0.02);
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
        if (restStartAge >= 0) {
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

            RagdollRenderContext.set(Config.enableLimbs() ? skeleton : null);
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
        }
    }

    /** Combined fade factor: the lifetime tail-fade and any forced dissolve, whichever is lower. */
    private float fadeAlpha(float partialTick) {
        float a = 1.0f;
        if (fadeTicks > 0) {
            float remaining = maxAgeTicks - (age + partialTick);
            if (remaining < fadeTicks) {
                a = Math.min(a, Mth.clamp(remaining / fadeTicks, 0.0f, 1.0f));
            }
        }
        if (fadeStartAge >= 0) {
            float elapsed = (age + partialTick) - fadeStartAge;
            a = Math.min(a, Mth.clamp(1.0f - elapsed / fadeDurTicks, 0.0f, 1.0f));
        }
        return a;
    }

    public boolean isFinished() {
        if (entity.level() != Minecraft.getInstance().level) {
            return true;
        }
        if (age >= maxAgeTicks) {
            return true;
        }
        return fadeStartAge >= 0 && (age - fadeStartAge) >= fadeDurTicks;
    }
}
