package it.magius.struttura.architect.ingame.model;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Represents a building that can be spawned in the world via the InGame system.
 * Contains all metadata needed to evaluate and execute spawning.
 */
public class SpawnableBuilding {

    private final String rdns;              // Reverse DNS identifier (e.g., "it.magius.pip.home")
    private final long pk;                  // Primary key from the database
    private final BlockPos entrance;        // Entrance anchor position (normalized coordinates)
    private final float entranceYaw;        // Entrance facing direction
    private final int xWorld;               // Max spawns in this world (0 = unlimited)
    private final List<SpawnRule> rules;    // Spawn rules per biome
    private final AABB bounds;              // Bounding box (dimensions, origin at 0,0,0)

    // Runtime state (not persisted)
    private int spawnedCount = 0;

    public SpawnableBuilding(String rdns, long pk, BlockPos entrance, float entranceYaw,
                             int xWorld, List<SpawnRule> rules, AABB bounds) {
        this.rdns = rdns;
        this.pk = pk;
        this.entrance = entrance;
        this.entranceYaw = entranceYaw;
        this.xWorld = xWorld;
        this.rules = rules != null ? List.copyOf(rules) : List.of();
        this.bounds = bounds;
    }

    /**
     * Gets the reverse DNS identifier of this building.
     */
    public String getRdns() {
        return rdns;
    }

    /**
     * Gets the primary key from the database.
     */
    public long getPk() {
        return pk;
    }

    /**
     * Gets the entrance anchor position in normalized coordinates.
     */
    public BlockPos getEntrance() {
        return entrance;
    }

    /**
     * Gets the entrance facing direction (yaw).
     */
    public float getEntranceYaw() {
        return entranceYaw;
    }

    /**
     * Gets the maximum number of spawns allowed in this world.
     * @return the limit, or 0 for unlimited
     */
    public int getXWorld() {
        return xWorld;
    }

    /**
     * Gets the spawn rules for this building.
     */
    public List<SpawnRule> getRules() {
        return rules;
    }

    /**
     * Gets the bounding box of this building (dimensions).
     */
    public AABB getBounds() {
        return bounds;
    }

    /**
     * Gets the width (X size) of this building.
     */
    public int getSizeX() {
        return (int) (bounds.maxX - bounds.minX) + 1;
    }

    /**
     * Gets the height (Y size) of this building.
     */
    public int getSizeY() {
        return (int) (bounds.maxY - bounds.minY) + 1;
    }

    /**
     * Gets the depth (Z size) of this building.
     */
    public int getSizeZ() {
        return (int) (bounds.maxZ - bounds.minZ) + 1;
    }

    /**
     * Gets the current spawn count in this world.
     */
    public int getSpawnedCount() {
        return spawnedCount;
    }

    /**
     * Increments the spawn count for this building.
     */
    public void incrementSpawnCount() {
        spawnedCount++;
    }

    /**
     * Checks if this building can still be spawned (hasn't reached xWorld limit).
     * @return true if spawning is allowed
     */
    public boolean canSpawn() {
        return xWorld == 0 || spawnedCount < xWorld;
    }

    /**
     * Finds the first spawn rule that applies to the given biome.
     * @param biomeId the biome resource location (e.g., "minecraft:plains")
     * @return the matching rule, or null if no rule applies
     */
    public SpawnRule findRuleForBiome(String biomeId) {
        for (SpawnRule rule : rules) {
            if (rule.appliesToBiome(biomeId)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Resets the runtime spawn count.
     */
    public void resetSpawnCount() {
        spawnedCount = 0;
    }

    @Override
    public String toString() {
        return "SpawnableBuilding{rdns='" + rdns + "', pk=" + pk +
               ", size=[" + getSizeX() + "x" + getSizeY() + "x" + getSizeZ() + "]" +
               ", rules=" + rules.size() + ", spawned=" + spawnedCount + "/" + (xWorld == 0 ? "∞" : xWorld) + "}";
    }
}
