package com.ragdolls;

import com.ragdolls.network.DeathPayload;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side: works out where the killing blow came from and how strong it was, then notifies
 * the clients tracking the entity so they can spawn a matching ragdoll.
 */
@EventBusSubscriber(modid = Ragdolls.MODID)
public final class DeathEvents {

    /** target entity id -> game time of the last critical hit against it. */
    private static final Map<Integer, Long> RECENT_CRITS = new ConcurrentHashMap<>();
    private static final long CRIT_WINDOW_TICKS = 20L;

    private DeathEvents() {}

    @SubscribeEvent
    public static void onCriticalHit(final CriticalHitEvent event) {
        if (!event.isCriticalHit()) {
            return;
        }
        Entity target = event.getTarget();
        if (target != null && !target.level().isClientSide) {
            long now = target.level().getGameTime();
            RECENT_CRITS.put(target.getId(), now);
            // Bound the map: drop stale entries for entities that were crit but never died.
            if (RECENT_CRITS.size() > 128) {
                RECENT_CRITS.values().removeIf(when -> now - when > CRIT_WINDOW_TICKS);
            }
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(final LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Level level = entity.level();
        if (level.isClientSide) {
            return;
        }

        long now = level.getGameTime();
        boolean critical = consumeRecentCrit(entity.getId(), now);

        DamageSource source = event.getSource();

        // Determine the origin of the blow: prefer the precise source position, then the responsible
        // entity, then fall back to a random horizontal direction so the corpse always reacts.
        Vec3 origin = source != null ? source.getSourcePosition() : null;
        if (origin == null && source != null) {
            Entity causing = source.getEntity();
            if (causing != null) {
                origin = causing.position().add(0.0, causing.getBbHeight() * 0.5, 0.0);
            }
        }

        Vec3 dir;
        float hitHeight = 0.5f;
        if (origin != null) {
            double bb = Math.max(0.1, entity.getBbHeight());
            hitHeight = (float) Mth.clamp((origin.y - entity.getY()) / bb, 0.0, 1.0);
            // Push the corpse away from the attacker, horizontally.
            dir = new Vec3(entity.getX() - origin.x, 0.0, entity.getZ() - origin.z);
        } else {
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
            dir = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
        }

        if (dir.lengthSqr() < 1.0e-4) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
            dir = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
        }
        dir = dir.normalize();

        float strength = 0.5f + level.getRandom().nextFloat() * 0.2f + (critical ? 0.5f : 0.0f);

        DeathPayload payload = new DeathPayload(
                entity.getId(),
                (float) dir.x, (float) dir.y, (float) dir.z,
                strength, hitHeight, critical);

        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, payload);
        Ragdolls.LOGGER.debug("Death ragdoll sent: id={} type={} strength={} crit={} hitHeight={}",
                entity.getId(), entity.getType(), strength, critical, hitHeight);
    }

    private static boolean consumeRecentCrit(int entityId, long now) {
        Long when = RECENT_CRITS.remove(entityId);
        return when != null && (now - when) <= CRIT_WINDOW_TICKS;
    }
}
