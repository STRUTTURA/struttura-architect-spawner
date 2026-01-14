package it.magius.struttura.architect.dev.test;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Interface for automated development tests.
 *
 * Each test must:
 * - Have a unique ID (returned by getId())
 * - Clean up after itself (restore world state)
 * - Move the player to a safe position if needed
 * - Call the completion callback when done (with success/failure result)
 */
public interface DevTest {

    /**
     * Unique identifier for this test (e.g., "roomsAfterPull4Dir").
     * Used to select tests from command line.
     */
    String getId();

    /**
     * Human-readable description of what this test does.
     */
    String getDescription();

    /**
     * Run the test.
     *
     * @param player The player running the test
     * @param server The Minecraft server
     * @param onComplete Callback to invoke when test completes.
     *                   Pass true if test passed, false if failed.
     */
    void run(ServerPlayer player, MinecraftServer server, TestCompletionCallback onComplete);

    /**
     * Callback interface for test completion.
     */
    @FunctionalInterface
    interface TestCompletionCallback {
        void onComplete(boolean passed, String message);
    }
}
