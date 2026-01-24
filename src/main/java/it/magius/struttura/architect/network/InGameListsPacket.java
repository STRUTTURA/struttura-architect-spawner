package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.ingame.model.InGameListInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

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
     */
    public record ListInfo(
        long id,
        String name,
        String description,
        int buildingCount
    ) {}

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
            lists.add(new ListInfo(info.id(), info.name(), info.description(), info.buildingCount()));
        }
        return new InGameListsPacket(lists, isNewWorld, false);
    }

    private static InGameListsPacket read(FriendlyByteBuf buf) {
        int listCount = buf.readVarInt();
        List<ListInfo> lists = new ArrayList<>(listCount);
        for (int i = 0; i < listCount; i++) {
            long id = buf.readLong();
            String name = buf.readUtf(256);
            String description = buf.readUtf(1024);
            int buildingCount = buf.readVarInt();
            lists.add(new ListInfo(id, name, description, buildingCount));
        }
        boolean isNewWorld = buf.readBoolean();
        boolean connectionError = buf.readBoolean();
        return new InGameListsPacket(lists, isNewWorld, connectionError);
    }

    private static void write(FriendlyByteBuf buf, InGameListsPacket packet) {
        buf.writeVarInt(packet.lists.size());
        for (ListInfo info : packet.lists) {
            buf.writeLong(info.id);
            buf.writeUtf(info.name, 256);
            buf.writeUtf(info.description, 1024);
            buf.writeVarInt(info.buildingCount);
        }
        buf.writeBoolean(packet.isNewWorld);
        buf.writeBoolean(packet.connectionError);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
