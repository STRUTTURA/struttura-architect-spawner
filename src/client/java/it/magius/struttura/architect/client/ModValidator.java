package it.magius.struttura.architect.client;

import it.magius.struttura.architect.model.ModInfo;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Utility class for validating mod requirements on client side.
 * Checks which required mods are missing and calculates impact.
 */
public class ModValidator {

    /**
     * Returns a map of missing mods with their info.
     *
     * @param requiredMods the mods required by the construction
     * @return map of modId -> ModInfo for mods that are not loaded
     */
    public static Map<String, ModInfo> getMissingMods(Map<String, ModInfo> requiredMods) {
        Map<String, ModInfo> missing = new HashMap<>();

        if (requiredMods == null) {
            return missing;
        }

        for (Map.Entry<String, ModInfo> entry : requiredMods.entrySet()) {
            if (!FabricLoader.getInstance().isModLoaded(entry.getKey())) {
                missing.put(entry.getKey(), entry.getValue());
            }
        }

        return missing;
    }

    /**
     * Checks if all required mods are loaded.
     *
     * @param requiredMods the mods required by the construction
     * @return true if all required mods are present
     */
    public static boolean hasAllMods(Map<String, ModInfo> requiredMods) {
        return getMissingMods(requiredMods).isEmpty();
    }

    /**
     * Calculates the total number of blocks that will become air due to missing mods.
     *
     * @param missingMods the map of missing mods with their info
     * @return total block count from missing mods
     */
    public static int getTotalMissingBlocks(Map<String, ModInfo> missingMods) {
        if (missingMods == null) {
            return 0;
        }

        return missingMods.values().stream()
            .mapToInt(ModInfo::getBlockCount)
            .sum();
    }

    /**
     * Calculates the total number of mobs that will be lost due to missing mods.
     *
     * @param missingMods the map of missing mods with their info
     * @return total mobs count from missing mods
     */
    public static int getTotalMissingMobs(Map<String, ModInfo> missingMods) {
        if (missingMods == null) {
            return 0;
        }

        return missingMods.values().stream()
            .mapToInt(ModInfo::getMobsCount)
            .sum();
    }

    /**
     * Gets the installed version of a mod.
     *
     * @param modId the mod ID to check
     * @return the version string, or null if not installed
     */
    public static String getInstalledVersion(String modId) {
        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(modId);
        return container.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    /**
     * Gets a map of installed mod versions for the given required mods.
     *
     * @param requiredMods the mods required by the list
     * @return map of modId -> installed version (null value if not installed)
     */
    public static Map<String, String> getInstalledVersions(Map<String, ModInfo> requiredMods) {
        Map<String, String> versions = new HashMap<>();

        if (requiredMods == null) {
            return versions;
        }

        for (String modId : requiredMods.keySet()) {
            versions.put(modId, getInstalledVersion(modId));
        }

        return versions;
    }
}
