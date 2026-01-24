package it.magius.struttura.architect.client.ingame;

import it.magius.struttura.architect.config.ArchitectConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * HUD overlay that shows building information when player is inside a spawned building.
 * Position is configurable via ArchitectConfig overlay settings.
 */
@Environment(EnvType.CLIENT)
public class InBuildingHud {

    private static final int PADDING = 6;
    private static final int LINE_HEIGHT = 12;
    private static final int PANEL_BG_COLOR = 0xC0000000;  // Semi-transparent black
    private static final int PANEL_BORDER_COLOR = 0xFF505050;  // Gray border
    private static final int BUILDING_NAME_COLOR = 0xFFFFAA00;  // Yellow/gold
    private static final int AUTHOR_COLOR = 0xFF88AAFF;  // Light blue

    /**
     * Renders the in-building HUD overlay.
     * Called from StrutturaHud.
     *
     * @param graphics the GUI graphics context
     * @param font the font to use for text
     * @return true if something was rendered, false otherwise
     */
    public static boolean render(GuiGraphics graphics, Font font) {
        InGameClientState state = InGameClientState.getInstance();

        // Don't render if not in a building
        if (!state.isInBuilding()) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        String buildingName = state.getBuildingName();
        String author = state.getAuthor();

        // Skip if no valid data
        if (buildingName.isEmpty()) {
            return false;
        }

        // Format text lines
        String line1 = buildingName;
        String line2 = author.isEmpty() ? "" : "by " + author;

        // Calculate dimensions
        int textWidth1 = font.width(line1);
        int textWidth2 = line2.isEmpty() ? 0 : font.width(line2);
        int maxTextWidth = Math.max(textWidth1, textWidth2);

        int panelWidth = maxTextWidth + PADDING * 2;
        int lineCount = line2.isEmpty() ? 1 : 2;
        int panelHeight = LINE_HEIGHT * lineCount + PADDING * 2 - 2;

        // Get position from config
        ArchitectConfig config = ArchitectConfig.getInstance();
        String anchorV = config.getOverlayAnchorV();
        String anchorH = config.getOverlayAnchorH();
        int offsetXPercent = config.getOverlayOffsetX();
        int offsetYPercent = config.getOverlayOffsetY();

        // Calculate panel position based on anchors and offsets
        int panelX = calculateX(screenWidth, panelWidth, anchorH, offsetXPercent);
        int panelY = calculateY(screenHeight, panelHeight, anchorV, offsetYPercent);

        // Draw background
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BG_COLOR);
        graphics.renderOutline(panelX, panelY, panelWidth, panelHeight, PANEL_BORDER_COLOR);

        // Draw text
        int textY = panelY + PADDING;
        int textCenterX = panelX + panelWidth / 2;

        // Building name (centered in panel, yellow)
        graphics.drawCenteredString(font, line1, textCenterX, textY, BUILDING_NAME_COLOR);
        textY += LINE_HEIGHT;

        // Author (centered in panel, blue)
        if (!line2.isEmpty()) {
            graphics.drawCenteredString(font, line2, textCenterX, textY, AUTHOR_COLOR);
        }

        return true;
    }

    /**
     * Calculates X position based on horizontal anchor and offset.
     * Offset 0% = attached to edge, 50% = center of available space
     */
    private static int calculateX(int screenWidth, int panelWidth, String anchorH, int offsetPercent) {
        switch (anchorH) {
            case "LEFT":
                // Offset moves from left edge (0%) toward center (50%)
                int maxOffsetLeft = (screenWidth - panelWidth) / 2;
                return (maxOffsetLeft * offsetPercent) / 100;
            case "RIGHT":
                // Offset moves from right edge (0%) toward center (50%)
                int maxOffsetRight = (screenWidth - panelWidth) / 2;
                return screenWidth - panelWidth - (maxOffsetRight * offsetPercent) / 100;
            case "HCENTER":
            default:
                // Centered horizontally, offset has no effect
                return (screenWidth - panelWidth) / 2;
        }
    }

    /**
     * Calculates Y position based on vertical anchor and offset.
     * Offset 0% = attached to edge, 50% = center of available space
     */
    private static int calculateY(int screenHeight, int panelHeight, String anchorV, int offsetPercent) {
        switch (anchorV) {
            case "TOP":
                // Offset moves from top edge (0%) toward center (50%)
                int maxOffsetTop = (screenHeight - panelHeight) / 2;
                return (maxOffsetTop * offsetPercent) / 100;
            case "BOTTOM":
                // Offset moves from bottom edge (0%) toward center (50%)
                int maxOffsetBottom = (screenHeight - panelHeight) / 2;
                return screenHeight - panelHeight - (maxOffsetBottom * offsetPercent) / 100;
            case "VCENTER":
            default:
                // Centered vertically, offset has no effect
                return (screenHeight - panelHeight) / 2;
        }
    }
}
