package it.magius.struttura.architect.dev;

import it.magius.struttura.architect.Architect;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handler for automated testing when running with -Dstruttura.devtest=true.
 *
 * This class provides a centralized place to add test commands that will be
 * executed automatically when a player joins the "Struttura Develop" world.
 *
 * Usage:
 * - Run: npm run architect:test
 * - The world "Struttura Develop" will auto-load
 * - When the player spawns, onPlayerJoinWorld() will be called
 * - Add your test commands in the runTestCommands() method
 *
 * Available actions:
 * - Execute commands: executeCommand(player, "command args")
 * - Teleport: player.teleportTo(x, y, z)
 * - Send messages: player.sendSystemMessage(Component.literal("text"))
 * - Stop server: server.halt(false);
 */
public class DevTestHandler {

    private static final DevTestHandler INSTANCE = new DevTestHandler();
    private static final boolean DEV_TEST_ENABLED = "true".equalsIgnoreCase(System.getProperty("struttura.devtest"));
    private static boolean tickHandlerRegistered = false;

    private DevTestHandler() {}

    public static DevTestHandler getInstance() {
        return INSTANCE;
    }

    public static boolean isEnabled() {
        return DEV_TEST_ENABLED;
    }

    /**
     * Called when a player joins the world in devtest mode.
     * This is the entry point for automated testing.
     */
    public void onPlayerJoinWorld(ServerPlayer player) {
        if (!DEV_TEST_ENABLED) {
            return;
        }

        Architect.LOGGER.info("=== STRUTTURA DevTest: Player {} joined world ===", player.getName().getString());

        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        if (server == null) {
            Architect.LOGGER.error("DevTest: Server is null, cannot run tests");
            return;
        }

        // Register tick handler if not already registered
        if (!tickHandlerRegistered) {
            ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
            tickHandlerRegistered = true;
            Architect.LOGGER.info("DevTest: Registered tick handler");
        }

        // Delay execution by 1 second (20 ticks) to ensure world is fully loaded
        final int targetTick = server.getTickCount() + 20;
        scheduleDelayedExecution(server, targetTick, () -> runTestCommands(player));
    }

    // Pending delayed tasks
    private static final java.util.Map<Integer, Runnable> pendingTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private static int nextTaskId = 0;

    /**
     * Schedules a task to run after a certain tick count.
     * Uses Fabric's tick events to avoid stack overflow from recursive execute() calls.
     */
    private void scheduleDelayedExecution(MinecraftServer server, int targetTick, Runnable task) {
        int taskId = nextTaskId++;
        pendingTasks.put(taskId, () -> {
            if (server.getTickCount() >= targetTick) {
                pendingTasks.remove(taskId);
                task.run();
            }
        });
    }

    /**
     * Called every server tick to check pending tasks.
     * Register this with ServerTickEvents.END_SERVER_TICK.
     */
    public void onServerTick(MinecraftServer server) {
        // Copy to avoid ConcurrentModificationException
        for (Runnable task : new java.util.ArrayList<>(pendingTasks.values())) {
            task.run();
        }
    }

    /**
     * ========================================================================
     * AUTOMATED TEST FUNCTION - Edit this method to add your test commands!
     * ========================================================================
     */
    private void runTestCommands(ServerPlayer player) {
        Architect.LOGGER.info("=== STRUTTURA DevTest: Running test commands ===");

        player.sendSystemMessage(Component.literal("[DevTest] Starting rotation test..."));

        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        String constructionId = "it.magius.pip.home";

        // Step 1: Delete construction if exists
        Architect.LOGGER.info("=== STEP 1: Deleting construction ===");
        player.sendSystemMessage(Component.literal("[DevTest] Step 1: Deleting construction..."));
        executeCommand(player, "struttura destroy " + constructionId);

        // Wait 1 second (20 ticks), then Step 2
        scheduleDelayedExecution(server, server.getTickCount() + 20, () -> {
            // Step 2: Face NORTH (yaw=180) and pull - NO rotation
            Architect.LOGGER.info("=== STEP 2: Facing NORTH and pulling ===");
            player.sendSystemMessage(Component.literal("[DevTest] Step 2: Facing NORTH (yaw=180), pulling..."));

            executeCommand(player, "tp @s ~ ~ ~ 180 0");
            executeCommand(player, "struttura pull " + constructionId);

            // Wait 2 seconds for pull, then Step 3
            scheduleDelayedExecution(server, server.getTickCount() + 40, () -> {
                // Step 3: Face EAST (yaw=-90) and move - 90° rotation
                Architect.LOGGER.info("=== STEP 3: Facing EAST and moving (90° rotation) ===");
                player.sendSystemMessage(Component.literal("[DevTest] Step 3: Facing EAST (yaw=-90), moving..."));

                executeCommand(player, "tp @s ~ ~ ~ -90 0");
                executeCommand(player, "struttura move " + constructionId);

                // Wait 2 seconds for move, then Step 4
                scheduleDelayedExecution(server, server.getTickCount() + 40, () -> {
                    // Step 4: Face WEST (yaw=90) and move - 180° rotation from EAST
                    Architect.LOGGER.info("=== STEP 4: Facing WEST and moving (180° rotation from EAST) ===");
                    player.sendSystemMessage(Component.literal("[DevTest] Step 4: Facing WEST (yaw=90), moving..."));

                    executeCommand(player, "tp @s ~ ~ ~ 90 0");
                    executeCommand(player, "struttura move " + constructionId);

                    // Wait 2 seconds for move, then exit
                    scheduleDelayedExecution(server, server.getTickCount() + 40, () -> {
                        Architect.LOGGER.info("=== STEP 5: Test completed ===");
                        player.sendSystemMessage(Component.literal("[DevTest] Rotation test completed! Exiting..."));

                        // Stop server
                        server.halt(false);

                        new Thread(() -> {
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException e) {
                                // Ignore
                            }
                            Architect.LOGGER.info("=== STRUTTURA DevTest: Exiting JVM ===");
                            System.exit(0);
                        }).start();
                    });
                });
            });
        });
    }

    /**
     * Helper method to execute a command as the player.
     */
    private void executeCommand(ServerPlayer player, String command) {
        Architect.LOGGER.info("DevTest executing: /{}", command);
        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        server.getCommands().performPrefixedCommand(
            player.createCommandSourceStack(),
            command
        );
    }
}
