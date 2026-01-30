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
    boolean inBuilding,         // True if player is currently inside a building
    String rdns,                // Building RDNS identifier (empty if not in building)
    long pk,                    // Building primary key (0 if not in building)
    boolean hasLiked,           // True if player has already liked this building
    boolean isOwner,            // True if current player owns this building (cannot like own buildings)
    String localizedName,       // Localized building name (empty if not available)
    String localizedDescription,// Localized building description (empty if not available)
    String author,              // Author nickname (from list or chunk data fallback)
    boolean showLikeTutorial    // True if like tutorial should be shown (first building entry in this world)
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<InGameBuildingPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "ingame_building"));

    public static final StreamCodec<FriendlyByteBuf, InGameBuildingPacket> STREAM_CODEC =
        StreamCodec.of(InGameBuildingPacket::write, InGameBuildingPacket::read);

    /**
     * Create an empty packet (player is not in any building).
     */
    public static InGameBuildingPacket empty() {
        return new InGameBuildingPacket(false, "", 0, false, false, "", "", "", false);
    }

    /**
     * Create a packet for when player enters a building.
     */
    public static InGameBuildingPacket entered(String rdns, long pk, boolean hasLiked, boolean isOwner,
                                               String localizedName, String localizedDescription,
                                               String author, boolean showLikeTutorial) {
        return new InGameBuildingPacket(true, rdns, pk, hasLiked, isOwner,
            localizedName != null ? localizedName : "",
            localizedDescription != null ? localizedDescription : "",
            author != null ? author : "",
            showLikeTutorial);
    }

    private static InGameBuildingPacket read(FriendlyByteBuf buf) {
        boolean inBuilding = buf.readBoolean();
        String rdns = buf.readUtf(512);
        long pk = buf.readLong();
        boolean hasLiked = buf.readBoolean();
        boolean isOwner = buf.readBoolean();
        String localizedName = buf.readUtf(256);
        String localizedDescription = buf.readUtf(2048);
        String author = buf.readUtf(128);
        boolean showLikeTutorial = buf.readBoolean();
        return new InGameBuildingPacket(inBuilding, rdns, pk, hasLiked, isOwner, localizedName, localizedDescription, author, showLikeTutorial);
    }

    private static void write(FriendlyByteBuf buf, InGameBuildingPacket packet) {
        buf.writeBoolean(packet.inBuilding);
        buf.writeUtf(packet.rdns, 512);
        buf.writeLong(packet.pk);
        buf.writeBoolean(packet.hasLiked);
        buf.writeBoolean(packet.isOwner);
        buf.writeUtf(packet.localizedName, 256);
        buf.writeUtf(packet.localizedDescription, 2048);
        buf.writeUtf(packet.author, 128);
        buf.writeBoolean(packet.showLikeTutorial);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Gets the building name - prefers localized name, falls back to RDNS parsing.
     */
    public String getBuildingName() {
        if (localizedName != null && !localizedName.isEmpty()) {
            return localizedName;
        }
        // Fallback to RDNS parsing
        if (rdns == null || rdns.isEmpty()) {
            return "";
        }
        int lastDot = rdns.lastIndexOf('.');
        return lastDot >= 0 ? rdns.substring(lastDot + 1) : rdns;
    }

    /**
     * Gets the author nickname.
     * Returns the author field if available, otherwise falls back to RDNS parsing.
     */
    public String getAuthor() {
        if (author != null && !author.isEmpty()) {
            return author;
        }
        // Fallback to RDNS parsing
        if (rdns == null || rdns.isEmpty()) {
            return "";
        }
        String[] parts = rdns.split("\\.");
        // RDNS format: tld.author.buildingname (e.g., com.johndoe.myhouse)
        return parts.length >= 2 ? parts[1] : "";
    }

    /**
     * Gets the localized description.
     */
    public String getDescription() {
        return localizedDescription != null ? localizedDescription : "";
    }
}
