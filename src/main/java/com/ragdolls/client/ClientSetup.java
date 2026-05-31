package com.ragdolls.client;

import com.ragdolls.Ragdolls;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Client-only mod-bus setup. Kept in a separate class so it (and its client-only imports) is never
 * loaded on a dedicated server.
 */
public final class ClientSetup {

    private ClientSetup() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(ClientSetup::onRegisterRenderers);
    }

    private static void onRegisterRenderers(final EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Ragdolls.RAGDOLL_BODY.get(), RagdollBodyRenderer::new);
    }
}
