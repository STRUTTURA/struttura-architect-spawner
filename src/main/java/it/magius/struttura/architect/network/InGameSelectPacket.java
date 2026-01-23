package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packet C2S for selecting an InGame list or declining InGame mode.
 * Sent when player makes a choice in the InGameSetupScreen.
 */
public record InGameSelectPacket(
    boolean declined,       // True if player chose to decline InGame mode
    long listId,           // Selected list ID (only valid if not declined)
    String listName        // Selected list name (for logging)
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<InGameSelectPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "ingame_select"));

    public static final StreamCodec<FriendlyByteBuf, InGameSelectPacket> STREAM_CODEC =
        StreamCodec.of(InGameSelectPacket::write, InGameSelectPacket::read);

    /**
     * Create a packet for declining InGame mode.
     */
    public static InGameSelectPacket decline() {
        return new InGameSelectPacket(true, 0, "");
    }

    /**
     * Create a packet for selecting a list.
     */
    public static InGameSelectPacket select(long listId, String listName) {
        return new InGameSelectPacket(false, listId, listName);
    }

    private static InGameSelectPacket read(FriendlyByteBuf buf) {
        boolean declined = buf.readBoolean();
        long listId = buf.readLong();
        String listName = buf.readUtf(256);
        return new InGameSelectPacket(declined, listId, listName);
    }

    private static void write(FriendlyByteBuf buf, InGameSelectPacket packet) {
        buf.writeBoolean(packet.declined);
        buf.writeLong(packet.listId);
        buf.writeUtf(packet.listName, 256);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
