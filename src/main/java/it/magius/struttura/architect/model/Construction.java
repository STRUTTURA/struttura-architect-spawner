package it.magius.struttura.architect.model;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rappresenta una costruzione STRUTTURA.
 * Contiene i blocchi tracciati e i metadata.
 */
public class Construction {

    // ID formato: namespace.categoria.nome (es: it.magius.medieval.tower)
    private final String id;

    // UUID dell'autore
    private final UUID authorId;

    // Nome dell'autore
    private final String authorName;

    // Timestamp creazione
    private final Instant createdAt;

    // Titoli della costruzione per lingua (obbligatorio almeno uno per il push)
    // Chiave: codice lingua (es: "en", "it"), Valore: titolo
    private final Map<String, String> titles = new HashMap<>();

    // Descrizioni brevi della costruzione per lingua (opzionale)
    // Chiave: codice lingua (es: "en", "it"), Valore: descrizione breve
    private final Map<String, String> shortDescriptions = new HashMap<>();

    // Descrizioni complete della costruzione per lingua (opzionale)
    // Chiave: codice lingua (es: "en", "it"), Valore: descrizione completa
    private final Map<String, String> descriptions = new HashMap<>();

    // Blocchi tracciati: posizione -> stato blocco
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();

    // Bounds calcolati dai blocchi
    private final ConstructionBounds bounds = new ConstructionBounds();

    // Mod richiesti dalla costruzione (namespace -> info mod)
    private Map<String, ModInfo> requiredMods = new HashMap<>();

    public Construction(String id, UUID authorId, String authorName) {
        this.id = id;
        this.authorId = authorId;
        this.authorName = authorName;
        this.createdAt = Instant.now();
    }

    public Construction(String id, UUID authorId, String authorName, Instant createdAt) {
        this.id = id;
        this.authorId = authorId;
        this.authorName = authorName;
        this.createdAt = createdAt;
    }

    public Construction(String id, UUID authorId, String authorName, Instant createdAt,
                        Map<String, String> titles, Map<String, String> shortDescriptions,
                        Map<String, String> descriptions) {
        this.id = id;
        this.authorId = authorId;
        this.authorName = authorName;
        this.createdAt = createdAt;
        if (titles != null) {
            this.titles.putAll(titles);
        }
        if (shortDescriptions != null) {
            this.shortDescriptions.putAll(shortDescriptions);
        }
        if (descriptions != null) {
            this.descriptions.putAll(descriptions);
        }
    }

    /**
     * Valida il formato dell'ID costruzione.
     * Formato: namespace.categoria.nome (minimo 3 segmenti, solo lowercase e underscore)
     */
    public static boolean isValidId(String id) {
        if (id == null || id.isEmpty()) return false;

        String[] parts = id.split("\\.");
        if (parts.length < 3) return false;

        for (String part : parts) {
            if (part.isEmpty()) return false;
            if (!part.matches("^[a-z][a-z0-9_]*$")) return false;
        }

        return true;
    }

    /**
     * Aggiunge un blocco alla costruzione.
     */
    public void addBlock(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
        bounds.expandToInclude(pos);
    }

    /**
     * Rimuove un blocco dalla costruzione.
     * Ricalcola i bounds automaticamente dopo la rimozione.
     */
    public boolean removeBlock(BlockPos pos) {
        boolean removed = blocks.remove(pos) != null;
        if (removed) {
            recalculateBounds();
        }
        return removed;
    }

    /**
     * Verifica se una posizione fa parte della costruzione.
     */
    public boolean containsBlock(BlockPos pos) {
        return blocks.containsKey(pos);
    }

    /**
     * Ottiene lo stato del blocco in una posizione.
     */
    public BlockState getBlock(BlockPos pos) {
        return blocks.get(pos);
    }

    /**
     * Conta i blocchi totali (inclusa l'aria).
     */
    public int getBlockCount() {
        return blocks.size();
    }

    /**
     * Conta i blocchi solidi (esclude l'aria).
     */
    public int getSolidBlockCount() {
        return (int) blocks.values().stream()
            .filter(state -> !state.isAir())
            .count();
    }

    /**
     * Ricalcola i bounds dai blocchi correnti.
     */
    public void recalculateBounds() {
        bounds.reset();
        for (BlockPos pos : blocks.keySet()) {
            bounds.expandToInclude(pos);
        }
    }

    /**
     * Rimuove tutti i blocchi di un determinato tipo.
     * @param blockId ID del blocco (es: "minecraft:air", "minecraft:stone")
     * @return numero di blocchi rimossi
     */
    public int removeBlocksByType(String blockId) {
        java.util.List<BlockPos> toRemove = new java.util.ArrayList<>();

        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            String entryBlockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(entry.getValue().getBlock())
                .toString();

            if (entryBlockId.equals(blockId)) {
                toRemove.add(entry.getKey());
            }
        }

        for (BlockPos pos : toRemove) {
            blocks.remove(pos);
        }

        // Ricalcola i bounds dopo la rimozione
        if (!toRemove.isEmpty()) {
            recalculateBounds();
        }

        return toRemove.size();
    }

    /**
     * Conta quanti blocchi di un determinato tipo sono presenti.
     * @param blockId ID del blocco (es: "minecraft:air", "minecraft:stone")
     * @return numero di blocchi di quel tipo
     */
    public int countBlocksByType(String blockId) {
        int count = 0;
        for (BlockState state : blocks.values()) {
            String entryBlockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock())
                .toString();

            if (entryBlockId.equals(blockId)) {
                count++;
            }
        }
        return count;
    }

    // Getters
    public String getId() { return id; }
    public UUID getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
    public Instant getCreatedAt() { return createdAt; }
    public Map<BlockPos, BlockState> getBlocks() { return blocks; }
    public ConstructionBounds getBounds() { return bounds; }

    // Getter/Setter multilingua per titoli
    public Map<String, String> getTitles() { return titles; }
    public String getTitle(String lang) { return titles.getOrDefault(lang, ""); }
    public void setTitle(String lang, String title) {
        if (title != null && !title.trim().isEmpty()) {
            titles.put(lang, title);
        } else {
            titles.remove(lang);
        }
    }

    // Getter/Setter multilingua per descrizioni brevi
    public Map<String, String> getShortDescriptions() { return shortDescriptions; }
    public String getShortDescription(String lang) { return shortDescriptions.getOrDefault(lang, ""); }
    public void setShortDescription(String lang, String shortDescription) {
        if (shortDescription != null && !shortDescription.trim().isEmpty()) {
            shortDescriptions.put(lang, shortDescription);
        } else {
            shortDescriptions.remove(lang);
        }
    }

    // Getter/Setter multilingua per descrizioni complete
    public Map<String, String> getDescriptions() { return descriptions; }
    public String getDescription(String lang) { return descriptions.getOrDefault(lang, ""); }
    public void setDescription(String lang, String description) {
        if (description != null && !description.trim().isEmpty()) {
            descriptions.put(lang, description);
        } else {
            descriptions.remove(lang);
        }
    }

    /**
     * Verifica se la costruzione ha almeno un titolo valido (obbligatorio per il push).
     */
    public boolean hasValidTitle() {
        return !titles.isEmpty() && titles.values().stream().anyMatch(t -> t != null && !t.trim().isEmpty());
    }

    /**
     * Ottiene il titolo nella lingua preferita, con fallback a inglese o prima disponibile.
     */
    public String getTitleWithFallback(String preferredLang) {
        if (titles.containsKey(preferredLang) && !titles.get(preferredLang).isEmpty()) {
            return titles.get(preferredLang);
        }
        if (titles.containsKey("en") && !titles.get("en").isEmpty()) {
            return titles.get("en");
        }
        return titles.values().stream().filter(t -> t != null && !t.isEmpty()).findFirst().orElse("");
    }

    /**
     * Ottiene la descrizione breve nella lingua preferita, con fallback a inglese o prima disponibile.
     */
    public String getShortDescriptionWithFallback(String preferredLang) {
        if (shortDescriptions.containsKey(preferredLang) && !shortDescriptions.get(preferredLang).isEmpty()) {
            return shortDescriptions.get(preferredLang);
        }
        if (shortDescriptions.containsKey("en") && !shortDescriptions.get("en").isEmpty()) {
            return shortDescriptions.get("en");
        }
        return shortDescriptions.values().stream().filter(d -> d != null && !d.isEmpty()).findFirst().orElse("");
    }

    /**
     * Ottiene la descrizione completa nella lingua preferita, con fallback a inglese o prima disponibile.
     */
    public String getDescriptionWithFallback(String preferredLang) {
        if (descriptions.containsKey(preferredLang) && !descriptions.get(preferredLang).isEmpty()) {
            return descriptions.get(preferredLang);
        }
        if (descriptions.containsKey("en") && !descriptions.get("en").isEmpty()) {
            return descriptions.get("en");
        }
        return descriptions.values().stream().filter(d -> d != null && !d.isEmpty()).findFirst().orElse("");
    }

    /**
     * Crea una copia della costruzione con un nuovo ID.
     * Tutti i dati (blocchi, titoli, descrizioni, etc.) vengono copiati.
     */
    public Construction copyWithNewId(String newId) {
        Construction copy = new Construction(newId, this.authorId, this.authorName, this.createdAt,
            this.titles, this.shortDescriptions, this.descriptions);

        // Copia tutti i blocchi
        for (Map.Entry<BlockPos, BlockState> entry : this.blocks.entrySet()) {
            copy.addBlock(entry.getKey(), entry.getValue());
        }

        // Copia i mod richiesti
        copy.setRequiredMods(this.requiredMods);

        return copy;
    }

    // Getter/Setter per mod richiesti
    public Map<String, ModInfo> getRequiredMods() {
        return requiredMods;
    }

    public void setRequiredMods(Map<String, ModInfo> mods) {
        this.requiredMods = mods != null ? new HashMap<>(mods) : new HashMap<>();
    }

    /**
     * Calcola i mod richiesti analizzando i blocchi della costruzione.
     * Per ogni blocco non vanilla, estrae il namespace e popola le info del mod.
     */
    public void computeRequiredMods() {
        requiredMods.clear();

        // Conta i blocchi per ogni mod non-vanilla
        for (BlockState state : blocks.values()) {
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            String namespace = blockId.getNamespace();

            // Ignora i blocchi vanilla
            if (!"minecraft".equals(namespace)) {
                ModInfo info = requiredMods.computeIfAbsent(namespace, ModInfo::new);
                info.incrementBlockCount();
            }
        }

        // Popola displayName, version e downloadUrl dai mod caricati (se disponibili)
        for (ModInfo info : requiredMods.values()) {
            FabricLoader.getInstance().getModContainer(info.getModId()).ifPresent(container -> {
                info.setDisplayName(container.getMetadata().getName());
                info.setVersion(container.getMetadata().getVersion().getFriendlyString());
                container.getMetadata().getContact().get("homepage")
                    .ifPresent(info::setDownloadUrl);
            });
        }
    }

    /**
     * Verifica se la costruzione richiede mod non-vanilla.
     */
    public boolean hasModdedBlocks() {
        return !requiredMods.isEmpty();
    }
}
