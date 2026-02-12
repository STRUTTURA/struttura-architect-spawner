package it.magius.struttura.architect.placement;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.ConstructionBounds;
import it.magius.struttura.architect.model.Construction;
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
        BlockPos newOrigin
    ) {}

    /**
     * Places a construction in the world without triggering physics or block callbacks.
     * Uses the player's position to calculate the spawn point.
     *
     * @param player The player (used to calculate position for SPAWN/PULL/MOVE modes)
     * @param construction The construction to place
     * @param mode The placement mode
     * @param updateConstructionCoords If true, updates the construction's stored coordinates
     * @return PlacementResult with counts and new origin position
     */
    public static PlacementResult placeConstruction(
        ServerPlayer player,
        Construction construction,
        PlacementMode mode,
        boolean updateConstructionCoords
    ) {
        return placeConstruction(player, construction, mode, updateConstructionCoords, null, 0f, false);
    }

    /**
     * Places a construction in the world without triggering physics or block callbacks.
     *
     * @param player The player (used to calculate position for SPAWN/PULL/MOVE modes if spawnPoint is null)
     * @param construction The construction to place
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
        PlacementMode mode,
        boolean updateConstructionCoords,
        @Nullable BlockPos spawnPoint,
        float yaw,
        boolean runInitCommandBlocks
    ) {
        if (construction.getBlockCount() == 0) {
            return new PlacementResult(0, 0, BlockPos.ZERO);
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

        return placeConstructionAt(level, construction, targetPos, updateConstructionCoords, rotationSteps, pivotX, pivotZ);
    }

    /**
     * Places a construction at a specific target position without triggering physics.
     * The target position is where the construction's min corner will be placed.
     * No rotation is applied.
     *
     * @param level The ServerLevel
     * @param construction The construction to place
     * @param targetPos The target position for the construction's min corner
     * @param updateConstructionCoords If true, updates the construction's stored coordinates
     * @return PlacementResult with counts and new origin position
     */
    public static PlacementResult placeConstructionAt(
        ServerLevel level,
        Construction construction,
        BlockPos targetPos,
        boolean updateConstructionCoords
    ) {
        return placeConstructionAt(level, construction, targetPos, updateConstructionCoords, 0, 0, 0);
    }

    /**
     * Places a construction at a specific target position without triggering physics.
     * The target position is where the construction's min corner will be placed.
     * Applies rotation around the specified pivot point.
     *
     * @param level The ServerLevel
     * @param construction The construction to place
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
        BlockPos targetPos,
        boolean updateConstructionCoords,
        int rotationSteps,
        int pivotX,
        int pivotZ
    ) {
        if (construction.getBlockCount() == 0) {
            return new PlacementResult(0, 0, BlockPos.ZERO);
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
        List<Map.Entry<BlockPos, BlockState>> sortedBlocks = new ArrayList<>(construction.getBlocks().entrySet());
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
            CompoundTag blockNbt = construction.getBlockEntityNbt(originalPos);

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
                // No rotation: simple offset update
                int offsetX = targetPos.getX() - originalMinX;
                int offsetY = targetPos.getY() - originalMinY;
                int offsetZ = targetPos.getZ() - originalMinZ;
                if (offsetX != 0 || offsetY != 0 || offsetZ != 0) {
                    updateConstructionCoordinates(construction, offsetX, offsetY, offsetZ, newBlocks);
                }
            } else {
                // With rotation: rebuild construction with new rotated positions and states
                // Entities ARE rotated here, so spawnEntitiesFrozenRotated will use rotationSteps=0
                updateConstructionCoordinatesRotated(construction, newBlocks, originalPosMap,
                    originalMinX, originalMinY, originalMinZ, targetPos,
                    rotationSteps, pivotX, pivotZ, originalSizeX, originalSizeZ);
            }
        }

        // Phase 5: Spawn entities with freeze
        // Clear any previously tracked entity UUIDs before spawning new ones
        construction.clearSpawnedEntityUuids();

        // If updateConstructionCoords is true and rotationSteps != 0, entities were already rotated
        // in updateConstructionCoordinatesRotated, so we spawn with rotationSteps=0.
        // If updateConstructionCoords is false (SHOW mode), we apply the rotation during spawn.
        int entityRotationSteps = (updateConstructionCoords && rotationSteps != 0) ? 0 : rotationSteps;

        // When entities were pre-rotated (entityRotationSteps == 0 but original rotationSteps != 0),
        // their relativePos is stored relative to newBounds.min (updated in updateConstructionCoordinatesRotated).
        // So we must spawn using bounds.min, not targetPos.
        // When NOT pre-rotated (SHOW mode), relativePos is relative to the original bounds, so use targetPos.
        BlockPos entitySpawnBase;
        if (entityRotationSteps == 0 && rotationSteps != 0) {
            // Pre-rotated: use updated bounds.min
            entitySpawnBase = construction.getBounds().getMin();
        } else {
            // Not pre-rotated (SHOW mode or no rotation): use targetPos
            entitySpawnBase = targetPos;
        }

        int entitiesSpawned = spawnEntitiesFrozenRotated(
            construction.getEntities(),
            level,
            entitySpawnBase.getX(), entitySpawnBase.getY(), entitySpawnBase.getZ(),
            entityRotationSteps, pivotX, pivotZ,
            construction
        );

        Architect.LOGGER.info("Placed {} blocks, {} entities at {}", placedCount, entitiesSpawned, targetPos);

        return new PlacementResult(placedCount, entitiesSpawned, targetPos);
    }

    /**
     * Places room delta blocks without triggering physics.
     * Used when entering room edit mode.
     *
     * @param level The ServerLevel
     * @param room The room with delta blocks to apply
     * @param baseConstruction The base construction (for fallback states)
     * @return Number of blocks placed
     */
    public static int placeRoomBlocks(ServerLevel level, Room room, Construction baseConstruction) {
        if (room.getBlockChanges().isEmpty()) {
            return 0;
        }

        // Phase 1: Clear containers at positions we're about to modify
        for (BlockPos pos : room.getBlockChanges().keySet()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Clearable clearable) {
                clearable.clearContent();
            }
        }

        // Phase 2: Sort and place room blocks
        List<Map.Entry<BlockPos, BlockState>> sortedBlocks = new ArrayList<>(room.getBlockChanges().entrySet());
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

            CompoundTag blockNbt = room.getBlockEntityNbt(pos);
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
        for (BlockPos pos : room.getBlockChanges().keySet()) {
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
     *
     * @param level The ServerLevel
     * @param room The room whose positions to restore
     * @param baseConstruction The base construction with original blocks
     * @return Number of blocks restored
     */
    public static int restoreBaseBlocks(ServerLevel level, Room room, Construction baseConstruction) {
        if (room.getBlockChanges().isEmpty()) {
            return 0;
        }

        // Phase 1: Clear containers
        for (BlockPos pos : room.getBlockChanges().keySet()) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Clearable clearable) {
                clearable.clearContent();
            }
        }

        // Phase 2: Build list of base blocks to restore
        List<Map.Entry<BlockPos, BlockState>> blocksToRestore = new ArrayList<>();
        for (BlockPos pos : room.getBlockChanges().keySet()) {
            BlockState baseState = baseConstruction.getBlocks().get(pos);
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
        for (Map.Entry<BlockPos, BlockState> entry : blocksToRestore) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            if (state.isAir()) continue;

            CompoundTag blockNbt = baseConstruction.getBlockEntityNbt(pos);
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

        // Phase 5: Update shape connections
        for (BlockPos pos : room.getBlockChanges().keySet()) {
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
     * @param level The server level
     * @param positions The specific positions to restore
     * @param baseConstruction The base construction with original blocks
     * @return Number of blocks restored
     */
    public static int restoreBaseBlocksAt(ServerLevel level, Collection<BlockPos> positions, Construction baseConstruction) {
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
            BlockState baseState = baseConstruction.getBlocks().get(pos);
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
        for (Map.Entry<BlockPos, BlockState> entry : blocksToRestore) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            if (state.isAir()) continue;

            CompoundTag blockNbt = baseConstruction.getBlockEntityNbt(pos);
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
        Set<UUID> trackedUuids = construction.getSpawnedEntityUuids();
        if (!trackedUuids.isEmpty()) {
            // Get all entities in the server and filter by UUID
            for (UUID uuid : new HashSet<>(trackedUuids)) {
                Entity entity = level.getServer().getPlayerList().getPlayers().stream()
                    .filter(p -> p.getUUID().equals(uuid))
                    .findFirst()
                    .orElse(null);

                if (entity == null) {
                    // Search in all loaded entities using the level's entity lookup
                    entity = level.getEntity(uuid);
                }

                if (entity != null && !(entity instanceof Player)) {
                    entity.discard();
                    entitiesRemoved++;
                }
                construction.untrackSpawnedEntity(uuid);
            }
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
        int spawnX = player.blockPosition().getX();
        int spawnY = (int) Math.round(player.getY());
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
        int spawnX = player.blockPosition().getX();
        int spawnY = (int) Math.round(player.getY());
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
     * Updates construction coordinates after placement at new position.
     */
    private static void updateConstructionCoordinates(
        Construction construction,
        int offsetX, int offsetY, int offsetZ,
        Map<BlockPos, BlockState> newBlocks
    ) {
        // Update block entity NBT coordinates
        Map<BlockPos, CompoundTag> newBlockEntityNbt = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : construction.getBlocks().entrySet()) {
            BlockPos originalPos = entry.getKey();
            CompoundTag nbt = construction.getBlockEntityNbt(originalPos);
            if (nbt != null) {
                BlockPos newPos = originalPos.offset(offsetX, offsetY, offsetZ);
                CompoundTag nbtCopy = nbt.copy();
                nbtCopy.putInt("x", newPos.getX());
                nbtCopy.putInt("y", newPos.getY());
                nbtCopy.putInt("z", newPos.getZ());
                newBlockEntityNbt.put(newPos, nbtCopy);
            }
        }

        // Update blocks
        construction.getBlocks().clear();
        construction.getBounds().reset();
        for (Map.Entry<BlockPos, BlockState> entry : newBlocks.entrySet()) {
            construction.addBlock(entry.getKey(), entry.getValue());
        }

        // Update block entity NBT
        construction.getBlockEntityNbtMap().clear();
        construction.getBlockEntityNbtMap().putAll(newBlockEntityNbt);

        // NOTE: Entity positions are stored relative to bounds.min.
        // Since we're updating blocks to world coordinates (bounds.min changes),
        // but entities are spawned using: worldPos = bounds.min + relativePos,
        // the relativePos doesn't need to change - it stays relative to bounds.
        // Same for anchor - it stays relative to bounds.

        // Update room coordinates (translate together with construction)
        for (Room room : construction.getRooms().values()) {
            Map<BlockPos, BlockState> newRoomBlocks = new HashMap<>();
            Map<BlockPos, CompoundTag> newRoomNbt = new HashMap<>();

            for (Map.Entry<BlockPos, BlockState> entry : room.getBlockChanges().entrySet()) {
                BlockPos originalPos = entry.getKey();
                BlockPos newPos = originalPos.offset(offsetX, offsetY, offsetZ);
                newRoomBlocks.put(newPos, entry.getValue());

                CompoundTag nbt = room.getBlockEntityNbt(originalPos);
                if (nbt != null) {
                    CompoundTag nbtCopy = nbt.copy();
                    nbtCopy.putInt("x", newPos.getX());
                    nbtCopy.putInt("y", newPos.getY());
                    nbtCopy.putInt("z", newPos.getZ());
                    newRoomNbt.put(newPos, nbtCopy);
                }
            }

            room.getBlockChanges().clear();
            room.getBlockChanges().putAll(newRoomBlocks);
            room.getBlockEntityNbtMap().clear();
            room.getBlockEntityNbtMap().putAll(newRoomNbt);
        }
    }

    /**
     * Updates construction coordinates after placement with rotation.
     * Rebuilds the block map with rotated positions and states.
     *
     * @param construction The construction to update
     * @param newBlocks Map of new world positions to rotated block states
     * @param originalPosMap Map from new position to original position (for NBT lookup)
     * @param offsetY Y offset applied during placement
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
        // newBlocks contains world coordinates after rotation.
        // All coordinates (blocks, entities, anchor) will be updated to world coordinates.
        // The key insight: entities and anchor must be rotated around the SAME pivot
        // used for blocks (which is the entrance anchor position in the original coordinate system).

        // Step 1: Build new block entity NBT map with world coordinates
        Map<BlockPos, CompoundTag> newBlockEntityNbt = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : newBlocks.entrySet()) {
            BlockPos newPos = entry.getKey();
            BlockPos originalPos = originalPosMap.get(newPos);
            if (originalPos != null) {
                CompoundTag nbt = construction.getBlockEntityNbt(originalPos);
                if (nbt != null) {
                    CompoundTag nbtCopy = nbt.copy();
                    nbtCopy.putInt("x", newPos.getX());
                    nbtCopy.putInt("y", newPos.getY());
                    nbtCopy.putInt("z", newPos.getZ());
                    newBlockEntityNbt.put(newPos, nbtCopy);
                }
            }
        }

        // Step 2: Update blocks with world positions
        construction.getBlocks().clear();
        construction.getBounds().reset();
        for (Map.Entry<BlockPos, BlockState> entry : newBlocks.entrySet()) {
            construction.addBlock(entry.getKey(), entry.getValue());
        }

        // Update block entity NBT
        construction.getBlockEntityNbtMap().clear();
        construction.getBlockEntityNbtMap().putAll(newBlockEntityNbt);

        // Step 2.5: Update room block positions after rotation.
        // Room blocks are stored with absolute world coordinates (applied from bounds during deserialization).
        // We need to transform them the same way as base blocks.
        Rotation rotation = stepsToRotation(rotationSteps);
        if (rotationSteps != 0 && !construction.getRooms().isEmpty()) {
            Architect.LOGGER.info("Rotating {} rooms by {} steps", construction.getRooms().size(), rotationSteps);

            for (Room room : construction.getRooms().values()) {
                if (room.getChangedBlockCount() == 0) continue;

                // Collect current room blocks (with old world coordinates)
                Map<BlockPos, BlockState> oldRoomBlocks = new HashMap<>(room.getBlockChanges());
                Map<BlockPos, CompoundTag> oldRoomNbt = new HashMap<>(room.getBlockEntityNbtMap());

                // Clear and rebuild with new coordinates
                room.clearBlockChanges();

                for (Map.Entry<BlockPos, BlockState> entry : oldRoomBlocks.entrySet()) {
                    BlockPos oldPos = entry.getKey();
                    BlockState state = entry.getValue();

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

                    // Rotate the block state
                    BlockState rotatedState = state.rotate(rotation);

                    // Get NBT if present
                    CompoundTag nbt = oldRoomNbt.get(oldPos);
                    if (nbt != null) {
                        CompoundTag nbtCopy = nbt.copy();
                        nbtCopy.putInt("x", newPos.getX());
                        nbtCopy.putInt("y", newPos.getY());
                        nbtCopy.putInt("z", newPos.getZ());
                        room.setBlockChange(newPos, rotatedState, nbtCopy);
                    } else {
                        room.setBlockChange(newPos, rotatedState);
                    }
                }

                Architect.LOGGER.debug("Room '{}': rotated {} blocks", room.getId(), room.getChangedBlockCount());
            }
        }

        // Step 2.6: Update room entity positions after rotation.
        // Room entities use the same relative position system as base entities.
        // We need to rotate them around the same pivot and apply the same normalization offset.
        if (rotationSteps != 0 && !construction.getRooms().isEmpty()) {
            // Pivot in normalized coordinates (same as base entities)
            double pivotCenterX = pivotX + 0.5;
            double pivotCenterZ = pivotZ + 0.5;

            // Calculate the normalization offset (same as base entities)
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

            for (Room room : construction.getRooms().values()) {
                if (room.getEntityCount() == 0) continue;

                List<EntityData> oldEntities = new ArrayList<>(room.getEntities());
                room.clearEntities();

                for (EntityData entity : oldEntities) {
                    // Entity relativePos is in normalized coords (same as base entities)
                    double relX = entity.getRelativePos().x;
                    double relY = entity.getRelativePos().y;
                    double relZ = entity.getRelativePos().z;

                    // Rotate around pivot (in normalized coordinate space)
                    double dx = relX - pivotCenterX;
                    double dz = relZ - pivotCenterZ;

                    double newDx = dx;
                    double newDz = dz;
                    for (int i = 0; i < rotationSteps; i++) {
                        double temp = newDx;
                        newDx = -newDz;  // 90° counter-clockwise
                        newDz = temp;
                    }

                    double rotatedX = pivotCenterX + newDx;
                    double rotatedZ = pivotCenterZ + newDz;

                    // Apply normalization offset so relPos is relative to new normalized (0,0,0)
                    double newRelX = rotatedX + normOffsetX;
                    double newRelZ = rotatedZ + normOffsetZ;

                    Vec3 newRelativePos = new Vec3(newRelX, relY, newRelZ);

                    // Rotate yaw
                    float newYaw = entity.getYaw() + (rotationSteps * 90f);

                    // Rotate entity NBT data
                    CompoundTag newNbt = entity.getNbt().copy();
                    rotateEntityNbtNormalized(newNbt, rotationSteps, pivotX, pivotZ, normOffsetX, normOffsetZ);

                    room.addEntity(new EntityData(
                        entity.getEntityType(),
                        newRelativePos,
                        newYaw,
                        entity.getPitch(),
                        newNbt
                    ));
                }

                Architect.LOGGER.debug("Room '{}': rotated {} entities", room.getId(), room.getEntityCount());
            }
        }

        // Step 3: Update entity positions after rotation.
        // Entity positions are stored RELATIVE to bounds.min (which is now in world coords).
        // worldEntityPos = bounds.min + entityRelPos
        // After rotation, we need to compute new relative positions based on the NEW bounds.
        //
        // The key insight: the blocks in the construction are now world coordinates.
        // The new bounds.min is the minimum of those world coordinates.
        // Entity positions should be stored as (entityWorldPos - newBounds.min).
        if (rotationSteps != 0 && !construction.getEntities().isEmpty()) {
            // Get the OLD bounds (before we updated them above)
            // We need to recalculate since bounds were already updated
            // The old bounds can be derived: old normalized entity pos + old bounds.min = old world pos
            // But old bounds.min was the previous construction's world position.
            //
            // Actually, we need to think about this differently.
            // Before this function:
            // - Blocks are at world coords: oldBoundsMin + normalizedBlockPos
            // - Entities have relPos relative to oldBoundsMin
            //
            // After placement:
            // - newBlocks contains new world coordinates
            // - newBounds.min is the min of newBlocks (new world position)
            //
            // The entity's OLD world position was: oldBoundsMin + oldRelPos
            // We need to rotate this around the WORLD pivot and compute new relPos.
            //
            // World pivot = oldBoundsMin + normalizedPivot (pivotX, pivotZ)
            // But we don't have oldBoundsMin here anymore...
            //
            // Different approach: entity relPos is in normalized coords (relative to construction's 0,0,0).
            // The normalized pivot is (pivotX, pivotZ).
            // Rotate entity around this pivot in normalized space.
            // Then the new world position = newBoundsMin + newRelPos (where newRelPos is from the rotated normalized space).
            //
            // But wait - newBoundsMin might not align with the rotated normalized (0,0,0)!
            // After rotation, the normalized (0,0,0) moves to a different position.
            //
            // Let's think step by step:
            // 1. Original normalized block at (nx, ny, nz) -> world pos = targetPos + rotatedNormPos
            // 2. The new construction.bounds are set from these world positions
            // 3. newBounds.min = min(worldPos for all blocks)
            //
            // For blocks: worldPos = targetPos + rotatedNormPos
            // newBounds.min = targetPos + min(rotatedNormPos)
            //
            // For entities: entityWorldPos = targetPos + rotatedNormEntityPos
            // newRelPos = entityWorldPos - newBounds.min = rotatedNormEntityPos - min(rotatedNormPos)
            //
            // This is exactly: newRelPos = rotatedNormEntityPos + normOffset
            // where normOffset = -min(rotatedNormPos)
            //
            // So the original code was correct! The issue is elsewhere.

            // Pivot in normalized coordinates
            double pivotCenterX = pivotX + 0.5;
            double pivotCenterZ = pivotZ + 0.5;

            // Calculate the normalization offset
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

            List<EntityData> rotatedEntities = new ArrayList<>();
            for (EntityData entity : construction.getEntities()) {
                // Entity relativePos is in normalized coords
                double relX = entity.getRelativePos().x;
                double relY = entity.getRelativePos().y;
                double relZ = entity.getRelativePos().z;

                // Rotate around pivot (in normalized coordinate space)
                double dx = relX - pivotCenterX;
                double dz = relZ - pivotCenterZ;

                double newDx = dx;
                double newDz = dz;
                for (int i = 0; i < rotationSteps; i++) {
                    double temp = newDx;
                    newDx = -newDz;  // 90° counter-clockwise
                    newDz = temp;
                }

                double rotatedX = pivotCenterX + newDx;
                double rotatedZ = pivotCenterZ + newDz;

                // Apply normalization offset so relPos is relative to new normalized (0,0,0)
                // which corresponds to newBounds.min in world space
                double newRelX = rotatedX + normOffsetX;
                double newRelZ = rotatedZ + normOffsetZ;

                Vec3 newRelativePos = new Vec3(newRelX, relY, newRelZ);

                // Rotate yaw
                float newYaw = entity.getYaw() + (rotationSteps * 90f);

                // Rotate entity NBT data
                CompoundTag newNbt = entity.getNbt().copy();
                rotateEntityNbtNormalized(newNbt, rotationSteps, pivotX, pivotZ, normOffsetX, normOffsetZ);

                rotatedEntities.add(new EntityData(
                    entity.getEntityType(),
                    newRelativePos,
                    newYaw,
                    entity.getPitch(),
                    newNbt
                ));
            }
            construction.clearEntities();
            for (EntityData entity : rotatedEntities) {
                construction.addEntity(entity);
            }
        }

        // Step 4: Update anchor position AND yaw after rotation.
        // Anchor is stored in normalized coordinates (relative to bounds.min).
        if (rotationSteps != 0 && construction.getAnchors().hasEntrance()) {
            BlockPos oldEntrancePos = construction.getAnchors().getEntrance();
            float oldYaw = construction.getAnchors().getEntranceYaw();

            // Calculate normalization offset (same as for entities)
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

        // Rotate Facing for hanging entities
        if (nbt.contains("Facing")) {
            int facing = nbt.getByteOr("Facing", (byte) 0);
            if (facing >= 2 && facing <= 5) {
                int newFacing = rotateFacing(facing, rotationSteps);
                nbt.putByte("Facing", (byte) newFacing);
            }
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
     * Rotates entity NBT data: block_pos, sleeping_pos, Facing, and Rotation tags.
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

        // Rotate Facing for hanging entities (item frames, paintings)
        if (nbt.contains("Facing")) {
            int facing = nbt.getByteOr("Facing", (byte) 0);
            if (facing >= 2 && facing <= 5) {
                int newFacing = rotateFacing(facing, rotationSteps);
                nbt.putByte("Facing", (byte) newFacing);
            }
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
    private static int spawnEntitiesFrozenRotated(
        List<EntityData> entities,
        ServerLevel level,
        int targetX, int targetY, int targetZ,
        int rotationSteps,
        int pivotX, int pivotZ,
        Construction construction
    ) {
        if (entities.isEmpty()) {
            return 0;
        }

        List<Entity> spawnedEntities = new ArrayList<>();
        int spawnedCount = 0;

        // For entities, pivot should be at center of the pivot block (add 0.5)
        // This ensures entities rotate around the same center as blocks
        double pivotCenterX = pivotX + 0.5;
        double pivotCenterZ = pivotZ + 0.5;

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

                // Update block_pos for hanging entities (item frames, paintings)
                // Read relative block_pos from NBT, rotate around pivot, add target offset
                // This must be done BEFORE entity creation since Minecraft validates block_pos
                updateHangingEntityCoordsRotated(nbt, targetX, targetY, targetZ, rotationSteps, pivotX, pivotZ);

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
                    } else {
                        entity.setNoGravity(true);
                        entity.setPos(worldX, worldY, worldZ);
                        entity.setXRot(data.getPitch());

                        if (entity instanceof Mob mob) {
                            mob.setNoAi(true);
                        }
                    }

                    level.addFreshEntity(entity);
                    spawnedEntities.add(entity);
                    spawnedCount++;

                    // Track entity UUID in construction for later removal
                    if (construction != null) {
                        construction.trackSpawnedEntity(entity.getUUID());
                    }
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
        float yaw
    ) {
        return architectSpawn(player, construction, yaw, null);
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
     * @param yaw The rotation angle for the construction
     * @param forcedRoomIds Optional list of room IDs to force spawn (100% probability).
     *                      Rooms still won't spawn if they overlap with each other.
     * @return ArchitectSpawnResult with counts and origin position
     */
    public static ArchitectSpawnResult architectSpawn(
        ServerPlayer player,
        Construction construction,
        float yaw,
        @Nullable List<String> forcedRoomIds
    ) {
        return architectSpawn(
            (ServerLevel) player.level(),
            construction,
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
        float yaw,
        @Nullable List<String> forcedRoomIds,
        @Nullable BlockPos spawnPoint,
        long roomSeed,
        @Nullable ServerPlayer player
    ) {
        if (construction.getBlockCount() == 0) {
            return new ArchitectSpawnResult(0, 0, 0, BlockPos.ZERO);
        }

        ConstructionBounds bounds = construction.getBounds();

        // Step 1: Extract eligible rooms (all rooms in ArchitectSpawn mode)
        List<Room> eligibleRooms = extractEligibleRooms(construction);

        // Step 2: Select rooms to spawn (random, non-overlapping, with forced rooms support)
        List<Room> roomsToSpawn = selectRoomsToSpawnWithSeed(eligibleRooms, roomSeed, construction, forcedRoomIds);

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
            level, construction, targetPos, rotationSteps, pivotX, pivotZ
        );

        // Step 5: Place selected room blocks (overwrite base where applicable)
        for (Room room : roomsToSpawn) {
            blocksPlaced += placeRoomBlocksRotated(
                level, construction, room, targetPos, rotationSteps, pivotX, pivotZ
            );
        }

        // Step 6: Spawn all entities (base + selected rooms) - frozen
        List<Entity> spawnedEntities = spawnEntitiesForArchitectSpawn(
            level, construction, roomsToSpawn, targetPos, rotationSteps, pivotX, pivotZ
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
     * @param forcedRoomIds Optional list of room IDs to force spawn (100% probability)
     * @return List of non-overlapping rooms to spawn
     */
    private static List<Room> selectRoomsToSpawnWithSeed(
        List<Room> eligibleRooms,
        long seed,
        Construction construction,
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
                    if (!hasOverlappingBounds(room, roomsToSpawn, construction)) {
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
                    if (!hasOverlappingBounds(room, roomsToSpawn, construction)) {
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
        Construction construction
    ) {
        if (existingRooms.isEmpty()) {
            return false;
        }

        // Calculate candidate bounds from its block positions
        AABB candidateBounds = calculateRoomBounds(candidate, construction);
        if (candidateBounds == null) {
            return false; // Room has no blocks
        }

        for (Room existing : existingRooms) {
            AABB existingBounds = calculateRoomBounds(existing, construction);
            if (existingBounds != null && candidateBounds.intersects(existingBounds)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Calculates the AABB bounds of a room based on its block changes.
     * Returns null if the room has no blocks.
     */
    @Nullable
    private static AABB calculateRoomBounds(Room room, Construction construction) {
        if (room.getBlockChanges().isEmpty() && room.getEntities().isEmpty()) {
            return null;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        // Process block positions
        for (BlockPos pos : room.getBlockChanges().keySet()) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        // Include room entities in bounds calculation
        ConstructionBounds constrBounds = construction.getBounds();
        for (EntityData entity : room.getEntities()) {
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
        List<Map.Entry<BlockPos, BlockState>> sortedBlocks = new ArrayList<>(construction.getBlocks().entrySet());
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

            CompoundTag blockNbt = construction.getBlockEntityNbt(originalPos);
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
        Room room,
        BlockPos targetPos,
        int rotationSteps,
        int pivotX,
        int pivotZ
    ) {
        if (room.getBlockChanges().isEmpty()) {
            return 0;
        }

        ConstructionBounds bounds = construction.getBounds();
        int originalMinX = bounds.getMinX();
        int originalMinY = bounds.getMinY();
        int originalMinZ = bounds.getMinZ();

        Rotation rotation = stepsToRotation(rotationSteps);

        // Sort blocks by Y ascending
        List<Map.Entry<BlockPos, BlockState>> sortedBlocks = new ArrayList<>(room.getBlockChanges().entrySet());
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

            CompoundTag blockNbt = room.getBlockEntityNbt(originalPos);
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

        Architect.LOGGER.debug("Room '{}': placed {} rotated blocks", room.getId(), placedCount);
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
        List<Room> roomsToSpawn,
        BlockPos targetPos,
        int rotationSteps,
        int pivotX,
        int pivotZ
    ) {
        List<Entity> spawnedEntities = new ArrayList<>();

        // Collect all room entity positions (to filter out overlapping base entities)
        Set<String> roomEntityPositions = collectRoomEntityPositions(roomsToSpawn);

        // Spawn base entities (excluding those at room entity positions)
        List<EntityData> filteredBaseEntities = filterBaseEntities(
            construction.getEntities(), roomEntityPositions
        );
        spawnedEntities.addAll(spawnEntitiesListForArchitectSpawn(
            level, filteredBaseEntities, targetPos,
            rotationSteps, pivotX, pivotZ
        ));

        // Spawn room entities (only from selected rooms)
        for (Room room : roomsToSpawn) {
            spawnedEntities.addAll(spawnEntitiesListForArchitectSpawn(
                level, room.getEntities(), targetPos,
                rotationSteps, pivotX, pivotZ
            ));
        }

        return spawnedEntities;
    }

    /**
     * Collects all entity positions from rooms that will be spawned.
     * Positions are stored as strings "x,y,z" (rounded to block coordinates).
     */
    private static Set<String> collectRoomEntityPositions(List<Room> roomsToSpawn) {
        Set<String> positions = new HashSet<>();
        for (Room room : roomsToSpawn) {
            for (EntityData entity : room.getEntities()) {
                Vec3 pos = entity.getRelativePos();
                // Round to block position for comparison
                String posKey = String.format("%d,%d,%d",
                    (int) Math.floor(pos.x),
                    (int) Math.floor(pos.y),
                    (int) Math.floor(pos.z));
                positions.add(posKey);
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
     * Entities are spawned with NoAI enabled for mobs.
     */
    private static List<Entity> spawnEntitiesListForArchitectSpawn(
        ServerLevel level,
        List<EntityData> entities,
        BlockPos targetPos,
        int rotationSteps,
        int pivotX,
        int pivotZ
    ) {
        List<Entity> spawnedEntities = new ArrayList<>();
        if (entities.isEmpty()) {
            return spawnedEntities;
        }

        // Pivot for entity rotation (center of block)
        double pivotCenterX = pivotX + 0.5;
        double pivotCenterZ = pivotZ + 0.5;

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
                double worldX = targetPos.getX() + rotatedRelX;
                double worldY = targetPos.getY() + relY;
                double worldZ = targetPos.getZ() + rotatedRelZ;

                // Copy NBT - only remove UUID (will be regenerated)
                CompoundTag nbt = data.getNbt().copy();
                nbt.remove("UUID");
                // Remove spawn-related properties from saved NBT - these will be restored
                // to default values in unfreezeSpawnedEntities using a fresh entity.
                // This ensures mobs can move (NoAI), gravity works correctly (NoGravity),
                // and entities behave normally after spawn.
                // All other properties (color, type, inventory, etc.) are preserved.
                nbt.remove("NoGravity");
                nbt.remove("NoAI");

                // Set Pos in NBT BEFORE entity creation
                net.minecraft.nbt.ListTag posTag = new net.minecraft.nbt.ListTag();
                posTag.add(net.minecraft.nbt.DoubleTag.valueOf(worldX));
                posTag.add(net.minecraft.nbt.DoubleTag.valueOf(worldY));
                posTag.add(net.minecraft.nbt.DoubleTag.valueOf(worldZ));
                nbt.put("Pos", posTag);

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
                updateHangingEntityCoordsRotated(nbt, targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                    rotationSteps, pivotX, pivotZ);

                // Update sleeping_pos for villagers
                updateSleepingPosRotated(nbt, targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                    rotationSteps, pivotX, pivotZ);

                // Rotate Facing for hanging entities
                if (nbt.contains("Facing") && rotationSteps != 0) {
                    int facing = nbt.getByteOr("Facing", (byte) 0);
                    if (facing >= 2 && facing <= 5) {
                        int newFacing = rotateFacing(facing, rotationSteps);
                        nbt.putByte("Facing", (byte) newFacing);
                    }
                }

                // Rotate yaw in NBT for non-hanging entities
                boolean isHangingEntity = entityType.equals("minecraft:item_frame") ||
                    entityType.equals("minecraft:glow_item_frame") ||
                    entityType.equals("minecraft:painting");
                if (!isHangingEntity && rotationSteps != 0 && nbt.contains("Rotation")) {
                    net.minecraft.nbt.Tag rotationTag = nbt.get("Rotation");
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

                // Create entity from NBT
                Entity entity = EntityType.loadEntityRecursive(nbt, level, EntitySpawnReason.LOAD, e -> e);

                if (entity != null) {
                    // Temporarily disable gravity during spawn (will be restored in unfreeze)
                    entity.setNoGravity(true);

                    // Mark entity as spawned by struttura (for cleanup and unfreeze)
                    entity.getTags().add("struttura_spawned");

                    // Set position
                    entity.setPos(worldX, worldY, worldZ);
                    entity.setXRot(data.getPitch());
                    entity.setUUID(UUID.randomUUID());

                    // Disable AI for mobs (will be re-enabled in unfreezeSpawnedEntities)
                    if (entity instanceof Mob mob) {
                        mob.setNoAi(true);
                    }

                    level.addFreshEntity(entity);
                    spawnedEntities.add(entity);
                }
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to spawn entity of type {}: {}",
                    data.getEntityType(), e.getMessage());
            }
        }

        return spawnedEntities;
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
