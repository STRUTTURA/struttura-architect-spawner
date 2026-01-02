package it.magius.struttura.architect.model;

/**
 * Informazioni su un mod richiesto da una costruzione.
 * Contiene dettagli sul mod e quanti blocchi/entità della costruzione lo richiedono.
 */
public class ModInfo {

    // Namespace del mod (es. "create", "supplementaries")
    private final String modId;

    // Nome esteso del mod (es. "Create", "Supplementaries")
    private String displayName;

    // URL per scaricare il mod (nullable)
    private String downloadUrl;

    // Versione minima richiesta del mod (nullable)
    private String version;

    // Numero di blocchi che richiedono questo mod
    private int blockCount;

    // Numero di entità che richiedono questo mod (per futuro uso)
    private int entityCount;

    public ModInfo(String modId) {
        this.modId = modId;
        this.displayName = modId; // default al modId
        this.blockCount = 0;
        this.entityCount = 0;
    }

    public ModInfo(String modId, String displayName, int blockCount, int entityCount, String downloadUrl, String version) {
        this.modId = modId;
        this.displayName = displayName != null ? displayName : modId;
        this.blockCount = blockCount;
        this.entityCount = entityCount;
        this.downloadUrl = downloadUrl;
        this.version = version;
    }

    // Getters
    public String getModId() { return modId; }
    public String getDisplayName() { return displayName; }
    public String getDownloadUrl() { return downloadUrl; }
    public String getVersion() { return version; }
    public int getBlockCount() { return blockCount; }
    public int getEntityCount() { return entityCount; }

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

    public void setEntityCount(int entityCount) {
        this.entityCount = entityCount;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    // Incrementers
    public void incrementBlockCount() {
        this.blockCount++;
    }

    public void incrementEntityCount() {
        this.entityCount++;
    }

    @Override
    public String toString() {
        return "ModInfo{" +
            "modId='" + modId + '\'' +
            ", displayName='" + displayName + '\'' +
            ", version='" + version + '\'' +
            ", blockCount=" + blockCount +
            ", entityCount=" + entityCount +
            ", downloadUrl='" + downloadUrl + '\'' +
            '}';
    }
}
