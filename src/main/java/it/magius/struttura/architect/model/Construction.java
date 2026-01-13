package it.magius.struttura.architect.model;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // NBT dei block entities (casse, furnace, etc.): posizione -> NBT
    // Solo per blocchi che hanno dati NBT (contenuto inventario, etc.)
    private final Map<BlockPos, CompoundTag> blockEntityNbt = new HashMap<>();

    // Entities in this construction (type and position data only, no UUID in saved data)
    private final List<EntityData> entities = new ArrayList<>();

    // UUIDs of spawned entities in the world (saved to file for entity removal after restart)
    // Used to track and remove entities even if they move outside construction bounds
    private final Set<UUID> spawnedEntityUuids = new HashSet<>();

    // Bounds calcolati dai blocchi
    private final ConstructionBounds bounds = new ConstructionBounds();

    // Mod richiesti dalla costruzione (namespace -> info mod)
    private Map<String, ModInfo> requiredMods = new HashMap<>();

    // Stanze (varianti) della costruzione: id stanza -> Room
    private final Map<String, Room> rooms = new HashMap<>();

    // Anchor points for the construction (only for base, not rooms)
    private final Anchors anchors = new Anchors();

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
     * Aggiunge un blocco con il suo NBT (per block entities come casse, furnace, etc.)
     */
    public void addBlock(BlockPos pos, BlockState state, CompoundTag nbt) {
        blocks.put(pos.immutable(), state);
        if (nbt != null && !nbt.isEmpty()) {
            blockEntityNbt.put(pos.immutable(), nbt);
        }
        bounds.expandToInclude(pos);
    }

    /**
     * Aggiunge un blocco senza espandere i bounds.
     * Usato quando si caricano blocchi da file/server dove i bounds sono già noti.
     */
    public void addBlockRaw(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
    }

    /**
     * Aggiunge un blocco con NBT senza espandere i bounds.
     * Usato quando si caricano blocchi da file/server dove i bounds sono già noti.
     */
    public void addBlockRaw(BlockPos pos, BlockState state, CompoundTag nbt) {
        blocks.put(pos.immutable(), state);
        if (nbt != null && !nbt.isEmpty()) {
            blockEntityNbt.put(pos.immutable(), nbt);
        }
    }

    /**
     * Imposta l'NBT di un block entity.
     */
    public void setBlockEntityNbt(BlockPos pos, CompoundTag nbt) {
        if (nbt != null && !nbt.isEmpty()) {
            blockEntityNbt.put(pos.immutable(), nbt);
        } else {
            blockEntityNbt.remove(pos);
        }
    }

    /**
     * Ottiene l'NBT di un block entity.
     */
    public CompoundTag getBlockEntityNbt(BlockPos pos) {
        return blockEntityNbt.get(pos);
    }

    /**
     * Verifica se un blocco ha un NBT associato.
     */
    public boolean hasBlockEntityNbt(BlockPos pos) {
        return blockEntityNbt.containsKey(pos);
    }

    /**
     * Ottiene tutti gli NBT dei block entities.
     */
    public Map<BlockPos, CompoundTag> getBlockEntityNbtMap() {
        return blockEntityNbt;
    }

    /**
     * Conta i block entities con NBT.
     */
    public int getBlockEntityCount() {
        return blockEntityNbt.size();
    }

    /**
     * Rimuove un blocco dalla costruzione.
     * Ricalcola i bounds automaticamente dopo la rimozione.
     */
    public boolean removeBlock(BlockPos pos) {
        boolean removed = blocks.remove(pos) != null;
        if (removed) {
            blockEntityNbt.remove(pos);  // Rimuovi anche l'NBT se presente
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
     * Also clears any anchors that are now outside the new bounds.
     */
    public void recalculateBounds() {
        bounds.reset();
        for (BlockPos pos : blocks.keySet()) {
            bounds.expandToInclude(pos);
        }
        // Validate anchors against new bounds
        validateAnchors();
    }

    /**
     * Validates that all anchors are within the current bounds.
     * Clears any anchors that are outside.
     */
    private void validateAnchors() {
        if (!bounds.isValid()) {
            // No valid bounds means no blocks - clear all anchors
            anchors.clearEntrance();
            return;
        }

        // Check entrance anchor (stored as normalized coordinates)
        if (anchors.hasEntrance()) {
            BlockPos entrance = anchors.getEntrance();
            // Normalized coords are relative to (0,0,0)
            // X and Z: max valid is sizeX-1, sizeZ-1 (within bounds)
            // Y: max valid is sizeY (player can stand ON TOP of the construction)
            int maxX = bounds.getSizeX() - 1;
            int maxY = bounds.getSizeY();  // Allow Y up to sizeY (standing on top)
            int maxZ = bounds.getSizeZ() - 1;

            if (entrance.getX() < 0 || entrance.getX() > maxX ||
                entrance.getY() < 0 || entrance.getY() > maxY ||
                entrance.getZ() < 0 || entrance.getZ() > maxZ) {
                anchors.clearEntrance();
            }
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

    /**
     * Ritorna una mappa con il conteggio di tutti i tipi di blocchi.
     * Include anche i blocchi d'aria per permettere la rimozione dalla GUI.
     * @return Map blockId -> count (include air)
     */
    public Map<String, Integer> getBlockCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (BlockState state : blocks.values()) {
            String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock())
                .toString();

            counts.merge(blockId, 1, Integer::sum);
        }
        return counts;
    }

    // Getters
    public String getId() { return id; }
    public UUID getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
    public Instant getCreatedAt() { return createdAt; }
    public Map<BlockPos, BlockState> getBlocks() { return blocks; }
    public ConstructionBounds getBounds() { return bounds; }
    public Anchors getAnchors() { return anchors; }

    // ===== Entity management =====

    /**
     * Adds an entity to this construction.
     * @param data The entity data (type, position, nbt)
     * @return The index of the added entity in the list
     */
    public int addEntity(EntityData data) {
        entities.add(data);
        return entities.size() - 1;
    }

    /**
     * Removes an entity by its index in the list.
     * @param index The index of the entity to remove
     * @return true if removed successfully, false if index is invalid
     */
    public boolean removeEntity(int index) {
        if (index >= 0 && index < entities.size()) {
            entities.remove(index);
            return true;
        }
        return false;
    }

    /**
     * Gets an entity by index.
     * @param index The index in the list
     * @return The entity data, or null if index is invalid
     */
    public EntityData getEntity(int index) {
        if (index >= 0 && index < entities.size()) {
            return entities.get(index);
        }
        return null;
    }

    /**
     * Clears all entities from this construction.
     */
    public void clearEntities() {
        entities.clear();
    }

    /**
     * Gets all entities in this construction.
     * @return The list of entities
     */
    public List<EntityData> getEntities() {
        return entities;
    }

    /**
     * Counts entities in this construction.
     */
    public int getEntityCount() {
        return entities.size();
    }

    /**
     * Counts mobs (living entities) in this construction.
     * Excludes non-mob entities like armor_stand, item_frame, painting, etc.
     */
    public int getMobCount() {
        int count = 0;
        for (EntityData data : entities) {
            if (isMobEntity(data.getEntityType())) {
                count++;
            }
        }
        return count;
    }

    // ===== Spawned entity UUID tracking (runtime-only) =====

    /**
     * Tracks a spawned entity UUID. Used to find and remove entities even if they move outside bounds.
     * This data is NOT saved to file - it's runtime-only.
     * @param uuid The UUID of the spawned entity
     */
    public void trackSpawnedEntity(UUID uuid) {
        spawnedEntityUuids.add(uuid);
    }

    /**
     * Untracks a spawned entity UUID.
     * @param uuid The UUID to untrack
     */
    public void untrackSpawnedEntity(UUID uuid) {
        spawnedEntityUuids.remove(uuid);
    }

    /**
     * Gets all tracked spawned entity UUIDs.
     * @return Set of UUIDs (runtime-only, not saved)
     */
    public Set<UUID> getSpawnedEntityUuids() {
        return spawnedEntityUuids;
    }

    /**
     * Clears all tracked spawned entity UUIDs.
     */
    public void clearSpawnedEntityUuids() {
        spawnedEntityUuids.clear();
    }

    /**
     * Checks if an entity UUID is tracked as spawned by this construction.
     * @param uuid The UUID to check
     * @return true if tracked
     */
    public boolean isEntityTracked(UUID uuid) {
        return spawnedEntityUuids.contains(uuid);
    }

    // ===== Total stats (base + all rooms) =====

    /**
     * Conta i blocchi totali della costruzione (base + tutte le room).
     */
    public int getTotalBlockCount() {
        int total = blocks.size();
        for (Room room : rooms.values()) {
            total += room.getChangedBlockCount();
        }
        return total;
    }

    /**
     * Conta i blocchi solidi totali (base + tutte le room).
     */
    public int getTotalSolidBlockCount() {
        int total = getSolidBlockCount();
        for (Room room : rooms.values()) {
            for (var state : room.getBlockChanges().values()) {
                if (!state.isAir()) {
                    total++;
                }
            }
        }
        return total;
    }

    /**
     * Conta i blocchi aria totali (base + tutte le room).
     */
    public int getTotalAirBlockCount() {
        int total = getBlockCount() - getSolidBlockCount();
        for (Room room : rooms.values()) {
            for (var state : room.getBlockChanges().values()) {
                if (state.isAir()) {
                    total++;
                }
            }
        }
        return total;
    }

    /**
     * Conta le entità totali (base + tutte le room).
     */
    public int getTotalEntityCount() {
        int total = entities.size();
        for (Room room : rooms.values()) {
            total += room.getEntityCount();
        }
        return total;
    }

    /**
     * Counts total mobs (base + all rooms).
     */
    public int getTotalMobCount() {
        int total = getMobCount();
        for (Room room : rooms.values()) {
            for (EntityData data : room.getEntities()) {
                if (data != null && isMobEntity(data.getEntityType())) {
                    total++;
                }
            }
        }
        return total;
    }

    /**
     * Counts command blocks in base construction.
     * Includes all types: command_block, chain_command_block, repeating_command_block.
     */
    public int getCommandBlockCount() {
        int count = 0;
        for (BlockState state : blocks.values()) {
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (isCommandBlock(blockId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts total command blocks (base + all rooms).
     */
    public int getTotalCommandBlockCount() {
        int total = getCommandBlockCount();
        for (Room room : rooms.values()) {
            for (BlockState state : room.getBlockChanges().values()) {
                String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                if (isCommandBlock(blockId)) {
                    total++;
                }
            }
        }
        return total;
    }

    /**
     * Checks if a block ID is a command block type.
     */
    private static boolean isCommandBlock(String blockId) {
        return blockId.equals("minecraft:command_block") ||
               blockId.equals("minecraft:chain_command_block") ||
               blockId.equals("minecraft:repeating_command_block");
    }

    /**
     * Verifica se un tipo di entità è un mob (entità vivente).
     * Metodo pubblico per essere utilizzabile anche da altre classi (es: NetworkHandler).
     */
    public static boolean isMobEntity(String entityType) {
        // Lista di entità NON-mob comuni
        return !entityType.equals("minecraft:armor_stand") &&
               !entityType.equals("minecraft:item_frame") &&
               !entityType.equals("minecraft:glow_item_frame") &&
               !entityType.equals("minecraft:painting") &&
               !entityType.equals("minecraft:minecart") &&
               !entityType.equals("minecraft:chest_minecart") &&
               !entityType.equals("minecraft:hopper_minecart") &&
               !entityType.equals("minecraft:furnace_minecart") &&
               !entityType.equals("minecraft:tnt_minecart") &&
               !entityType.equals("minecraft:spawner_minecart") &&
               !entityType.equals("minecraft:command_block_minecart") &&
               !entityType.equals("minecraft:boat") &&
               !entityType.equals("minecraft:chest_boat") &&
               !entityType.equals("minecraft:leash_knot") &&
               !entityType.equals("minecraft:end_crystal") &&
               !entityType.equals("minecraft:falling_block") &&
               !entityType.equals("minecraft:tnt") &&
               !entityType.equals("minecraft:marker") &&
               !entityType.equals("minecraft:interaction") &&
               !entityType.equals("minecraft:display.block") &&
               !entityType.equals("minecraft:display.item") &&
               !entityType.equals("minecraft:display.text");
    }

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
     * Creates a copy of this construction with a new ID.
     * All data (blocks, entities, titles, descriptions, etc.) is copied.
     */
    public Construction copyWithNewId(String newId) {
        Construction copy = new Construction(newId, this.authorId, this.authorName, this.createdAt,
            this.titles, this.shortDescriptions, this.descriptions);

        // Copy all blocks with their NBT
        for (Map.Entry<BlockPos, BlockState> entry : this.blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            CompoundTag nbt = this.blockEntityNbt.get(pos);
            if (nbt != null) {
                copy.addBlock(pos, entry.getValue(), nbt.copy());
            } else {
                copy.addBlock(pos, entry.getValue());
            }
        }

        // Copy all entities
        for (EntityData data : this.entities) {
            copy.addEntity(data);
        }

        // Copy required mods
        copy.setRequiredMods(this.requiredMods);

        // Copy anchors
        if (this.anchors.hasEntrance()) {
            copy.getAnchors().setEntrance(this.anchors.getEntrance(), this.anchors.getEntranceYaw());
        }

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
     * Calcola i mod richiesti analizzando i blocchi e le entità della costruzione.
     * Per ogni blocco/entità non vanilla, estrae il namespace e popola le info del mod.
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

        // Count entities and mobs for each non-vanilla mod
        for (EntityData entityData : entities) {
            String namespace = entityData.getModNamespace();

            // Skip vanilla entities
            if (!"minecraft".equals(namespace)) {
                ModInfo info = requiredMods.computeIfAbsent(namespace, ModInfo::new);
                info.incrementEntitiesCount();

                // Also count mobs separately
                if (isMobEntity(entityData.getEntityType())) {
                    info.incrementMobsCount();
                }
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

    // ===== Room management =====

    /**
     * Aggiunge una stanza alla costruzione.
     */
    public void addRoom(Room room) {
        rooms.put(room.getId(), room);
    }

    /**
     * Ottiene una stanza per ID.
     */
    public Room getRoom(String id) {
        return rooms.get(id);
    }

    /**
     * Rimuove una stanza dalla costruzione.
     */
    public boolean removeRoom(String id) {
        return rooms.remove(id) != null;
    }

    /**
     * Ottiene tutte le stanze.
     */
    public Map<String, Room> getRooms() {
        return rooms;
    }

    /**
     * Verifica se esiste una stanza con l'ID specificato.
     */
    public boolean hasRoom(String id) {
        return rooms.containsKey(id);
    }

    /**
     * Conta le stanze.
     */
    public int getRoomCount() {
        return rooms.size();
    }

    /**
     * Trova una stanza per nome (case insensitive).
     */
    public Room getRoomByName(String name) {
        for (Room room : rooms.values()) {
            if (room.getName().equalsIgnoreCase(name)) {
                return room;
            }
        }
        return null;
    }
}
