package it.magius.struttura.architect.ingame.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Represents a spawnable list containing buildings that can be spawned in the world.
 * Downloaded from the REST API when InGame mode is initialized.
 */
public class SpawnableList {

    private final String listHash;               // Content hash of the list for cache validation
    private final double spawningPercentage;     // 0.0-1.0, base probability of any spawn in a chunk
    private final List<SpawnableBuilding> buildings;

    public SpawnableList(String listHash, double spawningPercentage, List<SpawnableBuilding> buildings) {
        this.listHash = listHash;
        this.spawningPercentage = Math.max(0.0, Math.min(1.0, spawningPercentage));
        this.buildings = buildings != null ? new ArrayList<>(buildings) : new ArrayList<>();
    }

    /**
     * Gets the content hash of this list for cache validation.
     */
    public String getListHash() {
        return listHash;
    }

    /**
     * Gets the base spawning percentage for this list.
     * This is the probability that any building will attempt to spawn in a chunk.
     * @return a value between 0.0 and 1.0
     */
    public double getSpawningPercentage() {
        return spawningPercentage;
    }

    /**
     * Gets all buildings in this list.
     */
    public List<SpawnableBuilding> getBuildings() {
        return buildings;
    }

    /**
     * Gets the number of buildings in this list.
     */
    public int getBuildingCount() {
        return buildings.size();
    }

    /**
     * Checks if this list has any buildings.
     */
    public boolean hasBuildings() {
        return !buildings.isEmpty();
    }

    /**
     * Gets a building by its RDNS identifier.
     * @param rdns the reverse DNS identifier
     * @return the building, or null if not found
     */
    public SpawnableBuilding getBuildingByRdns(String rdns) {
        for (SpawnableBuilding building : buildings) {
            if (building.getRdns().equals(rdns)) {
                return building;
            }
        }
        return null;
    }

    /**
     * Selects a random building that can still be spawned.
     * @param random the random generator (seeded for deterministic selection)
     * @return a spawnable building, or null if none are available
     */
    public SpawnableBuilding selectRandomBuilding(Random random) {
        // Filter to buildings that can still spawn
        List<SpawnableBuilding> available = new ArrayList<>();
        for (SpawnableBuilding building : buildings) {
            if (building.canSpawn()) {
                available.add(building);
            }
        }

        if (available.isEmpty()) {
            return null;
        }

        // Select randomly from available buildings
        return available.get(random.nextInt(available.size()));
    }

    /**
     * Resets all spawn counts for buildings in this list.
     */
    public void resetAllSpawnCounts() {
        for (SpawnableBuilding building : buildings) {
            building.resetSpawnCount();
        }
    }

    @Override
    public String toString() {
        return "SpawnableList{listHash='" + listHash + "', spawningPercentage=" + spawningPercentage +
               ", buildings=" + buildings.size() + "}";
    }
}
