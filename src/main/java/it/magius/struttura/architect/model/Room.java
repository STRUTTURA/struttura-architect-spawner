package it.magius.struttura.architect.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Represents a room (variant) of a construction.
 * Stores only block positions and entity UUIDs (reference-only, like Construction).
 */
public class Room {

    // Pattern for generating valid IDs from names
    private static final Pattern INVALID_CHARS = Pattern.compile("[^a-z0-9_]");

    // Unique ID (generated from name, e.g., "treasure_room")
    private final String id;

    // Display name (user-entered, e.g., "Treasure Room!")
    private String name;

    // Delta: only block positions that differ from the base construction
    private final Set<BlockPos> changedBlocks;

    // Entity UUIDs in this room
    private final Set<UUID> roomEntities;

    // Creation timestamp
    private final Instant createdAt;

    // Cached stats (updated live during editing)
    private int cachedSolidBlockCount = 0;
    private int cachedEntityCount = 0;
    private int cachedMobCount = 0;
    private int cachedCommandBlockCount = 0;

    /**
     * Creates a new room with name.
     * ID is generated automatically from the name.
     */
    public Room(String name) {
        this.id = generateId(name);
        this.name = name;
        this.changedBlocks = new HashSet<>();
        this.roomEntities = new HashSet<>();
        this.createdAt = Instant.now();
    }

    /**
     * Creates a room with all parameters (for deserialization).
     */
    public Room(String id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.changedBlocks = new HashSet<>();
        this.roomEntities = new HashSet<>();
        this.createdAt = createdAt;
    }

    /**
     * Generates a safe ID from the name.
     * Converts to lowercase, replaces spaces with underscores, removes invalid characters.
     * E.g., "Treasure Room!" -> "treasure_room"
     */
    public static String generateId(String name) {
        if (name == null || name.isEmpty()) {
            return "room_" + System.currentTimeMillis();
        }

        String id = name.toLowerCase()
            .trim()
            .replace(' ', '_')
            .replace('-', '_');

        // Remove invalid characters
        id = INVALID_CHARS.matcher(id).replaceAll("");

        // Remove consecutive underscores
        while (id.contains("__")) {
            id = id.replace("__", "_");
        }

        // Remove leading/trailing underscores
        id = id.replaceAll("^_+|_+$", "");

        // If empty after cleanup, use a default ID
        if (id.isEmpty()) {
            return "room_" + System.currentTimeMillis();
        }

        // Ensure it doesn't start with a digit
        if (Character.isDigit(id.charAt(0))) {
            id = "room_" + id;
        }

        // Truncate to 50 characters
        if (id.length() > 50) {
            id = id.substring(0, 50);
        }

        return id;
    }

    // ===== Block management (reference-only) =====

    /**
     * Adds a block position to the room delta.
     */
    public void setBlockChange(BlockPos pos) {
        changedBlocks.add(pos.immutable());
    }

    /**
     * Removes a block from the delta.
     */
    public void removeBlockChange(BlockPos pos) {
        changedBlocks.remove(pos);
    }

    /**
     * Checks if a position has a change.
     */
    public boolean hasBlockChange(BlockPos pos) {
        return changedBlocks.contains(pos);
    }

    /**
     * Gets all changed block positions.
     */
    public Set<BlockPos> getChangedBlocks() {
        return changedBlocks;
    }

    /**
     * Counts blocks in the delta.
     */
    public int getChangedBlockCount() {
        return changedBlocks.size();
    }

    /**
     * Checks if there are any changes.
     */
    public boolean hasChanges() {
        return !changedBlocks.isEmpty() || !roomEntities.isEmpty();
    }

    /**
     * Clears all blocks from the delta.
     */
    public void clearBlockChanges() {
        changedBlocks.clear();
    }

    // ===== Entity management (reference-only) =====

    /**
     * Adds an entity UUID to this room.
     */
    public void addEntity(UUID uuid) {
        roomEntities.add(uuid);
    }

    /**
     * Removes an entity UUID from this room.
     */
    public boolean removeEntity(UUID uuid) {
        return roomEntities.remove(uuid);
    }

    /**
     * Gets all entity UUIDs in this room.
     */
    public Set<UUID> getRoomEntities() {
        return roomEntities;
    }

    /**
     * Counts entities in this room.
     * Returns live tracked count if entities are in the world (during room editing),
     * otherwise returns the cached count (from snapshot/disk).
     */
    public int getEntityCount() {
        return Math.max(roomEntities.size(), cachedEntityCount);
    }

    /**
     * Clears all entities from this room.
     */
    public void clearEntities() {
        roomEntities.clear();
    }

    // ===== Cached stats =====

    public int getCachedSolidBlockCount() { return cachedSolidBlockCount; }
    public int getCachedEntityCount() { return cachedEntityCount; }
    public int getCachedMobCount() { return cachedMobCount; }
    public int getCachedCommandBlockCount() { return cachedCommandBlockCount; }

    public void setCachedEntityCount(int entityCount) {
        this.cachedEntityCount = entityCount;
    }

    public void setCachedStats(int solidBlockCount, int mobCount, int commandBlockCount) {
        this.cachedSolidBlockCount = solidBlockCount;
        this.cachedMobCount = mobCount;
        this.cachedCommandBlockCount = commandBlockCount;
    }

    /**
     * Updates cached stats by querying the world.
     */
    public void updateCachedStats(ServerLevel level) {
        cachedSolidBlockCount = 0;
        cachedCommandBlockCount = 0;
        cachedMobCount = 0;
        int entityCount = 0;

        for (BlockPos pos : changedBlocks) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                cachedSolidBlockCount++;
                String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                if (blockId.equals("minecraft:command_block") ||
                    blockId.equals("minecraft:chain_command_block") ||
                    blockId.equals("minecraft:repeating_command_block")) {
                    cachedCommandBlockCount++;
                }
            }
        }

        for (UUID entityUuid : roomEntities) {
            net.minecraft.world.entity.Entity worldEntity = level.getEntity(entityUuid);
            if (worldEntity != null && EntityData.shouldSaveEntity(worldEntity)) {
                entityCount++;
                String entityType = BuiltInRegistries.ENTITY_TYPE
                    .getKey(worldEntity.getType()).toString();
                if (Construction.isMobEntity(entityType)) {
                    cachedMobCount++;
                }
            }
        }

        // Only update cachedEntityCount from world if entities are actually in the world
        if (!roomEntities.isEmpty()) {
            cachedEntityCount = entityCount;
        }
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
     * Keeps all data (block positions, entity UUIDs) but with the new ID/name.
     * Also keeps the original createdAt timestamp.
     */
    public Room copyWithNewName(String newName) {
        String newId = generateId(newName);
        Room copy = new Room(newId, newName, this.createdAt);

        // Copy all block positions
        copy.changedBlocks.addAll(this.changedBlocks);

        // Copy all entity UUIDs
        copy.roomEntities.addAll(this.roomEntities);

        // Copy cached stats
        copy.cachedSolidBlockCount = this.cachedSolidBlockCount;
        copy.cachedEntityCount = this.cachedEntityCount;
        copy.cachedMobCount = this.cachedMobCount;
        copy.cachedCommandBlockCount = this.cachedCommandBlockCount;

        return copy;
    }

    /**
     * Calculates the bounding box dimensions string for this room's block changes.
     * Returns "WxHxD" format (e.g., "10x5x8") or "0x0x0" if no blocks and no entities.
     *
     * @param constructionBounds The construction bounds to normalize block positions
     */
    public String getBoundsString(ConstructionBounds constructionBounds) {
        if (changedBlocks.isEmpty() && roomEntities.isEmpty()) {
            return "0x0x0";
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        // Block positions are absolute world coordinates, convert to relative
        int boundsMinX = constructionBounds.isValid() ? constructionBounds.getMinX() : 0;
        int boundsMinY = constructionBounds.isValid() ? constructionBounds.getMinY() : 0;
        int boundsMinZ = constructionBounds.isValid() ? constructionBounds.getMinZ() : 0;

        for (BlockPos pos : changedBlocks) {
            int relX = pos.getX() - boundsMinX;
            int relY = pos.getY() - boundsMinY;
            int relZ = pos.getZ() - boundsMinZ;
            minX = Math.min(minX, relX);
            minY = Math.min(minY, relY);
            minZ = Math.min(minZ, relZ);
            maxX = Math.max(maxX, relX);
            maxY = Math.max(maxY, relY);
            maxZ = Math.max(maxZ, relZ);
        }

        // Entity positions would need world query - for now, only use blocks for sizing
        // (entities only affect bounds string when they're in the world)

        if (minX == Integer.MAX_VALUE) {
            // No blocks - only entities, cannot compute bounds without world access
            return "0x0x0";
        }

        // Size is the span from min to max (inclusive)
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;

        return sizeX + "x" + sizeY + "x" + sizeZ;
    }

    @Override
    public String toString() {
        return "Room{id='" + id + "', name='" + name + "', blocks=" + changedBlocks.size() +
               ", entities=" + roomEntities.size() + "}";
    }
}
