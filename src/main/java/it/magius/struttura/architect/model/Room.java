package it.magius.struttura.architect.model;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Rappresenta una stanza (variante) di una costruzione.
 * Contiene solo i blocchi che differiscono dalla costruzione base (delta compression).
 */
public class Room {

    // Pattern per generare ID validi dal nome
    private static final Pattern INVALID_CHARS = Pattern.compile("[^a-z0-9_]");

    // ID univoco (generato dal nome, es: "treasure_room")
    private final String id;

    // Nome visualizzato (inserito dall'utente, es: "Treasure Room!")
    private String name;

    // Delta: solo blocchi che differiscono dalla costruzione base
    // Chiave: posizione relativa, Valore: stato blocco
    private final Map<BlockPos, BlockState> blockChanges;

    // NBT dei block entities modificati
    private final Map<BlockPos, CompoundTag> blockEntityNbt;

    // Entities in this room (type and position data only, no UUID in saved data)
    private final List<EntityData> entities;

    // Timestamp creazione
    private final Instant createdAt;

    /**
     * Crea una nuova stanza con nome.
     * L'ID viene generato automaticamente dal nome.
     */
    public Room(String name) {
        this.id = generateId(name);
        this.name = name;
        this.blockChanges = new HashMap<>();
        this.blockEntityNbt = new HashMap<>();
        this.entities = new ArrayList<>();
        this.createdAt = Instant.now();
    }

    /**
     * Crea una stanza con tutti i parametri (per deserializzazione).
     */
    public Room(String id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.blockChanges = new HashMap<>();
        this.blockEntityNbt = new HashMap<>();
        this.entities = new ArrayList<>();
        this.createdAt = createdAt;
    }

    /**
     * Genera un ID sicuro dal nome.
     * Converte in lowercase, sostituisce spazi con underscore, rimuove caratteri non validi.
     * Es: "Treasure Room!" -> "treasure_room"
     */
    public static String generateId(String name) {
        if (name == null || name.isEmpty()) {
            return "room_" + System.currentTimeMillis();
        }

        String id = name.toLowerCase()
            .trim()
            .replace(' ', '_')
            .replace('-', '_');

        // Rimuovi caratteri non validi
        id = INVALID_CHARS.matcher(id).replaceAll("");

        // Rimuovi underscore multipli consecutivi
        while (id.contains("__")) {
            id = id.replace("__", "_");
        }

        // Rimuovi underscore iniziali e finali
        id = id.replaceAll("^_+|_+$", "");

        // Se vuoto dopo la pulizia, usa un ID di default
        if (id.isEmpty()) {
            return "room_" + System.currentTimeMillis();
        }

        // Assicura che non inizi con un numero
        if (Character.isDigit(id.charAt(0))) {
            id = "room_" + id;
        }

        // Tronca a 50 caratteri
        if (id.length() > 50) {
            id = id.substring(0, 50);
        }

        return id;
    }

    // ===== Block management =====

    /**
     * Aggiunge o modifica un blocco nel delta.
     */
    public void setBlockChange(BlockPos pos, BlockState state) {
        blockChanges.put(pos.immutable(), state);
    }

    /**
     * Aggiunge un blocco con NBT.
     */
    public void setBlockChange(BlockPos pos, BlockState state, CompoundTag nbt) {
        blockChanges.put(pos.immutable(), state);
        if (nbt != null && !nbt.isEmpty()) {
            blockEntityNbt.put(pos.immutable(), nbt);
        }
    }

    /**
     * Rimuove un blocco dal delta.
     */
    public void removeBlockChange(BlockPos pos) {
        blockChanges.remove(pos);
        blockEntityNbt.remove(pos);
    }

    /**
     * Verifica se una posizione ha un cambiamento.
     */
    public boolean hasBlockChange(BlockPos pos) {
        return blockChanges.containsKey(pos);
    }

    /**
     * Ottiene il blocco modificato in una posizione.
     */
    public BlockState getBlockChange(BlockPos pos) {
        return blockChanges.get(pos);
    }

    /**
     * Ottiene l'NBT del block entity modificato.
     */
    public CompoundTag getBlockEntityNbt(BlockPos pos) {
        return blockEntityNbt.get(pos);
    }

    /**
     * Verifica se un blocco ha NBT associato.
     */
    public boolean hasBlockEntityNbt(BlockPos pos) {
        return blockEntityNbt.containsKey(pos);
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
     * Conta i blocchi nel delta.
     */
    public int getChangedBlockCount() {
        return blockChanges.size();
    }

    /**
     * Verifica se ci sono modifiche.
     */
    public boolean hasChanges() {
        return !blockChanges.isEmpty() || !entities.isEmpty();
    }

    /**
     * Ottiene tutti i blocchi modificati.
     */
    public Map<BlockPos, BlockState> getBlockChanges() {
        return blockChanges;
    }

    /**
     * Ottiene tutti gli NBT dei block entities.
     */
    public Map<BlockPos, CompoundTag> getBlockEntityNbtMap() {
        return blockEntityNbt;
    }

    /**
     * Pulisce tutti i blocchi dal delta.
     */
    public void clearBlockChanges() {
        blockChanges.clear();
        blockEntityNbt.clear();
    }

    // ===== Entity management =====

    /**
     * Adds an entity to this room.
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
     * Gets all entities in this room.
     * @return Unmodifiable list of entities
     */
    public List<EntityData> getEntities() {
        return entities;
    }

    /**
     * Counts entities in this room.
     */
    public int getEntityCount() {
        return entities.size();
    }

    /**
     * Clears all entities from this room.
     */
    public void clearEntities() {
        entities.clear();
    }

    // ===== Getters/Setters =====

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Creates a copy of this room with a new name (and thus new ID).
     * Keeps all data (blocks, entities) but with the new ID/name.
     * Also keeps the original createdAt timestamp.
     */
    public Room copyWithNewName(String newName) {
        String newId = generateId(newName);
        Room copy = new Room(newId, newName, this.createdAt);

        // Copy all blocks
        for (Map.Entry<BlockPos, BlockState> entry : this.blockChanges.entrySet()) {
            copy.blockChanges.put(entry.getKey(), entry.getValue());
        }

        // Copy all block entity NBTs
        for (Map.Entry<BlockPos, CompoundTag> entry : this.blockEntityNbt.entrySet()) {
            copy.blockEntityNbt.put(entry.getKey(), entry.getValue().copy());
        }

        // Copy all entities
        for (EntityData data : this.entities) {
            copy.entities.add(data);
        }

        return copy;
    }

    @Override
    public String toString() {
        return "Room{id='" + id + "', name='" + name + "', blocks=" + blockChanges.size() +
               ", entities=" + entities.size() + "}";
    }
}
