package com.ragdolls.client;

import com.ragdolls.Config;
import com.ragdolls.Ragdolls;
import com.ragdolls.network.DeathPayload;
import net.minecraft.client.Minecraft;
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

    private RagdollManager() {}

    public static void clear() {
        ACTIVE.clear();
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
        // The local player's death has its own camera/respawn handling, leave it untouched.
        if (entity == mc.player) {
            return;
        }
        // Only entities drawn by a LivingEntityRenderer are supported. Anything else (e.g. the Ender
        // Dragon, which uses a bespoke multi-part renderer) is skipped.
        EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof LivingEntityRenderer<?, ?>)) {
            Ragdolls.LOGGER.debug("Skip ragdoll id={}: renderer {} is not a LivingEntityRenderer (e.g. boss)",
                    payload.entityId(), renderer == null ? "null" : renderer.getClass().getSimpleName());
            return;
        }
        if (ACTIVE.containsKey(entity.getId())) {
            return;
        }

        // Performance cap: evict the oldest corpse(s) before adding a new one.
        int max = Config.maxRagdolls();
        Iterator<Integer> it = ACTIVE.keySet().iterator();
        while (ACTIVE.size() >= max && it.hasNext()) {
            it.next();
            it.remove();
            Ragdolls.LOGGER.debug("Evicted oldest ragdoll to honour cap ({})", max);
        }

        ACTIVE.put(entity.getId(), new Ragdoll(living, payload));

        // Take the entity out of the world: stops the vanilla model, fire and shadow render at the
        // death position. The ragdoll holds its own reference for rendering.
        mc.level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);

        Ragdolls.LOGGER.debug("Spawned ragdoll id={} type={} (active={})",
                entity.getId(), entity.getType(), ACTIVE.size());
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            ACTIVE.clear();
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
