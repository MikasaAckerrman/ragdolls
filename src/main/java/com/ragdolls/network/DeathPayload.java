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
 * @param dirY      reserved vertical component (currently 0)
 * @param dirZ      z of the push direction
 * @param strength  0..~1 magnitude of the blow (higher for critical hits)
 * @param hitHeight 0..1 normalized height of the impact along the body (0 = feet, 1 = head)
 * @param critical  whether the killing blow was a critical hit
 */
public record DeathPayload(int entityId, float dirX, float dirY, float dirZ,
                           float strength, float hitHeight, boolean critical)
        implements CustomPacketPayload {

    public static final Type<DeathPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Ragdolls.MODID, "death"));

    public static final StreamCodec<FriendlyByteBuf, DeathPayload> STREAM_CODEC =
            StreamCodec.ofMember(DeathPayload::write, DeathPayload::decode);

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeFloat(dirX);
        buf.writeFloat(dirY);
        buf.writeFloat(dirZ);
        buf.writeFloat(strength);
        buf.writeFloat(hitHeight);
        buf.writeBoolean(critical);
    }

    public static DeathPayload decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        float dx = buf.readFloat();
        float dy = buf.readFloat();
        float dz = buf.readFloat();
        float strength = buf.readFloat();
        float hitHeight = buf.readFloat();
        boolean critical = buf.readBoolean();
        return new DeathPayload(id, dx, dy, dz, strength, hitHeight, critical);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
