package com.ragdolls.network;

import com.ragdolls.Ragdolls;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent from the server to tracking clients when a living entity dies.
 *
 * @param entityId  network id of the entity that died
 * @param dirX      x of the (horizontal) push direction, normalized server-side
 * @param dirZ      z of the push direction
 * @param damage    actual final damage of the killing blow (drives launch distance realistically)
 * @param hitHeight 0..1 normalized height of the impact along the body (0 = feet/legs, 1 = head)
 * @param cause     how the entity died (see CAUSE_* constants) -> shapes the launch impulse
 * @param critical  whether the killing blow was a critical hit
 * @param onFire    whether the entity was burning at death (corpse carries flames)
 */
public record DeathPayload(int entityId, float dirX, float dirZ,
                           float damage, float hitHeight, int cause,
                           boolean critical, boolean onFire)
        implements CustomPacketPayload {

    public static final int CAUSE_GENERIC = 0;
    public static final int CAUSE_PROJECTILE = 1;
    public static final int CAUSE_EXPLOSION = 2;
    public static final int CAUSE_FIRE = 3;
    public static final int CAUSE_FALL = 4;

    public static final Type<DeathPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Ragdolls.MODID, "death"));

    public static final StreamCodec<FriendlyByteBuf, DeathPayload> STREAM_CODEC =
            StreamCodec.ofMember(DeathPayload::write, DeathPayload::decode);

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeFloat(dirX);
        buf.writeFloat(dirZ);
        buf.writeFloat(damage);
        buf.writeFloat(hitHeight);
        buf.writeVarInt(cause);
        buf.writeBoolean(critical);
        buf.writeBoolean(onFire);
    }

    public static DeathPayload decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        float dx = buf.readFloat();
        float dz = buf.readFloat();
        float damage = buf.readFloat();
        float hitHeight = buf.readFloat();
        int cause = buf.readVarInt();
        boolean critical = buf.readBoolean();
        boolean onFire = buf.readBoolean();
        return new DeathPayload(id, dx, dz, damage, hitHeight, cause, critical, onFire);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
