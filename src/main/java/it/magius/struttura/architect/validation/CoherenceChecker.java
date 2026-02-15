package it.magius.struttura.architect.validation;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.Room;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Utility class to validate coherence between tracked positions/entities and world state.
 * Used to detect bugs where blocks/entities are not properly synchronized.
 *
 * Since Construction uses reference-only storage (tracked positions and UUIDs instead of
 * copies of BlockState/EntityData), coherence checks verify that tracked references are
 * valid and within bounds.
 */
public class CoherenceChecker {

    /**
     * Checks coherence of a single block in a construction.
     * Verifies the position is within construction bounds and is tracked.
     * @param level The server level
     * @param construction The construction containing the block
     * @param pos The block position (absolute world coordinates)
     * @param checkInWorld If true, also verify the block exists in the world (unused in reference-only mode)
     * @return true if coherent, false if there's a mismatch
     */
    public static boolean checkBlockCoherence(ServerLevel level, Construction construction,
                                               BlockPos pos, boolean checkInWorld) {
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

        // Check if block is tracked
        if (!construction.getTrackedBlocks().contains(pos)) {
            Architect.LOGGER.error("COHERENCE ERROR: Block at {} not found in construction {} tracked blocks",
                pos, construction.getId());
            return false;
        }

        return true;
    }

    /**
     * Checks coherence of a single block in a room.
     * Verifies the position is within construction bounds and is in the room's changed blocks.
     * @param level The server level
     * @param construction The parent construction
     * @param room The room containing the block change
     * @param pos The block position (absolute world coordinates)
     * @param checkInWorld If true, also verify the block exists in the world (unused in reference-only mode)
     * @return true if coherent, false if there's a mismatch
     */
    public static boolean checkRoomBlockCoherence(ServerLevel level, Construction construction, Room room,
                                                   BlockPos pos, boolean checkInWorld) {
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

        // Check if block is in room's changed blocks
        if (!room.getChangedBlocks().contains(pos)) {
            Architect.LOGGER.error("COHERENCE ERROR: Block at {} not found in room {} of construction {}",
                pos, room.getId(), construction.getId());
            return false;
        }

        return true;
    }

    /**
     * Checks coherence of a single entity in a construction.
     * Verifies the UUID is tracked and optionally exists in the world.
     * @param level The server level
     * @param construction The construction containing the entity
     * @param entityUuid The UUID of the entity to check
     * @param checkInWorld If true, also verify the entity exists in the world
     * @return true if coherent, false if there's a mismatch
     */
    public static boolean checkEntityCoherence(ServerLevel level, Construction construction,
                                                UUID entityUuid, boolean checkInWorld) {
        if (!construction.getTrackedEntities().contains(entityUuid)) {
            Architect.LOGGER.error("COHERENCE ERROR: Entity {} not tracked in construction {}",
                entityUuid, construction.getId());
            return false;
        }

        if (checkInWorld) {
            Entity worldEntity = level.getEntity(entityUuid);
            if (worldEntity == null) {
                Architect.LOGGER.error("COHERENCE ERROR: Tracked entity {} not found in world for construction {}",
                    entityUuid, construction.getId());
                return false;
            }
        }

        return true;
    }

    /**
     * Checks coherence of a single entity in a room.
     * Verifies the UUID is in the room's entity set and optionally exists in the world.
     * @param level The server level
     * @param construction The parent construction
     * @param room The room containing the entity
     * @param entityUuid The UUID of the entity to check
     * @param checkInWorld If true, also verify the entity exists in the world
     * @return true if coherent, false if there's a mismatch
     */
    public static boolean checkRoomEntityCoherence(ServerLevel level, Construction construction, Room room,
                                                    UUID entityUuid, boolean checkInWorld) {
        if (!room.getRoomEntities().contains(entityUuid)) {
            Architect.LOGGER.error("COHERENCE ERROR: Entity {} not found in room {} of construction {}",
                entityUuid, room.getId(), construction.getId());
            return false;
        }

        if (checkInWorld) {
            Entity worldEntity = level.getEntity(entityUuid);
            if (worldEntity == null) {
                Architect.LOGGER.error("COHERENCE ERROR: Room {} entity {} not found in world for construction {}",
                    room.getId(), entityUuid, construction.getId());
                return false;
            }
        }

        return true;
    }

    /**
     * Validates all blocks in a construction.
     * @param level The server level
     * @param construction The construction to validate
     * @param checkInWorld If true, also verify blocks exist in the world
     * @return true if all blocks are coherent, false if any mismatch found
     */
    public static boolean validateAllBlocks(ServerLevel level, Construction construction, boolean checkInWorld) {
        return validateAllBlocks(level, construction, checkInWorld, null);
    }

    /**
     * Validates all blocks in a construction.
     * @param level The server level
     * @param construction The construction to validate
     * @param checkInWorld If true, also verify blocks exist in the world
     * @param activeRoom If not null, skip world check for blocks that are overridden by this room
     * @return true if all blocks are coherent, false if any mismatch found
     */
    public static boolean validateAllBlocks(ServerLevel level, Construction construction, boolean checkInWorld, Room activeRoom) {
        boolean allCoherent = true;
        int errorCount = 0;

        for (BlockPos pos : construction.getTrackedBlocks()) {
            // If there's an active room and this position is overridden by it, skip world check
            // (the room block is in the world, not the base block)
            boolean skipWorldCheck = activeRoom != null && activeRoom.hasBlockChange(pos);
            boolean doCheckInWorld = checkInWorld && !skipWorldCheck;

            if (!checkBlockCoherence(level, construction, pos, doCheckInWorld)) {
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

        for (UUID entityUuid : construction.getTrackedEntities()) {
            if (!checkEntityCoherence(level, construction, entityUuid, checkInWorld)) {
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

        for (BlockPos pos : room.getChangedBlocks()) {
            if (!checkRoomBlockCoherence(level, construction, room, pos, checkInWorld)) {
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

        for (UUID entityUuid : room.getRoomEntities()) {
            if (!checkRoomEntityCoherence(level, construction, room, entityUuid, checkInWorld)) {
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
