package it.magius.struttura.architect.model;

import it.magius.struttura.architect.Architect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transient data carrier for push/save/move operations.
 * Created by reading from the world, consumed by serialization or placement.
 * Never stored long-term in Construction.
 */
public record ConstructionSnapshot(
    Map<BlockPos, BlockState> blocks,
    Map<BlockPos, CompoundTag> blockEntityNbt,
    List<EntityData> entities,
    Map<String, RoomSnapshot> rooms
) {

    /**
     * Snapshot of a single room's data.
     */
    public record RoomSnapshot(
        Map<BlockPos, BlockState> blocks,
        Map<BlockPos, CompoundTag> blockEntityNbt,
        List<EntityData> entities
    ) {}

    /**
     * Creates a snapshot by reading all tracked blocks/entities from the world.
     * This captures the current world state for serialization.
     */
    public static ConstructionSnapshot fromWorld(Construction construction, ServerLevel level) {
        var bounds = construction.getBounds();

        // Snapshot base blocks from world
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Map<BlockPos, CompoundTag> blockEntityNbt = new HashMap<>();

        for (BlockPos pos : construction.getTrackedBlocks()) {
            BlockState state = level.getBlockState(pos);
            blocks.put(pos, state);

            // Capture block entity NBT
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                CompoundTag nbt = blockEntity.saveWithoutMetadata(level.registryAccess());
                nbt.remove("x");
                nbt.remove("y");
                nbt.remove("z");
                nbt.remove("id");
                if (!nbt.isEmpty()) {
                    blockEntityNbt.put(pos, nbt);
                }
            }
        }

        // Snapshot base entities from world
        List<EntityData> entities = new ArrayList<>();
        if (bounds.isValid()) {
            for (UUID entityUuid : construction.getTrackedEntities()) {
                Entity worldEntity = level.getEntity(entityUuid);
                if (worldEntity != null && EntityData.shouldSaveEntity(worldEntity)) {
                    EntityData data = EntityData.fromEntity(worldEntity, bounds, level.registryAccess());
                    entities.add(data);
                }
            }
        }

        // Snapshot rooms
        Map<String, RoomSnapshot> rooms = new HashMap<>();
        for (var entry : construction.getRooms().entrySet()) {
            Room room = entry.getValue();
            RoomSnapshot roomSnapshot = snapshotRoom(room, level);
            rooms.put(entry.getKey(), roomSnapshot);
        }

        Architect.LOGGER.debug("Created snapshot: {} blocks, {} entities, {} rooms",
            blocks.size(), entities.size(), rooms.size());

        return new ConstructionSnapshot(blocks, blockEntityNbt, entities, rooms);
    }

    /**
     * Snapshots a single room by reading from the world.
     */
    private static RoomSnapshot snapshotRoom(Room room, ServerLevel level) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Map<BlockPos, CompoundTag> blockEntityNbt = new HashMap<>();

        for (BlockPos pos : room.getChangedBlocks()) {
            BlockState state = level.getBlockState(pos);
            blocks.put(pos, state);

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null && !state.isAir()) {
                CompoundTag nbt = blockEntity.saveWithoutMetadata(level.registryAccess());
                nbt.remove("x");
                nbt.remove("y");
                nbt.remove("z");
                nbt.remove("id");
                if (!nbt.isEmpty()) {
                    blockEntityNbt.put(pos, nbt);
                }
            }
        }

        // Room entities - these are stored as EntityData (serialized) for spawning later
        // Room entities are not in the world during normal operation (only during room editing)
        // So we return an empty list here - room entity data is loaded from disk when needed
        List<EntityData> entities = new ArrayList<>();

        return new RoomSnapshot(blocks, blockEntityNbt, entities);
    }

    /**
     * Creates a snapshot wrapping already-deserialized data (from pull/load).
     * Used when data comes from the cloud or from disk, not from the world.
     */
    public static ConstructionSnapshot fromDeserialized(
        Map<BlockPos, BlockState> blocks,
        Map<BlockPos, CompoundTag> blockEntityNbt,
        List<EntityData> entities,
        Map<String, RoomSnapshot> rooms
    ) {
        return new ConstructionSnapshot(
            blocks != null ? blocks : new HashMap<>(),
            blockEntityNbt != null ? blockEntityNbt : new HashMap<>(),
            entities != null ? entities : new ArrayList<>(),
            rooms != null ? rooms : new HashMap<>()
        );
    }

    /**
     * Gets the solid block count from the snapshot.
     */
    public int getSolidBlockCount() {
        return (int) blocks.values().stream().filter(s -> !s.isAir()).count();
    }

    /**
     * Gets block entity NBT for a specific position.
     */
    public CompoundTag getBlockEntityNbt(BlockPos pos) {
        return blockEntityNbt.get(pos);
    }

    /**
     * Gets the block counts map (blockId -> count) from the snapshot.
     */
    public Map<String, Integer> getBlockCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (BlockState state : blocks.values()) {
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            counts.merge(blockId, 1, Integer::sum);
        }
        return counts;
    }
}
