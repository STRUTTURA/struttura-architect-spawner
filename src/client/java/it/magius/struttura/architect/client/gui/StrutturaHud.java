package it.magius.struttura.architect.client.gui;

import it.magius.struttura.architect.Architect;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * HUD overlay that shows a mini editing status panel when in editing mode.
 * This is non-interactive - just displays current editing state.
 */
@Environment(EnvType.CLIENT)
public class StrutturaHud {

    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 10;

    /**
     * Initialize the HUD overlay.
     * Registers the render callback with Fabric API.
     */
    public static void init() {
        // Initialize the panel manager
        PanelManager.getInstance().init();

        // Register HUD render callback
        HudRenderCallback.EVENT.register(StrutturaHud::render);

        Architect.LOGGER.info("StrutturaHud initialized");
    }

    /**
     * Render the HUD overlay.
     */
    private static void render(GuiGraphics graphics, net.minecraft.client.DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();

        // Don't render when a screen is open
        if (mc.screen != null) {
            return;
        }

        // Don't render when HUD is hidden
        if (mc.options.hideGui) {
            return;
        }

        // Only render if in editing mode
        PanelManager pm = PanelManager.getInstance();
        if (!pm.isEditing()) {
            return;
        }

        // Render mini editing status panel (non-interactive)
        renderEditingStatusPanel(graphics, mc.font, pm);
    }

    /**
     * Render a compact editing status panel at the top center of the screen.
     */
    private static void renderEditingStatusPanel(GuiGraphics graphics, Font font, PanelManager pm) {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();

        // Calculate panel dimensions
        String idText = pm.getEditingConstructionId();
        String titleText = pm.getEditingTitle().isEmpty() ? "(no title)" : pm.getEditingTitle();
        String statsText = pm.getBlockCount() + " blocks | " + pm.getBounds() + " | " + pm.getMode();

        int maxTextWidth = Math.max(font.width(idText), Math.max(font.width(titleText), font.width(statsText)));
        int panelWidth = maxTextWidth + PADDING * 2;
        int panelHeight = LINE_HEIGHT * 3 + PADDING * 2;

        // Position at top center
        int panelX = (screenWidth - panelWidth) / 2;
        int panelY = 5;

        // Draw background
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xC0000000);
        graphics.renderOutline(panelX, panelY, panelWidth, panelHeight, 0xFF404040);

        // Draw content
        int textY = panelY + PADDING;

        // ID (yellow)
        graphics.drawCenteredString(font, idText, screenWidth / 2, textY, 0xFFFFAA00);
        textY += LINE_HEIGHT;

        // Title (white or gray if not set)
        int titleColor = pm.getEditingTitle().isEmpty() ? 0xFF808080 : 0xFFFFFFFF;
        graphics.drawCenteredString(font, titleText, screenWidth / 2, textY, titleColor);
        textY += LINE_HEIGHT;

        // Stats (green for ADD, red for REMOVE)
        int statsColor = pm.getMode().equals("ADD") ? 0xFF88FF88 : 0xFFFF8888;
        graphics.drawCenteredString(font, statsText, screenWidth / 2, textY, statsColor);
    }
}
