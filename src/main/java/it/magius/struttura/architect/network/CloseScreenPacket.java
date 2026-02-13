package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client packet that tells the client to close the current screen.
 * Sent when a GUI action results in an error so the player can see the error in chat.
 */
public record CloseScreenPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CloseScreenPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "close_screen"));

    public static final StreamCodec<FriendlyByteBuf, CloseScreenPacket> STREAM_CODEC =
        StreamCodec.of(CloseScreenPacket::write, CloseScreenPacket::read);

    private static CloseScreenPacket read(FriendlyByteBuf buf) {
        return new CloseScreenPacket();
    }

    private static void write(FriendlyByteBuf buf, CloseScreenPacket packet) {
        // No data to write
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
