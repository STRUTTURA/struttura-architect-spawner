package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Packet S2C for syncing editing state to client.
 * Sent when player enters editing mode or when editing data changes.
 */
public record EditingInfoPacket(
    boolean isEditing,
    String constructionId,
    String title,
    int blockCount,
    int solidBlockCount,
    int airBlockCount,
    int entityCount,
    int mobCount,       // Numero di mob (entità viventi)
    String bounds,      // "10x20x15" format
    String mode,        // "ADD" or "REMOVE"
    String shortDesc,   // Short description in player's language
    // Room fields
    boolean inRoom,         // True se stiamo editando una stanza
    String currentRoomId,   // ID della stanza corrente (vuoto se non in stanza)
    String currentRoomName, // Nome della stanza corrente
    int roomCount,          // Numero totale di stanze nella costruzione
    int roomBlockChanges,   // Numero di blocchi modificati nella stanza corrente
    List<RoomInfo> roomList, // Lista di tutte le stanze
    // Anchor fields (entrance coordinates are ABSOLUTE world coordinates for GUI display)
    boolean hasEntrance,    // True if entrance anchor is set
    int entranceX,          // Absolute X coordinate (or 0 if not set)
    int entranceY,          // Absolute Y coordinate (or 0 if not set)
    int entranceZ,          // Absolute Z coordinate (or 0 if not set)
    float entranceYaw       // Player yaw rotation in degrees (or 0 if not set)
) implements CustomPacketPayload {

    /**
     * Info about a room in the construction.
     */
    public record RoomInfo(String id, String name, int blockCount, int entityCount) {}

    public static final CustomPacketPayload.Type<EditingInfoPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "editing_info"));

    public static final StreamCodec<FriendlyByteBuf, EditingInfoPacket> STREAM_CODEC =
        StreamCodec.of(EditingInfoPacket::write, EditingInfoPacket::read);

    /**
     * Create an empty packet (for when not editing).
     */
    public static EditingInfoPacket empty() {
        return new EditingInfoPacket(false, "", "", 0, 0, 0, 0, 0, "", "ADD", "",
            false, "", "", 0, 0, List.of(),
            false, 0, 0, 0, 0f);
    }

    private static EditingInfoPacket read(FriendlyByteBuf buf) {
        boolean isEditing = buf.readBoolean();
        String constructionId = buf.readUtf(256);
        String title = buf.readUtf(256);
        int blockCount = buf.readVarInt();
        int solidBlockCount = buf.readVarInt();
        int airBlockCount = buf.readVarInt();
        int entityCount = buf.readVarInt();
        int mobCount = buf.readVarInt();
        String bounds = buf.readUtf(64);
        String mode = buf.readUtf(16);
        String shortDesc = buf.readUtf(1024);
        // Room fields
        boolean inRoom = buf.readBoolean();
        String currentRoomId = buf.readUtf(128);
        String currentRoomName = buf.readUtf(256);
        int roomCount = buf.readVarInt();
        int roomBlockChanges = buf.readVarInt();
        // Room list
        int roomListSize = buf.readVarInt();
        List<RoomInfo> roomList = new ArrayList<>(roomListSize);
        for (int i = 0; i < roomListSize; i++) {
            String roomId = buf.readUtf(128);
            String roomName = buf.readUtf(256);
            int roomBlocks = buf.readVarInt();
            int roomEntities = buf.readVarInt();
            roomList.add(new RoomInfo(roomId, roomName, roomBlocks, roomEntities));
        }
        // Anchor fields
        boolean hasEntrance = buf.readBoolean();
        int entranceX = buf.readVarInt();
        int entranceY = buf.readVarInt();
        int entranceZ = buf.readVarInt();
        float entranceYaw = buf.readFloat();
        return new EditingInfoPacket(isEditing, constructionId, title, blockCount, solidBlockCount, airBlockCount, entityCount, mobCount, bounds, mode, shortDesc,
            inRoom, currentRoomId, currentRoomName, roomCount, roomBlockChanges, roomList,
            hasEntrance, entranceX, entranceY, entranceZ, entranceYaw);
    }

    private static void write(FriendlyByteBuf buf, EditingInfoPacket packet) {
        buf.writeBoolean(packet.isEditing);
        buf.writeUtf(packet.constructionId, 256);
        buf.writeUtf(packet.title, 256);
        buf.writeVarInt(packet.blockCount);
        buf.writeVarInt(packet.solidBlockCount);
        buf.writeVarInt(packet.airBlockCount);
        buf.writeVarInt(packet.entityCount);
        buf.writeVarInt(packet.mobCount);
        buf.writeUtf(packet.bounds, 64);
        buf.writeUtf(packet.mode, 16);
        buf.writeUtf(packet.shortDesc, 1024);
        // Room fields
        buf.writeBoolean(packet.inRoom);
        buf.writeUtf(packet.currentRoomId, 128);
        buf.writeUtf(packet.currentRoomName, 256);
        buf.writeVarInt(packet.roomCount);
        buf.writeVarInt(packet.roomBlockChanges);
        // Room list
        buf.writeVarInt(packet.roomList.size());
        for (RoomInfo room : packet.roomList) {
            buf.writeUtf(room.id, 128);
            buf.writeUtf(room.name, 256);
            buf.writeVarInt(room.blockCount);
            buf.writeVarInt(room.entityCount);
        }
        // Anchor fields
        buf.writeBoolean(packet.hasEntrance);
        buf.writeVarInt(packet.entranceX);
        buf.writeVarInt(packet.entranceY);
        buf.writeVarInt(packet.entranceZ);
        buf.writeFloat(packet.entranceYaw);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
