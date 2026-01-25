package it.magius.struttura.architect.ingame.model;

import it.magius.struttura.architect.i18n.LanguageUtils;

import java.util.Map;

/**
 * Information about an InGame list available for selection.
 * Used during initialization to show available lists to the user.
 */
public record InGameListInfo(
    String id,  // Can be numeric (e.g., "123") or alphanumeric (e.g., "most-popular" for virtual lists)
    Map<String, String> names,        // Localized names (lang code -> name)
    Map<String, String> descriptions, // Localized descriptions (lang code -> description)
    int buildingCount,
    boolean isPublic,
    boolean isVirtual,  // True for system-generated lists like "Most Popular Buildings"
    boolean isOwn,      // True if the list belongs to the current user
    String icon,        // Minecraft item ID for display (e.g., "minecraft:bell")
    String contentHash
) {
    /**
     * Gets the localized name for the specified language.
     * Falls back to English, then to first available value.
     * @param langCode the language code (e.g., "en_us", "it_it")
     */
    public String getLocalizedName(String langCode) {
        return LanguageUtils.getLocalizedText(names, langCode, "Unnamed List " + id);
    }

    /**
     * Gets the localized description for the specified language.
     * Falls back to English, then to first available value, or empty string.
     * @param langCode the language code (e.g., "en_us", "it_it")
     */
    public String getLocalizedDescription(String langCode) {
        return LanguageUtils.getLocalizedText(descriptions, langCode, "");
    }

    @Override
    public String toString() {
        String displayName = names != null && !names.isEmpty()
            ? names.values().stream().findFirst().orElse("unnamed")
            : "unnamed";
        return "InGameListInfo{id=" + id + ", name='" + displayName + "', buildings=" + buildingCount + "}";
    }
}
