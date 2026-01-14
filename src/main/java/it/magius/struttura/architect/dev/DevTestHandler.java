package it.magius.struttura.architect.dev;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.dev.test.DevTestRunner;
import net.minecraft.server.level.ServerPlayer;

/**
 * Handler for automated testing when running with -Dstruttura.devtest=...
 *
 * This class delegates to DevTestRunner which manages the actual test execution.
 *
 * Usage:
 * - Run all tests:
 *   npm run architect:test
 *   (or: -Dstruttura.devtest=true)
 *
 * - Run specific tests (comma-separated):
 *   -Dstruttura.devtest=roomsAfterPull4Dir
 *   -Dstruttura.devtest=roomsAfterPull4Dir,otherTest
 *
 * Available tests are defined in DevTestRunner.AVAILABLE_TESTS.
 * Each test is in the it.magius.struttura.architect.dev.test package.
 */
public class DevTestHandler {

    private static final DevTestHandler INSTANCE = new DevTestHandler();

    private DevTestHandler() {}

    public static DevTestHandler getInstance() {
        return INSTANCE;
    }

    /**
     * Check if devtest mode is enabled.
     */
    public static boolean isEnabled() {
        return DevTestRunner.isEnabled();
    }

    /**
     * Called when a player joins the world in devtest mode.
     * Delegates to DevTestRunner.
     */
    public void onPlayerJoinWorld(ServerPlayer player) {
        if (!isEnabled()) {
            return;
        }

        Architect.LOGGER.info("DevTestHandler: Delegating to DevTestRunner");
        DevTestRunner.getInstance().onPlayerJoinWorld(player);
    }
}
