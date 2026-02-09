package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client packet that tells the client to show the first push disclaimer screen.
 * Sent when a building is pushed for the first time (version == 1).
 */
public record FirstPushDisclaimerPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FirstPushDisclaimerPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "first_push_disclaimer"));

    public static final StreamCodec<FriendlyByteBuf, FirstPushDisclaimerPacket> STREAM_CODEC =
        StreamCodec.of(FirstPushDisclaimerPacket::write, FirstPushDisclaimerPacket::read);

    private static FirstPushDisclaimerPacket read(FriendlyByteBuf buf) {
        return new FirstPushDisclaimerPacket();
    }

    private static void write(FriendlyByteBuf buf, FirstPushDisclaimerPacket packet) {
        // No data to write
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
