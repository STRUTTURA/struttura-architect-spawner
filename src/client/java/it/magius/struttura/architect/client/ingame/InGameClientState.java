package it.magius.struttura.architect.client.ingame;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-side state for in-game building tracking.
 * Updated when receiving InGameBuildingPacket from server.
 */
@Environment(EnvType.CLIENT)
public class InGameClientState {

    private static InGameClientState instance;

    private boolean inBuilding = false;
    private String rdns = "";
    private long pk = 0;
    private boolean hasLiked = false;

    // Cached display values
    private String buildingName = "";
    private String description = "";
    private String author = "";

    private InGameClientState() {}

    public static InGameClientState getInstance() {
        if (instance == null) {
            instance = new InGameClientState();
        }
        return instance;
    }

    /**
     * Updates state when player enters a building.
     * @param rdns the building RDNS
     * @param pk the building primary key
     * @param hasLiked whether the player has already liked this building
     * @param localizedName the localized name (from server, may be empty)
     * @param localizedDescription the localized description (from server, may be empty)
     */
    public void enterBuilding(String rdns, long pk, boolean hasLiked,
                              String localizedName, String localizedDescription) {
        this.inBuilding = true;
        this.rdns = rdns;
        this.pk = pk;
        this.hasLiked = hasLiked;

        // Use localized name if available, otherwise parse from RDNS
        if (localizedName != null && !localizedName.isEmpty()) {
            this.buildingName = localizedName;
        } else {
            this.buildingName = parseBuildingName(rdns);
        }

        // Use localized description if available
        this.description = localizedDescription != null ? localizedDescription : "";

        // Author is always parsed from RDNS
        this.author = parseAuthor(rdns);
    }

    /**
     * Updates state when player exits a building.
     */
    public void exitBuilding() {
        this.inBuilding = false;
        this.rdns = "";
        this.pk = 0;
        this.hasLiked = false;
        this.buildingName = "";
        this.description = "";
        this.author = "";
    }

    /**
     * Updates the liked state (after player likes the building).
     */
    public void setLiked(boolean hasLiked) {
        this.hasLiked = hasLiked;
    }

    /**
     * Resets all state (on disconnect).
     */
    public void reset() {
        exitBuilding();
    }

    // Getters

    public boolean isInBuilding() {
        return inBuilding;
    }

    public String getRdns() {
        return rdns;
    }

    public long getPk() {
        return pk;
    }

    public boolean hasLiked() {
        return hasLiked;
    }

    public String getBuildingName() {
        return buildingName;
    }

    public String getDescription() {
        return description;
    }

    public String getAuthor() {
        return author;
    }

    // RDNS parsing helpers

    /**
     * Gets the building name from the RDNS (last segment).
     * e.g., "com.johndoe.myhouse" -> "myhouse"
     */
    private static String parseBuildingName(String rdns) {
        if (rdns == null || rdns.isEmpty()) {
            return "";
        }
        int lastDot = rdns.lastIndexOf('.');
        String name = lastDot >= 0 ? rdns.substring(lastDot + 1) : rdns;
        // Convert to title case: myhouse -> Myhouse
        if (!name.isEmpty()) {
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
        return name;
    }

    /**
     * Gets the author from the RDNS (second segment if format is tld.author.name).
     * e.g., "com.johndoe.myhouse" -> "johndoe"
     */
    private static String parseAuthor(String rdns) {
        if (rdns == null || rdns.isEmpty()) {
            return "";
        }
        String[] parts = rdns.split("\\.");
        // RDNS format: tld.author.buildingname (e.g., com.johndoe.myhouse)
        return parts.length >= 2 ? parts[1] : "";
    }
}
