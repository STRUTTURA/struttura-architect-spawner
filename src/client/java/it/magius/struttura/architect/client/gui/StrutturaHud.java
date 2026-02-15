package it.magius.struttura.architect.client.gui;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.ingame.InBuildingHud;
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

        // Check if in editing mode
        PanelManager pm = PanelManager.getInstance();
        if (pm.isEditing()) {
            // Render mini editing status panel (non-interactive)
            renderEditingStatusPanel(graphics, mc.font, pm);
        } else {
            // If not editing, try to render in-building HUD
            InBuildingHud.render(graphics, mc.font);
        }
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
        String statsText;
        if (pm.isInRoom()) {
            // Room: blocks | entities | mode (no dimension)
            statsText = pm.getBlockCount() + " blocks | " + pm.getEntityCount() + " entities | " + pm.getMode();
        } else {
            // Base construction: blocks | entities | dimension | mode
            statsText = pm.getBlockCount() + " blocks | " + pm.getEntityCount() + " entities | " + pm.getBounds() + " | " + pm.getMode();
        }

        // Room info line (if in room editing)
        String roomText = null;
        if (pm.isInRoom()) {
            String roomName = pm.getCurrentRoomName().isEmpty() ? pm.getCurrentRoomId() : pm.getCurrentRoomName();
            roomText = "Room: " + roomName + " (" + pm.getRoomBlockChanges() + " changes)";
        }

        int maxTextWidth = Math.max(font.width(idText), Math.max(font.width(titleText), font.width(statsText)));
        if (roomText != null) {
            maxTextWidth = Math.max(maxTextWidth, font.width(roomText));
        }
        int panelWidth = maxTextWidth + PADDING * 2;
        int lineCount = roomText != null ? 4 : 3;
        int panelHeight = LINE_HEIGHT * lineCount + PADDING * 2;

        // Position at top center
        int panelX = (screenWidth - panelWidth) / 2;
        int panelY = 5;

        // Draw background (orange tint if in room editing)
        int bgColor = pm.isInRoom() ? 0xC0301000 : 0xC0000000;
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, bgColor);
        int outlineColor = pm.isInRoom() ? 0xFFFF8800 : 0xFF404040;
        graphics.renderOutline(panelX, panelY, panelWidth, panelHeight, outlineColor);

        // Draw content
        int textY = panelY + PADDING;

        // ID (yellow)
        graphics.drawCenteredString(font, idText, screenWidth / 2, textY, 0xFFFFAA00);
        textY += LINE_HEIGHT;

        // Title (white or gray if not set)
        int titleColor = pm.getEditingTitle().isEmpty() ? 0xFF808080 : 0xFFFFFFFF;
        graphics.drawCenteredString(font, titleText, screenWidth / 2, textY, titleColor);
        textY += LINE_HEIGHT;

        // Room info (green if in room)
        if (roomText != null) {
            graphics.drawCenteredString(font, roomText, screenWidth / 2, textY, 0xFF00FF88);
            textY += LINE_HEIGHT;
        }

        // Stats (green for ADD, red for REMOVE)
        int statsColor = pm.getMode().equals("ADD") ? 0xFF88FF88 : 0xFFFF8888;
        graphics.drawCenteredString(font, statsText, screenWidth / 2, textY, statsColor);
    }
}
