package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.i18n.LanguageUtils;
import it.magius.struttura.architect.ingame.model.InGameListInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Packet S2C for sending available InGame lists to the client.
 * Sent when a new world is loaded and InGame mode hasn't been initialized yet.
 */
public record InGameListsPacket(
    List<ListInfo> lists,
    boolean isNewWorld,      // True if this is a new world that needs setup
    boolean connectionError  // True if the API server was unreachable
) implements CustomPacketPayload {

    /**
     * Info about an InGame list for selection.
     * The id can be numeric (e.g., "123") or alphanumeric (e.g., "most-popular" for virtual lists).
     */
    public record ListInfo(
        String id,
        Map<String, String> names,        // Localized names
        Map<String, String> descriptions, // Localized descriptions
        int buildingCount,
        String icon         // Minecraft item ID for display (e.g., "minecraft:bell")
    ) {
        /**
         * Gets the localized name for the specified language.
         */
        public String getLocalizedName(String langCode) {
            return LanguageUtils.getLocalizedText(names, langCode, "Unnamed List " + id);
        }

        /**
         * Gets the localized description for the specified language.
         */
        public String getLocalizedDescription(String langCode) {
            return LanguageUtils.getLocalizedText(descriptions, langCode, "");
        }
    }

    public static final CustomPacketPayload.Type<InGameListsPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "ingame_lists"));

    public static final StreamCodec<FriendlyByteBuf, InGameListsPacket> STREAM_CODEC =
        StreamCodec.of(InGameListsPacket::write, InGameListsPacket::read);

    /**
     * Create an empty packet (no lists available).
     */
    public static InGameListsPacket empty(boolean isNewWorld) {
        return new InGameListsPacket(List.of(), isNewWorld, false);
    }

    /**
     * Create a packet indicating connection error.
     */
    public static InGameListsPacket connectionError(boolean isNewWorld) {
        return new InGameListsPacket(List.of(), isNewWorld, true);
    }

    /**
     * Create a packet from InGameListInfo models.
     */
    public static InGameListsPacket fromListInfos(List<InGameListInfo> infos, boolean isNewWorld) {
        List<ListInfo> lists = new ArrayList<>();
        for (InGameListInfo info : infos) {
            lists.add(new ListInfo(info.id(), info.names(), info.descriptions(), info.buildingCount(), info.icon()));
        }
        return new InGameListsPacket(lists, isNewWorld, false);
    }

    private static InGameListsPacket read(FriendlyByteBuf buf) {
        int listCount = buf.readVarInt();
        List<ListInfo> lists = new ArrayList<>(listCount);
        for (int i = 0; i < listCount; i++) {
            String id = buf.readUtf(64);  // List ID as string (max 64 chars)

            // Read names map
            int namesCount = buf.readVarInt();
            Map<String, String> names = new HashMap<>(namesCount);
            for (int j = 0; j < namesCount; j++) {
                String key = buf.readUtf(32);
                String value = buf.readUtf(256);
                names.put(key, value);
            }

            // Read descriptions map
            int descsCount = buf.readVarInt();
            Map<String, String> descriptions = new HashMap<>(descsCount);
            for (int j = 0; j < descsCount; j++) {
                String key = buf.readUtf(32);
                String value = buf.readUtf(1024);
                descriptions.put(key, value);
            }

            int buildingCount = buf.readVarInt();
            String icon = buf.readUtf(128);  // Minecraft item ID (e.g., "minecraft:bell")
            lists.add(new ListInfo(id, names, descriptions, buildingCount, icon));
        }
        boolean isNewWorld = buf.readBoolean();
        boolean connectionError = buf.readBoolean();
        return new InGameListsPacket(lists, isNewWorld, connectionError);
    }

    private static void write(FriendlyByteBuf buf, InGameListsPacket packet) {
        buf.writeVarInt(packet.lists.size());
        for (ListInfo info : packet.lists) {
            buf.writeUtf(info.id, 64);  // List ID as string (max 64 chars)

            // Write names map
            Map<String, String> names = info.names != null ? info.names : Map.of();
            buf.writeVarInt(names.size());
            for (Map.Entry<String, String> entry : names.entrySet()) {
                buf.writeUtf(entry.getKey(), 32);
                buf.writeUtf(entry.getValue(), 256);
            }

            // Write descriptions map
            Map<String, String> descriptions = info.descriptions != null ? info.descriptions : Map.of();
            buf.writeVarInt(descriptions.size());
            for (Map.Entry<String, String> entry : descriptions.entrySet()) {
                buf.writeUtf(entry.getKey(), 32);
                buf.writeUtf(entry.getValue(), 1024);
            }

            buf.writeVarInt(info.buildingCount);
            buf.writeUtf(info.icon != null ? info.icon : "", 128);  // Minecraft item ID
        }
        buf.writeBoolean(packet.isNewWorld);
        buf.writeBoolean(packet.connectionError);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
