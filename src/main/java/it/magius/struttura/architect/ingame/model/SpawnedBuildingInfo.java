package it.magius.struttura.architect.ingame.model;

import net.minecraft.world.phys.AABB;

/**
 * Information about a building that has been spawned in the world.
 * Stored in chunk NBT data for tracking and player proximity detection.
 * Includes name and author for display even if building is removed from list.
 */
public record SpawnedBuildingInfo(
    String rdns,        // Reverse DNS identifier
    long pk,            // Primary key from database
    AABB bounds,        // World-space bounding box of the spawned building
    int rotation,       // Rotation applied (0, 90, 180, 270 degrees)
    String name,        // Localized name at spawn time
    String author       // Author nickname
) {
    /**
     * Checks if a position is inside this building's bounds.
     * @param x world X coordinate
     * @param y world Y coordinate
     * @param z world Z coordinate
     * @return true if the position is inside the bounds
     */
    public boolean contains(double x, double y, double z) {
        return bounds.contains(x, y, z);
    }

    /**
     * Checks if a position is inside or near this building's bounds.
     * @param x world X coordinate
     * @param y world Y coordinate
     * @param z world Z coordinate
     * @param margin additional margin around the bounds
     * @return true if the position is within the expanded bounds
     */
    public boolean containsWithMargin(double x, double y, double z, double margin) {
        return bounds.inflate(margin).contains(x, y, z);
    }

    @Override
    public String toString() {
        return "SpawnedBuildingInfo{rdns='" + rdns + "', pk=" + pk + ", rotation=" + rotation + "°}";
    }
}
