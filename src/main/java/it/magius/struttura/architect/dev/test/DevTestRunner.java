package it.magius.struttura.architect.dev.test;

import it.magius.struttura.architect.Architect;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runner for automated development tests.
 *
 * Usage from command line:
 * - No test specified: runs ALL tests
 *   -Dstruttura.devtest=true
 *
 * - Specific tests (comma-separated):
 *   -Dstruttura.devtest=roomsAfterPull4Dir,otherTest
 *
 * Each test must implement the DevTest interface and be registered in AVAILABLE_TESTS.
 */
public class DevTestRunner {

    private static final DevTestRunner INSTANCE = new DevTestRunner();
    private static boolean tickHandlerRegistered = false;

    // All available tests - add new tests here
    private static final List<DevTest> AVAILABLE_TESTS = List.of(
        new TestRoomsAfterPull4Dir(),
        new TestRoomsAfterPullMove4Dir()
        // Add more tests here as they are created
    );

    // Pending delayed tasks for scheduling
    private static final Map<Integer, Runnable> pendingTasks = new ConcurrentHashMap<>();
    private static int nextTaskId = 0;

    // Test execution state
    private List<DevTest> testsToRun = new ArrayList<>();
    private int currentTestIndex = 0;
    private int totalPassed = 0;
    private int totalFailed = 0;
    private List<String> results = new ArrayList<>();

    private DevTestRunner() {}

    public static DevTestRunner getInstance() {
        return INSTANCE;
    }

    /**
     * Check if devtest mode is enabled and get which tests to run.
     *
     * @return null if devtest is disabled, empty list to run all tests,
     *         or list of specific test IDs to run
     */
    public static List<String> getRequestedTests() {
        String prop = System.getProperty("struttura.devtest");
        if (prop == null || prop.isEmpty()) {
            return null; // Devtest disabled
        }

        if (prop.equalsIgnoreCase("true")) {
            return Collections.emptyList(); // Run all tests
        }

        // Parse comma-separated test IDs
        return Arrays.asList(prop.split(","));
    }

    /**
     * Check if devtest mode is enabled.
     */
    public static boolean isEnabled() {
        return getRequestedTests() != null;
    }

    /**
     * Called when a player joins the world in devtest mode.
     */
    public void onPlayerJoinWorld(ServerPlayer player) {
        List<String> requestedTests = getRequestedTests();
        if (requestedTests == null) {
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

        // Determine which tests to run
        if (requestedTests.isEmpty()) {
            // Run all tests
            testsToRun = new ArrayList<>(AVAILABLE_TESTS);
            Architect.LOGGER.info("DevTest: Running ALL {} tests", testsToRun.size());
        } else {
            // Run specific tests
            testsToRun = new ArrayList<>();
            for (String testId : requestedTests) {
                DevTest test = findTestById(testId.trim());
                if (test != null) {
                    testsToRun.add(test);
                } else {
                    Architect.LOGGER.warn("DevTest: Unknown test '{}', skipping", testId);
                }
            }
            Architect.LOGGER.info("DevTest: Running {} specific tests: {}",
                testsToRun.size(), requestedTests);
        }

        if (testsToRun.isEmpty()) {
            Architect.LOGGER.error("DevTest: No tests to run!");
            player.sendSystemMessage(Component.literal("[DevTest] No tests to run! Available tests:"));
            for (DevTest test : AVAILABLE_TESTS) {
                player.sendSystemMessage(Component.literal("  - " + test.getId() + ": " + test.getDescription()));
            }
            exitTest(server);
            return;
        }

        // Reset state
        currentTestIndex = 0;
        totalPassed = 0;
        totalFailed = 0;
        results.clear();

        // Delay execution by 1 second to ensure world is fully loaded
        scheduleDelayed(server, 20, () -> runNextTest(player, server));
    }

    private DevTest findTestById(String id) {
        for (DevTest test : AVAILABLE_TESTS) {
            if (test.getId().equalsIgnoreCase(id)) {
                return test;
            }
        }
        return null;
    }

    private void runNextTest(ServerPlayer player, MinecraftServer server) {
        if (currentTestIndex >= testsToRun.size()) {
            // All tests completed
            printFinalResults(player, server);
            return;
        }

        DevTest test = testsToRun.get(currentTestIndex);

        Architect.LOGGER.info("============================================");
        Architect.LOGGER.info("=== RUNNING TEST {}/{}: {} ===",
            currentTestIndex + 1, testsToRun.size(), test.getId());
        Architect.LOGGER.info("============================================");

        player.sendSystemMessage(Component.literal(
            String.format("[DevTest] Running test %d/%d: %s",
                currentTestIndex + 1, testsToRun.size(), test.getId())));

        test.run(player, server, (passed, message) -> {
            String result = String.format("[%s] %s: %s",
                passed ? "PASS" : "FAIL", test.getId(), message);
            results.add(result);

            if (passed) {
                totalPassed++;
            } else {
                totalFailed++;
            }

            Architect.LOGGER.info("Test {} completed: {} - {}",
                test.getId(), passed ? "PASSED" : "FAILED", message);

            // Move to next test
            currentTestIndex++;
            scheduleDelayed(server, 20, () -> runNextTest(player, server));
        });
    }

    private void printFinalResults(ServerPlayer player, MinecraftServer server) {
        Architect.LOGGER.info("============================================");
        Architect.LOGGER.info("=== ALL TESTS COMPLETED ===");
        Architect.LOGGER.info("=== TOTAL PASSED: {}, FAILED: {} ===", totalPassed, totalFailed);
        Architect.LOGGER.info("============================================");

        for (String result : results) {
            Architect.LOGGER.info(result);
        }

        if (totalFailed == 0) {
            player.sendSystemMessage(Component.literal(
                "[DevTest] ALL " + testsToRun.size() + " TESTS PASSED!"));
        } else {
            player.sendSystemMessage(Component.literal(
                "[DevTest] COMPLETED: " + totalPassed + " passed, " + totalFailed + " failed"));
        }

        Architect.LOGGER.info("=== STRUTTURA DevTest: Session completed ===");
        exitTest(server);
    }

    /**
     * Called every server tick to check pending tasks.
     */
    public void onServerTick(MinecraftServer server) {
        // Copy to avoid ConcurrentModificationException
        for (Runnable task : new ArrayList<>(pendingTasks.values())) {
            task.run();
        }
    }

    /**
     * Schedule a task to run after a delay.
     * This is a static method so tests can use it.
     */
    public static void scheduleDelayed(MinecraftServer server, int ticksDelay, Runnable task) {
        int targetTick = server.getTickCount() + ticksDelay;
        int taskId = nextTaskId++;
        pendingTasks.put(taskId, () -> {
            if (server.getTickCount() >= targetTick) {
                pendingTasks.remove(taskId);
                task.run();
            }
        });
    }

    private void exitTest(MinecraftServer server) {
        server.halt(false);
        new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException e) {}
            System.exit(0);
        }).start();
    }

    /**
     * Get list of all available test IDs for documentation.
     */
    public static List<String> getAvailableTestIds() {
        List<String> ids = new ArrayList<>();
        for (DevTest test : AVAILABLE_TESTS) {
            ids.add(test.getId());
        }
        return ids;
    }
}
