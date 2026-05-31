package com.ragdolls.client;

import com.ragdolls.Config;
import com.ragdolls.Ragdolls;
import com.ragdolls.network.DeathPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private RagdollManager() {}

    public static void clear() {
        for (Ragdoll ragdoll : ACTIVE.values()) {
            ragdoll.dispose();
        }
        ACTIVE.clear();
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
