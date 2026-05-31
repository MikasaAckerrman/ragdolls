package com.ragdolls;

import com.mojang.logging.LogUtils;
import com.ragdolls.entity.RagdollBodyEntity;
import com.ragdolls.network.DeathPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * Entry point for the Ragdolls mod.
 *
 * <p>The mod turns dying living entities into a single, rigid "glued" ragdoll on the client.
 * The server only computes the direction/power of the killing blow and forwards it to clients;
 * all physics and rendering happens client-side so the simulation is purely cosmetic and works
 * for every entity (vanilla or modded) that has a registered renderer.</p>
 */
@Mod(Ragdolls.MODID)
public final class Ragdolls {
    public static final String MODID = "ragdolls";

    /** Shared mod logger. Reused across all classes to avoid duplicate logger instances. */
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    /** Invisible collision-body entity (only used when {@code useEntityCollision} is enabled). */
    public static final Supplier<EntityType<RagdollBodyEntity>> RAGDOLL_BODY = ENTITIES.register(
            "ragdoll_body",
            () -> EntityType.Builder.<RagdollBodyEntity>of(RagdollBodyEntity::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .build("ragdoll_body"));

    public Ragdolls(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        ENTITIES.register(modBus);
        modBus.addListener(this::registerPayloads);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.ragdolls.client.ClientSetup.init(modBus);
        }
        LOGGER.info("Ragdolls loaded (dist={}): client-side rigid-body corpses ready", FMLEnvironment.dist);
    }

    private void registerPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(
                DeathPayload.TYPE,
                DeathPayload.STREAM_CODEC,
                // The lambda body only references the client handler when actually executed.
                // On a dedicated server dist != CLIENT, so the client class is never loaded.
                (payload, context) -> {
                    if (FMLEnvironment.dist == Dist.CLIENT) {
                        com.ragdolls.client.RagdollClient.handleDeathPayload(payload, context);
                    }
                });
        LOGGER.debug("Registered DeathPayload network channel");
    }
}
