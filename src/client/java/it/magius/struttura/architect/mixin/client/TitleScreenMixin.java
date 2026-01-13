package it.magius.struttura.architect.mixin.client;

import it.magius.struttura.architect.Architect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

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
    private static final Identifier STRUTTURA_BACKGROUND = Identifier.fromNamespaceAndPath(
            "architect", "textures/gui/title_background.png");

    @Unique
    private static final Identifier WORM_TEXTURE = Identifier.fromNamespaceAndPath(
            "architect", "textures/gui/worm.png");

    // Background dimensions (ultrawide)
    @Unique
    private static final int BG_WIDTH = 1536;
    @Unique
    private static final int BG_HEIGHT = 658;

    // Worm dimensions
    @Unique
    private static final int WORM_WIDTH = 675;
    @Unique
    private static final int WORM_HEIGHT = 598;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    /**
     * Injects at the end of TitleScreen.init() to auto-load the dev world
     * when -Dstruttura.devtest=true system property is set.
     */
    @Inject(method = "init", at = @At("RETURN"))
    private void architect$onInit(CallbackInfo ci) {
        if (architect$autoLoadAttempted) {
            return;
        }

        String devTestProp = System.getProperty("struttura.devtest");
        if (!"true".equalsIgnoreCase(devTestProp)) {
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
        int screenWidth = this.width;
        int screenHeight = this.height;

        // Calculate aspect ratio to cover the entire screen (like CSS background-size: cover)
        float imageAspect = (float) BG_WIDTH / BG_HEIGHT;
        float screenAspect = (float) screenWidth / screenHeight;

        float u = 0, v = 0;
        int regionWidth = BG_WIDTH, regionHeight = BG_HEIGHT;

        if (screenAspect > imageAspect) {
            // Screen is wider than image - crop top/bottom
            int visibleTextureHeight = (int) (BG_WIDTH / screenAspect);
            v = (BG_HEIGHT - visibleTextureHeight) / 2f;
            regionHeight = visibleTextureHeight;
        } else {
            // Screen is taller than image - crop left/right
            int visibleTextureWidth = (int) (BG_HEIGHT * screenAspect);
            u = (BG_WIDTH - visibleTextureWidth) / 2f;
            regionWidth = visibleTextureWidth;
        }

        // Draw the custom background image
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                STRUTTURA_BACKGROUND,
                0, 0,
                u, v,
                screenWidth, screenHeight,
                regionWidth, regionHeight,
                BG_WIDTH, BG_HEIGHT
        );

        // Draw the worm (bilinear filtering enabled via worm.png.mcmeta)
        // Scale worm to be proportional to screen height (about 40% of screen height)
        int wormDrawHeight = (int) (screenHeight * 0.4f);
        int wormDrawWidth = (int) (wormDrawHeight * ((float) WORM_WIDTH / WORM_HEIGHT));

        // Position: close to left edge (1.5% margin), center at 60% from top
        int wormX = (int)(screenWidth * 0.015f);
        int wormY = (int) (screenHeight * 0.60f) - wormDrawHeight / 2;

        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                WORM_TEXTURE,
                wormX, wormY,
                0, 0,
                wormDrawWidth, wormDrawHeight,
                WORM_WIDTH, WORM_HEIGHT,
                WORM_WIDTH, WORM_HEIGHT
        );
    }
}
