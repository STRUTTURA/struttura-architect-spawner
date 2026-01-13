package it.magius.struttura.architect.dev;

import it.magius.struttura.architect.Architect;
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

        // Delay execution by 1 second (20 ticks) to ensure world is fully loaded
        final int targetTick = server.getTickCount() + 20;
        server.execute(() -> {
            scheduleDelayedExecution(server, targetTick, () -> runTestCommands(player));
        });
    }

    /**
     * Schedules a task to run after a certain tick count.
     */
    private void scheduleDelayedExecution(MinecraftServer server, int targetTick, Runnable task) {
        if (server.getTickCount() >= targetTick) {
            task.run();
        } else {
            server.execute(() -> scheduleDelayedExecution(server, targetTick, task));
        }
    }

    /**
     * ========================================================================
     * AUTOMATED TEST FUNCTION - Edit this method to add your test commands!
     * ========================================================================
     */
    private void runTestCommands(ServerPlayer player) {
        Architect.LOGGER.info("=== STRUTTURA DevTest: Running test commands ===");

        player.sendSystemMessage(Component.literal("[DevTest] Starting rotation test sequence..."));

        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        String constructionId = "it.magius.pip.home";

        // Step 1: Delete construction if exists
        Architect.LOGGER.info("=== STEP 1: Deleting construction ===");
        player.sendSystemMessage(Component.literal("[DevTest] Step 1: Deleting construction..."));
        executeCommand(player, "struttura destroy " + constructionId);

        // Wait 1 second (20 ticks), then Step 2
        scheduleDelayedExecution(server, server.getTickCount() + 20, () -> {
            // Step 2: Face NORTH (yaw=180) and pull
            Architect.LOGGER.info("=== STEP 2: Facing NORTH and pulling ===");
            player.sendSystemMessage(Component.literal("[DevTest] Step 2: Facing NORTH (yaw=180), pulling..."));

            // Rotate player to face NORTH using tp command: tp @s ~ ~ ~ <yaw> <pitch>
            // NORTH = yaw 180
            executeCommand(player, "tp @s ~ ~ ~ 180 0");

            // Pull construction
            executeCommand(player, "struttura pull " + constructionId);

            // Wait 2 seconds (40 ticks) for async pull to complete, then Step 3
            scheduleDelayedExecution(server, server.getTickCount() + 40, () -> {
                // Step 3: Face EAST (yaw=-90) and move
                Architect.LOGGER.info("=== STEP 3: Facing EAST and moving ===");
                player.sendSystemMessage(Component.literal("[DevTest] Step 3: Facing EAST (yaw=-90), moving..."));

                // Rotate player to face EAST
                // EAST = yaw -90
                executeCommand(player, "tp @s ~ ~ ~ -90 0");

                // Move construction
                executeCommand(player, "struttura move " + constructionId);

                // Wait 1 second, then Step 4
                scheduleDelayedExecution(server, server.getTickCount() + 20, () -> {
                    // Step 4: Face WEST (yaw=90) and move
                    Architect.LOGGER.info("=== STEP 4: Facing WEST and moving ===");
                    player.sendSystemMessage(Component.literal("[DevTest] Step 4: Facing WEST (yaw=90), moving..."));

                    // Rotate player to face WEST
                    // WEST = yaw 90
                    executeCommand(player, "tp @s ~ ~ ~ 90 0");

                    // Move construction
                    executeCommand(player, "struttura move " + constructionId);

                    // Wait 1 second, then complete
                    scheduleDelayedExecution(server, server.getTickCount() + 20, () -> {
                        // Step 5: Test complete
                        Architect.LOGGER.info("=== STEP 5: Test sequence completed ===");
                        player.sendSystemMessage(Component.literal("[DevTest] Test sequence completed! Exiting game..."));
                        Architect.LOGGER.info("=== STRUTTURA DevTest: Test commands completed ===");

                        // First stop the server gracefully
                        server.halt(false);

                        // Wait 2 seconds for server shutdown, then exit JVM
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
