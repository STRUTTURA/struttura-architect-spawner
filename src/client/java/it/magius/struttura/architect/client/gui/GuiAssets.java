package it.magius.struttura.architect.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Centralized assets and rendering utilities for STRUTTURA GUI screens.
 * Ensures consistent rendering of common elements like the worm logo.
 */
@Environment(EnvType.CLIENT)
public final class GuiAssets {

    // Struttura named logo texture (has blur enabled via .mcmeta)
    public static final Identifier WORM_TEXTURE = Identifier.fromNamespaceAndPath(
            "architect", "textures/gui/struttura-named-logo.png");
    public static final int WORM_WIDTH = 675;
    public static final int WORM_HEIGHT = 598;

    // Background texture
    public static final Identifier BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
            "architect", "textures/gui/title_background.png");
    public static final int BG_WIDTH = 1536;
    public static final int BG_HEIGHT = 658;

    // Like icon texture (square 1024x1024)
    public static final Identifier LIKE_ICON_TEXTURE = Identifier.fromNamespaceAndPath(
            "architect", "textures/gui/like-icon.png");
    public static final int LIKE_ICON_SIZE = 1024;

    private GuiAssets() {}

    /**
     * Renders the worm logo at specified position and size.
     * Uses the same rendering approach as TitleScreen for consistent antialiasing.
     *
     * @param graphics the graphics context
     * @param x left position
     * @param y top position
     * @param width desired width
     * @param height desired height
     */
    public static void renderWorm(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                WORM_TEXTURE,
                x, y,
                0, 0,
                width, height,
                WORM_WIDTH, WORM_HEIGHT,
                WORM_WIDTH, WORM_HEIGHT
        );
    }

    /**
     * Renders the worm logo scaled to a percentage of screen height.
     * Maintains aspect ratio.
     *
     * @param graphics the graphics context
     * @param screenHeight total screen height
     * @param heightPercent percentage of screen height for the worm (0.0 to 1.0)
     * @param x left position
     * @param y top position
     */
    public static void renderWormScaled(GuiGraphics graphics, int screenHeight, float heightPercent, int x, int y) {
        int height = (int) (screenHeight * heightPercent);
        int width = (int) (height * ((float) WORM_WIDTH / WORM_HEIGHT));
        renderWorm(graphics, x, y, width, height);
    }

    /**
     * Renders the background image to cover the entire screen.
     * Uses "background-size: cover" logic to fill while maintaining aspect ratio.
     *
     * @param graphics the graphics context
     * @param screenWidth total screen width
     * @param screenHeight total screen height
     */
    public static void renderBackground(GuiGraphics graphics, int screenWidth, int screenHeight) {
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

        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                BACKGROUND_TEXTURE,
                0, 0,
                u, v,
                screenWidth, screenHeight,
                regionWidth, regionHeight,
                BG_WIDTH, BG_HEIGHT
        );
    }

    /**
     * Renders the like icon at specified position and size.
     *
     * @param graphics the graphics context
     * @param x left position
     * @param y top position
     * @param size desired size (square)
     */
    public static void renderLikeIcon(GuiGraphics graphics, int x, int y, int size) {
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                LIKE_ICON_TEXTURE,
                x, y,
                0, 0,
                size, size,
                LIKE_ICON_SIZE, LIKE_ICON_SIZE,
                LIKE_ICON_SIZE, LIKE_ICON_SIZE
        );
    }
}
