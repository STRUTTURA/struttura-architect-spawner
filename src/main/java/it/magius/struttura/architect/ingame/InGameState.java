package it.magius.struttura.architect.ingame;

import it.magius.struttura.architect.ingame.model.SpawnableList;

/**
 * Runtime state for the InGame spawner system.
 * Holds the current initialization status and selected list data.
 */
public class InGameState {

    /**
     * Authentication type used for InGame mode.
     */
    public enum AuthType {
        API_KEY,    // Authenticated with user's API key
        PUBLIC      // Using public lists only
    }

    private boolean initialized = false;
    private boolean declined = false;
    private Long listId = null;
    private String listName = null;
    private AuthType authType = null;
    private String worldSeed = null;
    private boolean downloadsCompleted = false;  // Persisted: true when all buildings downloaded

    // Runtime data (not persisted)
    private SpawnableList spawnableList = null;

    public InGameState() {}

    // ===== Initialization state =====

    /**
     * Checks if InGame mode has been initialized (either with a list or declined).
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Checks if InGame mode is active (initialized with a list selected).
     */
    public boolean isActive() {
        return initialized && !declined && listId != null;
    }

    /**
     * Checks if the user has declined InGame mode.
     */
    public boolean isDeclined() {
        return declined;
    }

    /**
     * Initializes InGame mode with a selected list.
     */
    public void initialize(long listId, String listName, AuthType authType, String worldSeed) {
        this.initialized = true;
        this.declined = false;
        this.listId = listId;
        this.listName = listName;
        this.authType = authType;
        this.worldSeed = worldSeed;
    }

    /**
     * Marks InGame mode as declined (user chose not to use it).
     */
    public void decline() {
        this.initialized = true;
        this.declined = true;
        this.listId = null;
        this.listName = null;
        this.authType = null;
    }

    /**
     * Resets InGame state (allows re-initialization).
     */
    public void reset() {
        this.initialized = false;
        this.declined = false;
        this.listId = null;
        this.listName = null;
        this.authType = null;
        this.worldSeed = null;
        this.downloadsCompleted = false;
        this.spawnableList = null;
    }

    /**
     * Checks if all buildings have been downloaded.
     */
    public boolean isDownloadsCompleted() {
        return downloadsCompleted;
    }

    /**
     * Marks downloads as completed.
     */
    public void setDownloadsCompleted(boolean completed) {
        this.downloadsCompleted = completed;
    }

    // ===== Getters =====

    public Long getListId() {
        return listId;
    }

    public String getListName() {
        return listName;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public String getWorldSeed() {
        return worldSeed;
    }

    public SpawnableList getSpawnableList() {
        return spawnableList;
    }

    // ===== Setters =====

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    public void setDeclined(boolean declined) {
        this.declined = declined;
    }

    public void setListId(Long listId) {
        this.listId = listId;
    }

    public void setListName(String listName) {
        this.listName = listName;
    }

    public void setAuthType(AuthType authType) {
        this.authType = authType;
    }

    public void setWorldSeed(String worldSeed) {
        this.worldSeed = worldSeed;
    }

    public void setSpawnableList(SpawnableList spawnableList) {
        this.spawnableList = spawnableList;
    }

    @Override
    public String toString() {
        if (!initialized) {
            return "InGameState{not initialized}";
        }
        if (declined) {
            return "InGameState{declined}";
        }
        return "InGameState{listId=" + listId + ", listName='" + listName + "', authType=" + authType + "}";
    }
}
