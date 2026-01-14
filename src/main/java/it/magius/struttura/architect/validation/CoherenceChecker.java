package it.magius.struttura.architect.validation;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.model.Room;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * Utility class to validate coherence between registry data and world state.
 * Used to detect bugs where blocks/entities are not properly synchronized.
 */
public class CoherenceChecker {

    private static final double ENTITY_POSITION_TOLERANCE = 0.5;

    /**
     * Checks coherence of a single block in a construction.
     * @param level The server level
     * @param construction The construction containing the block
     * @param pos The block position (absolute world coordinates)
     * @param expectedState The expected block state from registry
     * @param checkInWorld If true, also verify the block exists in the world
     * @return true if coherent, false if there's a mismatch
     */
    public static boolean checkBlockCoherence(ServerLevel level, Construction construction,
                                               BlockPos pos, BlockState expectedState, boolean checkInWorld) {
        // Check if block position is within construction bounds
        var bounds = construction.getBounds();
        if (bounds.isValid()) {
            if (pos.getX() < bounds.getMinX() || pos.getX() > bounds.getMaxX() ||
                pos.getY() < bounds.getMinY() || pos.getY() > bounds.getMaxY() ||
                pos.getZ() < bounds.getMinZ() || pos.getZ() > bounds.getMaxZ()) {
                Architect.LOGGER.error("COHERENCE ERROR: Block at {} is OUTSIDE construction {} bounds [{},{},{} to {},{},{}]",
                    pos, construction.getId(),
                    bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
                    bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ());
                return false;
            }
        }

        // Check if block exists in construction registry
        BlockState registryState = construction.getBlocks().get(pos);

        if (registryState == null) {
            Architect.LOGGER.error("COHERENCE ERROR: Block at {} not found in construction {} registry",
                pos, construction.getId());
            return false;
        }

        if (!registryState.equals(expectedState)) {
            Architect.LOGGER.error("COHERENCE ERROR: Block at {} in construction {} has wrong state. Expected: {}, Got: {}",
                pos, construction.getId(), expectedState, registryState);
            return false;
        }

        if (checkInWorld) {
            BlockState worldState = level.getBlockState(pos);
            if (!worldState.equals(expectedState)) {
                Architect.LOGGER.error("COHERENCE ERROR: Block at {} in world doesn't match registry. World: {}, Registry: {}",
                    pos, worldState, expectedState);
                return false;
            }
        }

        return true;
    }

    /**
     * Checks coherence of a single block in a room.
     * @param level The server level
     * @param construction The parent construction
     * @param room The room containing the block change
     * @param pos The block position (absolute world coordinates)
     * @param expectedState The expected block state from room's block changes
     * @param checkInWorld If true, also verify the block exists in the world
     * @return true if coherent, false if there's a mismatch
     */
    public static boolean checkRoomBlockCoherence(ServerLevel level, Construction construction, Room room,
                                                   BlockPos pos, BlockState expectedState, boolean checkInWorld) {
        // Check if block position is within construction bounds
        var bounds = construction.getBounds();
        if (bounds.isValid()) {
            if (pos.getX() < bounds.getMinX() || pos.getX() > bounds.getMaxX() ||
                pos.getY() < bounds.getMinY() || pos.getY() > bounds.getMaxY() ||
                pos.getZ() < bounds.getMinZ() || pos.getZ() > bounds.getMaxZ()) {
                Architect.LOGGER.error("COHERENCE ERROR: Room {} block at {} is OUTSIDE construction {} bounds [{},{},{} to {},{},{}]",
                    room.getId(), pos, construction.getId(),
                    bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
                    bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ());
                return false;
            }
        }

        // Check if block exists in room's block changes
        BlockState roomState = room.getBlockChange(pos);

        if (roomState == null) {
            Architect.LOGGER.error("COHERENCE ERROR: Block at {} not found in room {} of construction {}",
                pos, room.getId(), construction.getId());
            return false;
        }

        if (!roomState.equals(expectedState)) {
            Architect.LOGGER.error("COHERENCE ERROR: Block at {} in room {} has wrong state. Expected: {}, Got: {}",
                pos, room.getId(), expectedState, roomState);
            return false;
        }

        if (checkInWorld) {
            BlockState worldState = level.getBlockState(pos);
            if (!worldState.equals(expectedState)) {
                Architect.LOGGER.error("COHERENCE ERROR: Block at {} in world doesn't match room {}. World: {}, Room: {}",
                    pos, room.getId(), worldState, expectedState);
                return false;
            }
        }

        return true;
    }

    /**
     * Checks coherence of a single entity in a construction.
     * @param level The server level
     * @param construction The construction containing the entity
     * @param entityIndex The index of the entity in the construction's entity list
     * @param checkInWorld If true, also verify an entity exists at the expected position in the world
     * @return true if coherent, false if there's a mismatch
     */
    public static boolean checkEntityCoherence(ServerLevel level, Construction construction,
                                                int entityIndex, boolean checkInWorld) {
        List<EntityData> entities = construction.getEntities();

        if (entityIndex < 0 || entityIndex >= entities.size()) {
            Architect.LOGGER.error("COHERENCE ERROR: Entity index {} out of bounds for construction {} (size: {})",
                entityIndex, construction.getId(), entities.size());
            return false;
        }

        EntityData entityData = entities.get(entityIndex);

        if (checkInWorld) {
            var bounds = construction.getBounds();
            if (!bounds.isValid()) {
                Architect.LOGGER.error("COHERENCE ERROR: Construction {} has invalid bounds, cannot check entity in world",
                    construction.getId());
                return false;
            }

            // Calculate expected world position
            double expectedX = bounds.getMinX() + entityData.getRelativePos().x;
            double expectedY = bounds.getMinY() + entityData.getRelativePos().y;
            double expectedZ = bounds.getMinZ() + entityData.getRelativePos().z;

            // Search for entity at expected position
            AABB searchArea = new AABB(
                expectedX - ENTITY_POSITION_TOLERANCE, expectedY - ENTITY_POSITION_TOLERANCE, expectedZ - ENTITY_POSITION_TOLERANCE,
                expectedX + ENTITY_POSITION_TOLERANCE, expectedY + ENTITY_POSITION_TOLERANCE, expectedZ + ENTITY_POSITION_TOLERANCE
            );

            List<Entity> foundEntities = level.getEntities(
                (Entity) null,
                searchArea,
                e -> {
                    String eType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(e.getType()).toString();
                    return eType.equals(entityData.getEntityType());
                }
            );

            if (foundEntities.isEmpty()) {
                Architect.LOGGER.error("COHERENCE ERROR: Entity {} of type {} not found in world at expected position ({}, {}, {})",
                    entityIndex, entityData.getEntityType(), expectedX, expectedY, expectedZ);
                return false;
            }
        }

        return true;
    }

    /**
     * Checks coherence of a single entity in a room.
     * @param level The server level
     * @param construction The parent construction
     * @param room The room containing the entity
     * @param entityIndex The index of the entity in the room's entity list
     * @param checkInWorld If true, also verify an entity exists at the expected position in the world
     * @return true if coherent, false if there's a mismatch
     */
    public static boolean checkRoomEntityCoherence(ServerLevel level, Construction construction, Room room,
                                                    int entityIndex, boolean checkInWorld) {
        List<EntityData> entities = room.getEntities();

        if (entityIndex < 0 || entityIndex >= entities.size()) {
            Architect.LOGGER.error("COHERENCE ERROR: Entity index {} out of bounds for room {} (size: {})",
                entityIndex, room.getId(), entities.size());
            return false;
        }

        EntityData entityData = entities.get(entityIndex);

        if (checkInWorld) {
            var bounds = construction.getBounds();
            if (!bounds.isValid()) {
                Architect.LOGGER.error("COHERENCE ERROR: Construction {} has invalid bounds, cannot check room entity in world",
                    construction.getId());
                return false;
            }

            // Calculate expected world position
            double expectedX = bounds.getMinX() + entityData.getRelativePos().x;
            double expectedY = bounds.getMinY() + entityData.getRelativePos().y;
            double expectedZ = bounds.getMinZ() + entityData.getRelativePos().z;

            // Search for entity at expected position
            AABB searchArea = new AABB(
                expectedX - ENTITY_POSITION_TOLERANCE, expectedY - ENTITY_POSITION_TOLERANCE, expectedZ - ENTITY_POSITION_TOLERANCE,
                expectedX + ENTITY_POSITION_TOLERANCE, expectedY + ENTITY_POSITION_TOLERANCE, expectedZ + ENTITY_POSITION_TOLERANCE
            );

            List<Entity> foundEntities = level.getEntities(
                (Entity) null,
                searchArea,
                e -> {
                    String eType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(e.getType()).toString();
                    return eType.equals(entityData.getEntityType());
                }
            );

            if (foundEntities.isEmpty()) {
                Architect.LOGGER.error("COHERENCE ERROR: Room {} entity {} of type {} not found in world at expected position ({}, {}, {})",
                    room.getId(), entityIndex, entityData.getEntityType(), expectedX, expectedY, expectedZ);
                return false;
            }
        }

        return true;
    }

    /**
     * Validates all blocks in a construction against the registry.
     * @param level The server level
     * @param construction The construction to validate
     * @param checkInWorld If true, also verify blocks exist in the world
     * @return true if all blocks are coherent, false if any mismatch found
     */
    public static boolean validateAllBlocks(ServerLevel level, Construction construction, boolean checkInWorld) {
        return validateAllBlocks(level, construction, checkInWorld, null);
    }

    /**
     * Validates all blocks in a construction against the registry.
     * @param level The server level
     * @param construction The construction to validate
     * @param checkInWorld If true, also verify blocks exist in the world
     * @param activeRoom If not null, skip world check for blocks that are overridden by this room
     * @return true if all blocks are coherent, false if any mismatch found
     */
    public static boolean validateAllBlocks(ServerLevel level, Construction construction, boolean checkInWorld, Room activeRoom) {
        boolean allCoherent = true;
        int errorCount = 0;

        for (Map.Entry<BlockPos, BlockState> entry : construction.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();

            // If there's an active room and this position is overridden by it, skip world check
            // (the room block is in the world, not the base block)
            boolean skipWorldCheck = activeRoom != null && activeRoom.hasBlockChange(pos);
            boolean doCheckInWorld = checkInWorld && !skipWorldCheck;

            if (!checkBlockCoherence(level, construction, pos, entry.getValue(), doCheckInWorld)) {
                allCoherent = false;
                errorCount++;
                if (errorCount >= 10) {
                    Architect.LOGGER.error("COHERENCE ERROR: Too many block errors ({}+), stopping validation for construction {}",
                        errorCount, construction.getId());
                    break;
                }
            }
        }

        if (!allCoherent) {
            Architect.LOGGER.error("COHERENCE VALIDATION FAILED: Construction {} has {} block coherence errors",
                construction.getId(), errorCount);
        }

        return allCoherent;
    }

    /**
     * Validates all entities in a construction.
     * @param level The server level
     * @param construction The construction to validate
     * @param checkInWorld If true, also verify entities exist in the world
     * @return true if all entities are coherent, false if any mismatch found
     */
    public static boolean validateAllEntities(ServerLevel level, Construction construction, boolean checkInWorld) {
        boolean allCoherent = true;
        int errorCount = 0;

        List<EntityData> entities = construction.getEntities();
        for (int i = 0; i < entities.size(); i++) {
            if (!checkEntityCoherence(level, construction, i, checkInWorld)) {
                allCoherent = false;
                errorCount++;
                if (errorCount >= 10) {
                    Architect.LOGGER.error("COHERENCE ERROR: Too many entity errors ({}+), stopping validation for construction {}",
                        errorCount, construction.getId());
                    break;
                }
            }
        }

        if (!allCoherent) {
            Architect.LOGGER.error("COHERENCE VALIDATION FAILED: Construction {} has {} entity coherence errors",
                construction.getId(), errorCount);
        }

        return allCoherent;
    }

    /**
     * Validates all blocks in a room.
     * @param level The server level
     * @param construction The parent construction
     * @param room The room to validate
     * @param checkInWorld If true, also verify blocks exist in the world
     * @return true if all blocks are coherent, false if any mismatch found
     */
    public static boolean validateAllRoomBlocks(ServerLevel level, Construction construction, Room room, boolean checkInWorld) {
        boolean allCoherent = true;
        int errorCount = 0;

        for (Map.Entry<BlockPos, BlockState> entry : room.getBlockChanges().entrySet()) {
            if (!checkRoomBlockCoherence(level, construction, room, entry.getKey(), entry.getValue(), checkInWorld)) {
                allCoherent = false;
                errorCount++;
                if (errorCount >= 10) {
                    Architect.LOGGER.error("COHERENCE ERROR: Too many block errors ({}+), stopping validation for room {}",
                        errorCount, room.getId());
                    break;
                }
            }
        }

        if (!allCoherent) {
            Architect.LOGGER.error("COHERENCE VALIDATION FAILED: Room {} has {} block coherence errors",
                room.getId(), errorCount);
        }

        return allCoherent;
    }

    /**
     * Validates all entities in a room.
     * @param level The server level
     * @param construction The parent construction
     * @param room The room to validate
     * @param checkInWorld If true, also verify entities exist in the world
     * @return true if all entities are coherent, false if any mismatch found
     */
    public static boolean validateAllRoomEntities(ServerLevel level, Construction construction, Room room, boolean checkInWorld) {
        boolean allCoherent = true;
        int errorCount = 0;

        List<EntityData> entities = room.getEntities();
        for (int i = 0; i < entities.size(); i++) {
            if (!checkRoomEntityCoherence(level, construction, room, i, checkInWorld)) {
                allCoherent = false;
                errorCount++;
                if (errorCount >= 10) {
                    Architect.LOGGER.error("COHERENCE ERROR: Too many entity errors ({}+), stopping validation for room {}",
                        errorCount, room.getId());
                    break;
                }
            }
        }

        if (!allCoherent) {
            Architect.LOGGER.error("COHERENCE VALIDATION FAILED: Room {} has {} entity coherence errors",
                room.getId(), errorCount);
        }

        return allCoherent;
    }

    /**
     * Full validation of a construction and all its rooms.
     * @param level The server level
     * @param construction The construction to validate
     * @param checkInWorld If true, also verify blocks/entities exist in the world
     * @return true if everything is coherent, false if any mismatch found
     */
    public static boolean validateConstruction(ServerLevel level, Construction construction, boolean checkInWorld) {
        return validateConstruction(level, construction, checkInWorld, null);
    }

    /**
     * Full validation of a construction and all its rooms.
     * @param level The server level
     * @param construction The construction to validate
     * @param checkInWorld If true, also verify blocks/entities exist in the world
     * @param activeRoomId If not null, the room with this ID is currently active and its blocks/entities
     *                     should be checked in the world (if checkInWorld is true)
     * @return true if everything is coherent, false if any mismatch found
     */
    public static boolean validateConstruction(ServerLevel level, Construction construction, boolean checkInWorld, String activeRoomId) {
        Architect.LOGGER.debug("Running coherence validation for construction {} (activeRoom: {})",
            construction.getId(), activeRoomId != null ? activeRoomId : "none");

        // Find the active room object (if any)
        Room activeRoom = activeRoomId != null ? construction.getRooms().get(activeRoomId) : null;

        // When checking base blocks in world, skip positions overridden by active room
        boolean blocksOk = validateAllBlocks(level, construction, checkInWorld, activeRoom);

        // When a room is active, base entities are NOT in the world (room entities replace them)
        // So we only check base entities in world if NO room is active
        boolean checkBaseEntitiesInWorld = checkInWorld && activeRoom == null;
        boolean entitiesOk = validateAllEntities(level, construction, checkBaseEntitiesInWorld);

        // Validate all rooms
        boolean roomsOk = true;
        for (Room room : construction.getRooms().values()) {
            // Check in world only if this room is the active one
            boolean checkRoomInWorld = checkInWorld && room.getId().equals(activeRoomId);

            if (!validateAllRoomBlocks(level, construction, room, checkRoomInWorld)) {
                roomsOk = false;
            }
            if (!validateAllRoomEntities(level, construction, room, checkRoomInWorld)) {
                roomsOk = false;
            }
        }

        boolean allOk = blocksOk && entitiesOk && roomsOk;

        if (allOk) {
            Architect.LOGGER.debug("Coherence validation PASSED for construction {}", construction.getId());
        } else {
            Architect.LOGGER.error("Coherence validation FAILED for construction {}", construction.getId());
        }

        return allOk;
    }
}
