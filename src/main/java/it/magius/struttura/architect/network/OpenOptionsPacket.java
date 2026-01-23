package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client packet that tells the client to open the settings screen.
 * Sent when the player executes /struttura options.
 */
public record OpenOptionsPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenOptionsPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "open_options"));

    public static final StreamCodec<FriendlyByteBuf, OpenOptionsPacket> STREAM_CODEC =
        StreamCodec.of(OpenOptionsPacket::write, OpenOptionsPacket::read);

    private static OpenOptionsPacket read(FriendlyByteBuf buf) {
        return new OpenOptionsPacket();
    }

    private static void write(FriendlyByteBuf buf, OpenOptionsPacket packet) {
        // No data to write
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
