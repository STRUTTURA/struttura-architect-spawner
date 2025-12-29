package it.magius.struttura.architect.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.magius.struttura.architect.Architect;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configurazione del mod Architect.
 * Il file viene salvato in .minecraft/config/architect.json
 */
public class ArchitectConfig {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private static final String CONFIG_FILE_NAME = "architect.json";

    private static ArchitectConfig instance;

    // Proprietà di configurazione
    private String endpoint = "http://localhost:8881/struttura/v1";
    private String auth = "Bearer au-ak_21c2e6dda17ac3f34ddce58fc4ec0a11ee8193cf12e71d114fba6ebaa269e0b431e17de77ea8e959ffbe4e0d2866b8442ed4e8d75ecee1adf162f1ac2270cb59";
    private String apikey = "us-ak_385b96c7595ed54693f6367e5d1765a585e45d2bbd940ad3a40154510975440edc42c016dcc76cbce19e7e16a613e550b093c918f141cd66f9032f9523a347c8";
    private int requestTimeout = 60;

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

    // Setters
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public void setAuth(String auth) { this.auth = auth; }
    public void setApikey(String apikey) { this.apikey = apikey; }
    public void setRequestTimeout(int requestTimeout) { this.requestTimeout = requestTimeout; }
}
