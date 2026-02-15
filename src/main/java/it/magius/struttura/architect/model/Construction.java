package it.magius.struttura.architect.model;

import it.magius.struttura.architect.Architect;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a STRUTTURA construction.
 * Stores only references (BlockPos for blocks, UUID for entities) - all data
 * is resolved from the world when needed.
 */
public class Construction {

    // ID format: namespace.category.name (e.g., it.magius.medieval.tower)
    private final String id;

    // Author UUID
    private final UUID authorId;

    // Author name
    private final String authorName;

    // Creation timestamp
    private final Instant createdAt;

    // Multilingual titles (required at least one for push)
    // Key: language code (e.g., "en", "it"), Value: title
    private final Map<String, String> titles = new HashMap<>();

    // Multilingual short descriptions (optional)
    // Key: language code (e.g., "en", "it"), Value: short description
    private final Map<String, String> shortDescriptions = new HashMap<>();

    // Multilingual full descriptions (optional)
    // Key: language code (e.g., "en", "it"), Value: full description
    private final Map<String, String> descriptions = new HashMap<>();

    // Tracked block positions (reference-only, no BlockState stored)
    private final Set<BlockPos> trackedBlocks = new HashSet<>();

    // Tracked entity UUIDs (reference-only, resolved from world when needed)
    private final Set<UUID> trackedEntities = new HashSet<>();

    // Bounds calculated from blocks and entities
    private final ConstructionBounds bounds = new ConstructionBounds();

    // Required mods (namespace -> mod info)
    private Map<String, ModInfo> requiredMods = new HashMap<>();

    // Rooms (variants) of the construction: room id -> Room
    private final Map<String, Room> rooms = new HashMap<>();

    // Anchor points for the construction (only for base, not rooms)
    private final Anchors anchors = new Anchors();

    // Cached stats (updated live during editing, full recalc at save/push)
    private int cachedSolidBlockCount = 0;
    private int cachedEntityCount = 0;
    private int cachedMobCount = 0;
    private int cachedCommandBlockCount = 0;

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
     * Validates construction ID format.
     * Format: namespace.category.name (minimum 3 segments, lowercase and underscore only)
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

    // ===== Block management (reference-only) =====

    /**
     * Adds a block position to the construction and expands bounds.
     * Queries the world to update cached stats (solid/command block counts).
     */
    public void addBlock(BlockPos pos, ServerLevel level) {
        trackedBlocks.add(pos.immutable());
        bounds.expandToInclude(pos);

        // Update cached stats from world
        BlockState state = level.getBlockState(pos);
        if (!state.isAir()) {
            cachedSolidBlockCount++;
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (isCommandBlock(blockId)) {
                cachedCommandBlockCount++;
            }
        }
    }

    /**
     * Adds a block position without expanding bounds or updating stats.
     * Used when loading from file/server where bounds are already known.
     */
    public void addBlockRaw(BlockPos pos) {
        trackedBlocks.add(pos.immutable());
    }

    /**
     * Removes a block from the construction.
     * Recalculates bounds including entities from the world.
     * Updates cached stats.
     */
    public boolean removeBlock(BlockPos pos, ServerLevel level) {
        boolean removed = trackedBlocks.remove(pos);
        if (removed) {
            // Update cached stats before recalculating
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                cachedSolidBlockCount = Math.max(0, cachedSolidBlockCount - 1);
                String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                if (isCommandBlock(blockId)) {
                    cachedCommandBlockCount = Math.max(0, cachedCommandBlockCount - 1);
                }
            }
            recalculateBounds(level);
        }
        return removed;
    }

    /**
     * Checks if a position is part of the construction.
     */
    public boolean containsBlock(BlockPos pos) {
        return trackedBlocks.contains(pos);
    }

    /**
     * Total block count (including air).
     */
    public int getBlockCount() {
        return trackedBlocks.size();
    }

    /**
     * Solid block count (from cached stats).
     */
    public int getSolidBlockCount() {
        return cachedSolidBlockCount;
    }

    /**
     * Gets all tracked block positions.
     */
    public Set<BlockPos> getTrackedBlocks() {
        return trackedBlocks;
    }

    /**
     * Recalculates bounds from tracked blocks only (no entities).
     * Also clears any anchors that are now outside the new bounds.
     */
    public void recalculateBounds() {
        bounds.reset();
        for (BlockPos pos : trackedBlocks) {
            bounds.expandToInclude(pos);
        }
        validateAnchors();
    }

    /**
     * Recalculates bounds from tracked blocks and tracked entities in the world.
     * Also clears any anchors that are now outside the new bounds.
     */
    public void recalculateBounds(ServerLevel level) {
        bounds.reset();
        for (BlockPos pos : trackedBlocks) {
            bounds.expandToInclude(pos);
        }
        // Include tracked entities from the world
        for (UUID entityUuid : trackedEntities) {
            Entity worldEntity = level.getEntity(entityUuid);
            if (worldEntity != null && EntityData.shouldSaveEntity(worldEntity)) {
                EntityData.expandBoundsForEntity(worldEntity, bounds);
            }
        }
        validateAnchors();
    }

    /**
     * Validates that all anchors are within the current bounds.
     * Clears any anchors that are outside.
     */
    private void validateAnchors() {
        if (!bounds.isValid()) {
            anchors.clearEntrance();
            return;
        }

        if (anchors.hasEntrance()) {
            BlockPos entrance = anchors.getEntrance();
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
     * Removes all blocks of a given type (querying world for block states).
     * Recalculates bounds including entities from the world.
     * @param blockId Block ID (e.g., "minecraft:air", "minecraft:stone")
     * @return number of blocks removed
     */
    public int removeBlocksByType(String blockId, ServerLevel level) {
        java.util.List<BlockPos> toRemove = new java.util.ArrayList<>();

        for (BlockPos pos : trackedBlocks) {
            BlockState state = level.getBlockState(pos);
            String entryBlockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (entryBlockId.equals(blockId)) {
                toRemove.add(pos);
            }
        }

        for (BlockPos pos : toRemove) {
            trackedBlocks.remove(pos);
        }

        if (!toRemove.isEmpty()) {
            recalculateBounds(level);
            // Full recalc of stats since we may have removed many blocks
            updateCachedStats(level);
        }

        return toRemove.size();
    }

    /**
     * Counts blocks of a given type by querying the world.
     */
    public int countBlocksByType(String blockId, ServerLevel level) {
        int count = 0;
        for (BlockPos pos : trackedBlocks) {
            BlockState state = level.getBlockState(pos);
            String entryBlockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (entryBlockId.equals(blockId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns a map with counts of all block types by querying the world.
     * Includes air blocks for GUI removal support.
     * @return Map blockId -> count (includes air)
     */
    public Map<String, Integer> getBlockCounts(ServerLevel level) {
        Map<String, Integer> counts = new HashMap<>();
        for (BlockPos pos : trackedBlocks) {
            BlockState state = level.getBlockState(pos);
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            counts.merge(blockId, 1, Integer::sum);
        }
        return counts;
    }

    // Getters
    public String getId() { return id; }
    public UUID getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
    public Instant getCreatedAt() { return createdAt; }
    public ConstructionBounds getBounds() { return bounds; }
    public Anchors getAnchors() { return anchors; }

    // ===== Entity management (reference-only) =====

    /**
     * Adds an entity UUID to this construction and expands bounds.
     * Updates cached entity/mob stats from world query.
     */
    public void addEntity(UUID uuid, ServerLevel level) {
        trackedEntities.add(uuid);

        // Expand bounds to include the entity
        Entity worldEntity = level.getEntity(uuid);
        if (worldEntity != null && EntityData.shouldSaveEntity(worldEntity)) {
            EntityData.expandBoundsForEntity(worldEntity, bounds);

            // Update cached stats
            cachedEntityCount++;
            String entityType = BuiltInRegistries.ENTITY_TYPE
                .getKey(worldEntity.getType()).toString();
            if (isMobEntity(entityType)) {
                cachedMobCount++;
            }
        }
    }

    /**
     * Adds an entity UUID without expanding bounds or updating stats.
     * Used when loading from file.
     */
    public void addEntityRaw(UUID uuid) {
        trackedEntities.add(uuid);
    }

    /**
     * Removes an entity UUID from this construction.
     * Recalculates bounds and updates cached stats.
     */
    public boolean removeEntity(UUID uuid, ServerLevel level) {
        boolean removed = trackedEntities.remove(uuid);
        if (removed) {
            // Update cached stats before the entity is gone
            Entity worldEntity = level.getEntity(uuid);
            if (worldEntity != null) {
                cachedEntityCount = Math.max(0, cachedEntityCount - 1);
                String entityType = BuiltInRegistries.ENTITY_TYPE
                    .getKey(worldEntity.getType()).toString();
                if (isMobEntity(entityType)) {
                    cachedMobCount = Math.max(0, cachedMobCount - 1);
                }
            }
            recalculateBounds(level);
        }
        return removed;
    }

    /**
     * Gets all tracked entity UUIDs.
     */
    public Set<UUID> getTrackedEntities() {
        return trackedEntities;
    }

    /**
     * Checks if an entity UUID is tracked.
     */
    public boolean isEntityTracked(UUID uuid) {
        return trackedEntities.contains(uuid);
    }

    /**
     * Clears all tracked entities.
     */
    public void clearTrackedEntities() {
        trackedEntities.clear();
        cachedEntityCount = 0;
        cachedMobCount = 0;
    }

    /**
     * Entity count (from cached stats).
     */
    public int getEntityCount() {
        return cachedEntityCount;
    }

    /**
     * Mob count (from cached stats).
     */
    public int getMobCount() {
        return cachedMobCount;
    }

    // ===== Cached stats management =====

    /**
     * Full recalculation of all cached stats from the world.
     * Called at save/push time and after bulk operations.
     */
    public void updateCachedStats(ServerLevel level) {
        cachedSolidBlockCount = 0;
        cachedCommandBlockCount = 0;
        cachedEntityCount = 0;
        cachedMobCount = 0;

        // Count block stats from world
        for (BlockPos pos : trackedBlocks) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                cachedSolidBlockCount++;
                String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                if (isCommandBlock(blockId)) {
                    cachedCommandBlockCount++;
                }
            }
        }

        // Count entity stats from world
        for (UUID entityUuid : trackedEntities) {
            Entity worldEntity = level.getEntity(entityUuid);
            if (worldEntity != null && EntityData.shouldSaveEntity(worldEntity)) {
                cachedEntityCount++;
                String entityType = BuiltInRegistries.ENTITY_TYPE
                    .getKey(worldEntity.getType()).toString();
                if (isMobEntity(entityType)) {
                    cachedMobCount++;
                }
            }
        }
    }

    /**
     * Sets cached stats directly (used when loading from metadata).
     */
    public void setCachedStats(int solidBlockCount, int entityCount, int mobCount, int commandBlockCount) {
        this.cachedSolidBlockCount = solidBlockCount;
        this.cachedEntityCount = entityCount;
        this.cachedMobCount = mobCount;
        this.cachedCommandBlockCount = commandBlockCount;
    }

    /**
     * Computes required mods by querying the world for block/entity namespaces.
     */
    public void computeRequiredMods(ServerLevel level) {
        requiredMods.clear();

        // Count blocks for each non-vanilla mod
        for (BlockPos pos : trackedBlocks) {
            BlockState state = level.getBlockState(pos);
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            String namespace = blockId.getNamespace();

            if (!"minecraft".equals(namespace)) {
                ModInfo info = requiredMods.computeIfAbsent(namespace, ModInfo::new);
                info.incrementBlockCount();
            }
        }

        // Count entities for each non-vanilla mod
        for (UUID entityUuid : trackedEntities) {
            Entity worldEntity = level.getEntity(entityUuid);
            if (worldEntity != null && EntityData.shouldSaveEntity(worldEntity)) {
                String entityType = BuiltInRegistries.ENTITY_TYPE
                    .getKey(worldEntity.getType()).toString();
                int colonIndex = entityType.indexOf(':');
                String namespace = colonIndex > 0 ? entityType.substring(0, colonIndex) : "minecraft";

                if (!"minecraft".equals(namespace)) {
                    ModInfo info = requiredMods.computeIfAbsent(namespace, ModInfo::new);
                    info.incrementEntitiesCount();
                }
            }
        }

        // Populate displayName, version and downloadUrl from loaded mods
        for (ModInfo info : requiredMods.values()) {
            FabricLoader.getInstance().getModContainer(info.getModId()).ifPresent(container -> {
                info.setDisplayName(container.getMetadata().getName());
                info.setVersion(container.getMetadata().getVersion().getFriendlyString());
                container.getMetadata().getContact().get("homepage")
                    .ifPresent(info::setDownloadUrl);
            });
        }
    }

    // ===== Total stats (base + all rooms) =====

    /**
     * Total block count (base + all rooms).
     */
    public int getTotalBlockCount() {
        int total = trackedBlocks.size();
        for (Room room : rooms.values()) {
            total += room.getChangedBlockCount();
        }
        return total;
    }

    /**
     * Total solid block count (base + all rooms).
     */
    public int getTotalSolidBlockCount() {
        int total = cachedSolidBlockCount;
        for (Room room : rooms.values()) {
            total += room.getCachedSolidBlockCount();
        }
        return total;
    }

    /**
     * Total air block count (base + all rooms).
     */
    public int getTotalAirBlockCount() {
        int total = getBlockCount() - cachedSolidBlockCount;
        for (Room room : rooms.values()) {
            total += room.getChangedBlockCount() - room.getCachedSolidBlockCount();
        }
        return total;
    }

    /**
     * Total entity count (base + all rooms).
     */
    public int getTotalEntityCount() {
        int total = cachedEntityCount;
        for (Room room : rooms.values()) {
            total += room.getEntityCount();
        }
        return total;
    }

    /**
     * Total mob count (base + all rooms).
     */
    public int getTotalMobCount() {
        int total = cachedMobCount;
        for (Room room : rooms.values()) {
            total += room.getCachedMobCount();
        }
        return total;
    }

    /**
     * Command blocks in base construction (from cached stats).
     */
    public int getCommandBlockCount() {
        return cachedCommandBlockCount;
    }

    /**
     * Total command blocks (base + all rooms).
     */
    public int getTotalCommandBlockCount() {
        int total = cachedCommandBlockCount;
        for (Room room : rooms.values()) {
            total += room.getCachedCommandBlockCount();
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
     * Checks if an entity type is a mob (living entity).
     * Public for use by other classes (e.g., NetworkHandler).
     */
    public static boolean isMobEntity(String entityType) {
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

    // Multilingual title getters/setters
    public Map<String, String> getTitles() { return titles; }
    public String getTitle(String lang) { return titles.getOrDefault(lang, ""); }
    public void setTitle(String lang, String title) {
        if (title != null && !title.trim().isEmpty()) {
            titles.put(lang, title);
        } else {
            titles.remove(lang);
        }
    }

    // Multilingual short description getters/setters
    public Map<String, String> getShortDescriptions() { return shortDescriptions; }
    public String getShortDescription(String lang) { return shortDescriptions.getOrDefault(lang, ""); }
    public void setShortDescription(String lang, String shortDescription) {
        if (shortDescription != null && !shortDescription.trim().isEmpty()) {
            shortDescriptions.put(lang, shortDescription);
        } else {
            shortDescriptions.remove(lang);
        }
    }

    // Multilingual full description getters/setters
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
     * Checks if the construction has at least one valid title (required for push).
     */
    public boolean hasValidTitle() {
        return !titles.isEmpty() && titles.values().stream().anyMatch(t -> t != null && !t.trim().isEmpty());
    }

    /**
     * Gets the title in the preferred language, with fallback to English or first available.
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
     * Gets the short description in the preferred language, with fallback.
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
     * Gets the full description in the preferred language, with fallback.
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
     * Copies tracked positions and entity UUIDs (NOT world data).
     */
    public Construction copyWithNewId(String newId) {
        Construction copy = new Construction(newId, this.authorId, this.authorName, this.createdAt,
            this.titles, this.shortDescriptions, this.descriptions);

        // Copy tracked block positions
        copy.trackedBlocks.addAll(this.trackedBlocks);

        // Copy tracked entity UUIDs
        copy.trackedEntities.addAll(this.trackedEntities);

        // Copy bounds
        if (this.bounds.isValid()) {
            copy.bounds.set(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
                bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ());
        }

        // Copy cached stats
        copy.cachedSolidBlockCount = this.cachedSolidBlockCount;
        copy.cachedEntityCount = this.cachedEntityCount;
        copy.cachedMobCount = this.cachedMobCount;
        copy.cachedCommandBlockCount = this.cachedCommandBlockCount;

        // Copy required mods
        copy.setRequiredMods(this.requiredMods);

        // Copy anchors
        if (this.anchors.hasEntrance()) {
            copy.getAnchors().setEntrance(this.anchors.getEntrance(), this.anchors.getEntranceYaw());
        }

        return copy;
    }

    // Getter/Setter for required mods
    public Map<String, ModInfo> getRequiredMods() {
        return requiredMods;
    }

    public void setRequiredMods(Map<String, ModInfo> mods) {
        this.requiredMods = mods != null ? new HashMap<>(mods) : new HashMap<>();
    }

    /**
     * Checks if the construction requires non-vanilla mods.
     */
    public boolean hasModdedBlocks() {
        return !requiredMods.isEmpty();
    }

    // ===== Room management =====

    public void addRoom(Room room) {
        rooms.put(room.getId(), room);
    }

    public Room getRoom(String id) {
        return rooms.get(id);
    }

    public boolean removeRoom(String id) {
        return rooms.remove(id) != null;
    }

    public Map<String, Room> getRooms() {
        return rooms;
    }

    public boolean hasRoom(String id) {
        return rooms.containsKey(id);
    }

    public int getRoomCount() {
        return rooms.size();
    }

    public Room getRoomByName(String name) {
        for (Room room : rooms.values()) {
            if (room.getName().equalsIgnoreCase(name)) {
                return room;
            }
        }
        return null;
    }
}
