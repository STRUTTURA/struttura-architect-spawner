package it.magius.struttura.architect.ingame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.magius.struttura.architect.Architect;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handles persistence of InGame state to the world directory.
 * State is saved in: <world>/struttura/ingame.json
 */
public class InGameStorage {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private static final String INGAME_FILE = "ingame.json";
    private static final String STRUTTURA_DIR = "struttura";

    private final Path worldPath;
    private InGameState state;

    public InGameStorage(Path worldPath) {
        this.worldPath = worldPath;
        this.state = new InGameState();
    }

    /**
     * Gets the current InGame state.
     */
    public InGameState getState() {
        return state;
    }

    /**
     * Loads the InGame state from disk.
     * If the file doesn't exist, returns a fresh uninitialized state.
     */
    public void load() {
        Path filePath = getFilePath();

        if (!Files.exists(filePath)) {
            Architect.LOGGER.debug("No ingame.json found, using fresh state");
            state = new InGameState();
            return;
        }

        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

            state = new InGameState();

            if (json.has("initialized")) {
                state.setInitialized(json.get("initialized").getAsBoolean());
            }

            if (json.has("declined")) {
                state.setDeclined(json.get("declined").getAsBoolean());
            }

            if (json.has("listLocked")) {
                state.setListLocked(json.get("listLocked").getAsBoolean());
            }

            if (json.has("listId") && !json.get("listId").isJsonNull()) {
                // Handle both numeric and string IDs (virtual lists have alphanumeric IDs)
                var listIdElement = json.get("listId");
                if (listIdElement.isJsonPrimitive()) {
                    state.setListId(listIdElement.getAsString());
                }
            }

            if (json.has("listName") && !json.get("listName").isJsonNull()) {
                state.setListName(json.get("listName").getAsString());
            }

            if (json.has("authType") && !json.get("authType").isJsonNull()) {
                try {
                    state.setAuthType(InGameState.AuthType.valueOf(json.get("authType").getAsString()));
                } catch (IllegalArgumentException e) {
                    Architect.LOGGER.warn("Invalid authType in ingame.json: {}", json.get("authType").getAsString());
                }
            }

            if (json.has("worldSeed") && !json.get("worldSeed").isJsonNull()) {
                state.setWorldSeed(json.get("worldSeed").getAsString());
            }

            if (json.has("downloadsCompleted")) {
                state.setDownloadsCompleted(json.get("downloadsCompleted").getAsBoolean());
            }

            Architect.LOGGER.info("Loaded InGame state: {}", state);

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to load ingame.json, using fresh state", e);
            state = new InGameState();
        }
    }

    /**
     * Saves the current InGame state to disk.
     */
    public void save() {
        Path filePath = getFilePath();

        try {
            // Ensure directory exists
            Files.createDirectories(filePath.getParent());

            JsonObject json = new JsonObject();
            json.addProperty("initialized", state.isInitialized());
            json.addProperty("declined", state.isDeclined());
            json.addProperty("listLocked", state.isListLocked());

            if (state.getListId() != null) {
                json.addProperty("listId", state.getListId());
            }

            if (state.getListName() != null) {
                json.addProperty("listName", state.getListName());
            }

            if (state.getAuthType() != null) {
                json.addProperty("authType", state.getAuthType().name());
            }

            if (state.getWorldSeed() != null) {
                json.addProperty("worldSeed", state.getWorldSeed());
            }

            json.addProperty("downloadsCompleted", state.isDownloadsCompleted());

            try (Writer writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }

            Architect.LOGGER.debug("Saved InGame state to {}", filePath);

        } catch (IOException e) {
            Architect.LOGGER.error("Failed to save ingame.json", e);
        }
    }

    /**
     * Checks if InGame mode has been initialized for this world.
     */
    public boolean isInitialized() {
        return state.isInitialized();
    }

    /**
     * Checks if InGame mode is active (initialized with a list selected).
     */
    public boolean isActive() {
        return state.isActive();
    }

    /**
     * Gets the selected list ID, or null if not initialized or declined.
     * Can be numeric (e.g., "123") or alphanumeric (e.g., "most-popular" for virtual lists).
     */
    public String getSelectedListId() {
        return state.getListId();
    }

    /**
     * Checks if the list is permanently locked to this world.
     */
    public boolean isListLocked() {
        return state.isListLocked();
    }

    private Path getFilePath() {
        return worldPath.resolve(STRUTTURA_DIR).resolve(INGAME_FILE);
    }
}
