package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.ingame.InGameManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe queue for processing chunk spawn evaluations.
 * Chunks are added to the queue from CHUNK_LOAD events and processed
 * gradually during server ticks to avoid blocking chunk loading.
 */
public class SpawnQueue {

    private static final int MAX_CHUNKS_PER_TICK = 3;  // Process max 3 chunks per tick
    private static final long CLEAR_DELAY_TICKS = 5 * 20;  // 5 seconds in ticks
    private static final int SPAWN_DELAY_TICKS = 0;  // No delay needed - process immediately

    private static SpawnQueue instance;

    // Thread-safe queue of chunks waiting for spawn evaluation
    private final Queue<ChunkEntry> pendingChunks = new ConcurrentLinkedQueue<>();

    // Tick at which to clear OccupiedChunks (0 = no clear scheduled)
    private final AtomicLong scheduledClearTick = new AtomicLong(0);

    private SpawnQueue() {}

    public static SpawnQueue getInstance() {
        if (instance == null) {
            instance = new SpawnQueue();
        }
        return instance;
    }

    /**
     * Registers the spawn queue tick handler.
     * Call this during mod initialization.
     */
    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    /**
     * Adds a chunk to the spawn evaluation queue.
     * Thread-safe, can be called from any thread.
     * The chunk will be processed after SPAWN_DELAY_TICKS to ensure world generation is complete.
     *
     * @param level the server level
     * @param chunk the chunk to evaluate
     */
    public void enqueue(ServerLevel level, LevelChunk chunk) {
        long processAfterTick = level.getServer().getTickCount() + SPAWN_DELAY_TICKS;
        pendingChunks.add(new ChunkEntry(level, chunk.getPos().x, chunk.getPos().z, processAfterTick));
    }

    /**
     * Processes pending chunks during server tick.
     */
    private void onServerTick(MinecraftServer server) {
        // Check if spawner is ready
        InGameManager manager = InGameManager.getInstance();
        if (!manager.isSpawnerReady()) {
            return;
        }

        // Process up to MAX_CHUNKS_PER_TICK chunks
        int processed = 0;
        long currentTick = server.getTickCount();
        while (processed < MAX_CHUNKS_PER_TICK && !pendingChunks.isEmpty()) {
            ChunkEntry entry = pendingChunks.peek();
            if (entry == null) {
                break;
            }

            // Check if chunk is ready to be processed (delay elapsed)
            if (currentTick < entry.processAfterTick) {
                // Not ready yet, stop processing (queue is ordered by enqueue time)
                break;
            }

            // Remove from queue now that we're processing it
            pendingChunks.poll();

            // Get the level and chunk
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level == null) {
                continue;
            }

            // Check if chunk is still loaded
            LevelChunk chunk = level.getChunkSource().getChunkNow(entry.chunkX, entry.chunkZ);
            if (chunk == null) {
                // Chunk was unloaded, skip
                continue;
            }

            // Evaluate spawn
            try {
                SpawnEvaluator.evaluate(level, chunk);
            } catch (Exception e) {
                Architect.LOGGER.error("Error evaluating spawn for chunk [{}, {}]",
                    entry.chunkX, entry.chunkZ, e);
            }

            processed++;
        }

        // If we processed any chunks, schedule a delayed clear of OccupiedChunks
        if (processed > 0) {
            // Reset the timer - clear will happen 5 seconds after the last chunk is processed
            scheduledClearTick.set(server.getTickCount() + CLEAR_DELAY_TICKS);
        }

        // Check if it's time to clear OccupiedChunks
        long clearTick = scheduledClearTick.get();
        if (clearTick > 0 && server.getTickCount() >= clearTick) {
            // Only clear if the queue is still empty
            if (pendingChunks.isEmpty()) {
                OccupiedChunks.clear();
            }
            // Reset the scheduled clear tick
            scheduledClearTick.set(0);
        }
    }

    /**
     * Gets the number of chunks waiting in the queue.
     */
    public int getQueueSize() {
        return pendingChunks.size();
    }

    /**
     * Clears the queue. Called when world unloads.
     */
    public void clear() {
        int size = pendingChunks.size();
        pendingChunks.clear();
        scheduledClearTick.set(0);
        if (size > 0) {
            Architect.LOGGER.debug("Cleared {} chunks from spawn queue", size);
        }
    }

    /**
     * Entry in the spawn queue containing chunk coordinates and the tick when it can be processed.
     * We store coordinates instead of LevelChunk reference to avoid holding
     * references to potentially unloaded chunks.
     */
    private record ChunkEntry(ServerLevel level, int chunkX, int chunkZ, long processAfterTick) {}
}
