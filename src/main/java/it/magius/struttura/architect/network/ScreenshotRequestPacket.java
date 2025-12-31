package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packet S2C per richiedere uno screenshot al client.
 * Inviato dal server quando il giocatore usa /struttura shot.
 */
public record ScreenshotRequestPacket(
    String constructionId,
    String title
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ScreenshotRequestPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "screenshot_request"));

    public static final StreamCodec<FriendlyByteBuf, ScreenshotRequestPacket> STREAM_CODEC =
        StreamCodec.of(ScreenshotRequestPacket::write, ScreenshotRequestPacket::read);

    private static ScreenshotRequestPacket read(FriendlyByteBuf buf) {
        String constructionId = buf.readUtf();
        String title = buf.readUtf();
        return new ScreenshotRequestPacket(constructionId, title);
    }

    private static void write(FriendlyByteBuf buf, ScreenshotRequestPacket packet) {
        buf.writeUtf(packet.constructionId);
        buf.writeUtf(packet.title);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
