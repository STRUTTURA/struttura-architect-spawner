package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Packet S2C for syncing the construction list to client.
 * Sent when GUI is opened or when construction list changes.
 */
public record ConstructionListPacket(
    List<ConstructionInfo> constructions
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ConstructionListPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "construction_list"));

    public static final StreamCodec<FriendlyByteBuf, ConstructionListPacket> STREAM_CODEC =
        StreamCodec.of(ConstructionListPacket::write, ConstructionListPacket::read);

    /**
     * Info about a single construction.
     */
    public record ConstructionInfo(
        String id,
        String title,
        int blockCount,
        int entityCount,
        boolean isBeingEdited
    ) {
        private static ConstructionInfo read(FriendlyByteBuf buf) {
            String id = buf.readUtf(256);
            String title = buf.readUtf(128);
            int blockCount = buf.readVarInt();
            int entityCount = buf.readVarInt();
            boolean isBeingEdited = buf.readBoolean();
            return new ConstructionInfo(id, title, blockCount, entityCount, isBeingEdited);
        }

        private static void write(FriendlyByteBuf buf, ConstructionInfo info) {
            buf.writeUtf(info.id, 256);
            buf.writeUtf(info.title, 128);
            buf.writeVarInt(info.blockCount);
            buf.writeVarInt(info.entityCount);
            buf.writeBoolean(info.isBeingEdited);
        }
    }

    /**
     * Create an empty packet (no constructions).
     */
    public static ConstructionListPacket empty() {
        return new ConstructionListPacket(List.of());
    }

    private static ConstructionListPacket read(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<ConstructionInfo> constructions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            constructions.add(ConstructionInfo.read(buf));
        }
        return new ConstructionListPacket(constructions);
    }

    private static void write(FriendlyByteBuf buf, ConstructionListPacket packet) {
        buf.writeVarInt(packet.constructions.size());
        for (ConstructionInfo info : packet.constructions) {
            ConstructionInfo.write(buf, info);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
