package it.magius.struttura.architect.ingame.model;

import java.util.Set;

/**
 * Virtual biomes that aggregate multiple real Minecraft biomes.
 * These are defined with the "struttura:" namespace and expand to groups of real biomes.
 */
public final class VirtualBiomes {

    private VirtualBiomes() {}

    // Virtual biome IDs
    public static final String ALL = "struttura:all";
    public static final String OVERWORLD_ALL = "struttura:overworld_all";
    public static final String OCEANS_ALL = "struttura:oceans_all";
    public static final String NETHER_ALL = "struttura:nether_all";
    public static final String END_ALL = "struttura:end_all";

    // Biome sets by category
    private static final Set<String> OVERWORLD_BIOMES = Set.of(
        // Plains & Forests
        "minecraft:plains",
        "minecraft:sunflower_plains",
        "minecraft:forest",
        "minecraft:flower_forest",
        "minecraft:birch_forest",
        "minecraft:old_growth_birch_forest",
        "minecraft:dark_forest",
        // Taiga
        "minecraft:taiga",
        "minecraft:old_growth_pine_taiga",
        "minecraft:old_growth_spruce_taiga",
        "minecraft:snowy_taiga",
        // Snowy
        "minecraft:snowy_plains",
        "minecraft:ice_spikes",
        "minecraft:frozen_peaks",
        // Mountains
        "minecraft:jagged_peaks",
        "minecraft:stony_peaks",
        "minecraft:meadow",
        "minecraft:cherry_grove",
        "minecraft:grove",
        "minecraft:snowy_slopes",
        "minecraft:windswept_hills",
        "minecraft:windswept_gravelly_hills",
        "minecraft:windswept_forest",
        // Savanna
        "minecraft:savanna",
        "minecraft:savanna_plateau",
        "minecraft:windswept_savanna",
        // Jungle
        "minecraft:jungle",
        "minecraft:sparse_jungle",
        "minecraft:bamboo_jungle",
        // Desert & Badlands
        "minecraft:desert",
        "minecraft:badlands",
        "minecraft:wooded_badlands",
        "minecraft:eroded_badlands",
        // Swamp & Beach
        "minecraft:swamp",
        "minecraft:mangrove_swamp",
        "minecraft:beach",
        "minecraft:snowy_beach",
        "minecraft:stony_shore",
        "minecraft:mushroom_fields",
        // River
        "minecraft:river",
        "minecraft:frozen_river"
    );

    private static final Set<String> OCEAN_BIOMES = Set.of(
        "minecraft:ocean",
        "minecraft:deep_ocean",
        "minecraft:warm_ocean",
        "minecraft:lukewarm_ocean",
        "minecraft:deep_lukewarm_ocean",
        "minecraft:cold_ocean",
        "minecraft:deep_cold_ocean",
        "minecraft:frozen_ocean",
        "minecraft:deep_frozen_ocean"
    );

    private static final Set<String> NETHER_BIOMES = Set.of(
        "minecraft:nether_wastes",
        "minecraft:soul_sand_valley",
        "minecraft:crimson_forest",
        "minecraft:warped_forest",
        "minecraft:basalt_deltas"
    );

    private static final Set<String> END_BIOMES = Set.of(
        "minecraft:the_end",
        "minecraft:end_highlands",
        "minecraft:end_midlands",
        "minecraft:small_end_islands",
        "minecraft:end_barrens"
    );

    /**
     * Checks if a virtual biome ID matches a real biome ID.
     *
     * @param virtualBiomeId the virtual biome ID (e.g., "struttura:all")
     * @param realBiomeId the real Minecraft biome ID (e.g., "minecraft:plains")
     * @return true if the virtual biome includes the real biome
     */
    public static boolean matches(String virtualBiomeId, String realBiomeId) {
        if (virtualBiomeId == null || realBiomeId == null) {
            return false;
        }

        return switch (virtualBiomeId) {
            case ALL -> true; // All biomes match
            case OVERWORLD_ALL -> OVERWORLD_BIOMES.contains(realBiomeId);
            case OCEANS_ALL -> OCEAN_BIOMES.contains(realBiomeId);
            case NETHER_ALL -> NETHER_BIOMES.contains(realBiomeId);
            case END_ALL -> END_BIOMES.contains(realBiomeId);
            default -> false; // Unknown virtual biome
        };
    }

    /**
     * Checks if a biome ID is a virtual biome (starts with "struttura:").
     *
     * @param biomeId the biome ID to check
     * @return true if it's a virtual biome
     */
    public static boolean isVirtual(String biomeId) {
        return biomeId != null && biomeId.startsWith("struttura:");
    }
}
