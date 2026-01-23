package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packet S2C for syncing in-game building state to client.
 * Sent when player enters or exits a spawned building.
 */
public record InGameBuildingPacket(
    boolean inBuilding,     // True if player is currently inside a building
    String rdns,            // Building RDNS identifier (empty if not in building)
    long pk,                // Building primary key (0 if not in building)
    boolean hasLiked        // True if player has already liked this building
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<InGameBuildingPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "ingame_building"));

    public static final StreamCodec<FriendlyByteBuf, InGameBuildingPacket> STREAM_CODEC =
        StreamCodec.of(InGameBuildingPacket::write, InGameBuildingPacket::read);

    /**
     * Create an empty packet (player is not in any building).
     */
    public static InGameBuildingPacket empty() {
        return new InGameBuildingPacket(false, "", 0, false);
    }

    /**
     * Create a packet for when player enters a building.
     */
    public static InGameBuildingPacket entered(String rdns, long pk, boolean hasLiked) {
        return new InGameBuildingPacket(true, rdns, pk, hasLiked);
    }

    private static InGameBuildingPacket read(FriendlyByteBuf buf) {
        boolean inBuilding = buf.readBoolean();
        String rdns = buf.readUtf(512);
        long pk = buf.readLong();
        boolean hasLiked = buf.readBoolean();
        return new InGameBuildingPacket(inBuilding, rdns, pk, hasLiked);
    }

    private static void write(FriendlyByteBuf buf, InGameBuildingPacket packet) {
        buf.writeBoolean(packet.inBuilding);
        buf.writeUtf(packet.rdns, 512);
        buf.writeLong(packet.pk);
        buf.writeBoolean(packet.hasLiked);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Gets the building name from the RDNS (last segment).
     */
    public String getBuildingName() {
        if (rdns == null || rdns.isEmpty()) {
            return "";
        }
        int lastDot = rdns.lastIndexOf('.');
        return lastDot >= 0 ? rdns.substring(lastDot + 1) : rdns;
    }

    /**
     * Gets the author from the RDNS (second segment if format is tld.author.name).
     */
    public String getAuthor() {
        if (rdns == null || rdns.isEmpty()) {
            return "";
        }
        String[] parts = rdns.split("\\.");
        // RDNS format: tld.author.buildingname (e.g., com.johndoe.myhouse)
        return parts.length >= 2 ? parts[1] : "";
    }
}
