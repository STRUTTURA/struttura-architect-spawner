package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packet C2S per inviare i dati dello screenshot dal client al server.
 * I dati dell'immagine sono JPEG codificati.
 */
public record ScreenshotDataPacket(
    String constructionId,
    String title,
    byte[] imageData
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ScreenshotDataPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "screenshot_data"));

    public static final StreamCodec<FriendlyByteBuf, ScreenshotDataPacket> STREAM_CODEC =
        StreamCodec.of(ScreenshotDataPacket::write, ScreenshotDataPacket::read);

    private static ScreenshotDataPacket read(FriendlyByteBuf buf) {
        String constructionId = buf.readUtf();
        String title = buf.readUtf();
        byte[] imageData = buf.readByteArray();
        return new ScreenshotDataPacket(constructionId, title, imageData);
    }

    private static void write(FriendlyByteBuf buf, ScreenshotDataPacket packet) {
        buf.writeUtf(packet.constructionId);
        buf.writeUtf(packet.title);
        buf.writeByteArray(packet.imageData);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
