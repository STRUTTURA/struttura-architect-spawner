package it.magius.struttura.architect.placement;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.ConstructionBounds;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ConstructionSnapshot;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.model.Room;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Centralized operations for placing and removing constructions.
 * All operations prevent physics triggers, block drops, and entity deaths.
 */
public class ConstructionOperations {

    // Flags for silent block placement (no physics, no onPlace callbacks, no drops from replaced blocks)
    // UPDATE_SKIP_ON_PLACE prevents rails from auto-orienting based on neighbors
    // UPDATE_SUPPRESS_DROPS prevents replaced blocks from dropping items (grass seeds, saplings, etc.)
    private static final int SILENT_PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE | Block.UPDATE_SUPPRESS_DROPS;

    // Flags for silent block removal (no drops, no cascading updates)
    // UPDATE_SUPPRESS_DROPS prevents blocks from dropping items when destroyed
    private static final int SILENT_REMOVE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    // ============== PLACEMENT OPERATIONS ==============

    public enum PlacementMode {
        SPAWN,        // Place at new position, do NOT update construction coords
        PULL,         // Place at new position, update construction coords
        MOVE,         // Place at new position after removal (update coords)
        SHOW          // Place at original position
    }

    public record PlacementResult(
        int blocksPlaced,
        int entitiesSpawned,
        BlockPos newOrigin,
        Map<String, ConstructionSnapshot.RoomSnapshot> transformedRoomSnapshots
    ) {}

    /**
     * Places a construction in the world without triggering physics or block callbacks.
     * Uses the player's position to calculate the spawn point.
     *
     * @param player The player (used to calculate position for SPAWN/PULL/MOVE modes)
     * @param construction The construction to place
     * @param snapshot The snapshot containing block states, NBT and entity data
     * @param mode The placement mode
     * @param updateConstructionCoords If true, updates the construction's stored coordinates
     * @return PlacementResult with counts and new origin position
     */
    public static PlacementResult placeConstruction(
        ServerPlayer player,
        Construction construction,
        ConstructionSnapshot snapshot,
        PlacementMode mode,
        boolean updateConstructionCoords
    ) {
        return placeConstruction(player, construction, snapshot, mode, updateConstructionCoords, null, 0f, false);
    }

    /**
     * Places a construction in the world without triggering physics or block callbacks.
     *
     * @param player The player (used to calculate position for SPAWN/PULL/MOVE modes if spawnPoint is null)
     * @param construction The construction to place
     * @param snapshot The snapshot containing block states, NBT and entity data
     * @param mode The placement mode
     * @param updateConstructionCoords If true, updates the construction's stored coordinates
     * @param spawnPoint Optional: the exact world position where the entrance anchor should be placed.
     *                   If null, uses player position with round(Y) - 1.
     *                   For natural spawning, pass the surface block position directly.
     * @param yaw The rotation angle for the construction (not used yet)
     * @param runInitCommandBlocks If true, runs initialization command blocks after placement (not used yet)
     * @return PlacementResult with counts and new origin position
     */
    public static PlacementResult placeConstruction(
        ServerPlayer player,
        Construction construction,
        ConstructionSnapshot snapshot,
        PlacementMode mode,
        boolean updateConstructionCoords,
        @Nullable BlockPos spawnPoint,
        float yaw,
        boolean runInitCommandBlocks
    ) {
        if (snapshot.blocks().isEmpty()) {
            return new PlacementResult(0, 0, BlockPos.ZERO, java.util.Map.of());
        }

        ServerLevel level = (ServerLevel) player.level();
        ConstructionBounds bounds = construction.getBounds();

        // Calculate rotation steps and pivot point
        int rotationSteps = 0;
        int pivotX = 0;
        int pivotZ = 0;

        if (mode != PlacementMode.SHOW) {
            // Calculate rotation based on yaw difference
            float entranceYaw = construction.getAnchors().hasEntrance()
                ? construction.getAnchors().getEntranceYaw()
                : 0f;
            rotationSteps = calculateRotationSteps(yaw, entranceYaw);

            // Pivot point: entrance anchor (normalized) or center of bounds
            if (construction.getAnchors().hasEntrance()) {
                BlockPos entrance = construction.getAnchors().getEntrance(); // normalized coords
                pivotX = entrance.getX();
                pivotZ = entrance.getZ();
            } else {
                // No entrance: pivot at center of bounds (normalized)
                pivotX = bounds.getSizeX() / 2;
                pivotZ = bounds.getSizeZ() / 2;
            }
        }

        // Calculate target position based on mode
        BlockPos targetPos;
        if (mode == PlacementMode.SHOW) {
            // SHOW: use original position, no rotation
            targetPos = bounds.getMin();
        } else {
            // SPAWN/PULL/MOVE: check if entrance anchor is set
            if (construction.getAnchors().hasEntrance()) {
                if (spawnPoint != null) {
                    // Use provided spawn point directly (for natural spawning)
                    targetPos = calculatePositionAtEntranceRotated(spawnPoint, construction, rotationSteps, pivotX, pivotZ);
                } else {
                    // Calculate position from player (uses round(Y) - 1)
                    targetPos = calculatePositionAtEntranceRotated(player, construction, rotationSteps, pivotX, pivotZ);
                }
            } else {
                // No entrance: calculate position in front of player (legacy behavior)
                targetPos = calculatePositionInFront(player, bounds);
            }
        }

        return placeConstructionAt(level, construction, snapshot, targetPos, updateConstructionCoords, rotationSteps, pivotX, pivotZ);
    }

    /**
     * Places a construction at a specific target position without triggering physics.
     * The target position is where the construction's min corner will be placed.
     * No rotation is applied.
     *
     * @param level The ServerLevel
     * @param construction The construction to place
     * @param snapshot The snapshot containing block states, NBT and entity data
     * @param targetPos The target position for the construction's min corner
     * @param updateConstructionCoords If true, updates the construction's stored coordinates
     * @return PlacementResult with counts and new origin position
     */
    public static PlacementResult placeConstructionAt(
        ServerLevel level,
        Construction construction,
        ConstructionSnapshot snapshot,
        BlockPos targetPos,
        boolean updateConstructionCoords
    ) {
        return placeConstructionAt(level, construction, snapshot, targetPos, updateConstructionCoords, 0, 0, 0);
    }

    /**
     * Places a construction at a specific target position without triggering physics.
     * The target position is where the construction's min corner will be placed.
     * Applies rotation around the specified pivot point.
     *
     * @param level The ServerLevel
     * @param construction The construction to place
     * @param snapshot The snapshot containing block states, NBT and entity data
     * @param targetPos The target position for the rotated construction's min corner
     * @param updateConstructionCoords If true, updates the construction's stored coordinates
     * @param rotationSteps Number of 90-degree clockwise rotations (0-3)
     * @param pivotX Pivot X coordinate for rotation (normalized)
     * @param pivotZ Pivot Z coordinate for rotation (normalized)
     * @return PlacementResult with counts and new origin position
     */
    public static PlacementResult placeConstructionAt(
        ServerLevel level,
        Construction construction,
        ConstructionSnapshot snapshot,
        BlockPos targetPos,
        boolean updateConstructionCoords,
        int rotationSteps,
        int pivotX,
        int pivotZ
    ) {
        if (snapshot.blocks().isEmpty()) {
            return new PlacementResult(0, 0, BlockPos.ZERO, java.util.Map.of());
        }

        ConstructionBounds bounds = construction.getBounds();
        int originalMinX = bounds.getMinX();
        int originalMinY = bounds.getMinY();
        int originalMinZ = bounds.getMinZ();
        // Save sizes BEFORE bounds get reset in updateConstructionCoordinatesRotated
        int originalSizeX = bounds.getSizeX();
        int originalSizeZ = bounds.getSizeZ();

        Rotation rotation = stepsToRotation(rotationSteps);

        // Phase 1: Place all blocks WITH rotation
        Map<BlockPos, BlockState> newBlocks = new HashMap<>();
        Map<BlockPos, BlockPos> originalPosMap = new HashMap<>(); // newPos -> originalPos for NBT lookup
        int placedCount = 0;

        // Sort blocks by Y ascending (support blocks first)
        List<Map.Entry<BlockPos, BlockState>> sortedBlocks = new ArrayList<>(snapshot.blocks().entrySet());
        sortedBlocks.sort(Comparator.comparingInt(e -> e.getKey().getY()));

        for (Map.Entry<BlockPos, BlockState> entry : sortedBlocks) {
            BlockPos originalPos = entry.getKey();
            BlockState state = entry.getValue();

            // Normalize position (relative to bounds min)
            int normX = originalPos.getX() - originalMinX;
            int normY = originalPos.getY() - originalMinY;
            int normZ = originalPos.getZ() - originalMinZ;

            // Rotate normalized position around pivot
            int[] rotatedXZ = rotateXZ(normX, normZ, pivotX, pivotZ, rotationSteps);
            int rotatedNormX = rotatedXZ[0];
            int rotatedNormZ = rotatedXZ[1];

            // Calculate final world position
            BlockPos newPos = new BlockPos(
                targetPos.getX() + rotatedNormX,
                targetPos.getY() + normY,
                targetPos.getZ() + rotatedNormZ
            );

            // Rotate the block state
            BlockState rotatedState = state.rotate(rotation);

            newBlocks.put(newPos, rotatedState);
            originalPosMap.put(newPos, originalPos);

            if (!rotatedState.isAir()) {
                level.setBlock(newPos, rotatedState, SILENT_PLACE_FLAGS);
                placedCount++;
            }
        }

        // Phase 2: Apply block entity NBT after ALL blocks are placed
        int blockEntityCount = 0;
        for (Map.Entry<BlockPos, BlockState> entry : newBlocks.entrySet()) {
            BlockPos newPos = entry.getKey();
            BlockState state = entry.getValue();
            if (state.isAir()) continue;

            BlockPos originalPos = originalPosMap.get(newPos);
            CompoundTag blockNbt = snapshot.blockEntityNbt().get(originalPos);

            if (blockNbt != null) {
                BlockEntity blockEntity = level.getBlockEntity(newPos);
                if (blockEntity != null) {
                    CompoundTag nbtCopy = blockNbt.copy();
                    nbtCopy.putInt("x", newPos.getX());
                    nbtCopy.putInt("y", newPos.getY());
                    nbtCopy.putInt("z", newPos.getZ());

                    ValueInput input = TagValueInput.create(
                        ProblemReporter.DISCARDING,
                        level.registryAccess(),
                        nbtCopy
                    );
                    blockEntity.loadCustomOnly(input);
                    blockEntity.setChanged();
                    blockEntityCount++;
                }
            }
        }

        if (blockEntityCount > 0) {
            Architect.LOGGER.debug("Applied NBT to {} block entities", blockEntityCount);
        }

        // Phase 3: Update shape connections (fences, walls, etc.) WITHOUT physics
        for (BlockPos newPos : newBlocks.keySet()) {
            BlockState state = level.getBlockState(newPos);
            if (!state.isAir()) {
                state.updateNeighbourShapes(level, newPos, Block.UPDATE_CLIENTS, 0);
            }
        }

        // Phase 4: Update construction coordinates if requested
        if (updateConstructionCoords) {
            if (rotationSteps == 0) {
                // No rotation: rebuild tracked positions from placed blocks
                int offsetX = targetPos.getX() - originalMinX;
                int offsetY = targetPos.getY() - originalMinY;
                int offsetZ = targetPos.getZ() - originalMinZ;
                updateConstructionCoordinates(construction, offsetX, offsetY, offsetZ, newBlocks);
            } else {
                // With rotation: rebuild construction with new rotated positions and states
                // Entities ARE rotated here, so spawnEntitiesFrozenRotated will use rotationSteps=0
                updateConstructionCoordinatesRotated(construction, newBlocks, originalPosMap,
                    originalMinX, originalMinY, originalMinZ, targetPos,
                    rotationSteps, pivotX, pivotZ, originalSizeX, originalSizeZ);
            }
        }

        // Phase 4b: Populate room changedBlocks from snapshot room data and build
        // transformed room snapshots (with new world positions paired with block states).
        // After pull, rooms are created empty (only id/name/createdAt from metadata).
        // The actual room block positions are in the snapshot's room data and need to be
        // populated into the Room objects with the same coordinate transformation as base blocks.
        Map<String, ConstructionSnapshot.RoomSnapshot> transformedRoomSnapshots = new HashMap<>();
        if (updateConstructionCoords && snapshot.rooms() != null && !snapshot.rooms().isEmpty()) {
            for (var roomEntry : snapshot.rooms().entrySet()) {
                String roomId = roomEntry.getKey();
                ConstructionSnapshot.RoomSnapshot roomSnapshot = roomEntry.getValue();
                Room room = construction.getRooms().get(roomId);

                if (room == null) continue;

                // Transform block positions and build new room snapshot
                Map<BlockPos, BlockState> transformedBlocks = new HashMap<>();
                Map<BlockPos, CompoundTag> transformedNbt = new HashMap<>();

                if (!roomSnapshot.blocks().isEmpty()) {
                    room.clearBlockChanges();

                    for (var blockEntry : roomSnapshot.blocks().entrySet()) {
                        BlockPos originalPos = blockEntry.getKey();
                        BlockPos newPos;
                        if (rotationSteps == 0) {
                            int offsetX = targetPos.getX() - originalMinX;
                            int offsetY = targetPos.getY() - originalMinY;
                            int offsetZ = targetPos.getZ() - originalMinZ;
                            newPos = originalPos.offset(offsetX, offsetY, offsetZ);
                        } else {
                            int normX = originalPos.getX() - originalMinX;
                            int normY = originalPos.getY() - originalMinY;
                            int normZ = originalPos.getZ() - originalMinZ;

                            int[] rotatedXZ = rotateXZ(normX, normZ, pivotX, pivotZ, rotationSteps);
                            newPos = new BlockPos(
                                targetPos.getX() + rotatedXZ[0],
                                targetPos.getY() + normY,
                                targetPos.getZ() + rotatedXZ[1]
                            );
                        }
                        room.setBlockChange(newPos);
                        // Rotate block state for directional blocks (stairs, pistons, etc.)
                        BlockState blockState = blockEntry.getValue();
                        if (rotationSteps != 0) {
                            blockState = blockState.rotate(rotation);
                        }
                        transformedBlocks.put(newPos, blockState);
                        CompoundTag nbt = roomSnapshot.blockEntityNbt().get(originalPos);
                        if (nbt != null) {
                            transformedNbt.put(newPos, nbt);
                        }
                    }
                }

                // Rotate room entities if needed (they are saved to disk for later spawning).
                // Room entities are saved with relPos relative to bounds.min, but after rotation
                // the bounds.min changes. We need to adjust the rotated relPos so that:
                //   bounds.min + storedRelPos == targetPos + rotatedRelPos
                // i.e. storedRelPos = targetPos + rotatedRelPos - bounds.min
                List<EntityData> transformedEntities;
                if (rotationSteps != 0 && !roomSnapshot.entities().isEmpty()) {
                    double pivotCenterX = pivotX + 0.5;
                    double pivotCenterZ = pivotZ + 0.5;
                    // Offset to convert from targetPos-relative to bounds.min-relative
                    var newBounds = construction.getBounds();
                    double offsetX = targetPos.getX() - newBounds.getMinX();
                    double offsetY = targetPos.getY() - newBounds.getMinY();
                    double offsetZ = targetPos.getZ() - newBounds.getMinZ();
                    int blockOffsetX = targetPos.getX() - newBounds.getMinX();
                    int blockOffsetY = targetPos.getY() - newBounds.getMinY();
                    int blockOffsetZ = targetPos.getZ() - newBounds.getMinZ();

                    transformedEntities = new ArrayList<>();
                    for (EntityData entityData : roomSnapshot.entities()) {
                        EntityData rotated = entityData.withRotation(rotationSteps, pivotCenterX, pivotCenterZ);
                        // Shift relPos from targetPos-relative to bounds.min-relative
                        Vec3 adjustedRelPos = new Vec3(
                            rotated.getRelativePos().x + offsetX,
                            rotated.getRelativePos().y + offsetY,
                            rotated.getRelativePos().z + offsetZ
                        );
                        // Shift block_pos in NBT similarly
                        CompoundTag adjustedNbt = rotated.getNbt().copy();
                        if (adjustedNbt.contains("block_pos")) {
                            net.minecraft.nbt.Tag rawTag = adjustedNbt.get("block_pos");
                            if (rawTag instanceof net.minecraft.nbt.IntArrayTag intArrayTag) {
                                int[] coords = intArrayTag.getAsIntArray();
                                if (coords.length >= 3) {
                                    adjustedNbt.putIntArray("block_pos", new int[]{
                                        coords[0] + blockOffsetX,
                                        coords[1] + blockOffsetY,
                                        coords[2] + blockOffsetZ
                                    });
                                }
                            }
                        }
                        transformedEntities.add(new EntityData(
                            rotated.getEntityType(), adjustedRelPos,
                            rotated.getYaw(), rotated.getPitch(), adjustedNbt));
                    }
                    Architect.LOGGER.debug("Room '{}': rotated {} entities by {} steps",
                        roomId, transformedEntities.size(), rotationSteps);
                } else if (rotationSteps == 0 && !roomSnapshot.entities().isEmpty()) {
                    // No rotation but still need to adjust for offset between targetPos and bounds.min
                    var newBounds = construction.getBounds();
                    double offsetX = targetPos.getX() - originalMinX - (newBounds.getMinX() - originalMinX);
                    double offsetY = targetPos.getY() - originalMinY - (newBounds.getMinY() - originalMinY);
                    double offsetZ = targetPos.getZ() - originalMinZ - (newBounds.getMinZ() - originalMinZ);
                    if (offsetX != 0 || offsetY != 0 || offsetZ != 0) {
                        transformedEntities = new ArrayList<>();
                        int blockOffsetX = (int) offsetX;
                        int blockOffsetY = (int) offsetY;
                        int blockOffsetZ = (int) offsetZ;
                        for (EntityData entityData : roomSnapshot.entities()) {
                            Vec3 adjustedRelPos = new Vec3(
                                entityData.getRelativePos().x + offsetX,
                                entityData.getRelativePos().y + offsetY,
                                entityData.getRelativePos().z + offsetZ
                            );
                            CompoundTag adjustedNbt = entityData.getNbt().copy();
                            if (adjustedNbt.contains("block_pos")) {
                                net.minecraft.nbt.Tag rawTag = adjustedNbt.get("block_pos");
                                if (rawTag instanceof net.minecraft.nbt.IntArrayTag intArrayTag) {
                                    int[] coords = intArrayTag.getAsIntArray();
                                    if (coords.length >= 3) {
                                        adjustedNbt.putIntArray("block_pos", new int[]{
                                            coords[0] + blockOffsetX,
                                            coords[1] + blockOffsetY,
                                            coords[2] + blockOffsetZ
                                        });
                                    }
                                }
                            }
                            transformedEntities.add(new EntityData(
                                entityData.getEntityType(), adjustedRelPos,
                                entityData.getYaw(), entityData.getPitch(), adjustedNbt));
                        }
                    } else {
                        transformedEntities = roomSnapshot.entities();
                    }
                } else {
                    transformedEntities = roomSnapshot.entities();
                }

                // Cache entity count from snapshot (room entities are not in the world,
                // they only get spawned when entering the room)
                if (!transformedEntities.isEmpty()) {
                    room.setCachedEntityCount(transformedEntities.size());
                }

                transformedRoomSnapshots.put(roomId, new ConstructionSnapshot.RoomSnapshot(
                    transformedBlocks, transformedNbt, transformedEntities));

                Architect.LOGGER.debug("Room '{}': populated {} blocks, {} entities from snapshot",
                    roomId, room.getChangedBlockCount(), transformedEntities.size());
            }
        }

        // Phase 5: Spawn entities with freeze
        // Clear any previously tracked entity UUIDs before spawning new ones
        construction.clearTrackedEntities();

        // Spawn entities from the snapshot data
        // Entities always use rotation during spawn (not pre-rotated in updateConstructionCoordinatesRotated)
        List<Entity> spawnedEntities = spawnEntitiesFrozenRotated(
            snapshot.entities(),
            level,
            targetPos.getX(), targetPos.getY(), targetPos.getZ(),
            rotationSteps, pivotX, pivotZ,
            construction,
            false
        );

        // Expand bounds to include spawned entities
        if (construction != null) {
            for (Entity entity : spawnedEntities) {
                EntityData.expandBoundsForEntity(entity, construction.getBounds());
            }
        }

        Architect.LOGGER.info("Placed {} blocks, {} entities at {}", placedCount, spawnedEntities.size(), targetPos);

        return new PlacementResult(placedCount, spawnedEntities.size(), targetPos, transformedRoomSnapshots);
    }

    /**
     * Places room delta blocks without triggering physics.
     * Used when entering room edit mode. Data comes from a RoomSnapshot
     * (loaded from disk or captured from the world).
     *
     * @param level The ServerLevel
     * @param roomSnapshot The room snapshot with block states and NBT data to place
     * @return Number of blocks placed
     */
    public static int placeRoomBlocks(ServerLevel level, ConstructionSnapshot.RoomSnapshot roomSnapshot) {
        if (roomSnapshot.blocks().isEmpty()) {
            return 0;
        }

        // Phase 1: Clear containers at positions we're about to modify
        for (BlockPos pos : roomSnapshot.blocks().keySet()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Clearable clearable) {
                clearable.clearContent();
            }
        }

        // Phase 2: Sort and place room blocks
        List<Map.Entry<BlockPos, BlockState>> sortedBlocks = new ArrayList<>(roomSnapshot.blocks().entrySet());
        sortedBlocks.sort(Comparator.comparingInt(e -> e.getKey().getY()));

        int placedCount = 0;
        for (Map.Entry<BlockPos, BlockState> entry : sortedBlocks) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            level.setBlock(pos, state, SILENT_PLACE_FLAGS);
            placedCount++;
        }

        // Phase 3: Apply block entity NBT
        for (Map.Entry<BlockPos, BlockState> entry : sortedBlocks) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            if (state.isAir()) continue;

            CompoundTag blockNbt = roomSnapshot.blockEntityNbt().get(pos);
            if (blockNbt != null) {
                BlockEntity blockEntity = level.getBlockEntity(pos);
                if (blockEntity != null) {
                    CompoundTag nbtCopy = blockNbt.copy();
                    nbtCopy.putInt("x", pos.getX());
                    nbtCopy.putInt("y", pos.getY());
                    nbtCopy.putInt("z", pos.getZ());

                    ValueInput input = TagValueInput.create(
                        ProblemReporter.DISCARDING,
                        level.registryAccess(),
                        nbtCopy
                    );
                    blockEntity.loadCustomOnly(input);
                    blockEntity.setChanged();
                }
            }
        }

        // Phase 4: Update shape connections
        for (BlockPos pos : roomSnapshot.blocks().keySet()) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                state.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS, 0);
            }
        }

        Architect.LOGGER.debug("Room: placed {} delta blocks", placedCount);
        return placedCount;
    }

    /**
     * Restores base construction blocks where room had modifications.
     * Used when exiting room edit mode (room done).
     * The caller must provide the saved base block states and NBT
     * (captured before room blocks were placed over them).
     *
     * @param level The ServerLevel
     * @param positions The positions to restore (room's changed block positions)
     * @param savedBaseBlocks Map of position to saved base block state
     * @param savedBaseNbt Map of position to saved base block entity NBT (can be null)
     * @return Number of blocks restored
     */
    public static int restoreBaseBlocks(
        ServerLevel level,
        Set<BlockPos> positions,
        Map<BlockPos, BlockState> savedBaseBlocks,
        @Nullable Map<BlockPos, CompoundTag> savedBaseNbt
    ) {
        if (positions.isEmpty()) {
            return 0;
        }

        // Phase 1: Clear containers
        for (BlockPos pos : positions) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Clearable clearable) {
                clearable.clearContent();
            }
        }

        // Phase 2: Build list of base blocks to restore
        List<Map.Entry<BlockPos, BlockState>> blocksToRestore = new ArrayList<>();
        for (BlockPos pos : positions) {
            BlockState baseState = savedBaseBlocks.get(pos);
            if (baseState != null) {
                blocksToRestore.add(Map.entry(pos, baseState));
            } else {
                // No base block = restore to air
                blocksToRestore.add(Map.entry(pos, Blocks.AIR.defaultBlockState()));
            }
        }

        // Sort by Y ascending
        blocksToRestore.sort(Comparator.comparingInt(e -> e.getKey().getY()));

        // Phase 3: Place base blocks
        int restoredCount = 0;
        for (Map.Entry<BlockPos, BlockState> entry : blocksToRestore) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            level.setBlock(pos, state, SILENT_PLACE_FLAGS);
            restoredCount++;
        }

        // Phase 4: Apply base block entity NBT
        if (savedBaseNbt != null) {
            for (Map.Entry<BlockPos, BlockState> entry : blocksToRestore) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                if (state.isAir()) continue;

                CompoundTag blockNbt = savedBaseNbt.get(pos);
                if (blockNbt != null) {
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        ValueInput input = TagValueInput.create(
                            ProblemReporter.DISCARDING,
                            level.registryAccess(),
                            blockNbt
                        );
                        blockEntity.loadCustomOnly(input);
                        blockEntity.setChanged();
                    }
                }
            }
        }

        // Phase 5: Update shape connections
        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                state.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS, 0);
            }
        }

        Architect.LOGGER.debug("Room: restored {} base blocks", restoredCount);
        return restoredCount;
    }

    /**
     * Restores specific base construction blocks at given positions.
     * Used when removing specific blocks from a room.
     * The caller must provide the saved base block states and NBT.
     *
     * @param level The server level
     * @param positions The specific positions to restore
     * @param savedBaseBlocks Map of position to saved base block state
     * @param savedBaseNbt Map of position to saved base block entity NBT (can be null)
     * @return Number of blocks restored
     */
    public static int restoreBaseBlocksAt(
        ServerLevel level,
        Collection<BlockPos> positions,
        Map<BlockPos, BlockState> savedBaseBlocks,
        @Nullable Map<BlockPos, CompoundTag> savedBaseNbt
    ) {
        if (positions.isEmpty()) {
            return 0;
        }

        // Phase 1: Clear containers
        for (BlockPos pos : positions) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Clearable clearable) {
                clearable.clearContent();
            }
        }

        // Phase 2: Build list of base blocks to restore
        List<Map.Entry<BlockPos, BlockState>> blocksToRestore = new ArrayList<>();
        for (BlockPos pos : positions) {
            BlockState baseState = savedBaseBlocks.get(pos);
            if (baseState != null) {
                blocksToRestore.add(Map.entry(pos, baseState));
            } else {
                // No base block = restore to air
                blocksToRestore.add(Map.entry(pos, Blocks.AIR.defaultBlockState()));
            }
        }

        // Sort by Y ascending
        blocksToRestore.sort(Comparator.comparingInt(e -> e.getKey().getY()));

        // Phase 3: Place base blocks
        int restoredCount = 0;
        for (Map.Entry<BlockPos, BlockState> entry : blocksToRestore) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            level.setBlock(pos, state, SILENT_PLACE_FLAGS);
            restoredCount++;
        }

        // Phase 4: Apply base block entity NBT
        if (savedBaseNbt != null) {
            for (Map.Entry<BlockPos, BlockState> entry : blocksToRestore) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                if (state.isAir()) continue;

                CompoundTag blockNbt = savedBaseNbt.get(pos);
                if (blockNbt != null) {
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        ValueInput input = TagValueInput.create(
                            ProblemReporter.DISCARDING,
                            level.registryAccess(),
                            blockNbt
                        );
                        blockEntity.loadCustomOnly(input);
                        blockEntity.setChanged();
                    }
                }
            }
        }

        // Phase 5: Update shape connections
        for (BlockPos pos : positions) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                state.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS, 0);
            }
        }

        Architect.LOGGER.debug("Restored {} base blocks at specific positions", restoredCount);
        return restoredCount;
    }

    // ============== REMOVAL OPERATIONS ==============

    public enum RemovalMode {
        HIDE,       // Remove blocks, keep construction in memory
        DESTROY,    // Remove blocks, construction will be deleted from registry
        MOVE_CLEAR  // Clear old position for MOVE operation
    }

    public record RemovalResult(
        int blocksRemoved,
        int entitiesRemoved
    ) {}

    /**
     * Removes a construction from the world without triggering drops or physics.
     *
     * @param level The ServerLevel
     * @param construction The construction to remove
     * @param mode The removal mode
     * @param customBounds Optional custom bounds (for MOVE_CLEAR with old bounds)
     * @return RemovalResult with counts
     */
    public static RemovalResult removeConstruction(
        ServerLevel level,
        Construction construction,
        RemovalMode mode,
        @Nullable ConstructionBounds customBounds
    ) {
        ConstructionBounds bounds = customBounds != null ? customBounds : construction.getBounds();

        if (!bounds.isValid()) {
            return new RemovalResult(0, 0);
        }

        // Phase 1: Clear all containers FIRST (prevent drops)
        int containersCleared = 0;
        for (int x = bounds.getMinX(); x <= bounds.getMaxX(); x++) {
            for (int y = bounds.getMinY(); y <= bounds.getMaxY(); y++) {
                for (int z = bounds.getMinZ(); z <= bounds.getMaxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) continue;
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity instanceof Clearable clearable) {
                        clearable.clearContent();
                        containersCleared++;
                    }
                }
            }
        }

        // Phase 2: Remove entities BEFORE blocks (prevent death from falling)
        // First, remove entities tracked by UUID (these can be anywhere in the world)
        int entitiesRemoved = 0;
        Set<UUID> trackedUuids = construction.getTrackedEntities();
        if (!trackedUuids.isEmpty()) {
            for (UUID uuid : new HashSet<>(trackedUuids)) {
                Entity entity = level.getEntity(uuid);

                if (entity != null && !(entity instanceof Player)) {
                    entity.discard();
                    entitiesRemoved++;
                }
            }
            construction.clearTrackedEntities();
        }

        // Also check the bounds area for any untracked entities (legacy cleanup)
        AABB area = new AABB(
            bounds.getMinX() - 1, bounds.getMinY() - 1, bounds.getMinZ() - 1,
            bounds.getMaxX() + 2, bounds.getMaxY() + 2, bounds.getMaxZ() + 2
        );

        List<Entity> entitiesToRemove = level.getEntitiesOfClass(Entity.class, area,
            e -> !(e instanceof Player));

        for (Entity entity : entitiesToRemove) {
            entity.discard();
            entitiesRemoved++;
        }

        // Phase 3: Remove all blocks (no drops, no physics)
        // IMPORTANT: Iterate from TOP to BOTTOM (Y descending) to remove plants/grass
        // before removing the ground blocks they sit on. This prevents plants from
        // breaking naturally and dropping items when their support block is removed.
        int blocksRemoved = 0;
        for (int y = bounds.getMaxY(); y >= bounds.getMinY(); y--) {
            for (int x = bounds.getMinX(); x <= bounds.getMaxX(); x++) {
                for (int z = bounds.getMinZ(); z <= bounds.getMaxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) continue;
                    BlockState currentState = level.getBlockState(pos);
                    if (!currentState.is(Blocks.AIR)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), SILENT_REMOVE_FLAGS);
                        blocksRemoved++;
                    }
                }
            }
        }

        Architect.LOGGER.info("{}: removed {} blocks, {} entities, {} containers cleared",
            mode, blocksRemoved, entitiesRemoved, containersCleared);

        return new RemovalResult(blocksRemoved, entitiesRemoved);
    }

    // ============== HELPER METHODS ==============

    /**
     * Calculate the position so that the entrance anchor ends up at the given spawn point.
     * The entrance anchor is stored in normalized coordinates (relative to bounds min 0,0,0).
     *
     * @param spawnPoint The world position where the entrance anchor should be placed
     * @param construction The construction with entrance anchor
     * @return The position where bounds min should be placed
     */
    public static BlockPos calculatePositionAtEntrance(BlockPos spawnPoint, Construction construction) {
        BlockPos entrance = construction.getAnchors().getEntrance(); // normalized coords

        // targetPos is where bounds min will be placed
        // We want: spawnPoint = targetPos + entrance
        // Therefore: targetPos = spawnPoint - entrance
        return new BlockPos(
            spawnPoint.getX() - entrance.getX(),
            spawnPoint.getY() - entrance.getY(),
            spawnPoint.getZ() - entrance.getZ()
        );
    }

    /**
     * Calculate the position so that the player ends up at the entrance anchor.
     * Uses round(player.getY()) - 1 as the spawn Y coordinate.
     *
     * @param player The player
     * @param construction The construction with entrance anchor
     * @return The position where bounds min should be placed
     */
    public static BlockPos calculatePositionAtEntrance(ServerPlayer player, Construction construction) {
        // Calculate spawn point from player position: X, Z from blockPosition, Y = round(Y) - 1
        // The player stands ON TOP of the entrance block, so subtract 1 from Y
        int spawnX = player.blockPosition().getX();
        int spawnY = (int) Math.round(player.getY()) - 1;
        int spawnZ = player.blockPosition().getZ();
        return calculatePositionAtEntrance(new BlockPos(spawnX, spawnY, spawnZ), construction);
    }

    /**
     * Calculate the position so that the entrance anchor (after rotation) ends up at the given spawn point.
     * Takes into account that the entrance anchor position changes after rotation.
     *
     * In placeConstructionAt, blocks are placed at:
     *   worldPos = targetPos + rotatedNormalizedPos
     *
     * So for the entrance to end up at spawnPoint:
     *   spawnPoint = targetPos + rotatedEntranceNormalized
     *   targetPos = spawnPoint - rotatedEntranceNormalized
     *
     * @param spawnPoint The world position where the entrance anchor should be placed
     * @param construction The construction with entrance anchor
     * @param rotationSteps Number of 90-degree clockwise rotations (0-3)
     * @param pivotX Pivot X coordinate (normalized)
     * @param pivotZ Pivot Z coordinate (normalized)
     * @return The target position to pass to placeConstructionAt
     */
    public static BlockPos calculatePositionAtEntranceRotated(
        BlockPos spawnPoint,
        Construction construction,
        int rotationSteps,
        int pivotX,
        int pivotZ
    ) {
        // Get entrance or use pivot as fallback (center of building)
        int entranceX, entranceY, entranceZ;
        if (construction.getAnchors().hasEntrance()) {
            BlockPos entrance = construction.getAnchors().getEntrance(); // normalized coords
            entranceX = entrance.getX();
            entranceY = entrance.getY();
            entranceZ = entrance.getZ();
        } else {
            // No entrance anchor - use pivot (center) at ground level
            entranceX = pivotX;
            entranceY = 0;
            entranceZ = pivotZ;
        }

        // Rotate the entrance position around the pivot (same rotation applied in placeConstructionAt)
        int[] rotatedEntrance = rotateXZ(entranceX, entranceZ, pivotX, pivotZ, rotationSteps);

        // targetPos = spawnPoint - entrance offset
        // If entrance is at Y=0 and ground is at Y=60, building origin should be at Y=60
        return new BlockPos(
            spawnPoint.getX() - rotatedEntrance[0],
            spawnPoint.getY() - entranceY,
            spawnPoint.getZ() - rotatedEntrance[1]
        );
    }

    /**
     * Calculate the position so that the player ends up at the entrance anchor (with rotation).
     * Uses round(player.getY()) - 1 as the spawn Y coordinate.
     *
     * @param player The player
     * @param construction The construction with entrance anchor
     * @param rotationSteps Number of 90-degree clockwise rotations (0-3)
     * @param pivotX Pivot X coordinate (normalized)
     * @param pivotZ Pivot Z coordinate (normalized)
     * @return The position where the rotated bounds min should be placed
     */
    public static BlockPos calculatePositionAtEntranceRotated(
        ServerPlayer player,
        Construction construction,
        int rotationSteps,
        int pivotX,
        int pivotZ
    ) {
        // The player stands ON TOP of the entrance block, so subtract 1 from Y
        int spawnX = player.blockPosition().getX();
        int spawnY = (int) Math.round(player.getY()) - 1;
        int spawnZ = player.blockPosition().getZ();
        return calculatePositionAtEntranceRotated(
            new BlockPos(spawnX, spawnY, spawnZ),
            construction,
            rotationSteps,
            pivotX,
            pivotZ
        );
    }

    /**
     * Calculate the position in front of a player for placing a construction.
     * The nearest edge of the construction will be SPAWN_DISTANCE blocks away from the player.
     * The construction spawns at the same Y level as the player.
     */
    public static BlockPos calculatePositionInFront(ServerPlayer player, ConstructionBounds bounds) {
        final int SPAWN_DISTANCE = 10;

        int sizeX = bounds.getSizeX();
        int sizeZ = bounds.getSizeZ();

        float yaw = player.getYRot();
        // Normalize yaw to 0-360
        yaw = ((yaw % 360) + 360) % 360;

        int playerX = (int) player.getX();
        int playerY = (int) player.getY();
        int playerZ = (int) player.getZ();

        int targetX, targetZ;

        if (yaw >= 315 || yaw < 45) {
            // Looking South (+Z): nearest face is minZ (north face)
            targetZ = playerZ + SPAWN_DISTANCE;
            targetX = playerX - sizeX / 2; // centered
        } else if (yaw >= 45 && yaw < 135) {
            // Looking West (-X): nearest face is maxX (east face)
            targetX = playerX - SPAWN_DISTANCE - sizeX + 1;
            targetZ = playerZ - sizeZ / 2; // centered
        } else if (yaw >= 135 && yaw < 225) {
            // Looking North (-Z): nearest face is maxZ (south face)
            targetZ = playerZ - SPAWN_DISTANCE - sizeZ + 1;
            targetX = playerX - sizeX / 2; // centered
        } else {
            // Looking East (+X): nearest face is minX (west face)
            targetX = playerX + SPAWN_DISTANCE;
            targetZ = playerZ - sizeZ / 2; // centered
        }

        return new BlockPos(targetX, playerY, targetZ);
    }

    /**
     * Updates construction tracked positions after placement at new position.
     * In the reference-only model, we only update block positions (not states or NBT).
     */
    private static void updateConstructionCoordinates(
        Construction construction,
        int offsetX, int offsetY, int offsetZ,
        Map<BlockPos, BlockState> newBlocks
    ) {
        // Update tracked block positions
        construction.getTrackedBlocks().clear();
        construction.getBounds().reset();
        for (BlockPos pos : newBlocks.keySet()) {
            construction.addBlockRaw(pos);
        }
        // Recalculate bounds from the new positions
        construction.recalculateBounds();

        // NOTE: Entity positions are stored relative to bounds.min.
        // Since we're updating blocks to world coordinates (bounds.min changes),
        // but entities are spawned using: worldPos = bounds.min + relativePos,
        // the relativePos doesn't need to change - it stays relative to bounds.
        // Same for anchor - it stays relative to bounds.

        // Update room block positions (translate together with construction)
        for (Room room : construction.getRooms().values()) {
            Set<BlockPos> newRoomPositions = new HashSet<>();

            for (BlockPos originalPos : room.getChangedBlocks()) {
                BlockPos newPos = originalPos.offset(offsetX, offsetY, offsetZ);
                newRoomPositions.add(newPos);
            }

            room.clearBlockChanges();
            for (BlockPos newPos : newRoomPositions) {
                room.setBlockChange(newPos);
            }
        }
    }

    /**
     * Updates construction tracked positions after placement with rotation.
     * In the reference-only model, we only update block positions (not states or NBT).
     * Entity rotation is NOT handled here - entities are spawned directly from the snapshot
     * with rotation applied during spawn.
     *
     * @param construction The construction to update
     * @param newBlocks Map of new world positions to rotated block states (positions used, states ignored)
     * @param originalPosMap Map from new position to original position (unused in reference-only model)
     * @param originalMinX Original bounds min X
     * @param originalMinY Original bounds min Y
     * @param originalMinZ Original bounds min Z
     * @param targetPos Target placement position
     * @param rotationSteps Number of 90-degree rotations (0-3)
     * @param pivotX Pivot X coordinate for rotation (normalized)
     * @param pivotZ Pivot Z coordinate for rotation (normalized)
     * @param originalSizeX Original construction width (X)
     * @param originalSizeZ Original construction depth (Z)
     */
    private static void updateConstructionCoordinatesRotated(
        Construction construction,
        Map<BlockPos, BlockState> newBlocks,
        Map<BlockPos, BlockPos> originalPosMap,
        int originalMinX,
        int originalMinY,
        int originalMinZ,
        BlockPos targetPos,
        int rotationSteps,
        int pivotX,
        int pivotZ,
        int originalSizeX,
        int originalSizeZ
    ) {
        // Step 1: Update tracked block positions with new world positions
        construction.getTrackedBlocks().clear();
        construction.getBounds().reset();
        for (BlockPos pos : newBlocks.keySet()) {
            construction.addBlockRaw(pos);
        }
        // Recalculate bounds from the new positions
        construction.recalculateBounds();

        // Step 2: Update room block positions after rotation.
        // Room blocks are stored with absolute world coordinates.
        // We need to transform them the same way as base blocks.
        if (rotationSteps != 0 && !construction.getRooms().isEmpty()) {
            Architect.LOGGER.info("Rotating {} rooms by {} steps", construction.getRooms().size(), rotationSteps);

            for (Room room : construction.getRooms().values()) {
                if (room.getChangedBlockCount() == 0) continue;

                // Collect current room block positions (with old world coordinates)
                Set<BlockPos> oldRoomPositions = new HashSet<>(room.getChangedBlocks());

                // Clear and rebuild with new coordinates
                room.clearBlockChanges();

                for (BlockPos oldPos : oldRoomPositions) {
                    // Convert to normalized coordinates (relative to original bounds)
                    int normX = oldPos.getX() - originalMinX;
                    int normY = oldPos.getY() - originalMinY;
                    int normZ = oldPos.getZ() - originalMinZ;

                    // Rotate normalized position around pivot
                    int[] rotatedXZ = rotateXZ(normX, normZ, pivotX, pivotZ, rotationSteps);
                    int rotatedNormX = rotatedXZ[0];
                    int rotatedNormZ = rotatedXZ[1];

                    // Calculate final world position (same formula as base blocks)
                    BlockPos newPos = new BlockPos(
                        targetPos.getX() + rotatedNormX,
                        targetPos.getY() + normY,
                        targetPos.getZ() + rotatedNormZ
                    );

                    room.setBlockChange(newPos);
                }

                Architect.LOGGER.debug("Room '{}': rotated {} blocks", room.getId(), room.getChangedBlockCount());
            }
        }

        // Step 3: Entity rotation is NOT handled here.
        // In the reference-only model, entities are spawned directly from the snapshot data
        // with rotation applied during the spawnEntitiesFrozenRotated call.
        // The new entity UUIDs are tracked after spawning.

        // Step 4: Update anchor position AND yaw after rotation.
        // Anchor is stored in normalized coordinates (relative to bounds.min).
        if (rotationSteps != 0 && construction.getAnchors().hasEntrance()) {
            BlockPos oldEntrancePos = construction.getAnchors().getEntrance();
            float oldYaw = construction.getAnchors().getEntranceYaw();

            // Calculate normalization offset
            int[][] oldCorners = {
                {0, 0},
                {originalSizeX - 1, 0},
                {0, originalSizeZ - 1},
                {originalSizeX - 1, originalSizeZ - 1}
            };
            int minRotatedX = Integer.MAX_VALUE;
            int minRotatedZ = Integer.MAX_VALUE;
            for (int[] corner : oldCorners) {
                int[] rotated = rotateXZ(corner[0], corner[1], pivotX, pivotZ, rotationSteps);
                minRotatedX = Math.min(minRotatedX, rotated[0]);
                minRotatedZ = Math.min(minRotatedZ, rotated[1]);
            }
            int normOffsetX = -minRotatedX;
            int normOffsetZ = -minRotatedZ;

            // Rotate entrance position around pivot (in normalized space)
            int[] rotatedPos = rotateXZ(oldEntrancePos.getX(), oldEntrancePos.getZ(), pivotX, pivotZ, rotationSteps);

            // Apply normalization offset
            BlockPos newEntrancePos = new BlockPos(
                rotatedPos[0] + normOffsetX,
                oldEntrancePos.getY(),
                rotatedPos[1] + normOffsetZ
            );

            // Rotate yaw
            float newYaw = oldYaw + (rotationSteps * 90f);
            // Normalize to -180 to 180 range
            newYaw = ((newYaw + 180f) % 360f) - 180f;
            if (newYaw < -180f) newYaw += 360f;

            construction.getAnchors().setEntrance(newEntrancePos, newYaw);
        }
    }

    /**
     * Rotates entity NBT data with normalization.
     * Used for entities in construction storage (normalized coordinates).
     */
    private static void rotateEntityNbtNormalized(CompoundTag nbt, int rotationSteps, int pivotX, int pivotZ, int normOffsetX, int normOffsetZ) {
        if (rotationSteps == 0) return;

        // Rotate block_pos for hanging entities
        if (nbt.contains("block_pos")) {
            Tag rawTag = nbt.get("block_pos");
            if (rawTag instanceof IntArrayTag intArrayTag) {
                int[] coords = intArrayTag.getAsIntArray();
                if (coords.length >= 3) {
                    int[] rotated = rotateXZ(coords[0], coords[2], pivotX, pivotZ, rotationSteps);
                    nbt.putIntArray("block_pos", new int[]{rotated[0] + normOffsetX, coords[1], rotated[1] + normOffsetZ});
                }
            }
        } else if (nbt.contains("TileX") && nbt.contains("TileY") && nbt.contains("TileZ")) {
            int relX = nbt.getIntOr("TileX", 0);
            int relZ = nbt.getIntOr("TileZ", 0);
            int[] rotated = rotateXZ(relX, relZ, pivotX, pivotZ, rotationSteps);
            nbt.putInt("TileX", rotated[0] + normOffsetX);
            nbt.putInt("TileZ", rotated[1] + normOffsetZ);
        }

        // Rotate sleeping_pos for villagers
        if (nbt.contains("sleeping_pos")) {
            Tag sleepingTag = nbt.get("sleeping_pos");
            if (sleepingTag instanceof IntArrayTag sleepingIntArray) {
                int[] coords = sleepingIntArray.getAsIntArray();
                if (coords.length >= 3) {
                    int[] rotated = rotateXZ(coords[0], coords[2], pivotX, pivotZ, rotationSteps);
                    nbt.putIntArray("sleeping_pos", new int[]{rotated[0] + normOffsetX, coords[1], rotated[1] + normOffsetZ});
                }
            }
        }

        // Rotate Facing for hanging entities (item frames use uppercase "Facing" with 3D values)
        if (nbt.contains("Facing")) {
            int facing = nbt.getByteOr("Facing", (byte) 0);
            if (facing >= 2 && facing <= 5) {
                int newFacing = rotateFacing(facing, rotationSteps);
                nbt.putByte("Facing", (byte) newFacing);
            }
        }

        // Rotate facing for paintings (lowercase "facing" with 2D values)
        if (nbt.contains("facing")) {
            int facing2D = nbt.getByteOr("facing", (byte) 0);
            int newFacing2D = rotateFacing2D(facing2D, rotationSteps);
            nbt.putByte("facing", (byte) newFacing2D);
        }

        // Rotate yaw in Rotation tag for mobs
        if (nbt.contains("Rotation")) {
            Tag rotationTag = nbt.get("Rotation");
            if (rotationTag instanceof net.minecraft.nbt.ListTag rotationList && rotationList.size() >= 2) {
                float originalYaw = rotationList.getFloatOr(0, 0f);
                float pitch = rotationList.getFloatOr(1, 0f);
                float rotatedYaw = originalYaw + (rotationSteps * 90f);

                net.minecraft.nbt.ListTag newRotation = new net.minecraft.nbt.ListTag();
                newRotation.add(net.minecraft.nbt.FloatTag.valueOf(rotatedYaw));
                newRotation.add(net.minecraft.nbt.FloatTag.valueOf(pitch));
                nbt.put("Rotation", newRotation);
            }
        }
    }

    /**
     * Rotates entity NBT data: block_pos, sleeping_pos, Facing, facing, and Rotation tags.
     * Used when updating construction coordinates after a rotated placement.
     * Does NOT re-normalize coordinates - keeps them relative to the rotated pivot.
     */
    private static void rotateEntityNbt(CompoundTag nbt, int rotationSteps, int pivotX, int pivotZ) {
        if (rotationSteps == 0) return;

        // Rotate block_pos for hanging entities
        if (nbt.contains("block_pos")) {
            Tag rawTag = nbt.get("block_pos");
            if (rawTag instanceof IntArrayTag intArrayTag) {
                int[] coords = intArrayTag.getAsIntArray();
                if (coords.length >= 3) {
                    int[] rotated = rotateXZ(coords[0], coords[2], pivotX, pivotZ, rotationSteps);
                    nbt.putIntArray("block_pos", new int[]{rotated[0], coords[1], rotated[1]});
                }
            }
        } else if (nbt.contains("TileX") && nbt.contains("TileY") && nbt.contains("TileZ")) {
            int relX = nbt.getIntOr("TileX", 0);
            int relZ = nbt.getIntOr("TileZ", 0);
            int[] rotated = rotateXZ(relX, relZ, pivotX, pivotZ, rotationSteps);
            nbt.putInt("TileX", rotated[0]);
            nbt.putInt("TileZ", rotated[1]);
        }

        // Rotate sleeping_pos for villagers
        if (nbt.contains("sleeping_pos")) {
            Tag sleepingTag = nbt.get("sleeping_pos");
            if (sleepingTag instanceof IntArrayTag sleepingIntArray) {
                int[] coords = sleepingIntArray.getAsIntArray();
                if (coords.length >= 3) {
                    int[] rotated = rotateXZ(coords[0], coords[2], pivotX, pivotZ, rotationSteps);
                    nbt.putIntArray("sleeping_pos", new int[]{rotated[0], coords[1], rotated[1]});
                }
            }
        }

        // Rotate Facing for hanging entities (item frames use uppercase "Facing" with 3D values)
        if (nbt.contains("Facing")) {
            int facing = nbt.getByteOr("Facing", (byte) 0);
            if (facing >= 2 && facing <= 5) {
                int newFacing = rotateFacing(facing, rotationSteps);
                nbt.putByte("Facing", (byte) newFacing);
            }
        }

        // Rotate facing for paintings (lowercase "facing" with 2D values)
        if (nbt.contains("facing")) {
            int facing2D = nbt.getByteOr("facing", (byte) 0);
            int newFacing2D = rotateFacing2D(facing2D, rotationSteps);
            nbt.putByte("facing", (byte) newFacing2D);
        }

        // Rotate yaw in Rotation tag for mobs
        if (nbt.contains("Rotation")) {
            Tag rotationTag = nbt.get("Rotation");
            if (rotationTag instanceof net.minecraft.nbt.ListTag rotationList && rotationList.size() >= 2) {
                float originalYaw = rotationList.getFloatOr(0, 0f);
                float pitch = rotationList.getFloatOr(1, 0f);
                float rotatedYaw = originalYaw + (rotationSteps * 90f);

                net.minecraft.nbt.ListTag newRotation = new net.minecraft.nbt.ListTag();
                newRotation.add(net.minecraft.nbt.FloatTag.valueOf(rotatedYaw));
                newRotation.add(net.minecraft.nbt.FloatTag.valueOf(pitch));
                nbt.put("Rotation", newRotation);
            }
        }
    }

    /**
     * Spawns entities with freeze (NoGravity during spawn, mobs with NoAI permanently).
     */
    private static int spawnEntitiesFrozen(
        List<EntityData> entities,
        ServerLevel level,
        int originX, int originY, int originZ
    ) {
        if (entities.isEmpty()) {
            return 0;
        }

        List<Entity> spawnedEntities = new ArrayList<>();
        int spawnedCount = 0;

        for (EntityData data : entities) {
            try {
                // Calculate world position
                double worldX = originX + data.getRelativePos().x;
                double worldY = originY + data.getRelativePos().y;
                double worldZ = originZ + data.getRelativePos().z;

                // Copy and clean NBT
                CompoundTag nbt = data.getNbt().copy();
                nbt.remove("Motion");
                nbt.remove("UUID");

                // Remove maps from item frames
                String entityType = data.getEntityType();
                if (entityType.equals("minecraft:item_frame") || entityType.equals("minecraft:glow_item_frame")) {
                    EntityData.removeMapFromItemFrameNbt(nbt);
                }

                // Ensure id tag exists
                if (!nbt.contains("id")) {
                    nbt.putString("id", data.getEntityType());
                }

                // Update block_pos for hanging entities
                updateHangingEntityCoords(nbt, originX, originY, originZ);

                // Update sleeping_pos for villagers
                updateSleepingPos(nbt, originX, originY, originZ);

                // Set Pos in NBT BEFORE entity creation.
                // For hanging entities (paintings, item frames), use block_pos as approximate Pos
                // so MC validation passes, then loadEntityRecursive recalculates the exact position
                // from block_pos + facing + size. For other entities, use the stored relative position.
                if (nbt.contains("block_pos")) {
                    Tag rawTag = nbt.get("block_pos");
                    if (rawTag instanceof IntArrayTag intArrayTag) {
                        int[] coords = intArrayTag.getAsIntArray();
                        if (coords.length >= 3) {
                            net.minecraft.nbt.ListTag posTag = new net.minecraft.nbt.ListTag();
                            posTag.add(net.minecraft.nbt.DoubleTag.valueOf(coords[0] + 0.5));
                            posTag.add(net.minecraft.nbt.DoubleTag.valueOf(coords[1] + 0.5));
                            posTag.add(net.minecraft.nbt.DoubleTag.valueOf(coords[2] + 0.5));
                            nbt.put("Pos", posTag);
                        }
                    }
                } else {
                    net.minecraft.nbt.ListTag posTag = new net.minecraft.nbt.ListTag();
                    posTag.add(net.minecraft.nbt.DoubleTag.valueOf(worldX));
                    posTag.add(net.minecraft.nbt.DoubleTag.valueOf(worldY));
                    posTag.add(net.minecraft.nbt.DoubleTag.valueOf(worldZ));
                    nbt.put("Pos", posTag);
                }

                // Create entity from NBT
                Entity entity = EntityType.loadEntityRecursive(nbt, level, EntitySpawnReason.LOAD, e -> e);

                if (entity != null) {
                    entity.setUUID(UUID.randomUUID());

                    if (entity instanceof HangingEntity) {
                        // Hanging entities (paintings, item frames) calculate their own position
                        // from block_pos + facing in NBT. Do NOT call setPos() or it will
                        // override the correct position and cause them to detach.
                    } else {
                        entity.setNoGravity(true);
                        entity.setPos(worldX, worldY, worldZ);
                        entity.setYRot(data.getYaw());
                        entity.setXRot(data.getPitch());

                        if (entity instanceof Mob mob) {
                            mob.setNoAi(true);
                        }
                    }

                    level.addFreshEntity(entity);
                    spawnedEntities.add(entity);
                    spawnedCount++;
                } else {
                    Architect.LOGGER.warn("Failed to create entity of type {}", data.getEntityType());
                }
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to spawn entity of type {}: {}",
                    data.getEntityType(), e.getMessage());
            }
        }

        // Re-enable gravity for non-mob entities after all are spawned
        for (Entity entity : spawnedEntities) {
            if (!(entity instanceof Mob) && !(entity instanceof HangingEntity)) {
                entity.setNoGravity(false);
            }
        }

        return spawnedCount;
    }

    /**
     * Spawns entities with freeze and rotation support.
     * Rotates entity positions around pivot and adjusts entity yaw.
     * Tracks spawned entity UUIDs in the construction for later removal.
     *
     * @param construction The construction to track spawned entities in (can be null to skip tracking)
     */
    private static List<Entity> spawnEntitiesFrozenRotated(
        List<EntityData> entities,
        ServerLevel level,
        int targetX, int targetY, int targetZ,
        int rotationSteps,
        int pivotX, int pivotZ,
        Construction construction,
        boolean keepFrozen
    ) {
        if (entities.isEmpty()) {
            return new ArrayList<>();
        }

        List<Entity> spawnedEntities = new ArrayList<>();

        // For entities, pivot should be at center of the pivot block (add 0.5)
        // This ensures entities rotate around the same center as blocks
        double pivotCenterX = pivotX + 0.5;
        double pivotCenterZ = pivotZ + 0.5;

        Architect.LOGGER.info("spawnEntitiesFrozenRotated: target=({},{},{}), rotation={}, pivot=({},{}), pivotCenter=({},{}), entities={}",
            targetX, targetY, targetZ, rotationSteps, pivotX, pivotZ, pivotCenterX, pivotCenterZ, entities.size());

        for (EntityData data : entities) {
            try {
                // Get relative position and rotate it around pivot center
                double relX = data.getRelativePos().x;
                double relY = data.getRelativePos().y;
                double relZ = data.getRelativePos().z;

                // Rotate the relative position around pivot center
                double[] rotatedXZ = rotateXZDouble(relX, relZ, pivotCenterX, pivotCenterZ, rotationSteps);
                double rotatedRelX = rotatedXZ[0];
                double rotatedRelZ = rotatedXZ[1];

                // Calculate world position
                double worldX = targetX + rotatedRelX;
                double worldY = targetY + relY;
                double worldZ = targetZ + rotatedRelZ;

                Architect.LOGGER.info("  Entity {}: relPos=({},{},{}), rotatedRel=({},{}), worldPos=({},{},{})",
                    data.getEntityType(), relX, relY, relZ, rotatedRelX, rotatedRelZ, worldX, worldY, worldZ);

                // Copy and clean NBT
                CompoundTag nbt = data.getNbt().copy();
                nbt.remove("Motion");
                nbt.remove("UUID");
                if (keepFrozen) {
                    // Remove spawn properties - will be restored by unfreezeSpawnedEntities
                    nbt.remove("NoGravity");
                    nbt.remove("NoAI");
                }

                // Remove maps from item frames
                String entityType = data.getEntityType();
                if (entityType.equals("minecraft:item_frame") || entityType.equals("minecraft:glow_item_frame")) {
                    EntityData.removeMapFromItemFrameNbt(nbt);
                }

                // Ensure id tag exists
                if (!nbt.contains("id")) {
                    nbt.putString("id", data.getEntityType());
                }

                // Update block_pos for hanging entities (item frames, paintings)
                // Read relative block_pos from NBT, rotate around pivot, add target offset
                // This must be done BEFORE entity creation since Minecraft validates block_pos
                // Log block_pos BEFORE update
                if (nbt.contains("block_pos")) {
                    Tag bpBefore = nbt.get("block_pos");
                    if (bpBefore instanceof IntArrayTag bpArr) {
                        int[] c = bpArr.getAsIntArray();
                        Architect.LOGGER.info("  block_pos BEFORE update: [{},{},{}]", c[0], c[1], c[2]);
                    }
                }

                updateHangingEntityCoordsRotated(nbt, targetX, targetY, targetZ, rotationSteps, pivotX, pivotZ);

                // Log block_pos AFTER update
                if (nbt.contains("block_pos")) {
                    Tag bpAfter = nbt.get("block_pos");
                    if (bpAfter instanceof IntArrayTag bpArr) {
                        int[] c = bpArr.getAsIntArray();
                        Architect.LOGGER.info("  block_pos AFTER update: [{},{},{}]", c[0], c[1], c[2]);
                    }
                }

                // Update sleeping_pos for villagers (with rotation)
                updateSleepingPosRotated(nbt, targetX, targetY, targetZ, rotationSteps, pivotX, pivotZ);

                // Set Pos in NBT BEFORE entity creation.
                // For hanging entities, use block_pos as approximate Pos so MC validation passes,
                // then loadEntityRecursive recalculates the exact position from block_pos + facing + size.
                // For other entities, use the rotated relative position.
                if (nbt.contains("block_pos")) {
                    Tag rawTag = nbt.get("block_pos");
                    if (rawTag instanceof IntArrayTag intArrayTag) {
                        int[] coords = intArrayTag.getAsIntArray();
                        if (coords.length >= 3) {
                            Architect.LOGGER.info("  Pos set from block_pos: ({},{},{})",
                                coords[0] + 0.5, coords[1] + 0.5, coords[2] + 0.5);
                            net.minecraft.nbt.ListTag posTag = new net.minecraft.nbt.ListTag();
                            posTag.add(net.minecraft.nbt.DoubleTag.valueOf(coords[0] + 0.5));
                            posTag.add(net.minecraft.nbt.DoubleTag.valueOf(coords[1] + 0.5));
                            posTag.add(net.minecraft.nbt.DoubleTag.valueOf(coords[2] + 0.5));
                            nbt.put("Pos", posTag);
                        }
                    }
                } else {
                    net.minecraft.nbt.ListTag posTag = new net.minecraft.nbt.ListTag();
                    posTag.add(net.minecraft.nbt.DoubleTag.valueOf(worldX));
                    posTag.add(net.minecraft.nbt.DoubleTag.valueOf(worldY));
                    posTag.add(net.minecraft.nbt.DoubleTag.valueOf(worldZ));
                    nbt.put("Pos", posTag);
                }

                // Rotate facing for ALL entities that have it (item frames, paintings, etc.)
                // MC 1.21.11 uses "Facing" (capital F), not "facing"
                if (nbt.contains("Facing") && rotationSteps != 0) {
                    int facing = nbt.getByteOr("Facing", (byte) 0);
                    // Only rotate horizontal facings (2-5): down=0, up=1, north=2, south=3, west=4, east=5
                    if (facing >= 2 && facing <= 5) {
                        int newFacing = rotateFacing(facing, rotationSteps);
                        nbt.putByte("Facing", (byte) newFacing);
                        Architect.LOGGER.debug("Rotated entity Facing from {} to {} (steps={})", facing, newFacing, rotationSteps);
                    }
                }

                // Rotate "facing" (lowercase) for paintings (MC 1.21.11 LEGACY_ID_CODEC_2D)
                // Paintings use different facing values than item frames:
                // 2D: 0=south, 1=west, 2=north, 3=east
                if (nbt.contains("facing") && rotationSteps != 0) {
                    int facing2D = nbt.getByteOr("facing", (byte) 0);
                    int newFacing2D = rotateFacing2D(facing2D, rotationSteps);
                    nbt.putByte("facing", (byte) newFacing2D);
                    Architect.LOGGER.debug("Rotated painting facing (2D) from {} to {} (steps={})", facing2D, newFacing2D, rotationSteps);
                }

                // Rotate yaw in NBT for non-hanging entities (mobs, armor stands, etc.)
                // Must be done BEFORE creating the entity, as setYRot() after creation may be ignored
                boolean isHangingEntity = entityType.equals("minecraft:item_frame") ||
                                          entityType.equals("minecraft:glow_item_frame") ||
                                          entityType.equals("minecraft:painting");
                if (!isHangingEntity && rotationSteps != 0 && nbt.contains("Rotation")) {
                    // Rotation tag is a list of 2 floats: [yaw, pitch]
                    net.minecraft.nbt.Tag rotationTag = nbt.get("Rotation");
                    if (rotationTag instanceof net.minecraft.nbt.ListTag rotationList && rotationList.size() >= 2) {
                        float originalYaw = rotationList.getFloatOr(0, 0f);
                        float pitch = rotationList.getFloatOr(1, 0f);
                        // Coordinates rotate COUNTER-CLOCKWISE, yaw must match
                        // MC yaw increases clockwise (0=south, 90=west, 180=north, 270=east)
                        // So to rotate entity view counter-clockwise, we ADD degrees
                        float rotatedYaw = originalYaw + (rotationSteps * 90f);

                        net.minecraft.nbt.ListTag newRotation = new net.minecraft.nbt.ListTag();
                        newRotation.add(net.minecraft.nbt.FloatTag.valueOf(rotatedYaw));
                        newRotation.add(net.minecraft.nbt.FloatTag.valueOf(pitch));
                        nbt.put("Rotation", newRotation);

                        Architect.LOGGER.debug("Rotated entity yaw in NBT from {} to {} (steps={})", originalYaw, rotatedYaw, rotationSteps);
                    }
                }

                // Create entity from NBT
                Entity entity = EntityType.loadEntityRecursive(nbt, level, EntitySpawnReason.LOAD, e -> e);

                if (entity != null) {
                    entity.setUUID(UUID.randomUUID());

                    if (entity instanceof HangingEntity) {
                        // Hanging entities (paintings, item frames) calculate their own position
                        // from block_pos + facing in NBT. Do NOT call setPos() or it will
                        // override the correct position and cause them to detach.
                        Architect.LOGGER.info("  HangingEntity created: finalPos=({},{},{}), blockPos={}",
                            entity.getX(), entity.getY(), entity.getZ(), entity.blockPosition());
                    } else {
                        entity.setNoGravity(true);
                        entity.setPos(worldX, worldY, worldZ);
                        entity.setXRot(data.getPitch());

                        if (entity instanceof Mob mob) {
                            mob.setNoAi(true);
                        }
                        Architect.LOGGER.info("  Entity created: finalPos=({},{},{})",
                            entity.getX(), entity.getY(), entity.getZ());
                    }

                    if (keepFrozen) {
                        // Mark entity as spawned by struttura (for cleanup and unfreeze)
                        entity.getTags().add("struttura_spawned");
                    }

                    level.addFreshEntity(entity);
                    spawnedEntities.add(entity);

                    // Track entity UUID in construction for later removal
                    if (construction != null) {
                        construction.addEntityRaw(entity.getUUID());
                    }
                } else {
                    Architect.LOGGER.warn("Failed to create entity of type {}", data.getEntityType());
                }
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to spawn entity of type {}: {}",
                    data.getEntityType(), e.getMessage());
            }
        }

        if (!keepFrozen) {
            // Re-enable gravity for non-mob entities after all are spawned
            for (Entity entity : spawnedEntities) {
                if (!(entity instanceof Mob) && !(entity instanceof HangingEntity)) {
                    entity.setNoGravity(false);
                }
            }
        }

        return spawnedEntities;
    }

    /**
     * Updates block_pos or TileX/Y/Z for hanging entities (item frames, paintings).
     */
    private static void updateHangingEntityCoords(CompoundTag nbt, int originX, int originY, int originZ) {
        // MC 1.21.11 uses IntArrayTag for block_pos
        if (nbt.contains("block_pos")) {
            Tag rawTag = nbt.get("block_pos");
            if (rawTag instanceof IntArrayTag intArrayTag) {
                int[] coords = intArrayTag.getAsIntArray();
                if (coords.length >= 3) {
                    int newX = originX + coords[0];
                    int newY = originY + coords[1];
                    int newZ = originZ + coords[2];
                    nbt.putIntArray("block_pos", new int[]{newX, newY, newZ});
                }
            }
        }
        // Fallback for old format
        else if (nbt.contains("TileX") && nbt.contains("TileY") && nbt.contains("TileZ")) {
            int newX = originX + nbt.getIntOr("TileX", 0);
            int newY = originY + nbt.getIntOr("TileY", 0);
            int newZ = originZ + nbt.getIntOr("TileZ", 0);
            nbt.putInt("TileX", newX);
            nbt.putInt("TileY", newY);
            nbt.putInt("TileZ", newZ);
        }
    }

    /**
     * Updates sleeping_pos for sleeping villagers.
     */
    private static void updateSleepingPos(CompoundTag nbt, int originX, int originY, int originZ) {
        if (nbt.contains("sleeping_pos")) {
            Tag sleepingTag = nbt.get("sleeping_pos");
            if (sleepingTag instanceof IntArrayTag sleepingIntArray) {
                int[] coords = sleepingIntArray.getAsIntArray();
                if (coords.length >= 3) {
                    int newX = originX + coords[0];
                    int newY = originY + coords[1];
                    int newZ = originZ + coords[2];
                    nbt.putIntArray("sleeping_pos", new int[]{newX, newY, newZ});
                }
            }
        }
    }

    /**
     * Updates block_pos or TileX/Y/Z for hanging entities using the calculated world position.
     * This replaces the old rotation-based approach which had coordinate system issues.
     */
    private static void updateHangingEntityCoordsFromWorldPos(CompoundTag nbt, int worldX, int worldY, int worldZ) {
        if (nbt.contains("block_pos")) {
            nbt.putIntArray("block_pos", new int[]{worldX, worldY, worldZ});
        } else if (nbt.contains("TileX") && nbt.contains("TileY") && nbt.contains("TileZ")) {
            nbt.putInt("TileX", worldX);
            nbt.putInt("TileY", worldY);
            nbt.putInt("TileZ", worldZ);
        }
    }

    /**
     * Rotates a horizontal facing direction by the specified number of 90-degree steps.
     * Facing values: 2=north(-Z), 3=south(+Z), 4=west(-X), 5=east(+X)
     *
     * Must match stepsToRotation which uses CLOCKWISE rotation for blocks.
     * Clockwise: north -> east -> south -> west -> north
     */
    /**
     * Rotates a 2D facing value by the specified number of 90-degree clockwise steps.
     * Used by Painting in MC 1.21.11 which stores "facing" (lowercase) with LEGACY_ID_CODEC_2D.
     * 2D facing values: 0=south(+Z), 1=west(-X), 2=north(-Z), 3=east(+X)
     * Clockwise: south -> west -> north -> east -> south
     */
    private static int rotateFacing2D(int facing2D, int steps) {
        if (steps == 0 || facing2D < 0 || facing2D > 3) return facing2D;
        return (facing2D + steps) % 4;
    }

    private static int rotateFacing(int facing, int steps) {
        if (steps == 0 || facing < 2 || facing > 5) return facing;

        // Map facing to clockwise index: north=0, east=1, south=2, west=3
        int cwIndex;
        switch (facing) {
            case 2: cwIndex = 0; break; // north
            case 5: cwIndex = 1; break; // east
            case 3: cwIndex = 2; break; // south
            case 4: cwIndex = 3; break; // west
            default: return facing;
        }

        // Rotate clockwise
        int rotatedIndex = (cwIndex + steps) % 4;

        // Map back to facing value
        switch (rotatedIndex) {
            case 0: return 2; // north
            case 1: return 5; // east
            case 2: return 3; // south
            case 3: return 4; // west
            default: return facing;
        }
    }

    /**
     * Updates block_pos or TileX/Y/Z for hanging entities (item frames, paintings) with rotation.
     * Reads the relative block_pos from NBT, rotates it around the pivot, and adds the target offset.
     */
    private static void updateHangingEntityCoordsRotated(
        CompoundTag nbt,
        int targetX, int targetY, int targetZ,
        int rotationSteps, int pivotX, int pivotZ
    ) {
        if (nbt.contains("block_pos")) {
            Tag rawTag = nbt.get("block_pos");
            if (rawTag instanceof IntArrayTag intArrayTag) {
                int[] coords = intArrayTag.getAsIntArray();
                if (coords.length >= 3) {
                    // Rotate the relative block position around the pivot
                    int[] rotated = rotateXZ(coords[0], coords[2], pivotX, pivotZ, rotationSteps);
                    int newX = targetX + rotated[0];
                    int newY = targetY + coords[1];
                    int newZ = targetZ + rotated[1];
                    nbt.putIntArray("block_pos", new int[]{newX, newY, newZ});
                }
            }
        } else if (nbt.contains("TileX") && nbt.contains("TileY") && nbt.contains("TileZ")) {
            // Fallback for old format
            int relX = nbt.getIntOr("TileX", 0);
            int relY = nbt.getIntOr("TileY", 0);
            int relZ = nbt.getIntOr("TileZ", 0);
            int[] rotated = rotateXZ(relX, relZ, pivotX, pivotZ, rotationSteps);
            nbt.putInt("TileX", targetX + rotated[0]);
            nbt.putInt("TileY", targetY + relY);
            nbt.putInt("TileZ", targetZ + rotated[1]);
        }
    }

    /**
     * Updates sleeping_pos for sleeping villagers with rotation support.
     */
    private static void updateSleepingPosRotated(
        CompoundTag nbt,
        int targetX, int targetY, int targetZ,
        int rotationSteps, int pivotX, int pivotZ
    ) {
        if (nbt.contains("sleeping_pos")) {
            Tag sleepingTag = nbt.get("sleeping_pos");
            if (sleepingTag instanceof IntArrayTag sleepingIntArray) {
                int[] coords = sleepingIntArray.getAsIntArray();
                if (coords.length >= 3) {
                    int[] rotated = rotateXZ(coords[0], coords[2], pivotX, pivotZ, rotationSteps);
                    int newX = targetX + rotated[0];
                    int newY = targetY + coords[1];
                    int newZ = targetZ + rotated[1];
                    nbt.putIntArray("sleeping_pos", new int[]{newX, newY, newZ});
                }
            }
        }
    }

    // ============== ROTATION HELPER METHODS ==============

    /**
     * Calculates the number of 90-degree rotation steps needed to align the construction
     * with the target yaw.
     *
     * @param targetYaw The target yaw (typically player's yaw)
     * @param entranceYaw The yaw stored in the entrance anchor (0 if no anchor)
     * @return Number of 90-degree clockwise rotation steps (0-3)
     */
    public static int calculateRotationSteps(float targetYaw, float entranceYaw) {
        float deltaYaw = targetYaw - entranceYaw;
        // Normalize to 0-360
        deltaYaw = ((deltaYaw % 360) + 360) % 360;
        // Convert to steps (0, 1, 2, 3)
        int steps = Math.round(deltaYaw / 90f) % 4;
        return steps;
    }

    /**
     * Converts rotation steps to Minecraft Rotation enum.
     *
     * @param steps Number of 90-degree rotations (0-3)
     * @return The corresponding Rotation enum value
     */
    private static Rotation stepsToRotation(int steps) {
        // Minecraft's Rotation enum uses clockwise naming
        // We need blocks to rotate in the same direction as coordinates
        return switch (steps) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /**
     * Rotates X and Z coordinates around a pivot point by the specified number of 90-degree steps.
     *
     * @param x The X coordinate to rotate
     * @param z The Z coordinate to rotate
     * @param pivotX The pivot X coordinate
     * @param pivotZ The pivot Z coordinate
     * @param steps Number of 90-degree counter-clockwise rotations (0-3)
     * @return Array with [newX, newZ]
     */
    private static int[] rotateXZ(int x, int z, int pivotX, int pivotZ, int steps) {
        if (steps == 0) return new int[]{x, z};

        int relX = x - pivotX;
        int relZ = z - pivotZ;
        int newRelX, newRelZ;

        // COUNTER-CLOCKWISE rotation
        // Looking from above (Y+), counter-clockwise means: +X -> -Z -> -X -> +Z -> +X
        switch (steps) {
            case 1: // 90° counter-clockwise: (x,z) -> (-z,x)
                newRelX = -relZ;
                newRelZ = relX;
                break;
            case 2: // 180°: (x,z) -> (-x,-z)
                newRelX = -relX;
                newRelZ = -relZ;
                break;
            case 3: // 270° counter-clockwise (= 90° clockwise): (x,z) -> (z,-x)
                newRelX = relZ;
                newRelZ = -relX;
                break;
            default:
                return new int[]{x, z};
        }

        return new int[]{pivotX + newRelX, pivotZ + newRelZ};
    }

    /**
     * Rotates a BlockPos around a pivot point.
     *
     * @param pos The position to rotate
     * @param pivotX The pivot X coordinate
     * @param pivotZ The pivot Z coordinate
     * @param steps Number of 90-degree clockwise rotations (0-3)
     * @return The rotated position
     */
    private static BlockPos rotateBlockPos(BlockPos pos, int pivotX, int pivotZ, int steps) {
        if (steps == 0) return pos;
        int[] rotated = rotateXZ(pos.getX(), pos.getZ(), pivotX, pivotZ, steps);
        return new BlockPos(rotated[0], pos.getY(), rotated[1]);
    }

    /**
     * Rotates a double coordinate pair around a pivot point.
     *
     * @param x The X coordinate to rotate
     * @param z The Z coordinate to rotate
     * @param pivotX The pivot X coordinate
     * @param pivotZ The pivot Z coordinate
     * @param steps Number of 90-degree counter-clockwise rotations (0-3)
     * @return Array with [newX, newZ]
     */
    private static double[] rotateXZDouble(double x, double z, double pivotX, double pivotZ, int steps) {
        if (steps == 0) return new double[]{x, z};

        double relX = x - pivotX;
        double relZ = z - pivotZ;
        double newRelX, newRelZ;

        // COUNTER-CLOCKWISE rotation (matching rotateXZ)
        // Looking from above (Y+), counter-clockwise means: +X -> -Z -> -X -> +Z -> +X
        switch (steps) {
            case 1: // 90° counter-clockwise: (x,z) -> (-z,x)
                newRelX = -relZ;
                newRelZ = relX;
                break;
            case 2: // 180°: (x,z) -> (-x,-z)
                newRelX = -relX;
                newRelZ = -relZ;
                break;
            case 3: // 270° counter-clockwise (= 90° clockwise): (x,z) -> (z,-x)
                newRelX = relZ;
                newRelZ = -relX;
                break;
            default:
                return new double[]{x, z};
        }

        return new double[]{pivotX + newRelX, pivotZ + newRelZ};
    }

    /**
     * Gets the cardinal direction name for a yaw angle.
     */
    private static String getDirectionName(float yaw) {
        // Normalize yaw to 0-360
        yaw = ((yaw % 360) + 360) % 360;
        if (yaw >= 315 || yaw < 45) return "SOUTH";
        if (yaw >= 45 && yaw < 135) return "WEST";
        if (yaw >= 135 && yaw < 225) return "NORTH";
        return "EAST";
    }

    // ============== ARCHITECT SPAWN ==============

    /**
     * Result of an ArchitectSpawn operation.
     */
    public record ArchitectSpawnResult(
        int blocksPlaced,
        int entitiesSpawned,
        int roomsSpawned,
        BlockPos origin
    ) {}

    /**
     * Spawns a construction in Architect mode with random room selection.
     * <p>
     * Process:
     * 1. Extract all rooms that pass spawn criteria (in ArchitectSpawn mode: all rooms)
     * 2. Randomly select rooms based on world seed + player position
     * 3. Filter rooms with non-overlapping bounds
     * 4. Place base construction blocks (no entities, no command blocks)
     * 5. Place selected room blocks (overwrite base where applicable)
     * 6. Spawn all entities (base + selected rooms) - frozen
     * 7. Place command blocks (base + selected rooms)
     * 8. Unfreeze all spawned entities
     * <p>
     * Note: Construction is NOT registered in the registry.
     *
     * @param player The player executing the spawn
     * @param construction The construction to spawn
     * @param yaw The rotation angle for the construction
     * @return ArchitectSpawnResult with counts and origin position
     */
    public static ArchitectSpawnResult architectSpawn(
        ServerPlayer player,
        Construction construction,
        ConstructionSnapshot snapshot,
        float yaw
    ) {
        return architectSpawn(player, construction, snapshot, yaw, null);
    }

    /**
     * Spawns a construction in Architect mode with random room selection.
     * <p>
     * Process:
     * 1. Extract all rooms that pass spawn criteria (in ArchitectSpawn mode: all rooms)
     * 2. Randomly select rooms based on world seed + player position
     * 3. Filter rooms with non-overlapping bounds
     * 4. Place base construction blocks (no entities, no command blocks)
     * 5. Place selected room blocks (overwrite base where applicable)
     * 6. Spawn all entities (base + selected rooms) - frozen
     * 7. Place command blocks (base + selected rooms)
     * 8. Unfreeze all spawned entities
     * <p>
     * Note: Construction is NOT registered in the registry.
     *
     * @param player The player executing the spawn
     * @param construction The construction to spawn
     * @param snapshot The snapshot containing block states, NBT and entity data
     * @param yaw The rotation angle for the construction
     * @param forcedRoomIds Optional list of room IDs to force spawn (100% probability).
     *                      Rooms still won't spawn if they overlap with each other.
     * @return ArchitectSpawnResult with counts and origin position
     */
    public static ArchitectSpawnResult architectSpawn(
        ServerPlayer player,
        Construction construction,
        ConstructionSnapshot snapshot,
        float yaw,
        @Nullable List<String> forcedRoomIds
    ) {
        return architectSpawn(
            (ServerLevel) player.level(),
            construction,
            snapshot,
            yaw,
            forcedRoomIds,
            null,  // No specific spawn point - calculate from player
            player.level().getSeed() ^ (long)(player.getX() * 1000) ^ (long)(player.getZ() * 1000),
            player
        );
    }

    /**
     * Spawns a construction with random room selection.
     * Used both for architect mode (position from player) and InGame spawning (specific position).
     *
     * @param level The server level
     * @param construction The construction to spawn
     * @param snapshot The snapshot containing block states, NBT and entity data
     * @param yaw The rotation angle for the construction
     * @param forcedRoomIds Optional list of room IDs to force spawn
     * @param spawnPoint Optional specific spawn point (entrance position). If null, calculates from player.
     * @param roomSeed Seed for random room selection
     * @param player Optional player for position calculation when spawnPoint is null
     * @return ArchitectSpawnResult with counts and origin position
     */
    public static ArchitectSpawnResult architectSpawn(
        ServerLevel level,
        Construction construction,
        ConstructionSnapshot snapshot,
        float yaw,
        @Nullable List<String> forcedRoomIds,
        @Nullable BlockPos spawnPoint,
        long roomSeed,
        @Nullable ServerPlayer player
    ) {
        if (snapshot.blocks().isEmpty()) {
            return new ArchitectSpawnResult(0, 0, 0, BlockPos.ZERO);
        }

        ConstructionBounds bounds = construction.getBounds();

        // Step 1: Extract eligible rooms (all rooms in ArchitectSpawn mode)
        List<Room> eligibleRooms = extractEligibleRooms(construction);

        // Step 2: Select rooms to spawn (random, non-overlapping, with forced rooms support)
        List<Room> roomsToSpawn = selectRoomsToSpawnWithSeed(eligibleRooms, roomSeed, construction, snapshot, forcedRoomIds);

        // Step 3: Calculate position and rotation
        int rotationSteps = 0;
        int pivotX = 0;
        int pivotZ = 0;

        float entranceYaw = construction.getAnchors().hasEntrance()
            ? construction.getAnchors().getEntranceYaw()
            : 0f;
        rotationSteps = calculateRotationSteps(yaw, entranceYaw);

        if (construction.getAnchors().hasEntrance()) {
            BlockPos entrance = construction.getAnchors().getEntrance();
            pivotX = entrance.getX();
            pivotZ = entrance.getZ();
        } else {
            pivotX = bounds.getSizeX() / 2;
            pivotZ = bounds.getSizeZ() / 2;
        }

        BlockPos targetPos;
        if (spawnPoint != null) {
            // Use provided spawn point (InGame spawning)
            targetPos = calculatePositionAtEntranceRotated(spawnPoint, construction, rotationSteps, pivotX, pivotZ);
        } else if (player != null) {
            // Calculate from player position (Architect mode)
            if (construction.getAnchors().hasEntrance()) {
                targetPos = calculatePositionAtEntranceRotated(player, construction, rotationSteps, pivotX, pivotZ);
            } else {
                targetPos = calculatePositionInFront(player, bounds);
            }
        } else {
            // No position available
            Architect.LOGGER.error("architectSpawn called without spawnPoint or player");
            return new ArchitectSpawnResult(0, 0, 0, BlockPos.ZERO);
        }

        // Step 4: Place base construction blocks (skip entities and command blocks)
        int blocksPlaced = placeBlocksForArchitectSpawn(
            level, construction, snapshot, targetPos, rotationSteps, pivotX, pivotZ
        );

        // Step 5: Place selected room blocks (overwrite base where applicable)
        for (Room room : roomsToSpawn) {
            ConstructionSnapshot.RoomSnapshot roomSnapshot = snapshot.rooms().get(room.getId());
            if (roomSnapshot != null) {
                blocksPlaced += placeRoomBlocksRotated(
                    level, construction, roomSnapshot, targetPos, rotationSteps, pivotX, pivotZ
                );
            }
        }

        // Step 6: Spawn all entities (base + selected rooms) - frozen
        List<Entity> spawnedEntities = spawnEntitiesForArchitectSpawn(
            level, construction, snapshot, roomsToSpawn, targetPos, rotationSteps, pivotX, pivotZ
        );

        // Step 7: Command blocks are already placed in step 4 and 5
        // (They are not separated in this implementation since we don't need special handling)

        // Step 8: Unfreeze all spawned entities
        unfreezeSpawnedEntities(level, spawnedEntities);

        Architect.LOGGER.info("ArchitectSpawn: {} blocks, {} entities, {} rooms at ({},{},{})",
            blocksPlaced, spawnedEntities.size(), roomsToSpawn.size(),
            targetPos.getX(), targetPos.getY(), targetPos.getZ());

        return new ArchitectSpawnResult(blocksPlaced, spawnedEntities.size(), roomsToSpawn.size(), targetPos);
    }

    /**
     * Extracts rooms that pass the spawn criteria.
     * In ArchitectSpawn mode, ALL rooms are eligible.
     *
     * @param construction The construction containing rooms
     * @return List of rooms that can potentially be spawned
     */
    private static List<Room> extractEligibleRooms(Construction construction) {
        return new ArrayList<>(construction.getRooms().values());
    }

    /**
     * Selects rooms to spawn using randomization with a provided seed.
     *
     * @param eligibleRooms Rooms that passed spawn criteria
     * @param seed Seed for random room selection
     * @param construction Construction for bounds calculation
     * @param snapshot The snapshot containing block states, NBT and entity data
     * @param forcedRoomIds Optional list of room IDs to force spawn (100% probability)
     * @return List of non-overlapping rooms to spawn
     */
    private static List<Room> selectRoomsToSpawnWithSeed(
        List<Room> eligibleRooms,
        long seed,
        Construction construction,
        ConstructionSnapshot snapshot,
        @Nullable List<String> forcedRoomIds
    ) {
        if (eligibleRooms.isEmpty()) {
            return Collections.emptyList();
        }

        List<Room> roomsToSpawn = new ArrayList<>();

        // Convert forced room IDs to a set for O(1) lookup
        Set<String> forcedRoomSet = forcedRoomIds != null
            ? new HashSet<>(forcedRoomIds)
            : Collections.emptySet();

        // Step 1: First, add all forced rooms (in order, checking for overlaps)
        if (!forcedRoomSet.isEmpty()) {
            for (Room room : eligibleRooms) {
                if (forcedRoomSet.contains(room.getId())) {
                    // Forced room: always try to spawn (100% probability)
                    // But still check for overlaps with already selected rooms
                    if (!hasOverlappingBounds(room, roomsToSpawn, construction, snapshot)) {
                        roomsToSpawn.add(room);
                        Architect.LOGGER.debug("Forced room '{}' added to spawn list", room.getId());
                    } else {
                        Architect.LOGGER.debug("Forced room '{}' skipped due to overlap", room.getId());
                    }
                }
            }
        }

        // Step 2: Then, randomly select from remaining rooms (not in forced list)
        List<Room> nonForcedRooms = eligibleRooms.stream()
            .filter(r -> !forcedRoomSet.contains(r.getId()))
            .toList();

        if (!nonForcedRooms.isEmpty()) {
            // Calculate spawn probability: 1/(N+1) where N = number of non-forced eligible rooms
            // The +1 accounts for the "no room spawns" option
            double spawnProbability = 1.0 / (nonForcedRooms.size() + 1);

            Random random = new Random(seed);

            for (Room room : nonForcedRooms) {
                // Roll for this room
                if (random.nextDouble() < spawnProbability) {
                    // Check bounds overlap with already selected rooms (including forced ones)
                    if (!hasOverlappingBounds(room, roomsToSpawn, construction, snapshot)) {
                        roomsToSpawn.add(room);
                    }
                }
            }
        }

        return roomsToSpawn;
    }

    /**
     * Checks if a room's bounds overlap with any room in the list.
     */
    private static boolean hasOverlappingBounds(
        Room candidate,
        List<Room> existingRooms,
        Construction construction,
        ConstructionSnapshot snapshot
    ) {
        if (existingRooms.isEmpty()) {
            return false;
        }

        // Calculate candidate bounds from its block positions
        AABB candidateBounds = calculateRoomBounds(candidate, construction, snapshot);
        if (candidateBounds == null) {
            return false; // Room has no blocks
        }

        for (Room existing : existingRooms) {
            AABB existingBounds = calculateRoomBounds(existing, construction, snapshot);
            if (existingBounds != null && candidateBounds.intersects(existingBounds)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Calculates the AABB bounds of a room based on its block changes and entities from snapshot.
     * Returns null if the room has no blocks or entities.
     */
    @Nullable
    private static AABB calculateRoomBounds(Room room, Construction construction, ConstructionSnapshot snapshot) {
        ConstructionSnapshot.RoomSnapshot roomSnapshot = snapshot.rooms().get(room.getId());

        // Use snapshot blocks if available, otherwise fall back to room positions
        Set<BlockPos> blockPositions = roomSnapshot != null
            ? roomSnapshot.blocks().keySet()
            : room.getChangedBlocks();
        List<EntityData> entities = roomSnapshot != null
            ? roomSnapshot.entities()
            : Collections.emptyList();

        if (blockPositions.isEmpty() && entities.isEmpty() && room.getRoomEntities().isEmpty()) {
            return null;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        // Process block positions
        for (BlockPos pos : blockPositions) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        // Include room entities from snapshot in bounds calculation
        ConstructionBounds constrBounds = construction.getBounds();
        for (EntityData entity : entities) {
            int ex = constrBounds.getMinX() + (int) entity.getRelativePos().x;
            int ey = constrBounds.getMinY() + (int) entity.getRelativePos().y;
            int ez = constrBounds.getMinZ() + (int) entity.getRelativePos().z;
            minX = Math.min(minX, ex);
            minY = Math.min(minY, ey);
            minZ = Math.min(minZ, ez);
            maxX = Math.max(maxX, ex);
            maxY = Math.max(maxY, ey);
            maxZ = Math.max(maxZ, ez);
        }

        if (minX == Integer.MAX_VALUE) {
            return null; // No blocks or entities
        }

        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    /**
     * Places construction blocks for ArchitectSpawn without spawning entities.
     * Uses the same logic as placeConstructionAt but doesn't update coordinates
     * and doesn't spawn entities (they are handled separately).
     *
     * @return Number of blocks placed
     */
    private static int placeBlocksForArchitectSpawn(
        ServerLevel level,
        Construction construction,
        ConstructionSnapshot snapshot,
        BlockPos targetPos,
        int rotationSteps,
        int pivotX,
        int pivotZ
    ) {
        ConstructionBounds bounds = construction.getBounds();
        int originalMinX = bounds.getMinX();
        int originalMinY = bounds.getMinY();
        int originalMinZ = bounds.getMinZ();

        Rotation rotation = stepsToRotation(rotationSteps);

        // Sort blocks by Y ascending (support blocks first)
        List<Map.Entry<BlockPos, BlockState>> sortedBlocks = new ArrayList<>(snapshot.blocks().entrySet());
        sortedBlocks.sort(Comparator.comparingInt(e -> e.getKey().getY()));

        Map<BlockPos, BlockPos> originalPosMap = new HashMap<>(); // newPos -> originalPos for NBT lookup
        int placedCount = 0;

        for (Map.Entry<BlockPos, BlockState> entry : sortedBlocks) {
            BlockPos originalPos = entry.getKey();
            BlockState state = entry.getValue();

            // Normalize position (relative to bounds min)
            int normX = originalPos.getX() - originalMinX;
            int normY = originalPos.getY() - originalMinY;
            int normZ = originalPos.getZ() - originalMinZ;

            // Rotate normalized position around pivot
            int[] rotatedXZ = rotateXZ(normX, normZ, pivotX, pivotZ, rotationSteps);
            int rotatedNormX = rotatedXZ[0];
            int rotatedNormZ = rotatedXZ[1];

            // Calculate final world position
            BlockPos newPos = new BlockPos(
                targetPos.getX() + rotatedNormX,
                targetPos.getY() + normY,
                targetPos.getZ() + rotatedNormZ
            );

            // Rotate the block state
            BlockState rotatedState = state.rotate(rotation);

            originalPosMap.put(newPos, originalPos);

            // Place ALL blocks from NBT, including air (to clear existing terrain)
            level.setBlock(newPos, rotatedState, SILENT_PLACE_FLAGS);
            placedCount++;
        }

        // Apply block entity NBT after ALL blocks are placed
        for (Map.Entry<BlockPos, BlockPos> entry : originalPosMap.entrySet()) {
            BlockPos newPos = entry.getKey();
            BlockPos originalPos = entry.getValue();

            BlockState state = level.getBlockState(newPos);
            if (state.isAir()) continue;

            CompoundTag blockNbt = snapshot.blockEntityNbt().get(originalPos);
            if (blockNbt != null) {
                BlockEntity blockEntity = level.getBlockEntity(newPos);
                if (blockEntity != null) {
                    CompoundTag nbtCopy = blockNbt.copy();
                    nbtCopy.putInt("x", newPos.getX());
                    nbtCopy.putInt("y", newPos.getY());
                    nbtCopy.putInt("z", newPos.getZ());

                    net.minecraft.world.level.storage.ValueInput input = net.minecraft.world.level.storage.TagValueInput.create(
                        ProblemReporter.DISCARDING,
                        level.registryAccess(),
                        nbtCopy
                    );
                    blockEntity.loadCustomOnly(input);
                    blockEntity.setChanged();
                }
            }
        }

        // Update shape connections (fences, walls, etc.) WITHOUT physics
        for (BlockPos newPos : originalPosMap.keySet()) {
            BlockState state = level.getBlockState(newPos);
            if (!state.isAir()) {
                state.updateNeighbourShapes(level, newPos, Block.UPDATE_CLIENTS, 0);
            }
        }

        return placedCount;
    }

    /**
     * Places room delta blocks with rotation support.
     * Overwrites base construction blocks where applicable.
     *
     * @return Number of blocks placed
     */
    private static int placeRoomBlocksRotated(
        ServerLevel level,
        Construction construction,
        ConstructionSnapshot.RoomSnapshot roomSnapshot,
        BlockPos targetPos,
        int rotationSteps,
        int pivotX,
        int pivotZ
    ) {
        if (roomSnapshot.blocks().isEmpty()) {
            return 0;
        }

        ConstructionBounds bounds = construction.getBounds();
        int originalMinX = bounds.getMinX();
        int originalMinY = bounds.getMinY();
        int originalMinZ = bounds.getMinZ();

        Rotation rotation = stepsToRotation(rotationSteps);

        // Sort blocks by Y ascending
        List<Map.Entry<BlockPos, BlockState>> sortedBlocks = new ArrayList<>(roomSnapshot.blocks().entrySet());
        sortedBlocks.sort(Comparator.comparingInt(e -> e.getKey().getY()));

        Map<BlockPos, BlockPos> posMap = new HashMap<>(); // newPos -> originalPos
        int placedCount = 0;

        for (Map.Entry<BlockPos, BlockState> entry : sortedBlocks) {
            BlockPos originalPos = entry.getKey();
            BlockState state = entry.getValue();

            // Room blocks are stored in world coordinates, convert to normalized
            int normX = originalPos.getX() - originalMinX;
            int normY = originalPos.getY() - originalMinY;
            int normZ = originalPos.getZ() - originalMinZ;

            // Rotate normalized position around pivot
            int[] rotatedXZ = rotateXZ(normX, normZ, pivotX, pivotZ, rotationSteps);
            int rotatedNormX = rotatedXZ[0];
            int rotatedNormZ = rotatedXZ[1];

            // Calculate final world position
            BlockPos newPos = new BlockPos(
                targetPos.getX() + rotatedNormX,
                targetPos.getY() + normY,
                targetPos.getZ() + rotatedNormZ
            );

            // Rotate the block state
            BlockState rotatedState = state.rotate(rotation);

            posMap.put(newPos, originalPos);
            level.setBlock(newPos, rotatedState, SILENT_PLACE_FLAGS);
            placedCount++;
        }

        // Apply block entity NBT
        for (Map.Entry<BlockPos, BlockPos> entry : posMap.entrySet()) {
            BlockPos newPos = entry.getKey();
            BlockPos originalPos = entry.getValue();

            BlockState state = level.getBlockState(newPos);
            if (state.isAir()) continue;

            CompoundTag blockNbt = roomSnapshot.blockEntityNbt().get(originalPos);
            if (blockNbt != null) {
                BlockEntity blockEntity = level.getBlockEntity(newPos);
                if (blockEntity != null) {
                    CompoundTag nbtCopy = blockNbt.copy();
                    nbtCopy.putInt("x", newPos.getX());
                    nbtCopy.putInt("y", newPos.getY());
                    nbtCopy.putInt("z", newPos.getZ());

                    net.minecraft.world.level.storage.ValueInput input = net.minecraft.world.level.storage.TagValueInput.create(
                        ProblemReporter.DISCARDING,
                        level.registryAccess(),
                        nbtCopy
                    );
                    blockEntity.loadCustomOnly(input);
                    blockEntity.setChanged();
                }
            }
        }

        // Update shape connections
        for (BlockPos newPos : posMap.keySet()) {
            BlockState state = level.getBlockState(newPos);
            if (!state.isAir()) {
                state.updateNeighbourShapes(level, newPos, Block.UPDATE_CLIENTS, 0);
            }
        }

        Architect.LOGGER.debug("Room: placed {} rotated blocks", placedCount);
        return placedCount;
    }

    /**
     * Spawns entities for ArchitectSpawn (base + selected rooms).
     * Entities are spawned frozen and will be unfrozen later.
     * Base entities are filtered to exclude those at positions where room entities will spawn.
     *
     * @return List of spawned entities (for later unfreezing)
     */
    private static List<Entity> spawnEntitiesForArchitectSpawn(
        ServerLevel level,
        Construction construction,
        ConstructionSnapshot snapshot,
        List<Room> roomsToSpawn,
        BlockPos targetPos,
        int rotationSteps,
        int pivotX,
        int pivotZ
    ) {
        List<Entity> spawnedEntities = new ArrayList<>();

        // Collect all room entity positions from snapshot (to filter out overlapping base entities)
        Set<String> roomEntityPositions = collectRoomEntityPositions(roomsToSpawn, snapshot);

        // Spawn base entities (excluding those at room entity positions)
        List<EntityData> filteredBaseEntities = filterBaseEntities(
            snapshot.entities(), roomEntityPositions
        );
        spawnedEntities.addAll(spawnEntitiesListForArchitectSpawn(
            level, filteredBaseEntities, targetPos,
            rotationSteps, pivotX, pivotZ
        ));

        // Spawn room entities (only from selected rooms, using snapshot data)
        for (Room room : roomsToSpawn) {
            ConstructionSnapshot.RoomSnapshot roomSnapshot = snapshot.rooms().get(room.getId());
            if (roomSnapshot != null) {
                spawnedEntities.addAll(spawnEntitiesListForArchitectSpawn(
                    level, roomSnapshot.entities(), targetPos,
                    rotationSteps, pivotX, pivotZ
                ));
            }
        }

        return spawnedEntities;
    }

    /**
     * Collects all entity positions from rooms that will be spawned, using snapshot data.
     * Positions are stored as strings "x,y,z" (rounded to block coordinates).
     */
    private static Set<String> collectRoomEntityPositions(List<Room> roomsToSpawn, ConstructionSnapshot snapshot) {
        Set<String> positions = new HashSet<>();
        for (Room room : roomsToSpawn) {
            ConstructionSnapshot.RoomSnapshot roomSnapshot = snapshot.rooms().get(room.getId());
            if (roomSnapshot != null) {
                for (EntityData entity : roomSnapshot.entities()) {
                    Vec3 pos = entity.getRelativePos();
                    // Round to block position for comparison
                    String posKey = String.format("%d,%d,%d",
                        (int) Math.floor(pos.x),
                        (int) Math.floor(pos.y),
                        (int) Math.floor(pos.z));
                    positions.add(posKey);
                }
            }
        }
        return positions;
    }

    /**
     * Filters base entities to exclude those at positions where room entities will spawn.
     */
    private static List<EntityData> filterBaseEntities(
        List<EntityData> baseEntities,
        Set<String> roomEntityPositions
    ) {
        if (roomEntityPositions.isEmpty()) {
            return baseEntities; // No filtering needed
        }

        List<EntityData> filtered = new ArrayList<>();
        for (EntityData entity : baseEntities) {
            Vec3 pos = entity.getRelativePos();
            String posKey = String.format("%d,%d,%d",
                (int) Math.floor(pos.x),
                (int) Math.floor(pos.y),
                (int) Math.floor(pos.z));

            if (!roomEntityPositions.contains(posKey)) {
                filtered.add(entity);
            } else {
                Architect.LOGGER.debug("Skipping base entity {} at {} (room entity at same position)",
                    entity.getEntityType(), posKey);
            }
        }
        return filtered;
    }

    /**
     * Spawns a list of entities for ArchitectSpawn with rotation support.
     * Delegates to the centralized spawnEntitiesFrozenRotated with keepFrozen=true,
     * so unfreezeSpawnedEntities handles gravity/AI restoration.
     */
    private static List<Entity> spawnEntitiesListForArchitectSpawn(
        ServerLevel level,
        List<EntityData> entities,
        BlockPos targetPos,
        int rotationSteps,
        int pivotX,
        int pivotZ
    ) {
        return spawnEntitiesFrozenRotated(
            entities, level,
            targetPos.getX(), targetPos.getY(), targetPos.getZ(),
            rotationSteps, pivotX, pivotZ,
            null,   // No construction tracking for architectSpawn
            true    // Keep frozen - unfreezeSpawnedEntities handles it
        );
    }

    /**
     * Unfreezes all spawned entities.
     * Re-enables AI for mobs and restores original gravity settings.
     *
     * @param level The server level
     * @param spawnedEntities List of entities that were spawned and need unfreezing
     */
    private static void unfreezeSpawnedEntities(ServerLevel level, List<Entity> spawnedEntities) {
        for (Entity entity : spawnedEntities) {
            // Check if entity is still valid
            if (entity.isRemoved()) {
                continue;
            }

            EntityType<?> entityType = entity.getType();

            // Create a default entity of the same type to get default property values
            Entity defaultEntity = entityType.create(level, EntitySpawnReason.LOAD);

            if (defaultEntity != null) {
                // Get default values from fresh entity
                boolean defaultNoGravity = defaultEntity.isNoGravity();
                boolean defaultNoAI = (defaultEntity instanceof Mob defaultMob) && defaultMob.isNoAi();

                // Apply default NoGravity (hanging entities always keep NoGravity)
                if (isHangingEntity(entity)) {
                    entity.setNoGravity(true);
                } else {
                    entity.setNoGravity(defaultNoGravity);
                }

                // Apply default NoAI for mobs and nudge out of blocks
                if (entity instanceof Mob mob) {
                    EntityUtils.nudgeEntityOutOfBlocks(level, mob);
                    mob.setNoAi(defaultNoAI);
                    // Prevent hostile mobs from despawning (they would be removed by MC otherwise)
                    mob.setPersistenceRequired();
                }

                // Discard the temporary default entity
                defaultEntity.discard();
            } else {
                // Fallback if we can't create default entity
                if (isHangingEntity(entity)) {
                    entity.setNoGravity(true);
                } else {
                    entity.setNoGravity(false);
                }

                if (entity instanceof Mob mob) {
                    EntityUtils.nudgeEntityOutOfBlocks(level, mob);
                    mob.setNoAi(false);
                    mob.setPersistenceRequired();
                }
            }

            // Clean up struttura tag
            entity.getTags().remove("struttura_spawned");
        }

        Architect.LOGGER.info("Unfroze {} entities", spawnedEntities.size());
    }

    /**
     * Checks if an entity is a hanging entity (item frame, painting, etc.)
     */
    private static boolean isHangingEntity(Entity entity) {
        String typeName = EntityType.getKey(entity.getType()).toString();
        return typeName.equals("minecraft:item_frame") ||
            typeName.equals("minecraft:glow_item_frame") ||
            typeName.equals("minecraft:painting");
    }
}
