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
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
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
 *   <li><b>Freeze</b> - the instant it is motionless on the ground and its limbs have stopped, it
 *       drops its physics (zero cost) but keeps its exact pose; it wakes again if pushed or if the
 *       block beneath it is removed.</li>
 *   <li><b>Fade</b> - it dissolves (vertex alpha + white motes) and is removed. Triggered by its
 *       lifetime expiring, eviction over the cap, or - for players - respawn. Player corpses persist
 *       until burned or the player respawns.</li>
 * </ol>
 *
 * <p>Entities whose model exposes no vanilla parts (e.g. GeckoLib mobs) cannot be articulated, so
 * instead of a rigid ragdoll they simply die with a graceful fade-out in place.</p>
 */
public final class Ragdoll {

    private static final double GRAVITY = 0.045;
    private static final double LINEAR_DRAG = 0.985;
    private static final double GROUND_BOUNCE = 0.22;
    private static final double GROUND_FRICTION = 0.88; // keep inertia: corpse slides, never snap-stops
    private static final double SPIN_DRAG = 0.98;       // gentle airborne spin bleed (keeps tumble)
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
    // hovering at centre-of-mass height. The drop tracks the live orientation (so it stays seated
    // while toppling) and is ramped in over a few ticks from the moment it first touches down.
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
        this.vy = Math.max(0.12, vyPop) + (payload.critical() ? 0.03 : 0.0);

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
        // Guarantee the body topples over and lies down instead of freezing bolt-upright like a live
        // mob (this is what made low-knockback corpses, e.g. a pig, "stand" with a vanilla head pose).
        if (Math.abs(this.spinSpeed) < 0.22f) {
            this.spinSpeed = (float) (sign * 0.22);
        }
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
            skeleton.tick(rot, speed, Math.abs(spinSpeed), (float) Config.limbFloppiness());
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
        boolean onGround = hitY && wanted.y < 0.0;
        if (onGround) {
            vy = -vy * GROUND_BOUNCE;
            vx *= GROUND_FRICTION; // gentle: the body keeps its inertia and slides, never snap-stops
            vz *= GROUND_FRICTION;
        } else if (hitY) {
            vy = 0.0; // bumped a ceiling
        }

        double horizontal = Math.sqrt(vx * vx + vz * vz);

        // Is the body actually resting on something? (cheap probe, only while it is moving slowly).
        boolean grounded = onGround;
        if (!grounded && !inFluid && Math.abs(vy) < 0.10 && horizontal < 0.12) {
            grounded = isSupported(level);
        }

        // While grounded and slow, topple naturally toward a flat lying pose (no freezing bolt
        // upright, no balancing on an edge). Otherwise the spin just bleeds off slowly so the body
        // keeps the angular momentum from the blow while it is airborne or sliding.
        boolean flat;
        if (grounded && !inFluid && horizontal < 0.10) {
            if (restStartAge < 0) {
                restStartAge = age; // start seating the model onto the ground
            }
            flat = settleToFlat();
        } else {
            spinSpeed *= SPIN_DRAG;
            flat = false;
            if (!grounded && (Math.abs(vy) > 0.12 || horizontal > 0.15)) {
                restStartAge = -1; // genuinely airborne again -> un-seat
            }
        }

        // Freeze only once the body lies flat and still AND its limbs have stopped flopping; until
        // then keep simulating so nothing stops abruptly or hangs in the air.
        boolean bodyAtRest = grounded && !inFluid && flat
                && Math.abs(vy) < 0.08 && horizontal < 0.03;
        if (bodyAtRest && (skeleton == null || !Config.enableLimbs() || skeleton.isSettled())) {
            freeze();
        }
    }

    /**
     * Gently rotate a grounded body toward a flat lying orientation (its up-axis horizontal), then
     * bleed off the last of the spin. Returns true once it is flat and no longer turning, so the
     * corpse may freeze. Replaces an abrupt stop and stops bodies resting balanced on an edge.
     */
    private boolean settleToFlat() {
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f).rotate(rot);
        float upY = up.y;
        if (Math.abs(upY) < 0.08f) { // already lying flat
            spinSpeed *= 0.5f;
            if (Math.abs(spinSpeed) < 0.01f) {
                spinSpeed = 0.0f;
            }
            return spinSpeed == 0.0f;
        }
        // d(upY)/dangle for turning about the (fixed, horizontal) tumble axis.
        Vector3f axis = new Vector3f(spinX, spinY, spinZ);
        float dUpY = axis.cross(up, new Vector3f()).y;
        if (Math.abs(dUpY) < 1.0e-3f) {
            // Turning about this axis no longer changes the tilt (body already on its side): rest.
            spinSpeed *= 0.5f;
            return Math.abs(spinSpeed) < 0.01f;
        }
        float sign = (upY * dUpY > 0.0f) ? -1.0f : 1.0f; // drive |upY| down toward zero
        spinSpeed = sign * Mth.clamp(Math.abs(upY) * 0.20f, 0.02f, 0.16f);
        return false;
    }

    /** Drop physics but keep the current pose. */
    private void freeze() {
        frozen = true;
        spinSpeed = 0.0f;
        vx = vy = vz = 0.0;
        if (skeleton != null) {
            skeleton.freezePose(); // hold the exact limb pose (no sub-degree jitter while frozen)
        }
        if (restStartAge < 0) {
            restStartAge = age;
        }
    }

    /** Resume physics (the corpse was pushed or lost its support). */
    private void unfreeze() {
        frozen = false;
        restStartAge = -1;
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

    /** World box around the corpse (for hit raycasting). */
    public AABB currentBox() {
        return AABB.ofSize(new Vec3(x, y + halfHeight, z), bbWidth, bbHeight, bbWidth);
    }

    /**
     * The player struck this corpse. Knocks it around (force scales with the weapon's damage and,
     * inversely, the mob's toughness), and - with gore enabled - a hard blow tears off a limb, while
     * a strong hit to the chest gibs the whole body in a burst of blood.
     */
    public void onHit(Vec3 hitPoint, Vec3 lookDir, double weaponDamage) {
        if (isFadingOut()) {
            return;
        }
        Level level = entity.level();
        RandomSource random = level.getRandom();

        Vec3 dir = new Vec3(lookDir.x, 0.0, lookDir.z);
        dir = dir.lengthSqr() > 1.0e-4 ? dir.normalize() : new Vec3(0.0, 0.0, 1.0);

        double mobHp = Math.max(1.0, entity.getMaxHealth());
        double relative = weaponDamage / mobHp; // 1.0 ~ a one-shot-kill-strength blow
        double localY = Mth.clamp((hitPoint.y - y) / bbHeight, 0.0, 1.0);
        double dh = Math.hypot(hitPoint.x - x, hitPoint.z - z);
        boolean chestCentre = dh < bbWidth * 0.4 && localY > 0.35 && localY < 0.78;

        // Wake and shove it (heavier mob => moves less).
        unfreeze();
        double push = Mth.clamp(0.14 * weaponDamage / Math.sqrt(mobHp), 0.05, 0.8);
        vx += dir.x * push;
        vz += dir.z * push;
        vy = Math.max(vy, 0.12);

        Vec3 axis = new Vec3(0.0, 1.0, 0.0).cross(dir);
        axis = axis.lengthSqr() > 1.0e-4 ? axis.normalize() : new Vec3(1.0, 0.0, 0.0);
        spinX = (float) axis.x;
        spinY = (float) axis.y;
        spinZ = (float) axis.z;
        spinSpeed = (float) ((localY >= 0.5 ? 1.0 : -1.0) * Mth.clamp(push * 0.8 + 0.1, 0.1, 0.5));

        if (!Config.enableGore()) {
            return; // gore disabled: knock it around only, no blood / tearing
        }

        if (chestCentre && relative >= 1.0 && skeleton != null) {
            gib(level, hitPoint);
            return;
        }
        if (relative >= 0.5 && skeleton != null) {
            int tears = relative >= 0.9 ? 2 : 1;
            boolean tore = false;
            for (int i = 0; i < tears; i++) {
                tore |= (skeleton.tearRandom(random) != null);
            }
            spawnBlood(level, tore ? 10 : 5, hitPoint, dir, tore);
        } else {
            spawnBlood(level, 5, hitPoint, dir, false);
        }
    }

    /** Chest gib: a big one-shot blood fountain, then the body dissolves away. */
    private void gib(Level level, Vec3 at) {
        spawnBlood(level, 40, at, null, true);
        startFade(10);
    }

    /**
     * Red, gravity-affected "blood" using redstone block-break particles (small, splattering bits
     * that arc and fall). One-shot bursts only - never per tick - so it stays cheap.
     */
    private void spawnBlood(Level level, int count, Vec3 at, Vec3 dir, boolean fountain) {
        RandomSource random = level.getRandom();
        BlockParticleOption blood = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());
        for (int i = 0; i < count; i++) {
            double mvx, mvy, mvz;
            if (fountain) {
                mvx = (random.nextDouble() - 0.5) * 0.25;
                mvz = (random.nextDouble() - 0.5) * 0.25;
                mvy = 0.20 + random.nextDouble() * 0.35;
            } else {
                Vec3 d = dir != null ? dir : new Vec3(random.nextDouble() - 0.5, 0.0, random.nextDouble() - 0.5);
                mvx = d.x * 0.15 + (random.nextDouble() - 0.5) * 0.10;
                mvy = 0.05 + random.nextDouble() * 0.15;
                mvz = d.z * 0.15 + (random.nextDouble() - 0.5) * 0.10;
            }
            level.addParticle(blood, at.x, at.y, at.z, mvx, mvy, mvz);
        }
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

    /** Player corpses persist until respawn, so they are exempt from cap eviction. */
    public boolean isPlayerCorpse() {
        return entity instanceof Player;
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
        double ox = (random.nextDouble() - 0.5) * bbWidth;
        double oy = random.nextDouble() * bbHeight;
        double oz = (random.nextDouble() - 0.5) * bbWidth;
        double upward = 0.10 + random.nextDouble() * 0.12; // small, rising well above the body
        level.addParticle(ParticleTypes.END_ROD,
                x + ox, y + oy, z + oz,
                (random.nextDouble() - 0.5) * 0.01, upward, (random.nextDouble() - 0.5) * 0.01);
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

        // Lower the body so it rests on the ground rather than hovering at its hitbox centre. The
        // drop is computed from the live orientation so the body stays seated on its edge while it
        // topples, and is ramped in from the moment it first touched down to avoid a pop.
        double drop = 0.0;
        if (restStartAge >= 0) {
            float t = Mth.clamp((age + partialTick - restStartAge) / (float) RESTDROP_RAMP_TICKS, 0.0f, 1.0f);
            drop = computeRestDrop(orientation) * t;
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
            if (alpha < 0.999f) {
                // Shrink toward the centre of mass as it fades: a smooth disappearance that works
                // even for cutout-rendered mobs, where vertex alpha alone would not blend.
                pose.scale(alpha, alpha, alpha);
            }
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
