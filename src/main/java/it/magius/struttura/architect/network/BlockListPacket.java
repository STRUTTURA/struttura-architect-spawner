package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Packet S2C for syncing the block list to client during editing.
 * Contains a list of block types with their display names and counts.
 */
public record BlockListPacket(
    List<BlockInfo> blocks
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BlockListPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "block_list"));

    public static final StreamCodec<FriendlyByteBuf, BlockListPacket> STREAM_CODEC =
        StreamCodec.of(BlockListPacket::write, BlockListPacket::read);

    /**
     * Info about a block type.
     */
    public record BlockInfo(
        String blockId,
        String displayName,
        int count
    ) {
        private static BlockInfo read(FriendlyByteBuf buf) {
            String blockId = buf.readUtf(256);
            String displayName = buf.readUtf(128);
            int count = buf.readVarInt();
            return new BlockInfo(blockId, displayName, count);
        }

        private static void write(FriendlyByteBuf buf, BlockInfo info) {
            buf.writeUtf(info.blockId, 256);
            buf.writeUtf(info.displayName, 128);
            buf.writeVarInt(info.count);
        }
    }

    /**
     * Create an empty packet (no blocks).
     */
    public static BlockListPacket empty() {
        return new BlockListPacket(List.of());
    }

    private static BlockListPacket read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<BlockInfo> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            blocks.add(BlockInfo.read(buf));
        }
        return new BlockListPacket(blocks);
    }

    private static void write(FriendlyByteBuf buf, BlockListPacket packet) {
        buf.writeVarInt(packet.blocks.size());
        for (BlockInfo info : packet.blocks) {
            BlockInfo.write(buf, info);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
