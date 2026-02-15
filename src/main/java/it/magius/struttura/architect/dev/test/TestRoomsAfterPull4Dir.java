package it.magius.struttura.architect.dev.test;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.registry.ConstructionRegistry;
import it.magius.struttura.architect.validation.CoherenceChecker;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * TEST: Room blocks coherence after pull in all 4 cardinal directions.
 *
 * Verifies that room block coordinates are correctly transformed when
 * a construction with rooms is pulled facing different directions.
 *
 * Prerequisites:
 * - Construction "it.magius.testroom" must exist on the server with at least one room (room_1)
 */
public class TestRoomsAfterPull4Dir implements DevTest {

    private static final String CONSTRUCTION_ID = "it.magius.testroom";
    private static final String ROOM_ID = "room_1";

    // Direction data: name, yaw, x offset for spawn position
    private static final String[][] DIRECTIONS = {
        {"SOUTH", "0", "0"},      // yaw 0
        {"WEST", "90", "50"},     // yaw 90
        {"NORTH", "180", "100"},  // yaw 180
        {"EAST", "-90", "150"}    // yaw -90 (or 270)
    };

    private int currentDirectionIndex = 0;
    private int passedCount = 0;
    private int failedCount = 0;
    private TestCompletionCallback completionCallback;

    @Override
    public String getId() {
        return "roomsAfterPull4Dir";
    }

    @Override
    public String getDescription() {
        return "Tests room block coherence after pull in all 4 cardinal directions";
    }

    @Override
    public void run(ServerPlayer player, MinecraftServer server, TestCompletionCallback onComplete) {
        this.completionCallback = onComplete;
        this.currentDirectionIndex = 0;
        this.passedCount = 0;
        this.failedCount = 0;

        Architect.LOGGER.info("[{}] Starting test: {}", getId(), getDescription());
        player.sendSystemMessage(Component.literal("[Test:" + getId() + "] Starting..."));

        testNextDirection(player, server);
    }

    private void testNextDirection(ServerPlayer player, MinecraftServer server) {
        if (currentDirectionIndex >= DIRECTIONS.length) {
            // All directions tested - cleanup and report
            cleanup(player, server, () -> {
                boolean allPassed = failedCount == 0;
                String message = String.format("PASSED: %d, FAILED: %d", passedCount, failedCount);

                Architect.LOGGER.info("[{}] Test completed: {}", getId(), message);
                completionCallback.onComplete(allPassed, message);
            });
            return;
        }

        String[] dir = DIRECTIONS[currentDirectionIndex];
        String dirName = dir[0];
        String yaw = dir[1];
        String xOffset = dir[2];

        ServerLevel level = (ServerLevel) player.level();

        Architect.LOGGER.info("[{}] Testing direction: {} (yaw={})", getId(), dirName, yaw);

        // Step 1: Delete construction if exists
        executeCommand(player, "struttura destroy " + CONSTRUCTION_ID);

        // Wait 1 second, then pull
        scheduleDelayed(server, 20, () -> {
            // Step 2: Teleport and pull
            executeCommand(player, "tp @s " + xOffset + " 100 0 " + yaw + " 0");
            executeCommand(player, "struttura pull " + CONSTRUCTION_ID);

            // Wait for pull to complete
            scheduleDelayed(server, 40, () -> {
                var registry = ConstructionRegistry.getInstance();
                Construction construction = registry.get(CONSTRUCTION_ID);

                if (construction == null) {
                    Architect.LOGGER.error("[{}] [{}] Construction NOT FOUND after pull!", getId(), dirName);
                    failedCount++;
                    currentDirectionIndex++;
                    testNextDirection(player, server);
                    return;
                }

                // Log bounds and room info
                var bounds = construction.getBounds();
                Architect.LOGGER.info("[{}] [{}] Bounds: min=({},{},{}), max=({},{},{})",
                    getId(), dirName,
                    bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
                    bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ());

                for (var room : construction.getRooms().values()) {
                    for (BlockPos pos : room.getChangedBlocks()) {
                        var worldState = level.getBlockState(pos);
                        Architect.LOGGER.info("[{}] [{}] Room block at: ({},{},{}) state={}",
                            getId(), dirName,
                            pos.getX(), pos.getY(), pos.getZ(), worldState);
                    }
                }

                // Step 3: Enter edit mode
                executeCommand(player, "struttura edit " + CONSTRUCTION_ID);

                scheduleDelayed(server, 20, () -> {
                    // Step 4: Edit the room
                    executeCommand(player, "struttura room edit " + ROOM_ID);

                    scheduleDelayed(server, 20, () -> {
                        // Step 5: Run coherence check
                        Construction updatedConstruction = registry.get(CONSTRUCTION_ID);
                        if (updatedConstruction == null) {
                            Architect.LOGGER.error("[{}] [{}] Construction lost after room edit!", getId(), dirName);
                            failedCount++;
                            currentDirectionIndex++;
                            scheduleDelayed(server, 20, () -> testNextDirection(player, server));
                            return;
                        }

                        // Log room blocks vs world
                        for (var room : updatedConstruction.getRooms().values()) {
                            for (BlockPos pos : room.getChangedBlocks()) {
                                var worldBlock = level.getBlockState(pos);
                                Architect.LOGGER.info("[{}] [{}] Room block: pos=({},{},{}), WORLD={}",
                                    getId(), dirName,
                                    pos.getX(), pos.getY(), pos.getZ(),
                                    worldBlock);
                            }
                            // Log room entity UUIDs
                            for (var entityUuid : room.getRoomEntities()) {
                                var entity = level.getEntity(entityUuid);
                                String entityType = entity != null
                                    ? entity.getType().toString() : "NOT_FOUND";
                                String entityPos = entity != null
                                    ? String.format("(%.1f,%.1f,%.1f)", entity.getX(), entity.getY(), entity.getZ())
                                    : "N/A";
                                Architect.LOGGER.info("[{}] [{}] Room entity: uuid={}, type={}, worldPos={}",
                                    getId(), dirName,
                                    entityUuid, entityType, entityPos);
                            }
                        }

                        boolean coherent = CoherenceChecker.validateConstruction(
                            level, updatedConstruction, true, ROOM_ID);

                        if (coherent) {
                            Architect.LOGGER.info("[{}] [{}] COHERENCE CHECK PASSED!", getId(), dirName);
                            passedCount++;
                        } else {
                            Architect.LOGGER.error("[{}] [{}] COHERENCE CHECK FAILED!", getId(), dirName);
                            failedCount++;
                        }

                        // Step 6: Exit room editing, then exit construction editing
                        executeCommand(player, "struttura room done");
                        executeCommand(player, "struttura done");

                        // Move to next direction
                        currentDirectionIndex++;
                        scheduleDelayed(server, 40, () -> testNextDirection(player, server));
                    });
                });
            });
        });
    }

    /**
     * Cleanup after test: destroy construction and teleport player to safe location.
     */
    private void cleanup(ServerPlayer player, MinecraftServer server, Runnable onDone) {
        Architect.LOGGER.info("[{}] Cleaning up...", getId());

        // Destroy the test construction
        executeCommand(player, "struttura destroy " + CONSTRUCTION_ID);

        // Teleport player back to origin
        scheduleDelayed(server, 20, () -> {
            executeCommand(player, "tp @s 0 100 0 0 0");
            scheduleDelayed(server, 10, onDone);
        });
    }

    private void executeCommand(ServerPlayer player, String command) {
        Architect.LOGGER.debug("[{}] Executing: /{}", getId(), command);
        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        server.getCommands().performPrefixedCommand(
            player.createCommandSourceStack(),
            command
        );
    }

    private void scheduleDelayed(MinecraftServer server, int ticksDelay, Runnable task) {
        // Use DevTestRunner's scheduler
        DevTestRunner.scheduleDelayed(server, ticksDelay, task);
    }
}
