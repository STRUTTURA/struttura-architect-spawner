package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packet per sincronizzare i dati del wireframe dal server al client.
 */
public record WireframeSyncPacket(
    // Dati costruzione in editing
    boolean constructionActive,
    BlockPos constructionMin,
    BlockPos constructionMax,
    String constructionId,
    // Dati selezione
    boolean selectionActive,
    BlockPos selectionPos1,
    BlockPos selectionPos2,
    boolean hasPos1,
    boolean hasPos2
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WireframeSyncPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "wireframe_sync"));

    public static final StreamCodec<FriendlyByteBuf, WireframeSyncPacket> STREAM_CODEC =
        StreamCodec.of(WireframeSyncPacket::write, WireframeSyncPacket::read);

    private static WireframeSyncPacket read(FriendlyByteBuf buf) {
        boolean constructionActive = buf.readBoolean();
        BlockPos constructionMin = buf.readBlockPos();
        BlockPos constructionMax = buf.readBlockPos();
        String constructionId = buf.readUtf();
        boolean selectionActive = buf.readBoolean();
        BlockPos selectionPos1 = buf.readBlockPos();
        BlockPos selectionPos2 = buf.readBlockPos();
        boolean hasPos1 = buf.readBoolean();
        boolean hasPos2 = buf.readBoolean();

        return new WireframeSyncPacket(
            constructionActive, constructionMin, constructionMax, constructionId,
            selectionActive, selectionPos1, selectionPos2, hasPos1, hasPos2
        );
    }

    private static void write(FriendlyByteBuf buf, WireframeSyncPacket packet) {
        buf.writeBoolean(packet.constructionActive);
        buf.writeBlockPos(packet.constructionMin);
        buf.writeBlockPos(packet.constructionMax);
        buf.writeUtf(packet.constructionId);
        buf.writeBoolean(packet.selectionActive);
        buf.writeBlockPos(packet.selectionPos1);
        buf.writeBlockPos(packet.selectionPos2);
        buf.writeBoolean(packet.hasPos1);
        buf.writeBoolean(packet.hasPos2);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Crea un packet vuoto (nessun wireframe attivo).
     */
    public static WireframeSyncPacket empty() {
        return new WireframeSyncPacket(
            false, BlockPos.ZERO, BlockPos.ZERO, "",
            false, BlockPos.ZERO, BlockPos.ZERO, false, false
        );
    }

    /**
     * Estrae i dati del wireframe costruzione.
     */
    public WireframeData.ConstructionWireframe getConstructionWireframe() {
        return new WireframeData.ConstructionWireframe(
            constructionActive, constructionMin, constructionMax, constructionId
        );
    }

    /**
     * Estrae i dati del wireframe selezione.
     */
    public WireframeData.SelectionWireframe getSelectionWireframe() {
        return new WireframeData.SelectionWireframe(
            selectionActive, selectionPos1, selectionPos2, hasPos1, hasPos2
        );
    }
}
