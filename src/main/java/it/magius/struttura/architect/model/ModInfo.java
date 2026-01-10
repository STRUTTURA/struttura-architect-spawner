package it.magius.struttura.architect.model;

/**
 * Informazioni su un mod richiesto da una costruzione.
 * Contiene dettagli sul mod e quanti blocchi/mob/command blocks della costruzione lo richiedono.
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

    // Numero di mob che richiedono questo mod
    private int mobsCount;

    // Numero di command blocks che richiedono questo mod
    private int commandBlocksCount;

    public ModInfo(String modId) {
        this.modId = modId;
        this.displayName = modId; // default al modId
        this.blockCount = 0;
        this.mobsCount = 0;
        this.commandBlocksCount = 0;
    }

    public ModInfo(String modId, String displayName, int blockCount, int mobsCount, int commandBlocksCount, String downloadUrl, String version) {
        this.modId = modId;
        this.displayName = displayName != null ? displayName : modId;
        this.blockCount = blockCount;
        this.mobsCount = mobsCount;
        this.commandBlocksCount = commandBlocksCount;
        this.downloadUrl = downloadUrl;
        this.version = version;
    }

    // Getters
    public String getModId() { return modId; }
    public String getDisplayName() { return displayName; }
    public String getDownloadUrl() { return downloadUrl; }
    public String getVersion() { return version; }
    public int getBlockCount() { return blockCount; }
    public int getMobsCount() { return mobsCount; }
    public int getCommandBlocksCount() { return commandBlocksCount; }

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

    public void setMobsCount(int mobsCount) {
        this.mobsCount = mobsCount;
    }

    public void setCommandBlocksCount(int commandBlocksCount) {
        this.commandBlocksCount = commandBlocksCount;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    // Incrementers
    public void incrementBlockCount() {
        this.blockCount++;
    }

    public void incrementMobsCount() {
        this.mobsCount++;
    }

    public void incrementCommandBlocksCount() {
        this.commandBlocksCount++;
    }

    @Override
    public String toString() {
        return "ModInfo{" +
            "modId='" + modId + '\'' +
            ", displayName='" + displayName + '\'' +
            ", version='" + version + '\'' +
            ", blockCount=" + blockCount +
            ", mobsCount=" + mobsCount +
            ", commandBlocksCount=" + commandBlocksCount +
            ", downloadUrl='" + downloadUrl + '\'' +
            '}';
    }
}
