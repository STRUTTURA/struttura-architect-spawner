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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Centralized operations for placing and removing constructions.
 * All operations prevent physics triggers, block drops, and entity deaths.
 */
public class ConstructionOperations {

    // Flags for silent block placement (no physics, no onPlace callbacks)
    // UPDATE_SKIP_ON_PLACE prevents rails from auto-orienting based on neighbors
    private static final int SILENT_PLACE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE;

    // Flags for silent block removal (no drops, no cascading updates)
    private static final int SILENT_REMOVE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

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
        if (construction.getBlockCount() == 0) {
            return new PlacementResult(0, 0, BlockPos.ZERO);
        }

        ServerLevel level = (ServerLevel) player.level();
        ConstructionBounds bounds = construction.getBounds();

        // Calculate target position based on mode
        BlockPos targetPos;
        if (mode == PlacementMode.SHOW) {
            // SHOW: use original position
            targetPos = bounds.getMin();
        } else {
            // SPAWN/PULL/MOVE: check if entrance anchor is set
            if (construction.getAnchors().hasEntrance()) {
                // Calculate position so player ends up at entrance
                targetPos = calculatePositionAtEntrance(player, construction);
            } else {
                // No entrance: calculate position in front of player (legacy behavior)
                targetPos = calculatePositionInFront(player, bounds);
            }
        }

        return placeConstructionAt(level, construction, targetPos, updateConstructionCoords);
    }

    /**
     * Places a construction at a specific target position without triggering physics.
     * The target position is where the construction's min corner will be placed.
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
        if (construction.getBlockCount() == 0) {
            return new PlacementResult(0, 0, BlockPos.ZERO);
        }

        ConstructionBounds bounds = construction.getBounds();
        int originalMinX = bounds.getMinX();
        int originalMinY = bounds.getMinY();
        int originalMinZ = bounds.getMinZ();

        int offsetX = targetPos.getX() - originalMinX;
        int offsetY = targetPos.getY() - originalMinY;
        int offsetZ = targetPos.getZ() - originalMinZ;

        // Phase 1: Place all blocks WITHOUT any updates
        Map<BlockPos, BlockState> newBlocks = new HashMap<>();
        int placedCount = 0;

        // Sort blocks by Y ascending (support blocks first)
        List<Map.Entry<BlockPos, BlockState>> sortedBlocks = new ArrayList<>(construction.getBlocks().entrySet());
        sortedBlocks.sort(Comparator.comparingInt(e -> e.getKey().getY()));

        for (Map.Entry<BlockPos, BlockState> entry : sortedBlocks) {
            BlockPos originalPos = entry.getKey();
            BlockState state = entry.getValue();
            BlockPos newPos = originalPos.offset(offsetX, offsetY, offsetZ);
            newBlocks.put(newPos, state);

            if (!state.isAir()) {
                level.setBlock(newPos, state, SILENT_PLACE_FLAGS);
                placedCount++;
            }
        }

        // Phase 2: Apply block entity NBT after ALL blocks are placed
        int blockEntityCount = 0;
        for (Map.Entry<BlockPos, BlockState> entry : sortedBlocks) {
            BlockPos originalPos = entry.getKey();
            BlockState state = entry.getValue();
            if (state.isAir()) continue;

            BlockPos newPos = originalPos.offset(offsetX, offsetY, offsetZ);
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
        if (updateConstructionCoords && (offsetX != 0 || offsetY != 0 || offsetZ != 0)) {
            updateConstructionCoordinates(construction, offsetX, offsetY, offsetZ, newBlocks);
        }

        // Phase 5: Spawn entities with freeze
        int originX = offsetX + originalMinX;
        int originY = offsetY + originalMinY;
        int originZ = offsetZ + originalMinZ;
        int entitiesSpawned = spawnEntitiesFrozen(construction.getEntities(), level, originX, originY, originZ);

        BlockPos newOrigin = new BlockPos(originX, originY, originZ);
        Architect.LOGGER.info("Placed {} blocks, {} entities at {}", placedCount, entitiesSpawned, newOrigin);

        return new PlacementResult(placedCount, entitiesSpawned, newOrigin);
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
        AABB area = new AABB(
            bounds.getMinX() - 1, bounds.getMinY() - 1, bounds.getMinZ() - 1,
            bounds.getMaxX() + 2, bounds.getMaxY() + 2, bounds.getMaxZ() + 2
        );

        // Remove all entities except players (HIDE, DESTROY, MOVE_CLEAR all need this)
        List<Entity> entitiesToRemove = level.getEntitiesOfClass(Entity.class, area,
            e -> !(e instanceof Player));

        for (Entity entity : entitiesToRemove) {
            entity.discard();
        }

        // Phase 3: Remove all blocks (no drops, no physics)
        int blocksRemoved = 0;
        for (int x = bounds.getMinX(); x <= bounds.getMaxX(); x++) {
            for (int y = bounds.getMinY(); y <= bounds.getMaxY(); y++) {
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
            mode, blocksRemoved, entitiesToRemove.size(), containersCleared);

        return new RemovalResult(blocksRemoved, entitiesToRemove.size());
    }

    // ============== HELPER METHODS ==============

    /**
     * Calculate the position so that the player ends up at the entrance anchor.
     * The entrance anchor is stored in normalized coordinates (relative to bounds min 0,0,0).
     * Entrance Y is stored as floor(player.getY()) + 1.
     * We need to place the construction so that: playerY = targetY + entranceY
     * Therefore: targetY = playerY - entranceY
     *
     * @param player The player
     * @param construction The construction with entrance anchor
     * @return The position where bounds min should be placed
     */
    public static BlockPos calculatePositionAtEntrance(ServerPlayer player, Construction construction) {
        BlockPos entrance = construction.getAnchors().getEntrance(); // normalized coords
        // Use round(player.getY()) - 1 for spawn position
        int playerX = player.blockPosition().getX();
        int playerY = (int) Math.round(player.getY()) - 1;
        int playerZ = player.blockPosition().getZ();

        // targetPos is where bounds min will be placed
        // We want: playerPos = targetPos + entrance
        // Therefore: targetPos = playerPos - entrance
        return new BlockPos(
            playerX - entrance.getX(),
            playerY - entrance.getY(),
            playerZ - entrance.getZ()
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
                nbt.remove("Pos");
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

                // Create entity from NBT
                Entity entity = EntityType.loadEntityRecursive(nbt, level, EntitySpawnReason.LOAD, e -> e);

                if (entity != null) {
                    // Disable gravity during spawn
                    entity.setNoGravity(true);

                    // Set position and rotation
                    entity.setPos(worldX, worldY, worldZ);
                    entity.setYRot(data.getYaw());
                    entity.setXRot(data.getPitch());
                    entity.setUUID(UUID.randomUUID());

                    // Disable AI for mobs (permanent freeze)
                    if (entity instanceof Mob mob) {
                        mob.setNoAi(true);
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
            if (!(entity instanceof Mob)) {
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
}
