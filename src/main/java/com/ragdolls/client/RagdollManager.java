package com.ragdolls.client;

import com.ragdolls.Config;
import com.ragdolls.Ragdolls;
import com.ragdolls.network.DeathPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Owns every active client-side ragdoll, drives their physics each client tick and renders them
 * once per frame after the regular entities have been drawn.
 *
 * <p>When a ragdoll is created the original entity is removed from the client world. That single
 * step makes vanilla stop drawing its model, its fire and its shadow at the death spot (no
 * leftovers), while the ragdoll keeps the entity instance alive purely as render data.</p>
 */
public final class RagdollManager {

    // Insertion-ordered so the eldest corpse can be evicted first when the cap is hit.
    private static final Map<Integer, Ragdoll> ACTIVE = new LinkedHashMap<>();

    private static final int EVICT_FADE_TICKS = 8; // fast, smooth dissolve when over the cap

    private static Ragdoll grabbed;   // corpse currently held by the player (RMB)
    private static double grabDist;   // distance in front of the eyes the held corpse floats at

    private RagdollManager() {}

    public static void clear() {
        for (Ragdoll ragdoll : ACTIVE.values()) {
            ragdoll.dispose();
        }
        ACTIVE.clear();
        grabbed = null;
    }

    /** Start the dissolve animation for the corpse belonging to {@code owner} (e.g. on respawn). */
    public static void fadeCorpseOf(Entity owner) {
        if (owner == null) {
            return;
        }
        for (Ragdoll ragdoll : ACTIVE.values()) {
            if (ragdoll.isFor(owner)) {
                ragdoll.startFade(Config.fadeTicks());
            }
        }
    }

    /** Create a ragdoll for the entity referenced by the payload, if it still exists locally. */
    public static void spawn(DeathPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity entity = mc.level.getEntity(payload.entityId());
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        // Only entities drawn by a LivingEntityRenderer are supported. Anything else (e.g. the Ender
        // Dragon, which uses a bespoke multi-part renderer) is skipped.
        EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof LivingEntityRenderer<?, ?> livingRenderer)) {
            Ragdolls.LOGGER.debug("Skip ragdoll id={}: renderer {} is not a LivingEntityRenderer (e.g. boss)",
                    payload.entityId(), renderer == null ? "null" : renderer.getClass().getSimpleName());
            return;
        }
        if (ACTIVE.containsKey(entity.getId())) {
            return;
        }

        // Performance cap: when too many live corpses exist, dissolve the oldest one(s) so the count
        // stays bounded. They are removed from memory once their fade-out completes.
        int max = Config.maxRagdolls();
        int liveCount = 0;
        for (Ragdoll r : ACTIVE.values()) {
            if (!r.isFadingOut()) {
                liveCount++;
            }
        }
        for (Ragdoll r : ACTIVE.values()) {
            if (liveCount < max) {
                break;
            }
            // Keep player corpses (they persist until respawn); only evict the oldest mob corpses.
            if (!r.isFadingOut() && !r.isPlayerCorpse()) {
                r.startFade(EVICT_FADE_TICKS);
                liveCount--;
                Ragdolls.LOGGER.debug("Evicting oldest ragdoll (fade-out) to honour cap ({})", max);
            }
        }

        EntityModel<?> model = livingRenderer.getModel();
        ACTIVE.put(entity.getId(), new Ragdoll(living, payload, model));

        // Take the entity out of the world so vanilla stops drawing its model, fire and shadow at
        // the death spot. The local player is left in place (removing it would break the respawn /
        // death-screen handling); its corpse simply renders alongside.
        if (entity != mc.player) {
            mc.level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }

        Ragdolls.LOGGER.debug("Spawned ragdoll id={} type={} (active={})",
                entity.getId(), entity.getType(), ACTIVE.size());
    }

    /**
     * The player swung at something: if a corpse is the nearest thing under the crosshair (and not
     * behind a block), strike it. Returns true if a corpse was hit (so the vanilla swing is eaten).
     */
    public static boolean handleAttack() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || ACTIVE.isEmpty()) {
            return false;
        }
        Player player = mc.player;
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getViewVector(1.0f);
        double maxDist = 4.5;

        // Only hit a corpse if nothing else (block OR live entity) is closer under the crosshair, so
        // we never steal an attack aimed at a real mob/block in front of the body.
        HitResult hr = mc.hitResult;
        if (hr != null && hr.getType() != HitResult.Type.MISS) {
            maxDist = Math.min(maxDist, eye.distanceTo(hr.getLocation()));
        }
        Vec3 clampedEnd = eye.add(look.scale(maxDist));

        Ragdoll best = null;
        Vec3 bestHit = null;
        double bestDist = Double.MAX_VALUE;
        for (Ragdoll ragdoll : ACTIVE.values()) {
            if (ragdoll.isFadingOut()) {
                continue;
            }
            Optional<Vec3> hit = ragdoll.currentBox().inflate(0.1).clip(eye, clampedEnd);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < bestDist) {
                    bestDist = d;
                    best = ragdoll;
                    bestHit = hit.get();
                }
            }
        }
        if (best == null) {
            return false;
        }
        best.onHit(bestHit, look, player.getAttributeValue(Attributes.ATTACK_DAMAGE));
        return true;
    }

    /**
     * RMB on a corpse grabs it (so it can be carried and thrown). Returns true if one was grabbed
     * (so the vanilla use action is eaten). Uses the same "nothing closer under the crosshair" rule
     * as attacking, so it never hijacks a right-click aimed at a block/entity in front of the body.
     */
    public static boolean tryGrab() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || grabbed != null || ACTIVE.isEmpty()) {
            return false;
        }
        Player player = mc.player;
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getViewVector(1.0f);
        double maxDist = 4.5;
        HitResult hr = mc.hitResult;
        if (hr != null && hr.getType() != HitResult.Type.MISS) {
            maxDist = Math.min(maxDist, eye.distanceTo(hr.getLocation()));
        }
        Vec3 end = eye.add(look.scale(maxDist));

        Ragdoll best = null;
        Vec3 bestHit = null;
        double bestDist = Double.MAX_VALUE;
        for (Ragdoll ragdoll : ACTIVE.values()) {
            if (ragdoll.isFadingOut()) {
                continue;
            }
            Optional<Vec3> hit = ragdoll.currentBox().inflate(0.1).clip(eye, end);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < bestDist) {
                    bestDist = d;
                    best = ragdoll;
                    bestHit = hit.get();
                }
            }
        }
        if (best == null) {
            return false;
        }
        grabbed = best;
        grabDist = Mth.clamp(eye.distanceTo(bestHit), 1.5, 4.0);
        best.setGrabbed(true);
        return true;
    }

    /**
     * Drive the held corpse each client tick: it floats at a fixed distance in front of the eyes,
     * and is thrown when the use key is released (velocity = how fast it was being whipped around).
     */
    public static void updateGrab() {
        if (grabbed == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || grabbed.isFinished() || grabbed.isFadingOut() || !ACTIVE.containsValue(grabbed)) {
            grabbed.setGrabbed(false);
            grabbed = null;
            return;
        }
        if (!mc.options.keyUse.isDown()) {
            grabbed.release(1.2);
            grabbed = null;
            return;
        }
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getViewVector(1.0f);
        Vec3 anchor = eye.add(look.scale(grabDist));
        grabbed.setGrabAnchor(anchor.x, anchor.y, anchor.z);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        Iterator<Map.Entry<Integer, Ragdoll>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Ragdoll ragdoll = it.next().getValue();
            ragdoll.tick(mc.level);
            if (ragdoll.isFinished()) {
                ragdoll.dispose();
                it.remove();
            }
        }
    }

    public static void render(RenderLevelStageEvent event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        for (Ragdoll ragdoll : ACTIVE.values()) {
            ragdoll.render(mc, event.getPoseStack(), buffers, cam, partialTick);
        }
        buffers.endBatch();
    }
}
