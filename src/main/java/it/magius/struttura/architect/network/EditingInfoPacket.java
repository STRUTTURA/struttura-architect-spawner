package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

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
    String bounds,      // "10x20x15" format
    String mode         // "ADD" or "REMOVE"
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<EditingInfoPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "editing_info"));

    public static final StreamCodec<FriendlyByteBuf, EditingInfoPacket> STREAM_CODEC =
        StreamCodec.of(EditingInfoPacket::write, EditingInfoPacket::read);

    /**
     * Create an empty packet (for when not editing).
     */
    public static EditingInfoPacket empty() {
        return new EditingInfoPacket(false, "", "", 0, 0, 0, "", "ADD");
    }

    private static EditingInfoPacket read(FriendlyByteBuf buf) {
        boolean isEditing = buf.readBoolean();
        String constructionId = buf.readUtf(256);
        String title = buf.readUtf(256);
        int blockCount = buf.readVarInt();
        int solidBlockCount = buf.readVarInt();
        int airBlockCount = buf.readVarInt();
        String bounds = buf.readUtf(64);
        String mode = buf.readUtf(16);
        return new EditingInfoPacket(isEditing, constructionId, title, blockCount, solidBlockCount, airBlockCount, bounds, mode);
    }

    private static void write(FriendlyByteBuf buf, EditingInfoPacket packet) {
        buf.writeBoolean(packet.isEditing);
        buf.writeUtf(packet.constructionId, 256);
        buf.writeUtf(packet.title, 256);
        buf.writeVarInt(packet.blockCount);
        buf.writeVarInt(packet.solidBlockCount);
        buf.writeVarInt(packet.airBlockCount);
        buf.writeUtf(packet.bounds, 64);
        buf.writeUtf(packet.mode, 16);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
