package it.magius.struttura.architect.mixin.client;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.gui.GuiAssets;
import it.magius.struttura.architect.client.gui.WelcomeScreen;
import it.magius.struttura.architect.config.ArchitectConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to replace Minecraft's title screen background with Struttura's custom image.
 * Also handles auto-loading a dev world when -Dstruttura.devtest=true is set.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    @Unique
    private static final String DEV_WORLD_NAME = "Struttura Develop";

    @Unique
    private static boolean architect$autoLoadAttempted = false;

    @Unique
    private static boolean architect$welcomeChecked = false;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    /**
     * Injects at the end of TitleScreen.init() to auto-load the dev world
     * when -Dstruttura.devtest=true system property is set, and to show the
     * welcome screen on first launch.
     */
    @Inject(method = "init", at = @At("RETURN"))
    private void architect$onInit(CallbackInfo ci) {
        // Show welcome screen once per Minecraft home (if not yet shown)
        if (!architect$welcomeChecked) {
            architect$welcomeChecked = true;
            ArchitectConfig config = ArchitectConfig.getInstance();
            if (!config.isWelcomeMessageShown()) {
                Architect.LOGGER.info("Showing welcome screen for the first time");
                Minecraft.getInstance().setScreen(new WelcomeScreen((Screen)(Object)this));
                return;
            }
        }

        if (architect$autoLoadAttempted) {
            return;
        }

        String devTestProp = System.getProperty("struttura.devtest");
        // Accept any non-null value: "true" for all tests, or specific test IDs like "roomsAfterPull4Dir"
        if (devTestProp == null || devTestProp.isEmpty()) {
            return;
        }

        architect$autoLoadAttempted = true;
        architect$tryAutoLoadWorld();
    }

    @Unique
    private void architect$tryAutoLoadWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        LevelStorageSource levelSource = minecraft.getLevelSource();

        try {
            // Find all available worlds asynchronously
            LevelStorageSource.LevelCandidates candidates = levelSource.findLevelCandidates();
            levelSource.loadLevelSummaries(candidates).thenAcceptAsync(levels -> {
                // Find the world with matching name
                LevelSummary targetWorld = levels.stream()
                        .filter(summary -> DEV_WORLD_NAME.equals(summary.getLevelName()))
                        .findFirst()
                        .orElse(null);

                if (targetWorld != null) {
                    Architect.LOGGER.info("STRUTTURA DevTest: Auto-loading world '{}'", DEV_WORLD_NAME);
                    String levelId = targetWorld.getLevelId();
                    WorldOpenFlows worldOpenFlows = minecraft.createWorldOpenFlows();
                    worldOpenFlows.openWorld(levelId, () -> {
                        // On cancel, just return to title screen
                        Architect.LOGGER.info("STRUTTURA DevTest: World load cancelled");
                    });
                } else {
                    Architect.LOGGER.warn("STRUTTURA DevTest: World '{}' not found. Available worlds: {}",
                            DEV_WORLD_NAME,
                            levels.stream().map(LevelSummary::getLevelName).toList());
                }
            }, minecraft);
        } catch (Exception e) {
            Architect.LOGGER.error("STRUTTURA DevTest: Failed to auto-load world", e);
        }
    }

    @Override
    protected void renderPanorama(GuiGraphics graphics, float partialTick) {
        // Draw the custom background image (uses centralized rendering)
        GuiAssets.renderBackground(graphics, this.width, this.height);

        // Draw the worm (bilinear filtering enabled via worm.png.mcmeta)
        // Scale worm to be proportional to screen height (about 40% of screen height)
        int wormDrawHeight = (int) (this.height * 0.4f);

        // Position: close to left edge (1.5% margin), center at 57% from top
        int wormX = (int)(this.width * 0.015f);
        int wormY = (int) (this.height * 0.57f) - wormDrawHeight / 2;

        GuiAssets.renderWormScaled(graphics, this.height, 0.4f, wormX, wormY);
    }
}
