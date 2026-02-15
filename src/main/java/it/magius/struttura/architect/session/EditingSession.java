package it.magius.struttura.architect.session;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EditMode;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.model.Room;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.placement.ConstructionOperations;
import it.magius.struttura.architect.registry.ConstructionRegistry;
import it.magius.struttura.architect.storage.ConstructionStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import it.magius.struttura.architect.entity.EntitySpawnHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Represents an active editing session for a player.
 * Tracks the current construction, mode, and entity state.
 */
public class EditingSession {

    // Active sessions per player
    private static final Map<UUID, EditingSession> ACTIVE_SESSIONS = new HashMap<>();

    // Player owning this session
    private final ServerPlayer player;

    // Construction being edited (can be replaced during rename)
    private Construction construction;

    // Current mode (ADD or REMOVE)
    private EditMode mode = EditMode.ADD;

    // Current room (null = base construction)
    private String currentRoom = null;

    // Hidden base entities during room editing (temporarily removed when entering a room)
    // Stores the EntityData so we can respawn them when exiting the room
    private final List<EntityData> hiddenBaseEntities = new ArrayList<>();

    // UUIDs of entities spawned for the current room (to remove when exiting)
    private final Set<UUID> spawnedRoomEntityUuids = new HashSet<>();

    // Saved base blocks before room application (for restoring when exiting room)
    private Map<BlockPos, BlockState> savedBaseBlocks = new HashMap<>();
    private Map<BlockPos, CompoundTag> savedBaseNbt = new HashMap<>();

    public EditingSession(ServerPlayer player, Construction construction) {
        this.player = player;
        this.construction = construction;
    }

    /**
     * Gets the active session for a player.
     */
    public static EditingSession getSession(ServerPlayer player) {
        return ACTIVE_SESSIONS.get(player.getUUID());
    }

    /**
     * Gets the active session for a UUID.
     */
    public static EditingSession getSession(UUID playerId) {
        return ACTIVE_SESSIONS.get(playerId);
    }

    /**
     * Checks if a player has an active session.
     */
    public static boolean hasSession(ServerPlayer player) {
        return ACTIVE_SESSIONS.containsKey(player.getUUID());
    }

    /**
     * Starts a new session for a player.
     * Automatically tracks existing entities in the construction bounds.
     */
    public static EditingSession startSession(ServerPlayer player, Construction construction) {
        EditingSession session = new EditingSession(player, construction);
        ACTIVE_SESSIONS.put(player.getUUID(), session);
        // Track entities already in the world (e.g., after a pull)
        session.trackExistingEntitiesInWorld();
        return session;
    }

    /**
     * Ends the session for a player.
     * If in a room, restores base blocks before exiting.
     * Note: Entities remain frozen in bounds even after exiting editing.
     */
    public static EditingSession endSession(ServerPlayer player) {
        EditingSession session = ACTIVE_SESSIONS.remove(player.getUUID());
        if (session != null) {
            // If in a room, restore base blocks
            if (session.currentRoom != null) {
                session.exitRoom();
            }
            // Update cached stats from world (captures final state)
            ServerLevel level = (ServerLevel) player.level();
            session.construction.updateCachedStats(level);
        }
        return session;
    }

    /**
     * Gets all active sessions.
     */
    public static java.util.Collection<EditingSession> getAllSessions() {
        return ACTIVE_SESSIONS.values();
    }

    /**
     * Handles block placement.
     * If in a room, saves in the room; otherwise in the base construction.
     */
    public void onBlockPlaced(BlockPos pos) {
        if (mode == EditMode.ADD) {
            ServerLevel level = (ServerLevel) player.level();
            if (isInRoom()) {
                // Editing a room: save in the room
                Room room = construction.getRoom(currentRoom);
                if (room != null) {
                    room.setBlockChange(pos);
                    // Expand construction bounds if block is outside current bounds
                    construction.getBounds().expandToInclude(pos);
                }
            } else {
                // Editing base construction
                construction.addBlock(pos, level);
            }
            // Update wireframe (bounds may have changed)
            NetworkHandler.sendWireframeSync(player);
            // Update editing info for GUI
            NetworkHandler.sendEditingInfo(player);
            // Update block positions
            NetworkHandler.sendBlockPositions(player);
        }
        // In REMOVE mode, placement does nothing special
    }

    /**
     * Handles block breaking.
     * If in a room, saves air in the room; otherwise in the base construction.
     */
    public void onBlockBroken(BlockPos pos) {
        if (mode == EditMode.ADD) {
            ServerLevel level = (ServerLevel) player.level();
            if (isInRoom()) {
                // Editing a room: air in the room
                Room room = construction.getRoom(currentRoom);
                if (room != null) {
                    room.setBlockChange(pos);
                    // Expand construction bounds if block is outside current bounds
                    construction.getBounds().expandToInclude(pos);
                }
            } else {
                // Editing base construction: breaking a block adds air
                construction.addBlock(pos, level);
            }
            // Update wireframe (bounds may have changed)
            NetworkHandler.sendWireframeSync(player);
            // Update editing info for GUI
            NetworkHandler.sendEditingInfo(player);
            // Update block positions
            NetworkHandler.sendBlockPositions(player);
        } else {
            ServerLevel level = (ServerLevel) player.level();
            if (isInRoom()) {
                // In REMOVE in a room: remove from the room
                Room room = construction.getRoom(currentRoom);
                if (room != null) {
                    room.removeBlockChange(pos);
                }
            } else {
                // In REMOVE on base: remove from construction
                construction.removeBlock(pos, level);
            }
            // Update wireframe (bounds have been recalculated)
            NetworkHandler.sendWireframeSync(player);
            // Update editing info for GUI
            NetworkHandler.sendEditingInfo(player);
            // Update block positions
            NetworkHandler.sendBlockPositions(player);
        }
    }

    // ===== Room management =====

    /**
     * Enters an existing room for editing.
     * Applies room blocks in the world and manages entities:
     * - Hides base construction entities (without triggers)
     * - Spawns room entities from disk
     * @param roomId the room ID
     * @return true if entry was successful
     */
    public boolean enterRoom(String roomId) {
        Room room = construction.getRoom(roomId);
        if (room == null) {
            Architect.LOGGER.warn("Cannot enter room '{}': room does not exist in construction {}",
                roomId, construction.getId());
            return false;
        }

        ServerLevel world = (ServerLevel) player.level();

        // If we were already in a room, capture data and restore first
        if (currentRoom != null) {
            Room previousRoom = construction.getRoom(currentRoom);
            if (previousRoom != null) {
                // Remove spawned room entities
                removeSpawnedRoomEntities(world);
                restoreBaseBlocks(world, previousRoom);
                // Respawn hidden base entities
                respawnHiddenBaseEntities(world);
            }
        }

        // Hide base construction entities
        hideBaseEntities(world);

        // Apply room blocks in the world
        applyRoomBlocks(world, room);

        // Spawn room entities from disk
        spawnRoomEntities(world, room);

        this.currentRoom = roomId;
        Architect.LOGGER.info("Player {} entered room '{}' ({}) in construction {}",
            player.getName().getString(), room.getName(), roomId, construction.getId());

        // Update UI
        NetworkHandler.sendWireframeSync(player);
        NetworkHandler.sendEditingInfo(player);
        NetworkHandler.sendBlockPositions(player);

        return true;
    }

    /**
     * Exits the current room and returns to base editing.
     * Removes room entities, restores base blocks and respawns base entities.
     */
    public void exitRoom() {
        if (currentRoom == null) {
            return;
        }

        ServerLevel world = (ServerLevel) player.level();
        Room room = construction.getRoom(currentRoom);

        if (room != null) {
            // Remove spawned room entities
            removeSpawnedRoomEntities(world);

            // Restore base blocks where the room had modifications
            restoreBaseBlocks(world, room);

            // Respawn base entities that were hidden
            respawnHiddenBaseEntities(world);
        }

        String previousRoom = currentRoom;
        this.currentRoom = null;

        Architect.LOGGER.info("Player {} exited room '{}' in construction {}",
            player.getName().getString(), previousRoom, construction.getId());

        // Update UI
        NetworkHandler.sendWireframeSync(player);
        NetworkHandler.sendEditingInfo(player);
        NetworkHandler.sendBlockPositions(player);
    }

    /**
     * Hides base construction entities when entering a room.
     * All tracked entities are hidden. EntityData is captured for respawning later.
     */
    private void hideBaseEntities(ServerLevel world) {
        var bounds = construction.getBounds();

        if (!bounds.isValid()) {
            return;
        }

        hiddenBaseEntities.clear();
        int hiddenCount = 0;

        // Hide all tracked entities
        for (UUID entityUuid : construction.getTrackedEntities()) {
            Entity entity = world.getEntity(entityUuid);
            if (entity != null && EntityData.shouldSaveEntity(entity)) {
                // Save entity data before hiding
                EntityData data = EntityData.fromEntity(entity, bounds, world.registryAccess());
                hiddenBaseEntities.add(data);

                // Remove entity without triggers (discard)
                entity.discard();
                hiddenCount++;
            }
        }

        Architect.LOGGER.debug("Hidden {} base entities for room editing", hiddenCount);
    }

    /**
     * Respawns base construction entities that were hidden when exiting the room.
     */
    private void respawnHiddenBaseEntities(ServerLevel world) {
        if (hiddenBaseEntities.isEmpty()) {
            return;
        }

        var bounds = construction.getBounds();
        if (!bounds.isValid()) {
            hiddenBaseEntities.clear();
            return;
        }

        int originX = bounds.getMinX();
        int originY = bounds.getMinY();
        int originZ = bounds.getMinZ();

        // Clear tracked entities - they'll be re-populated with new UUIDs
        construction.clearTrackedEntities();

        int respawnedCount = 0;

        for (EntityData data : hiddenBaseEntities) {
            try {
                // Calculate world position
                double worldX = originX + data.getRelativePos().x;
                double worldY = originY + data.getRelativePos().y;
                double worldZ = originZ + data.getRelativePos().z;

                // Copy NBT and remove/update position tags
                CompoundTag nbt = data.getNbt().copy();
                nbt.remove("Pos");
                nbt.remove("Motion");
                nbt.remove("UUID");

                // Ensure NBT contains the "id" tag
                if (!nbt.contains("id")) {
                    nbt.putString("id", data.getEntityType());
                }

                // Update block_pos for hanging entities
                if (nbt.contains("block_pos")) {
                    net.minecraft.nbt.Tag rawTag = nbt.get("block_pos");
                    if (rawTag instanceof net.minecraft.nbt.IntArrayTag intArrayTag) {
                        int[] coords = intArrayTag.getAsIntArray();
                        if (coords.length >= 3) {
                            int newX = originX + coords[0];
                            int newY = originY + coords[1];
                            int newZ = originZ + coords[2];
                            nbt.putIntArray("block_pos", new int[] { newX, newY, newZ });
                        }
                    }
                }

                // Create entity from NBT
                Entity entity = net.minecraft.world.entity.EntityType.loadEntityRecursive(
                    nbt, world, net.minecraft.world.entity.EntitySpawnReason.LOAD, e -> e);

                if (entity != null) {
                    entity.setPos(worldX, worldY, worldZ);
                    entity.setYRot(data.getYaw());
                    entity.setXRot(data.getPitch());
                    UUID newUuid = UUID.randomUUID();
                    entity.setUUID(newUuid);

                    // Register entity to ignore BEFORE adding to world
                    // to prevent EntitySpawnHandler from auto-adding it
                    EntitySpawnHandler.getInstance().ignoreEntity(newUuid);

                    world.addFreshEntity(entity);

                    // Track the new UUID in construction
                    construction.addEntity(newUuid, world);
                    respawnedCount++;
                }
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to respawn base entity of type {}: {}",
                    data.getEntityType(), e.getMessage());
            }
        }

        hiddenBaseEntities.clear();
        Architect.LOGGER.debug("Respawned {} hidden base entities", respawnedCount);
    }

    /**
     * Spawns room entities into the world.
     * Loads entity data from disk via ConstructionStorage, spawns them, and tracks UUIDs.
     */
    private void spawnRoomEntities(ServerLevel world, Room room) {
        // Load room entity data from disk via the registry's storage
        ConstructionStorage storage = ConstructionRegistry.getInstance().getStorage();
        if (storage == null) {
            Architect.LOGGER.warn("Cannot spawn room entities: storage not initialized");
            return;
        }
        List<EntityData> roomEntityData = storage.loadRoomEntities(construction, room.getId());

        if (roomEntityData.isEmpty()) {
            return;
        }

        // Calculate origin from construction bounds
        var bounds = construction.getBounds();
        if (!bounds.isValid()) {
            return;
        }

        int originX = bounds.getMinX();
        int originY = bounds.getMinY();
        int originZ = bounds.getMinZ();

        int spawnedCount = 0;
        spawnedRoomEntityUuids.clear();

        for (EntityData data : roomEntityData) {
            try {
                // Calculate world position
                double worldX = originX + data.getRelativePos().x;
                double worldY = originY + data.getRelativePos().y;
                double worldZ = originZ + data.getRelativePos().z;

                // Copy NBT and remove/update position tags
                CompoundTag nbt = data.getNbt().copy();
                nbt.remove("Pos");
                nbt.remove("Motion");
                nbt.remove("UUID");

                // Ensure NBT contains the "id" tag
                if (!nbt.contains("id")) {
                    nbt.putString("id", data.getEntityType());
                }

                // Update block_pos for hanging entities
                if (nbt.contains("block_pos")) {
                    net.minecraft.nbt.Tag rawTag = nbt.get("block_pos");
                    if (rawTag instanceof net.minecraft.nbt.IntArrayTag intArrayTag) {
                        int[] coords = intArrayTag.getAsIntArray();
                        if (coords.length >= 3) {
                            int newX = originX + coords[0];
                            int newY = originY + coords[1];
                            int newZ = originZ + coords[2];
                            nbt.putIntArray("block_pos", new int[] { newX, newY, newZ });
                        }
                    }
                }

                // Create entity from NBT
                Entity entity = net.minecraft.world.entity.EntityType.loadEntityRecursive(
                    nbt, world, net.minecraft.world.entity.EntitySpawnReason.LOAD, e -> e);

                if (entity != null) {
                    entity.setPos(worldX, worldY, worldZ);
                    entity.setYRot(data.getYaw());
                    entity.setXRot(data.getPitch());
                    UUID newUuid = UUID.randomUUID();
                    entity.setUUID(newUuid);

                    // Register entity to ignore BEFORE adding to world
                    // to prevent EntitySpawnHandler from auto-adding it
                    EntitySpawnHandler.getInstance().ignoreEntity(newUuid);

                    world.addFreshEntity(entity);

                    // Track the new UUID in the room
                    room.addEntity(newUuid);
                    spawnedRoomEntityUuids.add(newUuid);
                    spawnedCount++;
                }
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to spawn room entity of type {}: {}",
                    data.getEntityType(), e.getMessage());
            }
        }

        Architect.LOGGER.debug("Spawned {} entities for room '{}'", spawnedCount, room.getId());
    }

    /**
     * Removes entities spawned for the current room.
     */
    private void removeSpawnedRoomEntities(ServerLevel world) {
        Architect.LOGGER.debug("removeSpawnedRoomEntities: {} entities to remove", spawnedRoomEntityUuids.size());
        int removedCount = 0;

        for (UUID activeUuid : spawnedRoomEntityUuids) {
            Entity entity = world.getEntity(activeUuid);
            if (entity != null) {
                // Unfreeze entity before removing
                it.magius.struttura.architect.entity.EntityFreezeHandler.getInstance().unfreezeEntity(activeUuid);
                // Remove from ignored entities list
                EntitySpawnHandler.getInstance().unignoreEntity(activeUuid);
                // Remove the entity
                entity.discard();
                removedCount++;
            }
        }

        // Also remove the room entity UUIDs from the room's tracking
        // (they were spawned entities, not permanent)
        Room room = currentRoom != null ? construction.getRoom(currentRoom) : null;
        if (room != null) {
            for (UUID uuid : spawnedRoomEntityUuids) {
                room.removeEntity(uuid);
            }
        }

        spawnedRoomEntityUuids.clear();
        Architect.LOGGER.debug("Removed {} room entities", removedCount);
    }

    /**
     * Applies room delta blocks in the world.
     * Saves base blocks first (for restoration on exit), then loads room snapshot from disk
     * and delegates to ConstructionOperations.placeRoomBlocks.
     */
    private void applyRoomBlocks(ServerLevel world, Room room) {
        // Save base blocks at room positions BEFORE overwriting them
        savedBaseBlocks.clear();
        savedBaseNbt.clear();
        for (BlockPos pos : room.getChangedBlocks()) {
            savedBaseBlocks.put(pos, world.getBlockState(pos));
            net.minecraft.world.level.block.entity.BlockEntity be = world.getBlockEntity(pos);
            if (be != null) {
                CompoundTag nbt = be.saveWithoutMetadata(world.registryAccess());
                nbt.remove("x");
                nbt.remove("y");
                nbt.remove("z");
                nbt.remove("id");
                if (!nbt.isEmpty()) {
                    savedBaseNbt.put(pos, nbt);
                }
            }
        }

        // Load room snapshot from disk
        ConstructionStorage storage = ConstructionRegistry.getInstance().getStorage();
        if (storage == null) {
            Architect.LOGGER.error("Cannot apply room blocks: storage not initialized");
            return;
        }
        it.magius.struttura.architect.model.ConstructionSnapshot fullSnapshot =
            storage.loadNbtOnly(construction.getId(), construction.getBounds());
        if (fullSnapshot == null || !fullSnapshot.rooms().containsKey(room.getId())) {
            Architect.LOGGER.warn("No saved room snapshot for room '{}', skipping block application", room.getId());
            return;
        }
        it.magius.struttura.architect.model.ConstructionSnapshot.RoomSnapshot roomSnapshot =
            fullSnapshot.rooms().get(room.getId());

        int appliedCount = ConstructionOperations.placeRoomBlocks(world, roomSnapshot);
        Architect.LOGGER.debug("Applied {} room blocks for room '{}' in construction {}",
            appliedCount, room.getId(), construction.getId());
    }

    /**
     * Restores base construction blocks where the room had modifications.
     * Uses the saved base blocks captured before room application.
     */
    private void restoreBaseBlocks(ServerLevel world, Room room) {
        int restoredCount = ConstructionOperations.restoreBaseBlocks(
            world, room.getChangedBlocks(), savedBaseBlocks, savedBaseNbt);
        savedBaseBlocks.clear();
        savedBaseNbt.clear();
        Architect.LOGGER.debug("Restored {} base blocks after exiting room '{}' in construction {}",
            restoredCount, room.getId(), construction.getId());
    }

    /**
     * Creates a new room and enters it.
     * @param name the room name
     * @return the created room, or null if it couldn't be created
     */
    public Room createRoom(String name) {
        // Check limits
        if (construction.getRoomCount() >= 50) {
            Architect.LOGGER.warn("Cannot create room: max rooms (50) reached for construction {}",
                construction.getId());
            return null;
        }

        // Create the room
        Room room = new Room(name);

        // Verify unique ID
        if (construction.hasRoom(room.getId())) {
            // Add numeric suffix
            int suffix = 2;
            String baseId = room.getId();
            while (construction.hasRoom(baseId + "_" + suffix)) {
                suffix++;
            }
            room = new Room(baseId + "_" + suffix, name, room.getCreatedAt());
        }

        construction.addRoom(room);

        Architect.LOGGER.info("Player {} created room '{}' ({}) in construction {}",
            player.getName().getString(), name, room.getId(), construction.getId());

        // Enter the newly created room
        enterRoom(room.getId());

        return room;
    }

    /**
     * Deletes a room.
     * If currently editing that room, exits first.
     * @param roomId the ID of the room to delete
     * @return true if the room was deleted
     */
    public boolean deleteRoom(String roomId) {
        // If editing this room, exit first
        if (roomId.equals(currentRoom)) {
            exitRoom();
        }

        boolean removed = construction.removeRoom(roomId);

        if (removed) {
            Architect.LOGGER.info("Player {} deleted room '{}' from construction {}",
                player.getName().getString(), roomId, construction.getId());

            // Update UI
            NetworkHandler.sendEditingInfo(player);
        }

        return removed;
    }

    /**
     * Renames a room. Changes both name and ID (derived from name).
     * @param roomId current room ID
     * @param newName new name
     * @return the new room ID, or null if it fails
     */
    public String renameRoom(String roomId, String newName) {
        // Generate the new ID from name and delegate
        String newId = Room.generateId(newName);
        return renameRoomWithId(roomId, newId, newName);
    }

    /**
     * Renames a room specifying both new ID and name.
     * Used by GUI when setting a custom ID.
     *
     * @param roomId current room ID
     * @param newId new ID (can differ from name)
     * @param newName new name
     * @return the new room ID, or null if it fails (ID already exists)
     */
    public String renameRoomWithId(String roomId, String newId, String newName) {
        Room oldRoom = construction.getRoom(roomId);
        if (oldRoom == null) {
            return null;
        }

        // If the ID doesn't change, update only the name
        if (newId.equals(roomId)) {
            oldRoom.setName(newName);
            Architect.LOGGER.info("Player {} renamed room '{}' (same ID) in construction {}",
                player.getName().getString(), roomId, construction.getId());
            NetworkHandler.sendEditingInfo(player);
            return newId;
        }

        // Verify new ID doesn't already exist
        if (construction.hasRoom(newId)) {
            Architect.LOGGER.warn("Cannot rename room '{}' to '{}': ID already exists",
                roomId, newId);
            return null;
        }

        // Create a new room with the new ID/name, copying data
        Room newRoom = oldRoom.copyWithNewName(newName);

        // Remove old room and add new one
        construction.removeRoom(roomId);
        construction.addRoom(newRoom);

        // If user was in this room, update the reference
        if (roomId.equals(currentRoom)) {
            currentRoom = newRoom.getId();
        }

        Architect.LOGGER.info("Player {} renamed room '{}' to '{}' (new ID: '{}') in construction {}",
            player.getName().getString(), roomId, newName, newRoom.getId(), construction.getId());

        // Update UI
        NetworkHandler.sendEditingInfo(player);

        return newRoom.getId();
    }

    /**
     * Gets the current room being edited, or null if not in a room.
     */
    public Room getCurrentRoomObject() {
        if (currentRoom == null) {
            return null;
        }
        return construction.getRoom(currentRoom);
    }

    /**
     * Gets the effective block count for display.
     * If in a room, sums base blocks + delta changes.
     */
    public int getEffectiveBlockCount() {
        if (!isInRoom()) {
            return construction.getBlockCount();
        }

        Room room = getCurrentRoomObject();
        if (room == null) {
            return construction.getBlockCount();
        }

        // Base blocks + delta changes (approximate)
        return construction.getBlockCount() + room.getChangedBlockCount();
    }

    // Getters and Setters
    public ServerPlayer getPlayer() { return player; }
    public Construction getConstruction() { return construction; }
    public void setConstruction(Construction construction) { this.construction = construction; }

    public EditMode getMode() { return mode; }
    public void setMode(EditMode mode) { this.mode = mode; }

    public String getCurrentRoom() { return currentRoom; }
    public void setCurrentRoom(String currentRoom) { this.currentRoom = currentRoom; }

    public boolean isInRoom() { return currentRoom != null; }

    /**
     * Checks if an entity is tracked in the current context.
     * Delegates to construction or room entity tracking.
     */
    public boolean isEntityTracked(UUID entityUuid) {
        if (isInRoom()) {
            Room room = getCurrentRoomObject();
            if (room != null) {
                return room.getRoomEntities().contains(entityUuid);
            }
        }
        return construction.isEntityTracked(entityUuid);
    }

    /**
     * Scans the world for existing entities in the construction bounds and tracks them.
     * Called when starting an edit session to protect entities that were
     * spawned before the session started (e.g., after a pull).
     * Simply adds UUIDs to trackedEntities directly.
     */
    public void trackExistingEntitiesInWorld() {
        var bounds = construction.getBounds();
        if (!bounds.isValid()) {
            return;
        }

        ServerLevel world = (ServerLevel) player.level();

        // Create AABB from bounds
        AABB area = new AABB(
            bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
            bounds.getMaxX() + 1, bounds.getMaxY() + 1, bounds.getMaxZ() + 1
        );

        // Get all entities in the area
        List<Entity> worldEntities = world.getEntities(
            (Entity) null,
            area,
            EntityData::shouldSaveEntity
        );

        int trackedCount = 0;

        // Add all eligible entities to tracked set
        for (Entity worldEntity : worldEntities) {
            UUID entityUuid = worldEntity.getUUID();
            if (!construction.isEntityTracked(entityUuid)) {
                construction.addEntity(entityUuid, world);
                trackedCount++;
            }
        }

        Architect.LOGGER.debug("trackExistingEntitiesInWorld: tracked {} new entities for construction {}",
            trackedCount, construction.getId());
    }
}
