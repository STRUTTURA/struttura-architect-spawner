package it.magius.struttura.architect.ingame.model;

import it.magius.struttura.architect.i18n.LanguageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

/**
 * Represents a building that can be spawned in the world via the InGame system.
 * Contains all metadata needed to evaluate and execute spawning.
 */
public class SpawnableBuilding {

    private final String rdns;              // Reverse DNS identifier (e.g., "it.magius.pip.home")
    private final long pk;                  // Primary key from the database
    private final String hash;              // Content hash (SHA256) for cache validation
    private final String author;            // Author nickname
    private final BlockPos entrance;        // Entrance anchor position (normalized coordinates)
    private final float entranceYaw;        // Entrance facing direction
    private final int xWorld;               // Max spawns in this world (0 = unlimited)
    private final List<SpawnRule> rules;    // Spawn rules per biome
    private final AABB bounds;              // Bounding box (dimensions, origin at 0,0,0)
    private final Map<String, String> names;        // Localized names (lang code -> name)
    private final Map<String, String> descriptions; // Localized descriptions (lang code -> description)
    private final String ensureBounds;      // Pre-spawn bounds fill mode ("none" or "air")

    // Runtime state (not persisted)
    private int spawnedCount = 0;
    private boolean downloadFailed = false;  // True if NBT download failed, applies 20% penalty

    public SpawnableBuilding(String rdns, long pk, String hash, String author, BlockPos entrance, float entranceYaw,
                             int xWorld, List<SpawnRule> rules, AABB bounds,
                             Map<String, String> names, Map<String, String> descriptions, String ensureBounds) {
        this.rdns = rdns;
        this.pk = pk;
        this.hash = hash;
        this.author = author != null ? author : "";
        this.entrance = entrance;
        this.entranceYaw = entranceYaw;
        this.xWorld = xWorld;
        this.rules = rules != null ? List.copyOf(rules) : List.of();
        this.bounds = bounds;
        this.names = names != null ? Map.copyOf(names) : Map.of();
        this.descriptions = descriptions != null ? Map.copyOf(descriptions) : Map.of();
        this.ensureBounds = ensureBounds != null ? ensureBounds : "none";
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
     * Gets the content hash (SHA256) for cache validation.
     */
    public String getHash() {
        return hash;
    }

    /**
     * Gets the author nickname.
     */
    public String getAuthor() {
        return author;
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
     * Gets all localized names.
     */
    public Map<String, String> getNames() {
        return names;
    }

    /**
     * Gets all localized descriptions.
     */
    public Map<String, String> getDescriptions() {
        return descriptions;
    }

    /**
     * Gets the pre-spawn bounds fill mode.
     * @return "none" (default) or "air"
     */
    public String getEnsureBounds() {
        return ensureBounds;
    }

    /**
     * Gets the localized name for the specified language.
     * Falls back to English, then to RDNS if no translation is available.
     * Uses centralized fallback logic from LanguageUtils.
     * @param langCode the language code (e.g., "en_us", "it_it")
     */
    public String getLocalizedName(String langCode) {
        return LanguageUtils.getLocalizedText(names, langCode, rdns);
    }

    /**
     * Gets the localized description for the specified language.
     * Falls back to English, then to empty string if no translation is available.
     * Uses centralized fallback logic from LanguageUtils.
     * @param langCode the language code (e.g., "en_us", "it_it")
     */
    public String getLocalizedDescription(String langCode) {
        return LanguageUtils.getLocalizedText(descriptions, langCode, "");
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

    /**
     * Marks this building as having failed to download.
     * Applies a 20% penalty to spawn probability until the list is refreshed.
     */
    public void markDownloadFailed() {
        this.downloadFailed = true;
    }

    /**
     * Clears the download failure flag.
     * Called when the list is refreshed or building is updated.
     */
    public void clearDownloadFailure() {
        this.downloadFailed = false;
    }

    /**
     * Checks if this building has a download failure penalty.
     */
    public boolean hasDownloadFailure() {
        return downloadFailed;
    }

    /**
     * Gets the effective spawn percentage for a rule, accounting for download failure penalty.
     * If download failed, the percentage is reduced by 20%.
     * @param rule the spawn rule
     * @return the effective percentage (0.0-1.0)
     */
    public double getEffectivePercentage(SpawnRule rule) {
        double base = rule.getPercentage();
        if (downloadFailed) {
            return base * 0.8; // 20% penalty
        }
        return base;
    }

    @Override
    public String toString() {
        return "SpawnableBuilding{rdns='" + rdns + "', pk=" + pk +
               ", size=[" + getSizeX() + "x" + getSizeY() + "x" + getSizeZ() + "]" +
               ", rules=" + rules.size() + ", spawned=" + spawnedCount + "/" + (xWorld == 0 ? "∞" : xWorld) + "}";
    }
}
