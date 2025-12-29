package it.magius.struttura.architect.model;

import net.minecraft.core.BlockPos;
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

    // Blocchi tracciati: posizione -> stato blocco
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();

    // Bounds calcolati dai blocchi
    private final ConstructionBounds bounds = new ConstructionBounds();

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
}
