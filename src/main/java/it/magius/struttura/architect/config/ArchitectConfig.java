package it.magius.struttura.architect.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.ingame.InGameManager;
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
    private String endpoint = "https://api-struttura.magius.it/v1";
    private String auth = "Bearer us-ak_bba6abf3c975833465bf8ac99d9ce3ddcd70be0d6c61fcb04f4a9b5043159d63078908460af3cac585aacdc916cff934785e1709e11b7b94e7d7d46382612a0d";
    private String apikey = "";
    private int requestTimeout = 60;

    // Wireframe rendering settings
    private int wireframeFadeStart = 10;   // Distanza inizio fade (blocchi)
    private int wireframeFadeEnd = 30;     // Distanza fine fade / non renderizzato (blocchi)

    // Overlay position settings (for in-game building info display)
    private String overlayAnchorV = "TOP";      // TOP, BOTTOM, VCENTER
    private String overlayAnchorH = "HCENTER";  // LEFT, RIGHT, HCENTER
    private int overlayOffsetX = 0;             // 0-100 (%)
    private int overlayOffsetY = 5;             // 0-100 (%)

    // Website URL (for API key requests, etc.)
    private String www = "https://struttura.magius.it";

    // Server-fetched settings (updated from /mod/settings endpoint)
    private Map<String, String> modOptionsDisclaimer = new HashMap<>();

    // InGame spawner settings (for dedicated servers)
    private String inGameListId = null;  // If set, auto-initialize InGame with this list (can be numeric or alphanumeric)

    // InGame cache settings
    private int listRefreshIntervalMinutes = 60;  // How often to check for list updates (minutes)
    private int maxCachedNbt = 25;                // Maximum NBT files to keep in memory

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

        // Crea config con defaults e salvala (senza validazione API key al primo avvio)
        ArchitectConfig config = new ArchitectConfig();
        config.saveInternal(false);
        return config;
    }

    /**
     * Salva la configurazione su file.
     */
    public void save() {
        saveInternal(true);
    }

    /**
     * Internal save method with optional API key validation.
     * @param validateApiKey whether to validate API key and update userId
     */
    private void saveInternal(boolean validateApiKey) {
        Path configPath = getConfigPath();

        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
                Architect.LOGGER.info("Saved config to {}", configPath);
            }

            // Validate API key and update cached userId if in a game
            if (validateApiKey && apikey != null && !apikey.isEmpty()) {
                validateApiKeyAndUpdateUserId();
            }
        } catch (IOException e) {
            Architect.LOGGER.error("Failed to save config", e);
        }
    }

    /**
     * Validates the API key with the server and updates cached userId.
     * Called when config is saved to ensure userId is current.
     */
    private void validateApiKeyAndUpdateUserId() {
        ApiClient.validateApiKey(response -> {
            if (response.success() && response.userId() > 0) {
                InGameManager manager = InGameManager.getInstance();
                if (manager != null && manager.isReady()) {
                    long currentUserId = manager.getCurrentUserId();
                    if (currentUserId != response.userId()) {
                        manager.getStorage().getState().setCurrentUserId(response.userId());
                        manager.getStorage().save();
                        Architect.LOGGER.info("Config saved: userId updated to {}", response.userId());
                    }
                }
            } else if (!response.success()) {
                Architect.LOGGER.warn("API key validation failed: {}", response.message());
            }
        });
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
    public String getInGameListId() { return inGameListId; }
    public int getListRefreshIntervalMinutes() { return listRefreshIntervalMinutes; }
    public int getMaxCachedNbt() { return maxCachedNbt; }

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
    public void setInGameListId(String inGameListId) { this.inGameListId = inGameListId; }
    public void setListRefreshIntervalMinutes(int minutes) { this.listRefreshIntervalMinutes = Math.max(1, minutes); }
    public void setMaxCachedNbt(int max) { this.maxCachedNbt = Math.max(1, max); }
}
