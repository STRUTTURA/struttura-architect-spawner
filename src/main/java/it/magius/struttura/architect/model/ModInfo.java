package it.magius.struttura.architect.model;

/**
 * Information about a mod required by a building.
 * Contains details about the mod and how many blocks/entities require it.
 */
public class ModInfo {

    // Mod namespace (e.g. "create", "supplementaries")
    private final String modId;

    // Display name of the mod (e.g. "Create", "Supplementaries")
    private String displayName;

    // URL to download the mod (nullable)
    private String downloadUrl;

    // Minimum required version of the mod (nullable)
    private String version;

    // Number of blocks that require this mod
    private int blockCount;

    // Number of entities that require this mod
    private int entitiesCount;

    public ModInfo(String modId) {
        this.modId = modId;
        this.displayName = modId;
        this.blockCount = 0;
        this.entitiesCount = 0;
    }

    public ModInfo(String modId, String displayName, int blockCount, int entitiesCount, String downloadUrl, String version) {
        this.modId = modId;
        this.displayName = displayName != null ? displayName : modId;
        this.blockCount = blockCount;
        this.entitiesCount = entitiesCount;
        this.downloadUrl = downloadUrl;
        this.version = version;
    }

    // Getters
    public String getModId() { return modId; }
    public String getDisplayName() { return displayName; }
    public String getDownloadUrl() { return downloadUrl; }
    public String getVersion() { return version; }
    public int getBlockCount() { return blockCount; }
    public int getEntitiesCount() { return entitiesCount; }

    // Setters
    public void setDisplayName(String displayName) {
        this.displayName = displayName != null ? displayName : modId;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public void setBlockCount(int blockCount) {
        this.blockCount = blockCount;
    }

    public void setEntitiesCount(int entitiesCount) {
        this.entitiesCount = entitiesCount;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    // Incrementers
    public void incrementBlockCount() {
        this.blockCount++;
    }

    public void incrementEntitiesCount() {
        this.entitiesCount++;
    }

    @Override
    public String toString() {
        return "ModInfo{" +
            "modId='" + modId + '\'' +
            ", displayName='" + displayName + '\'' +
            ", version='" + version + '\'' +
            ", blockCount=" + blockCount +
            ", entitiesCount=" + entitiesCount +
            ", downloadUrl='" + downloadUrl + '\'' +
            '}';
    }
}
