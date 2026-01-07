package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Packet S2C for syncing the block and entity list to client during editing.
 * Contains a list of block types with their display names and counts,
 * plus a list of entities with their types and UUIDs.
 */
public record BlockListPacket(
    List<BlockInfo> blocks,
    List<EntityInfo> entities
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
     * Info about an entity type (grouped by type with count).
     */
    public record EntityInfo(
        String entityType,
        String displayName,
        int count
    ) {
        private static EntityInfo read(FriendlyByteBuf buf) {
            String entityType = buf.readUtf(256);
            String displayName = buf.readUtf(128);
            int count = buf.readVarInt();
            return new EntityInfo(entityType, displayName, count);
        }

        private static void write(FriendlyByteBuf buf, EntityInfo info) {
            buf.writeUtf(info.entityType, 256);
            buf.writeUtf(info.displayName, 128);
            buf.writeVarInt(info.count);
        }
    }

    /**
     * Create an empty packet (no blocks, no entities).
     */
    public static BlockListPacket empty() {
        return new BlockListPacket(List.of(), List.of());
    }

    private static BlockListPacket read(FriendlyByteBuf buf) {
        int blockCount = buf.readVarInt();
        List<BlockInfo> blocks = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            blocks.add(BlockInfo.read(buf));
        }
        int entityCount = buf.readVarInt();
        List<EntityInfo> entities = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            entities.add(EntityInfo.read(buf));
        }
        return new BlockListPacket(blocks, entities);
    }

    private static void write(FriendlyByteBuf buf, BlockListPacket packet) {
        buf.writeVarInt(packet.blocks.size());
        for (BlockInfo info : packet.blocks) {
            BlockInfo.write(buf, info);
        }
        buf.writeVarInt(packet.entities.size());
        for (EntityInfo info : packet.entities) {
            EntityInfo.write(buf, info);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
