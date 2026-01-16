package it.magius.struttura.architect.i18n;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for BCP 47 language code handling.
 * Supports conversion between legacy 2-letter codes and full BCP 47 tags.
 */
public class LanguageUtils {

    // Supported BCP 47 language codes for content (titles, descriptions)
    public static final String LANG_EN = "en-US";
    public static final String LANG_IT = "it-IT";
    public static final String LANG_DE = "de-DE";
    public static final String LANG_FR = "fr-FR";
    public static final String LANG_ES = "es-ES";
    public static final String LANG_PT = "pt-BR";
    public static final String LANG_NL = "nl-NL";
    public static final String LANG_PL = "pl-PL";
    public static final String LANG_RU = "ru-RU";
    public static final String LANG_UK = "uk-UA";
    public static final String LANG_ZH = "zh-Hans";  // Simplified Chinese
    public static final String LANG_JA = "ja-JP";    // Japanese
    public static final String LANG_KO = "ko-KR";    // Korean

    public static final String DEFAULT_LANG = LANG_EN;

    /**
     * Language info record for GUI display.
     */
    public record LangInfo(String bcp47, String displayName) {}

    /**
     * Ordered list of supported languages for GUI display.
     */
    public static final List<LangInfo> SUPPORTED_LANGUAGES_LIST = List.of(
        new LangInfo(LANG_EN, "English"),
        new LangInfo(LANG_IT, "Italiano"),
        new LangInfo(LANG_DE, "Deutsch"),
        new LangInfo(LANG_FR, "Français"),
        new LangInfo(LANG_ES, "Español"),
        new LangInfo(LANG_PT, "Português"),
        new LangInfo(LANG_NL, "Nederlands"),
        new LangInfo(LANG_PL, "Polski"),
        new LangInfo(LANG_RU, "Русский"),
        new LangInfo(LANG_UK, "Українська"),
        new LangInfo(LANG_ZH, "简体中文"),
        new LangInfo(LANG_JA, "日本語"),
        new LangInfo(LANG_KO, "한국어")
    );

    // Set of all supported BCP 47 language codes
    public static final Set<String> SUPPORTED_LANGUAGES = Set.of(
        LANG_EN, LANG_IT, LANG_DE, LANG_FR, LANG_ES,
        LANG_PT, LANG_NL, LANG_PL, LANG_RU, LANG_UK,
        LANG_ZH, LANG_JA, LANG_KO
    );

    // Mapping from legacy 2-letter codes to BCP 47
    private static final Map<String, String> LEGACY_TO_BCP47;
    static {
        Map<String, String> map = new HashMap<>();
        map.put("en", LANG_EN);
        map.put("it", LANG_IT);
        map.put("de", LANG_DE);
        map.put("fr", LANG_FR);
        map.put("es", LANG_ES);
        map.put("pt", LANG_PT);
        map.put("nl", LANG_NL);
        map.put("pl", LANG_PL);
        map.put("ru", LANG_RU);
        map.put("uk", LANG_UK);
        map.put("zh", LANG_ZH);
        map.put("ja", LANG_JA);
        map.put("ko", LANG_KO);
        LEGACY_TO_BCP47 = Map.copyOf(map);
    }

    // Mapping from BCP 47 to simple 2-letter codes (for UI file loading)
    private static final Map<String, String> BCP47_TO_SIMPLE;
    static {
        BCP47_TO_SIMPLE = new HashMap<>();
        BCP47_TO_SIMPLE.put(LANG_EN, "en");
        BCP47_TO_SIMPLE.put(LANG_IT, "it");
        BCP47_TO_SIMPLE.put(LANG_DE, "de");
        BCP47_TO_SIMPLE.put(LANG_FR, "fr");
        BCP47_TO_SIMPLE.put(LANG_ES, "es");
        BCP47_TO_SIMPLE.put(LANG_PT, "pt");
        BCP47_TO_SIMPLE.put(LANG_NL, "nl");
        BCP47_TO_SIMPLE.put(LANG_PL, "pl");
        BCP47_TO_SIMPLE.put(LANG_RU, "ru");
        BCP47_TO_SIMPLE.put(LANG_UK, "uk");
        BCP47_TO_SIMPLE.put(LANG_ZH, "zh");
        BCP47_TO_SIMPLE.put(LANG_JA, "ja");
        BCP47_TO_SIMPLE.put(LANG_KO, "ko");
    }

    /**
     * Converts a Minecraft language code (e.g., "en_us", "it_it") to BCP 47 format.
     *
     * @param minecraftCode the Minecraft language code
     * @return the BCP 47 language tag
     */
    public static String fromMinecraft(String minecraftCode) {
        if (minecraftCode == null || minecraftCode.isEmpty()) {
            return DEFAULT_LANG;
        }

        // Minecraft uses format "xx_yy" (e.g., en_us, it_it, de_de)
        String lower = minecraftCode.toLowerCase();

        // Direct mappings for known codes
        return switch (lower) {
            case "en_us", "en_gb", "en_au", "en_ca", "en_nz" -> LANG_EN;
            case "it_it" -> LANG_IT;
            case "de_de", "de_at", "de_ch" -> LANG_DE;
            case "fr_fr", "fr_ca", "fr_be" -> LANG_FR;
            case "es_es", "es_mx", "es_ar" -> LANG_ES;
            case "pt_br", "pt_pt" -> LANG_PT;
            case "nl_nl", "nl_be" -> LANG_NL;
            case "pl_pl" -> LANG_PL;
            case "ru_ru" -> LANG_RU;
            case "uk_ua" -> LANG_UK;
            case "zh_cn", "zh_tw" -> LANG_ZH;  // Simplified and Traditional Chinese map to zh-Hans
            case "ja_jp" -> LANG_JA;
            case "ko_kr" -> LANG_KO;
            default -> {
                // Try to extract the language part and map it
                String langPart = lower.contains("_") ? lower.split("_")[0] : lower;
                yield LEGACY_TO_BCP47.getOrDefault(langPart, DEFAULT_LANG);
            }
        };
    }

    /**
     * Converts a legacy 2-letter code to BCP 47 format.
     * If the code is already BCP 47, returns it as-is.
     *
     * @param code the language code (either legacy or BCP 47)
     * @return the BCP 47 language tag
     */
    public static String toBcp47(String code) {
        if (code == null || code.isEmpty()) {
            return DEFAULT_LANG;
        }

        // Already BCP 47 format (contains hyphen)
        if (code.contains("-")) {
            return SUPPORTED_LANGUAGES.contains(code) ? code : DEFAULT_LANG;
        }

        // Legacy 2-letter code
        return LEGACY_TO_BCP47.getOrDefault(code.toLowerCase(), DEFAULT_LANG);
    }

    /**
     * Gets the simple 2-letter code from a BCP 47 tag.
     * Used for loading translation files.
     *
     * @param bcp47 the BCP 47 language tag
     * @return the simple 2-letter code
     */
    public static String toSimple(String bcp47) {
        if (bcp47 == null || bcp47.isEmpty()) {
            return "en";
        }

        // If already simple, return as-is
        if (!bcp47.contains("-")) {
            return bcp47.toLowerCase();
        }

        return BCP47_TO_SIMPLE.getOrDefault(bcp47, "en");
    }

    /**
     * Checks if a language code is valid (either legacy or BCP 47).
     *
     * @param code the language code
     * @return true if valid
     */
    public static boolean isValid(String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }

        // BCP 47 format
        if (code.contains("-")) {
            return SUPPORTED_LANGUAGES.contains(code);
        }

        // Legacy format
        return LEGACY_TO_BCP47.containsKey(code.toLowerCase());
    }

    /**
     * Migrates a map with legacy language keys to BCP 47 keys.
     * Used when loading/saving construction metadata.
     *
     * @param original the original map with possibly legacy keys
     * @return a new map with BCP 47 keys
     */
    public static Map<String, String> migrateKeys(Map<String, String> original) {
        if (original == null || original.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, String> migrated = new HashMap<>();
        for (Map.Entry<String, String> entry : original.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value == null || value.isEmpty()) {
                continue;
            }

            // Convert legacy key to BCP 47
            String bcp47Key = toBcp47(key);
            migrated.put(bcp47Key, value);
        }

        return migrated;
    }

    /**
     * Gets the display name for a language code.
     *
     * @param code the BCP 47 language code
     * @return the display name
     */
    public static String getDisplayName(String code) {
        for (LangInfo lang : SUPPORTED_LANGUAGES_LIST) {
            if (lang.bcp47().equals(code)) {
                return lang.displayName();
            }
        }
        return code;
    }

    /**
     * Gets all supported BCP 47 language codes.
     *
     * @return set of supported codes
     */
    public static Set<String> getSupportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }
}
