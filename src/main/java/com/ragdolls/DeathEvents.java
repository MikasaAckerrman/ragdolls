package com.ragdolls;

import com.ragdolls.network.DeathPayload;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side: works out where the killing blow came from, how strong it was and how the entity
 * died, then notifies the clients tracking the entity so they can spawn a matching ragdoll.
 */
@EventBusSubscriber(modid = Ragdolls.MODID)
public final class DeathEvents {

    private record Hit(float damage, long time) {}

    /** entity id -> last critical hit time. */
    private static final Map<Integer, Long> RECENT_CRITS = new ConcurrentHashMap<>();
    /** entity id -> last damage taken (amount + time). */
    private static final Map<Integer, Hit> RECENT_DAMAGE = new ConcurrentHashMap<>();
    private static final long WINDOW_TICKS = 20L;

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
            if (RECENT_CRITS.size() > 128) {
                RECENT_CRITS.values().removeIf(when -> now - when > WINDOW_TICKS);
            }
        }
    }

    @SubscribeEvent
    public static void onLivingDamage(final LivingDamageEvent.Post event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) {
            return;
        }
        long now = entity.level().getGameTime();
        RECENT_DAMAGE.put(entity.getId(), new Hit(event.getNewDamage(), now));
        if (RECENT_DAMAGE.size() > 256) {
            RECENT_DAMAGE.values().removeIf(h -> now - h.time() > WINDOW_TICKS);
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
        float damage = consumeRecentDamage(entity.getId(), now);

        DamageSource source = event.getSource();
        int cause = classify(source);
        boolean onFire = entity.isOnFire() || cause == DeathPayload.CAUSE_FIRE;

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

        DeathPayload payload = new DeathPayload(
                entity.getId(),
                (float) dir.x, (float) dir.z,
                damage, hitHeight, cause, critical, onFire);

        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, payload);
        Ragdolls.LOGGER.debug("Death ragdoll sent: id={} type={} dmg={} cause={} crit={} fire={} hitHeight={}",
                entity.getId(), entity.getType(), damage, cause, critical, onFire, hitHeight);
    }

    private static int classify(DamageSource source) {
        if (source == null) {
            return DeathPayload.CAUSE_GENERIC;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return DeathPayload.CAUSE_EXPLOSION;
        }
        if (source.is(DamageTypeTags.IS_PROJECTILE)) {
            return DeathPayload.CAUSE_PROJECTILE;
        }
        if (source.is(DamageTypeTags.IS_FIRE)) {
            return DeathPayload.CAUSE_FIRE;
        }
        if (source.is(DamageTypeTags.IS_FALL)) {
            return DeathPayload.CAUSE_FALL;
        }
        return DeathPayload.CAUSE_GENERIC;
    }

    private static boolean consumeRecentCrit(int entityId, long now) {
        Long when = RECENT_CRITS.remove(entityId);
        return when != null && (now - when) <= WINDOW_TICKS;
    }

    private static float consumeRecentDamage(int entityId, long now) {
        Hit hit = RECENT_DAMAGE.remove(entityId);
        if (hit != null && (now - hit.time()) <= WINDOW_TICKS && hit.damage() > 0.0f) {
            return hit.damage();
        }
        return 2.0f; // fallback when the killing damage is unknown
    }
}
