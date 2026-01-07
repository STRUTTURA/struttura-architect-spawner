package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Packet per sincronizzare le posizioni dei blocchi della costruzione in editing.
 * Usato per renderizzare overlay sui blocchi.
 * - solidBlocks/airBlocks: blocchi già nella costruzione (wireframe rosso)
 * - previewBlocks: blocchi che verranno aggiunti con select add (wireframe ciano)
 * - roomBlocks: blocchi della room corrente (wireframe giallo)
 */
public record BlockPositionsSyncPacket(
    List<BlockPos> solidBlocks,
    List<BlockPos> airBlocks,
    List<BlockPos> previewBlocks,
    List<BlockPos> roomBlocks
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BlockPositionsSyncPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "block_positions_sync"));

    public static final StreamCodec<FriendlyByteBuf, BlockPositionsSyncPacket> STREAM_CODEC =
        StreamCodec.of(BlockPositionsSyncPacket::write, BlockPositionsSyncPacket::read);

    private static BlockPositionsSyncPacket read(FriendlyByteBuf buf) {
        int solidCount = buf.readVarInt();
        List<BlockPos> solidBlocks = new ArrayList<>(solidCount);
        for (int i = 0; i < solidCount; i++) {
            solidBlocks.add(buf.readBlockPos());
        }

        int airCount = buf.readVarInt();
        List<BlockPos> airBlocks = new ArrayList<>(airCount);
        for (int i = 0; i < airCount; i++) {
            airBlocks.add(buf.readBlockPos());
        }

        int previewCount = buf.readVarInt();
        List<BlockPos> previewBlocks = new ArrayList<>(previewCount);
        for (int i = 0; i < previewCount; i++) {
            previewBlocks.add(buf.readBlockPos());
        }

        int roomCount = buf.readVarInt();
        List<BlockPos> roomBlocks = new ArrayList<>(roomCount);
        for (int i = 0; i < roomCount; i++) {
            roomBlocks.add(buf.readBlockPos());
        }

        return new BlockPositionsSyncPacket(solidBlocks, airBlocks, previewBlocks, roomBlocks);
    }

    private static void write(FriendlyByteBuf buf, BlockPositionsSyncPacket packet) {
        buf.writeVarInt(packet.solidBlocks.size());
        for (BlockPos pos : packet.solidBlocks) {
            buf.writeBlockPos(pos);
        }

        buf.writeVarInt(packet.airBlocks.size());
        for (BlockPos pos : packet.airBlocks) {
            buf.writeBlockPos(pos);
        }

        buf.writeVarInt(packet.previewBlocks.size());
        for (BlockPos pos : packet.previewBlocks) {
            buf.writeBlockPos(pos);
        }

        buf.writeVarInt(packet.roomBlocks.size());
        for (BlockPos pos : packet.roomBlocks) {
            buf.writeBlockPos(pos);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Crea un packet vuoto (nessun blocco).
     */
    public static BlockPositionsSyncPacket empty() {
        return new BlockPositionsSyncPacket(List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Verifica se ci sono blocchi nella costruzione.
     */
    public boolean hasBlocks() {
        return !solidBlocks.isEmpty() || !airBlocks.isEmpty();
    }

    /**
     * Verifica se ci sono blocchi in anteprima.
     */
    public boolean hasPreviewBlocks() {
        return !previewBlocks.isEmpty();
    }

    /**
     * Verifica se ci sono blocchi della room.
     */
    public boolean hasRoomBlocks() {
        return !roomBlocks.isEmpty();
    }

    /**
     * Conta totale blocchi nella costruzione.
     */
    public int totalBlocks() {
        return solidBlocks.size() + airBlocks.size();
    }
}
