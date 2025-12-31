package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packet C2S per eseguire azioni di selezione tramite keybinding.
 * Inviato quando il giocatore preme uno dei tasti di selezione (Z, X, C, V di default).
 */
public record SelectionKeyPacket(Action action) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SelectionKeyPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "selection_key"));

    public static final StreamCodec<FriendlyByteBuf, SelectionKeyPacket> STREAM_CODEC =
        StreamCodec.of(SelectionKeyPacket::write, SelectionKeyPacket::read);

    public enum Action {
        POS1,
        POS2,
        CLEAR,
        APPLY,
        APPLYALL
    }

    private static SelectionKeyPacket read(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        return new SelectionKeyPacket(action);
    }

    private static void write(FriendlyByteBuf buf, SelectionKeyPacket packet) {
        buf.writeEnum(packet.action);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
