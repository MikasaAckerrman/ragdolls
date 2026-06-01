package com.ragdolls.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Invisible, silent, inert helper entity used only as a collision body for a corpse. Added to the
 * client world (by {@code Ragdoll}) so physics mods that move entities (Create Aeronautics / Sable,
 * Valkyrien Skies) carry it on their contraptions; the visible corpse follows its position.
 *
 * <p>Deliberately free of any client-only types so the class loads safely on a dedicated server,
 * where the entity type is registered but never spawned.</p>
 */
public final class RagdollBodyEntity extends Entity {

    // Client-only ids live in a negative range the server never assigns, to avoid clashes.
    private static final AtomicInteger CLIENT_ID = new AtomicInteger(-1_000_000);

    private float bodyWidth = 0.6f;
    private float bodyHeight = 1.8f;

    public RagdollBodyEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.setSilent(true);
        this.setNoGravity(true);
        this.noCulling = true;
    }

    /** A fresh negative id that the server will never assign to a real entity. */
    public static int nextClientId() {
        return CLIENT_ID.getAndDecrement();
    }

    public void setBodySize(float width, float height) {
        this.bodyWidth = Math.max(0.1f, width);
        this.bodyHeight = Math.max(0.1f, height);
        this.refreshDimensions();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        // Clamp: during the super constructor our fields are still 0, so guard against a zero box.
        return EntityDimensions.fixed(Math.max(0.1f, bodyWidth), Math.max(0.1f, bodyHeight));
    }

    // --- inert: never tick, never affect the world, never be affected by it ---

    @Override
    public void tick() {
        // Driven manually by the owning Ragdoll; do nothing on the normal entity tick.
    }

    @Override
    public void checkInsideBlocks() {
        // Skip portal/cobweb/cactus/etc. side effects while we move it for collision.
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    @Override
    public boolean isSilent() {
        return true;
    }

    // --- required Entity boilerplate (no synched data / no saving) ---

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // Core flags (silent, no-gravity, ...) are already defined by the Entity constructor.
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
