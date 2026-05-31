package com.ragdolls.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.ragdolls.Config;
import com.ragdolls.Ragdolls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * An item that has visually dropped from a corpse's hand (or from a torn-off arm). It falls, rests
 * on the ground spinning like a vanilla dropped item, and fades out after its lifetime. Purely
 * cosmetic and client-side - it is never a pickable item entity.
 *
 * <p>Owned by its {@link Ragdoll}: it ticks and renders with the corpse.</p>
 */
final class DroppedItem {

    private static final double GRAVITY = 0.045;
    private static final double DRAG = 0.98;
    private static final double GROUND_BOUNCE = 0.25;
    private static final double GROUND_FRICTION = 0.6;
    private static final double HALF_BOX = 0.12;

    private final ItemStack stack;
    private double cx, cy, cz;
    private double pcx, pcy, pcz;
    private double vx, vy, vz;
    private boolean resting = false;
    private boolean renderErrorLogged = false;

    private int age = 0;
    private final int maxLife;   // ticks before it fades away (~12s)
    private final int fadeTicks;

    DroppedItem(ItemStack stack, Vec3 centre, Vec3 velocity) {
        this.stack = stack;
        this.cx = this.pcx = centre.x;
        this.cy = this.pcy = centre.y;
        this.cz = this.pcz = centre.z;
        this.vx = velocity.x;
        this.vy = velocity.y;
        this.vz = velocity.z;
        this.maxLife = Config.droppedItemTicks();
        this.fadeTicks = Math.min(Config.fadeTicks(), maxLife);
    }

    /** Build a small outward+upward toss so the item visibly pops out of the hand. */
    static Vec3 toss(Vec3 dir, RandomSource random) {
        double speed = 0.08 + random.nextDouble() * 0.06;
        return new Vec3(
                dir.x * speed + (random.nextDouble() - 0.5) * 0.06,
                0.16 + random.nextDouble() * 0.08,
                dir.z * speed + (random.nextDouble() - 0.5) * 0.06);
    }

    void tick(Level level) {
        this.pcx = cx;
        this.pcy = cy;
        this.pcz = cz;
        age++;

        if (resting) {
            return;
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
            if (Math.abs(vy) < 0.04 && (vx * vx + vz * vz) < 0.0004) {
                resting = true;
                vx = vy = vz = 0.0;
            }
        } else if (hitY) {
            vy = 0.0;
        }
    }

    boolean isFinished() {
        return age >= maxLife;
    }

    private float fadeAlpha(float partialTick) {
        if (fadeTicks <= 0) {
            return 1.0f;
        }
        float remaining = maxLife - (age + partialTick);
        return remaining < fadeTicks ? Mth.clamp(remaining / fadeTicks, 0.0f, 1.0f) : 1.0f;
    }

    void render(Minecraft mc, PoseStack pose, MultiBufferSource buffers, Vec3 cam, float partialTick, float corpseAlpha) {
        if (stack.isEmpty()) {
            return;
        }
        float alpha = Math.min(corpseAlpha, fadeAlpha(partialTick));
        if (alpha <= 0.02f) {
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

        int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(rx, ry, rz));
        MultiBufferSource source = alpha < 0.999f ? new FadeBufferSource(buffers, alpha) : buffers;

        float spin = (age + partialTick) * 4.0f;             // slow spin like a vanilla dropped item
        float bob = resting ? 0.0f : Mth.sin((age + partialTick) * 0.1f) * 0.04f;

        pose.pushPose();
        try {
            pose.translate(rx - cam.x, (ry - HALF_BOX) - cam.y + bob, rz - cam.z);
            pose.mulPose(Axis.YP.rotationDegrees(spin));
            if (alpha < 0.999f) {
                pose.scale(alpha, alpha, alpha);
            }
            mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.GROUND, light,
                    OverlayTexture.NO_OVERLAY, pose, source, mc.level, 0);
        } catch (Exception e) {
            if (!renderErrorLogged) {
                renderErrorLogged = true;
                Ragdolls.LOGGER.warn("Dropped-item render failed for {} (render disabled)", stack, e);
            }
        } finally {
            pose.popPose();
        }
    }
}
