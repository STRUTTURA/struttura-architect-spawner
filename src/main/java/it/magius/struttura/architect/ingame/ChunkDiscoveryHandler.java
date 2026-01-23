package it.magius.struttura.architect.ingame;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.ingame.spawn.SpawnQueue;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Handles chunk load events to trigger building spawn evaluation.
 * Only processes chunks that haven't been seen before and are far enough from players.
 */
public class ChunkDiscoveryHandler {

    private static final int PLAYER_BUFFER_CHUNKS = 5;  // Don't spawn within 5 chunks of any player

    private static ChunkDiscoveryHandler instance;

    private ChunkDiscoveryHandler() {}

    public static ChunkDiscoveryHandler getInstance() {
        if (instance == null) {
            instance = new ChunkDiscoveryHandler();
        }
        return instance;
    }

    /**
     * Registers the chunk discovery handler.
     * Call this during mod initialization.
     */
    public void register() {
        ServerChunkEvents.CHUNK_LOAD.register(this::onChunkLoad);
        Architect.LOGGER.info("Chunk discovery handler registered");
    }

    /**
     * Called when a chunk is loaded.
     */
    private void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        try {
            onChunkLoadInternal(level, chunk);
        } catch (Exception e) {
            Architect.LOGGER.error("Error in onChunkLoad for chunk [{}, {}]",
                chunk.getPos().x, chunk.getPos().z, e);
        }
    }

    private void onChunkLoadInternal(ServerLevel level, LevelChunk chunk) {
        // Only process overworld for now (can be extended to other dimensions)
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }

        // Check if chunk was already processed
        if (ChunkDataManager.isChunkProcessed(chunk)) {
            return;
        }

        // Check if InGame mode is active
        InGameManager manager = InGameManager.getInstance();
        if (!manager.isActive()) {
            // Mark as processed even when InGame is not active
            // This prevents spawning when the mode is activated later
            ChunkDataManager.markChunkProcessed(chunk);
            return;
        }

        // Check player proximity - don't spawn if players are nearby
        if (isPlayerNearby(level, chunk.getPos(), PLAYER_BUFFER_CHUNKS)) {
            // Mark as processed but don't spawn (player was here first)
            ChunkDataManager.markChunkProcessed(chunk);
            return;
        }

        // Mark chunk as processed BEFORE evaluation to prevent race conditions
        ChunkDataManager.markChunkProcessed(chunk);

        // Check if spawner is ready (all buildings downloaded)
        // If still downloading, chunk is marked as processed but no spawn evaluation
        if (!manager.isSpawnerReady()) {
            Architect.LOGGER.debug("Chunk [{}, {}]: marked as explored (spawner not ready yet)",
                chunk.getPos().x, chunk.getPos().z);
            return;
        }

        // Add chunk to spawn queue for gradual processing
        // This avoids blocking chunk loading when many chunks are loaded at once
        SpawnQueue.getInstance().enqueue(level, chunk);
    }

    /**
     * Checks if any player is within the specified chunk radius.
     * @param level the server level
     * @param chunkPos the chunk position to check
     * @param radiusChunks the radius in chunks
     * @return true if a player is nearby
     */
    private boolean isPlayerNearby(ServerLevel level, ChunkPos chunkPos, int radiusChunks) {
        int chunkBlockRadius = radiusChunks * 16;
        int centerX = chunkPos.getMiddleBlockX();
        int centerZ = chunkPos.getMiddleBlockZ();

        for (ServerPlayer player : level.players()) {
            double dx = Math.abs(player.getX() - centerX);
            double dz = Math.abs(player.getZ() - centerZ);

            // Use Chebyshev distance (max of dx, dz) for chunk-based radius
            if (Math.max(dx, dz) <= chunkBlockRadius) {
                return true;
            }
        }

        return false;
    }
}
