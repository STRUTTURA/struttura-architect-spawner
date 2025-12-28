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
 * Sistema di internazionalizzazione per STRUTTURA: Architect.
 * Supporta traduzioni per-player basate sulle impostazioni client di Minecraft.
 * Fallback automatico a inglese se una traduzione non esiste.
 */
public class I18n {

    private static final String DEFAULT_LANG = "en";

    // Cache delle traduzioni: lang -> (key -> value)
    private static final Map<String, Map<String, String>> translations = new HashMap<>();

    // Lingue caricate
    private static boolean initialized = false;

    /**
     * Inizializza il sistema i18n caricando le lingue disponibili.
     */
    public static void init() {
        if (initialized) return;

        loadLanguage("en");
        loadLanguage("it");

        initialized = true;
        Architect.LOGGER.info("I18n initialized with languages: en, it (default: {})", DEFAULT_LANG);
    }

    /**
     * Carica un file di lingua.
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
     * Verifica se una lingua è disponibile.
     */
    public static boolean isLanguageAvailable(String lang) {
        return translations.containsKey(lang);
    }

    /**
     * Ottiene la lingua di default.
     */
    public static String getDefaultLanguage() {
        return DEFAULT_LANG;
    }

    /**
     * Traduce una chiave per un giocatore specifico.
     * Usa la lingua del client Minecraft del giocatore.
     *
     * @param player il giocatore
     * @param key la chiave di traduzione
     * @return la stringa tradotta
     */
    public static String translate(ServerPlayer player, String key) {
        if (!initialized) init();

        String lang = PlayerLanguageTracker.getInstance().getSimpleLanguageCode(player);
        return translateForLang(lang, key);
    }

    /**
     * Traduce una chiave per un giocatore specifico con parametri.
     *
     * @param player il giocatore
     * @param key la chiave di traduzione
     * @param args i parametri per i placeholder {0}, {1}, ecc.
     * @return la stringa tradotta con i parametri sostituiti
     */
    public static String translate(ServerPlayer player, String key, Object... args) {
        String template = translate(player, key);
        return applyArgs(template, args);
    }

    /**
     * Traduce una chiave per una lingua specifica.
     */
    private static String translateForLang(String lang, String key) {
        // Prova la lingua richiesta
        Map<String, String> langMap = translations.get(lang);
        if (langMap != null && langMap.containsKey(key)) {
            return langMap.get(key);
        }

        // Fallback a inglese
        if (!lang.equals(DEFAULT_LANG)) {
            Map<String, String> defaultMap = translations.get(DEFAULT_LANG);
            if (defaultMap != null && defaultMap.containsKey(key)) {
                return defaultMap.get(key);
            }
        }

        // Ritorna la chiave se non trovata
        return key;
    }

    /**
     * Applica gli argomenti ai placeholder.
     */
    private static String applyArgs(String template, Object... args) {
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return template;
    }

    // ===== Metodi legacy per compatibilità (usano lingua default) =====

    /**
     * Traduce una chiave nella lingua di default.
     * @deprecated Usa translate(ServerPlayer, String) per traduzione per-player
     */
    @Deprecated
    public static String translate(String key) {
        if (!initialized) init();
        return translateForLang(DEFAULT_LANG, key);
    }

    /**
     * Traduce una chiave con parametri nella lingua di default.
     * @deprecated Usa translate(ServerPlayer, String, Object...) per traduzione per-player
     */
    @Deprecated
    public static String translate(String key, Object... args) {
        return applyArgs(translate(key), args);
    }

    // ===== Shortcut methods =====

    /**
     * Shortcut per translate(player, key).
     */
    public static String tr(ServerPlayer player, String key) {
        return translate(player, key);
    }

    /**
     * Shortcut per translate(player, key, args).
     */
    public static String tr(ServerPlayer player, String key, Object... args) {
        return translate(player, key, args);
    }

    /**
     * Shortcut per translate(key) - lingua default.
     * @deprecated Usa tr(ServerPlayer, String) per traduzione per-player
     */
    @Deprecated
    public static String tr(String key) {
        return translate(key);
    }

    /**
     * Shortcut per translate(key, args) - lingua default.
     * @deprecated Usa tr(ServerPlayer, String, Object...) per traduzione per-player
     */
    @Deprecated
    public static String tr(String key, Object... args) {
        return translate(key, args);
    }
}
