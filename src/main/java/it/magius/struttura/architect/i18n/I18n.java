package it.magius.struttura.architect.i18n;

import it.magius.struttura.architect.Architect;
import net.minecraft.server.level.ServerPlayer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Internationalization system for STRUTTURA: Architect.
 * Supports per-player translations based on Minecraft client settings.
 * Automatic fallback to English if a translation doesn't exist.
 * Uses BCP 47 language tags (e.g., "en-US", "it-IT").
 */
public class I18n {

    // Cache of translations: simple lang code -> (key -> value)
    // We use simple codes for file loading but BCP 47 for external APIs
    private static final Map<String, Map<String, String>> translations = new HashMap<>();

    private static boolean initialized = false;

    /**
     * Initializes the i18n system by loading available languages.
     */
    public static void init() {
        if (initialized) return;

        // Load translation files using simple codes
        loadLanguage("en");
        loadLanguage("it");

        initialized = true;
        Architect.LOGGER.info("I18n initialized with languages: {} (default: {})",
            LanguageUtils.SUPPORTED_LANGUAGES, LanguageUtils.DEFAULT_LANG);
    }

    /**
     * Loads a language file.
     */
    private static void loadLanguage(String lang) {
        String path = "/assets/architect/lang/" + lang + ".properties";

        try (InputStream is = I18n.class.getResourceAsStream(path)) {
            if (is == null) {
                Architect.LOGGER.warn("Language file not found: {}", path);
                return;
            }

            Properties props = new Properties();
            props.load(new InputStreamReader(is, StandardCharsets.UTF_8));

            Map<String, String> langMap = new HashMap<>();
            for (String key : props.stringPropertyNames()) {
                langMap.put(key, props.getProperty(key));
            }

            translations.put(lang, langMap);
            Architect.LOGGER.debug("Loaded {} translations for language: {}", langMap.size(), lang);

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to load language file: {}", path, e);
        }
    }

    /**
     * Checks if a language is available for UI translations.
     */
    public static boolean isLanguageAvailable(String lang) {
        // Accept both BCP 47 and simple codes
        String simple = LanguageUtils.toSimple(lang);
        return translations.containsKey(simple);
    }

    /**
     * Gets the default language code (BCP 47).
     */
    public static String getDefaultLanguage() {
        return LanguageUtils.DEFAULT_LANG;
    }

    /**
     * Gets the player's language code in BCP 47 format.
     */
    public static String getPlayerLanguage(ServerPlayer player) {
        return PlayerLanguageTracker.getInstance().getBcp47LanguageCode(player);
    }

    /**
     * Translates a key for a specific player.
     * Uses the player's Minecraft client language settings.
     *
     * @param player the player
     * @param key the translation key
     * @return the translated string
     */
    public static String translate(ServerPlayer player, String key) {
        if (!initialized) init();

        // Get BCP 47 code and convert to simple for file lookup
        String bcp47 = PlayerLanguageTracker.getInstance().getBcp47LanguageCode(player);
        String simple = LanguageUtils.toSimple(bcp47);
        return translateForLang(simple, key);
    }

    /**
     * Translates a key for a specific player with parameters.
     *
     * @param player the player
     * @param key the translation key
     * @param args parameters for placeholders {0}, {1}, etc.
     * @return the translated string with parameters substituted
     */
    public static String translate(ServerPlayer player, String key, Object... args) {
        String template = translate(player, key);
        return applyArgs(template, args);
    }

    /**
     * Translates a key for a specific language (simple code).
     */
    private static String translateForLang(String lang, String key) {
        // Try the requested language
        Map<String, String> langMap = translations.get(lang);
        if (langMap != null && langMap.containsKey(key)) {
            return langMap.get(key);
        }

        // Fallback to English
        String defaultSimple = LanguageUtils.toSimple(LanguageUtils.DEFAULT_LANG);
        if (!lang.equals(defaultSimple)) {
            Map<String, String> defaultMap = translations.get(defaultSimple);
            if (defaultMap != null && defaultMap.containsKey(key)) {
                return defaultMap.get(key);
            }
        }

        // Return the key if not found
        return key;
    }

    /**
     * Applies arguments to placeholders.
     */
    private static String applyArgs(String template, Object... args) {
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return template;
    }

    // ===== Legacy methods for compatibility (use default language) =====

    /**
     * Translates a key in the default language.
     * @deprecated Use translate(ServerPlayer, String) for per-player translation
     */
    @Deprecated
    public static String translate(String key) {
        if (!initialized) init();
        String defaultSimple = LanguageUtils.toSimple(LanguageUtils.DEFAULT_LANG);
        return translateForLang(defaultSimple, key);
    }

    /**
     * Translates a key with parameters in the default language.
     * @deprecated Use translate(ServerPlayer, String, Object...) for per-player translation
     */
    @Deprecated
    public static String translate(String key, Object... args) {
        return applyArgs(translate(key), args);
    }

    // ===== Shortcut methods =====

    /**
     * Shortcut for translate(player, key).
     */
    public static String tr(ServerPlayer player, String key) {
        return translate(player, key);
    }

    /**
     * Shortcut for translate(player, key, args).
     */
    public static String tr(ServerPlayer player, String key, Object... args) {
        return translate(player, key, args);
    }

    /**
     * Shortcut for translate(key) - default language.
     * @deprecated Use tr(ServerPlayer, String) for per-player translation
     */
    @Deprecated
    public static String tr(String key) {
        return translate(key);
    }

    /**
     * Shortcut for translate(key, args) - default language.
     * @deprecated Use tr(ServerPlayer, String, Object...) for per-player translation
     */
    @Deprecated
    public static String tr(String key, Object... args) {
        return translate(key, args);
    }
}
