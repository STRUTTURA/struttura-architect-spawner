package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Packet S2C for syncing all translations (titles and short descriptions) to client.
 * Sent when player enters editing mode or when translations change.
 */
public record TranslationsPacket(
    Map<String, String> titles,           // langId -> title
    Map<String, String> shortDescriptions // langId -> shortDesc
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TranslationsPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "translations"));

    public static final StreamCodec<FriendlyByteBuf, TranslationsPacket> STREAM_CODEC =
        StreamCodec.of(TranslationsPacket::write, TranslationsPacket::read);

    /**
     * Create an empty packet.
     */
    public static TranslationsPacket empty() {
        return new TranslationsPacket(new HashMap<>(), new HashMap<>());
    }

    private static TranslationsPacket read(FriendlyByteBuf buf) {
        int titlesCount = buf.readVarInt();
        Map<String, String> titles = new HashMap<>();
        for (int i = 0; i < titlesCount; i++) {
            String lang = buf.readUtf(16);
            String title = buf.readUtf(256);
            titles.put(lang, title);
        }

        int shortDescCount = buf.readVarInt();
        Map<String, String> shortDescriptions = new HashMap<>();
        for (int i = 0; i < shortDescCount; i++) {
            String lang = buf.readUtf(16);
            String shortDesc = buf.readUtf(1024);
            shortDescriptions.put(lang, shortDesc);
        }

        return new TranslationsPacket(titles, shortDescriptions);
    }

    private static void write(FriendlyByteBuf buf, TranslationsPacket packet) {
        buf.writeVarInt(packet.titles.size());
        for (Map.Entry<String, String> entry : packet.titles.entrySet()) {
            buf.writeUtf(entry.getKey(), 16);
            buf.writeUtf(entry.getValue(), 256);
        }

        buf.writeVarInt(packet.shortDescriptions.size());
        for (Map.Entry<String, String> entry : packet.shortDescriptions.entrySet()) {
            buf.writeUtf(entry.getKey(), 16);
            buf.writeUtf(entry.getValue(), 1024);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
