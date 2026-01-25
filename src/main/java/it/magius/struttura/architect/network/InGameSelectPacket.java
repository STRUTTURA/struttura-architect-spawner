package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packet C2S for selecting an InGame list, declining, or skipping InGame mode.
 * Sent when player makes a choice in the InGameSetupScreen.
 */
public record InGameSelectPacket(
    Action action,         // The action the player chose
    String listId,         // Selected list ID (only valid if action is SELECT). Can be numeric or alphanumeric for virtual lists.
    String listName        // Selected list name (for logging)
) implements CustomPacketPayload {

    /**
     * Actions the player can take on the InGame setup screen.
     */
    public enum Action {
        SELECT,   // Player selected a list to use
        DECLINE,  // Player permanently disabled adventure mode
        SKIP      // Player skipped for now (will retry next world load)
    }

    public static final CustomPacketPayload.Type<InGameSelectPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "ingame_select"));

    public static final StreamCodec<FriendlyByteBuf, InGameSelectPacket> STREAM_CODEC =
        StreamCodec.of(InGameSelectPacket::write, InGameSelectPacket::read);

    /**
     * Create a packet for declining InGame mode permanently.
     */
    public static InGameSelectPacket decline() {
        return new InGameSelectPacket(Action.DECLINE, "", "");
    }

    /**
     * Create a packet for skipping InGame mode for now (retry later).
     */
    public static InGameSelectPacket skip() {
        return new InGameSelectPacket(Action.SKIP, "", "");
    }

    /**
     * Create a packet for selecting a list.
     * @param listId the list ID (can be numeric like "123" or alphanumeric like "most-popular")
     */
    public static InGameSelectPacket select(String listId, String listName) {
        return new InGameSelectPacket(Action.SELECT, listId, listName);
    }

    private static InGameSelectPacket read(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        String listId = buf.readUtf(64);  // List ID as string (max 64 chars)
        String listName = buf.readUtf(256);
        return new InGameSelectPacket(action, listId, listName);
    }

    private static void write(FriendlyByteBuf buf, InGameSelectPacket packet) {
        buf.writeEnum(packet.action);
        buf.writeUtf(packet.listId, 64);  // List ID as string (max 64 chars)
        buf.writeUtf(packet.listName, 256);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
