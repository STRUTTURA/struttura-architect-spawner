package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packet C2S for GUI actions.
 * Sent when the player interacts with GUI buttons.
 */
public record GuiActionPacket(
    String action,      // "show", "hide", "tp", "edit", "give", "exit", "shot", "rename", "title"
    String targetId,    // ID costruzione target (opzionale)
    String extraData    // Dati aggiuntivi (es. titolo per screenshot, nuovo nome)
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GuiActionPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "gui_action"));

    public static final StreamCodec<FriendlyByteBuf, GuiActionPacket> STREAM_CODEC =
        StreamCodec.of(GuiActionPacket::write, GuiActionPacket::read);

    private static GuiActionPacket read(FriendlyByteBuf buf) {
        String action = buf.readUtf(64);
        String targetId = buf.readUtf(256);
        String extraData = buf.readUtf(1024);
        return new GuiActionPacket(action, targetId, extraData);
    }

    private static void write(FriendlyByteBuf buf, GuiActionPacket packet) {
        buf.writeUtf(packet.action, 64);
        buf.writeUtf(packet.targetId, 256);
        buf.writeUtf(packet.extraData, 1024);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
