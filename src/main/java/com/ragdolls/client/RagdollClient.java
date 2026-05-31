package com.ragdolls.client;

import com.ragdolls.Ragdolls;
import com.ragdolls.network.DeathPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
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
        RagdollManager.updateGrab();
        RagdollManager.tick();
    }

    /** Punching a corpse knocks it around / tears it apart instead of swinging at the air. */
    @SubscribeEvent
    public static void onAttackInput(final InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack() && RagdollManager.handleAttack()) {
            event.setCanceled(true);
        }
    }

    /** Right-clicking a corpse grabs it; whip it around and release to throw it. */
    @SubscribeEvent
    public static void onUseInput(final InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isUseItem() && RagdollManager.tryGrab()) {
            event.setCanceled(true);
        }
    }

    /** When the local player respawns, dissolve their lingering corpse (survival "until respawn"). */
    @SubscribeEvent
    public static void onPlayerClone(final ClientPlayerNetworkEvent.Clone event) {
        RagdollManager.fadeCorpseOf(event.getOldPlayer());
    }

    @SubscribeEvent
    public static void onRenderLevelStage(final RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            RagdollManager.render(event);
        }
    }
}
