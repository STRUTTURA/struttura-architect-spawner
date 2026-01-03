package it.magius.struttura.architect.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin to replace Minecraft's title screen background with Struttura's custom image.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

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
