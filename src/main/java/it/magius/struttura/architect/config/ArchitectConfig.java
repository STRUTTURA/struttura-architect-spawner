package it.magius.struttura.architect.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.magius.struttura.architect.Architect;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Configurazione del mod STRUTTURA.
 * Il file viene salvato in .minecraft/config/struttura.json
 */
public class ArchitectConfig {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private static final String CONFIG_FILE_NAME = "struttura.json";

    private static ArchitectConfig instance;

    // Proprietà di configurazione
    private String endpoint = "http://localhost:8881/struttura/v1";
    private String auth = "Bearer au-ak_21c2e6dda17ac3f34ddce58fc4ec0a11ee8193cf12e71d114fba6ebaa269e0b431e17de77ea8e959ffbe4e0d2866b8442ed4e8d75ecee1adf162f1ac2270cb59";
    private String apikey = "us-ak_385b96c7595ed54693f6367e5d1765a585e45d2bbd940ad3a40154510975440edc42c016dcc76cbce19e7e16a613e550b093c918f141cd66f9032f9523a347c8";
    private int requestTimeout = 60;

    // Wireframe rendering settings
    private int wireframeFadeStart = 10;   // Distanza inizio fade (blocchi)
    private int wireframeFadeEnd = 30;     // Distanza fine fade / non renderizzato (blocchi)

    // Overlay position settings (for in-game building info display)
    private String overlayAnchorV = "TOP";      // TOP, BOTTOM, VCENTER
    private String overlayAnchorH = "HCENTER";  // LEFT, RIGHT, HCENTER
    private int overlayOffsetX = 0;             // 0-100 (%)
    private int overlayOffsetY = 0;             // 0-100 (%)

    // Website URL (for API key requests, etc.)
    private String www = "https://struttura.magius.it";

    // Server-fetched settings (updated from /mod/settings endpoint)
    private Map<String, String> modOptionsDisclaimer = new HashMap<>();

    private ArchitectConfig() {}

    /**
     * Ottiene l'istanza singleton della configurazione.
     * Carica automaticamente dal file se non ancora caricata.
     */
    public static ArchitectConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * Carica la configurazione dal file.
     * Se il file non esiste, crea uno nuovo con i valori di default.
     */
    public static ArchitectConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                ArchitectConfig config = GSON.fromJson(reader, ArchitectConfig.class);
                Architect.LOGGER.info("Loaded config from {}", configPath);
                return config;
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to load config, using defaults", e);
            }
        }

        // Crea config con defaults e salvala
        ArchitectConfig config = new ArchitectConfig();
        config.save();
        return config;
    }

    /**
     * Salva la configurazione su file.
     */
    public void save() {
        Path configPath = getConfigPath();

        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
                Architect.LOGGER.info("Saved config to {}", configPath);
            }
        } catch (IOException e) {
            Architect.LOGGER.error("Failed to save config", e);
        }
    }

    /**
     * Ricarica la configurazione dal file.
     */
    public static void reload() {
        instance = load();
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    }

    // Getters
    public String getEndpoint() { return endpoint; }
    public String getAuth() { return auth; }
    public String getApikey() { return apikey; }
    public int getRequestTimeout() { return requestTimeout; }
    public int getWireframeFadeStart() { return wireframeFadeStart; }
    public int getWireframeFadeEnd() { return wireframeFadeEnd; }
    public String getOverlayAnchorV() { return overlayAnchorV; }
    public String getOverlayAnchorH() { return overlayAnchorH; }
    public int getOverlayOffsetX() { return overlayOffsetX; }
    public int getOverlayOffsetY() { return overlayOffsetY; }
    public Map<String, String> getModOptionsDisclaimer() { return modOptionsDisclaimer; }
    public String getWww() { return www; }

    /**
     * Gets the disclaimer text for a specific language.
     * Fallback order: exact match -> base language -> English (en-US or en) -> first available.
     */
    public String getDisclaimerForLanguage(String langCode) {
        if (modOptionsDisclaimer == null || modOptionsDisclaimer.isEmpty()) {
            return "";
        }
        // Try exact match first
        if (modOptionsDisclaimer.containsKey(langCode)) {
            return modOptionsDisclaimer.get(langCode);
        }
        // Try base language (e.g., "en" from "en-US")
        String baseLang = langCode.contains("-") ? langCode.split("-")[0] : langCode;
        if (modOptionsDisclaimer.containsKey(baseLang)) {
            return modOptionsDisclaimer.get(baseLang);
        }
        // Fallback to English (try en-US first, then en)
        if (modOptionsDisclaimer.containsKey("en-US")) {
            return modOptionsDisclaimer.get("en-US");
        }
        if (modOptionsDisclaimer.containsKey("en")) {
            return modOptionsDisclaimer.get("en");
        }
        // Return first available
        return modOptionsDisclaimer.values().iterator().next();
    }

    // Setters
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public void setAuth(String auth) { this.auth = auth; }
    public void setApikey(String apikey) { this.apikey = apikey; }
    public void setRequestTimeout(int requestTimeout) { this.requestTimeout = requestTimeout; }
    public void setOverlayAnchorV(String overlayAnchorV) { this.overlayAnchorV = overlayAnchorV; }
    public void setOverlayAnchorH(String overlayAnchorH) { this.overlayAnchorH = overlayAnchorH; }
    public void setOverlayOffsetX(int overlayOffsetX) { this.overlayOffsetX = Math.max(0, Math.min(100, overlayOffsetX)); }
    public void setOverlayOffsetY(int overlayOffsetY) { this.overlayOffsetY = Math.max(0, Math.min(100, overlayOffsetY)); }
    public void setModOptionsDisclaimer(Map<String, String> modOptionsDisclaimer) { this.modOptionsDisclaimer = modOptionsDisclaimer; }
    public void setWww(String www) { this.www = www; }
}
