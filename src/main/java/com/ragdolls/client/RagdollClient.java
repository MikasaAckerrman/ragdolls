package com.ragdolls.client;

import com.ragdolls.Ragdolls;
import com.ragdolls.network.DeathPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-only event glue. Registered on the game event bus on the client distribution only.
 */
@EventBusSubscriber(modid = Ragdolls.MODID, value = Dist.CLIENT)
public final class RagdollClient {

    private RagdollClient() {}

    /** Called from the network handler when a death payload arrives. */
    public static void handleDeathPayload(DeathPayload payload, IPayloadContext context) {
        Ragdolls.LOGGER.debug("Received DeathPayload for entity id={}", payload.entityId());
        context.enqueueWork(() -> RagdollManager.spawn(payload));
    }

    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event) {
        RagdollManager.tick();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(final RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            RagdollManager.render(event);
        }
    }
}
