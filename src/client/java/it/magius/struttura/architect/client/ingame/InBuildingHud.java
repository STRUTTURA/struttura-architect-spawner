package it.magius.struttura.architect.client.ingame;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * HUD overlay that shows building information when player is inside a spawned building.
 * Displays at the top center of the screen (similar to boss bar area).
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

        // Position at top center (below boss bar area)
        int panelX = (screenWidth - panelWidth) / 2;
        int panelY = 32;  // Below boss bar

        // Draw background
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BG_COLOR);
        graphics.renderOutline(panelX, panelY, panelWidth, panelHeight, PANEL_BORDER_COLOR);

        // Draw text
        int textY = panelY + PADDING;

        // Building name (centered, yellow)
        graphics.drawCenteredString(font, line1, screenWidth / 2, textY, BUILDING_NAME_COLOR);
        textY += LINE_HEIGHT;

        // Author (centered, blue)
        if (!line2.isEmpty()) {
            graphics.drawCenteredString(font, line2, screenWidth / 2, textY, AUTHOR_COLOR);
        }

        return true;
    }
}
