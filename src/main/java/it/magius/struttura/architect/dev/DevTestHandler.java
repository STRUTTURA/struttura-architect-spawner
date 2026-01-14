package it.magius.struttura.architect.dev;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.registry.ConstructionRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

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

        player.sendSystemMessage(Component.literal("[DevTest] Starting entity rotation test..."));

        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        ServerLevel level = (ServerLevel) player.level();
        String constructionId = "it.magius.test2";

        // Step 1: Delete construction if exists and clear any entities
        Architect.LOGGER.info("=== STEP 1: Deleting construction and clearing area ===");
        player.sendSystemMessage(Component.literal("[DevTest] Step 1: Deleting construction..."));
        executeCommand(player, "struttura destroy " + constructionId);

        // Wait 1 second (20 ticks), then Step 2
        scheduleDelayedExecution(server, server.getTickCount() + 20, () -> {
            // Step 2: Face SOUTH (yaw=0) and pull - should align with entrance yaw (~0)
            Architect.LOGGER.info("=== STEP 2: Facing SOUTH (yaw=0) and pulling ===");
            player.sendSystemMessage(Component.literal("[DevTest] Step 2: Facing SOUTH (yaw=0), pulling..."));

            // Teleport to a clean position
            executeCommand(player, "tp @s 0 100 0 0 0");
            executeCommand(player, "struttura pull " + constructionId);

            // Wait 2 seconds for pull, then log and do Step 3
            scheduleDelayedExecution(server, server.getTickCount() + 40, () -> {
                Architect.LOGGER.info("=== AFTER PULL (SOUTH) ===");
                logConstructionAndWorldState(constructionId, level, "AFTER_PULL_SOUTH");

                // Step 3: Face EAST (yaw=-90) and move - 90° rotation
                Architect.LOGGER.info("=== STEP 3: Facing EAST (yaw=-90) and moving (90° rotation) ===");
                player.sendSystemMessage(Component.literal("[DevTest] Step 3: Facing EAST (yaw=-90), moving..."));

                executeCommand(player, "tp @s 20 100 0 -90 0");
                executeCommand(player, "struttura move " + constructionId);

                // Wait 2 seconds for move, then log and do Step 4
                scheduleDelayedExecution(server, server.getTickCount() + 40, () -> {
                    Architect.LOGGER.info("=== AFTER MOVE TO EAST ===");
                    logConstructionAndWorldState(constructionId, level, "AFTER_MOVE_EAST");

                    // Step 4: Face NORTH (yaw=180) and move - another 90° rotation
                    Architect.LOGGER.info("=== STEP 4: Facing NORTH (yaw=180) and moving (another 90° rotation) ===");
                    player.sendSystemMessage(Component.literal("[DevTest] Step 4: Facing NORTH (yaw=180), moving..."));

                    executeCommand(player, "tp @s 40 100 0 180 0");
                    executeCommand(player, "struttura move " + constructionId);

                    // Wait 2 seconds for move, then log and do Step 5
                    scheduleDelayedExecution(server, server.getTickCount() + 40, () -> {
                        Architect.LOGGER.info("=== AFTER MOVE TO NORTH ===");
                        logConstructionAndWorldState(constructionId, level, "AFTER_MOVE_NORTH");

                        // Step 5: Face WEST (yaw=90) and move - another 90° rotation
                        Architect.LOGGER.info("=== STEP 5: Facing WEST (yaw=90) and moving (another 90° rotation) ===");
                        player.sendSystemMessage(Component.literal("[DevTest] Step 5: Facing WEST (yaw=90), moving..."));

                        executeCommand(player, "tp @s 60 100 0 90 0");
                        executeCommand(player, "struttura move " + constructionId);

                        // Wait 2 seconds for move, then log and finish
                        scheduleDelayedExecution(server, server.getTickCount() + 40, () -> {
                            Architect.LOGGER.info("=== AFTER MOVE TO WEST (full 360°) ===");
                            logConstructionAndWorldState(constructionId, level, "AFTER_MOVE_WEST");

                            Architect.LOGGER.info("=== STEP 6: Test completed ===");
                            player.sendSystemMessage(Component.literal("[DevTest] Entity rotation test completed! Exiting..."));

                            Architect.LOGGER.info("=== STRUTTURA DevTest: Test commands completed ===");

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
        });
    }

    /**
     * Logs detailed information about construction stored data and world entities.
     */
    private void logConstructionAndWorldState(String constructionId, ServerLevel level, String phase) {
        Architect.LOGGER.info("--- {} ---", phase);

        // Get construction from registry
        Construction construction = ConstructionRegistry.getInstance().get(constructionId);
        if (construction == null) {
            Architect.LOGGER.error("Construction {} not found in registry!", constructionId);
            return;
        }

        // Log anchor info
        if (construction.getAnchors().hasEntrance()) {
            BlockPos entrance = construction.getAnchors().getEntrance();
            float entranceYaw = construction.getAnchors().getEntranceYaw();
            Architect.LOGGER.info("[{}] Anchor entrance: pos=({}, {}, {}), yaw={}",
                phase, entrance.getX(), entrance.getY(), entrance.getZ(), entranceYaw);
        }

        // Log bounds
        var bounds = construction.getBounds();
        Architect.LOGGER.info("[{}] Bounds: min=({}, {}, {}), max=({}, {}, {})",
            phase, bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
            bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ());

        // Log first block in construction storage
        Map<BlockPos, BlockState> blocks = construction.getBlocks();
        if (!blocks.isEmpty()) {
            BlockPos firstBlock = blocks.keySet().iterator().next();
            BlockState firstState = blocks.get(firstBlock);
            Architect.LOGGER.info("[{}] First stored block: pos=({}, {}, {}), state={}",
                phase, firstBlock.getX(), firstBlock.getY(), firstBlock.getZ(), firstState);
        }

        // Log entity data stored in construction
        List<EntityData> storedEntities = construction.getEntities();
        Architect.LOGGER.info("[{}] Stored entities count: {}", phase, storedEntities.size());
        for (int i = 0; i < storedEntities.size(); i++) {
            EntityData ed = storedEntities.get(i);
            Architect.LOGGER.info("[{}] Stored entity {}: type={}, relPos=({}, {}, {}), yaw={}",
                phase, i, ed.getEntityType(),
                String.format("%.2f", ed.getRelativePos().x),
                String.format("%.2f", ed.getRelativePos().y),
                String.format("%.2f", ed.getRelativePos().z),
                String.format("%.2f", ed.getYaw()));
        }

        // Log actual world entities in construction bounds area (expanded)
        AABB searchArea = new AABB(
            bounds.getMinX() - 5, bounds.getMinY() - 5, bounds.getMinZ() - 5,
            bounds.getMaxX() + 5, bounds.getMaxY() + 5, bounds.getMaxZ() + 5
        );
        List<Entity> worldEntities = level.getEntities((Entity) null, searchArea, e -> !(e instanceof ServerPlayer));
        Architect.LOGGER.info("[{}] World entities in area: {}", phase, worldEntities.size());
        for (Entity entity : worldEntities) {
            Architect.LOGGER.info("[{}] World entity: type={}, pos=({}, {}, {}), yaw={}",
                phase, entity.getType().toShortString(),
                String.format("%.2f", entity.getX()),
                String.format("%.2f", entity.getY()),
                String.format("%.2f", entity.getZ()),
                String.format("%.2f", entity.getYRot()));
        }

        Architect.LOGGER.info("--- END {} ---", phase);
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
