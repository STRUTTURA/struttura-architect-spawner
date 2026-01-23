package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packet C2S for sending a like request from client.
 * Sent when player clicks the like button while in a building.
 */
public record InGameLikePacket(
    String rdns,    // Building RDNS identifier
    long pk         // Building primary key
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<InGameLikePacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "ingame_like"));

    public static final StreamCodec<FriendlyByteBuf, InGameLikePacket> STREAM_CODEC =
        StreamCodec.of(InGameLikePacket::write, InGameLikePacket::read);

    private static InGameLikePacket read(FriendlyByteBuf buf) {
        String rdns = buf.readUtf(512);
        long pk = buf.readLong();
        return new InGameLikePacket(rdns, pk);
    }

    private static void write(FriendlyByteBuf buf, InGameLikePacket packet) {
        buf.writeUtf(packet.rdns, 512);
        buf.writeLong(packet.pk);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
