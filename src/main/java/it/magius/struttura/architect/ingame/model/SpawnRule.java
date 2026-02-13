package it.magius.struttura.architect.ingame.model;

import java.util.List;

/**
 * Defines spawn rules for a building in specific biomes.
 * Each rule specifies conditions under which a building can spawn.
 */
public class SpawnRule {

    private final List<String> biomes;      // e.g., ["minecraft:plains", "minecraft:forest"]
    private final double percentage;         // 0.0-1.0, probability of spawn in this biome
    private final PositionType type;         // How to position the building
    private final int y1;                    // Minimum Y level
    private final int y2;                    // Maximum Y level
    private final int margin;                // Safety margin around the building (default 5)
    private final EnsureBoundsMode ensureBoundsMode; // How to clear spawn area before placing

    public SpawnRule(List<String> biomes, double percentage, PositionType type, int y1, int y2, int margin, EnsureBoundsMode ensureBoundsMode) {
        this.biomes = biomes != null ? List.copyOf(biomes) : List.of();
        this.percentage = Math.max(0.0, Math.min(1.0, percentage));
        this.type = type != null ? type : PositionType.ON_GROUND;
        this.y1 = y1;
        this.y2 = y2;
        this.margin = margin > 0 ? margin : 5;
        this.ensureBoundsMode = ensureBoundsMode != null ? ensureBoundsMode : EnsureBoundsMode.NONE;
    }

    /**
     * Creates a default spawn rule for buildings without any rules defined.
     * Default: 100% chance, ON_GROUND positioning, Y range 60-100, applies to all biomes.
     */
    public static SpawnRule createDefault() {
        return new SpawnRule(
            List.of(),              // Empty list = applies to all biomes
            1.0,                    // 100% spawn chance
            PositionType.ON_GROUND, // Place on ground surface
            60,                     // Min Y level
            100,                    // Max Y level
            5,                      // Default margin
            EnsureBoundsMode.NONE   // Don't clear bounds by default
        );
    }

    /**
     * Gets the list of biome IDs this rule applies to.
     */
    public List<String> getBiomes() {
        return biomes;
    }

    /**
     * Gets the spawn probability (0.0-1.0) for this rule.
     */
    public double getPercentage() {
        return percentage;
    }

    /**
     * Gets the position type for placing the building.
     */
    public PositionType getType() {
        return type;
    }

    /**
     * Gets the minimum Y level for spawn.
     */
    public int getY1() {
        return y1;
    }

    /**
     * Gets the maximum Y level for spawn.
     */
    public int getY2() {
        return y2;
    }

    /**
     * Gets the safety margin around the building bounds.
     */
    public int getMargin() {
        return margin;
    }

    /**
     * Gets the ensure bounds mode for this rule.
     */
    public EnsureBoundsMode getEnsureBoundsMode() {
        return ensureBoundsMode;
    }

    /**
     * Checks if this rule applies to the given biome.
     * Supports both real biomes (e.g., "minecraft:plains") and virtual biomes
     * (e.g., "struttura:all", "struttura:overworld_all").
     *
     * @param biomeId the biome resource location (e.g., "minecraft:plains")
     * @return true if this rule applies to the biome
     */
    public boolean appliesToBiome(String biomeId) {
        if (biomes.isEmpty()) {
            // Empty biome list means this rule applies to all biomes
            return true;
        }

        for (String ruleBiome : biomes) {
            // Check if it's a virtual biome that expands to multiple real biomes
            if (VirtualBiomes.isVirtual(ruleBiome)) {
                if (VirtualBiomes.matches(ruleBiome, biomeId)) {
                    return true;
                }
            } else {
                // Direct match with real biome
                if (ruleBiome.equals(biomeId)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public String toString() {
        return "SpawnRule{biomes=" + biomes + ", percentage=" + percentage +
               ", type=" + type + ", y=[" + y1 + "," + y2 + "], margin=" + margin +
               ", ensureBoundsMode=" + ensureBoundsMode + "}";
    }
}
