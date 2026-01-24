package it.magius.struttura.architect.ingame.model;

/**
 * Information about an InGame list available for selection.
 * Used during initialization to show available lists to the user.
 */
public record InGameListInfo(
    long id,
    String name,
    String description,
    int buildingCount,
    boolean isPublic,
    String contentHash
) {
    @Override
    public String toString() {
        return "InGameListInfo{id=" + id + ", name='" + name + "', buildings=" + buildingCount + "}";
    }
}
