package it.magius.struttura.architect.client.gui;

import it.magius.struttura.architect.client.gui.panel.EditingPanel;
import it.magius.struttura.architect.client.gui.panel.MainPanel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Manages the state of GUI panels.
 * Coordinates between the HUD icon, main panel, and editing panel.
 */
@Environment(EnvType.CLIENT)
public class PanelManager {

    private static final PanelManager INSTANCE = new PanelManager();

    private boolean mainPanelOpen = false;
    private boolean isEditing = false;

    // Panel instances
    private MainPanel mainPanel;
    private EditingPanel editingPanel;

    // Current editing info (synced from server)
    private String editingConstructionId = "";
    private String editingTitle = "";
    private int blockCount = 0;
    private int solidBlockCount = 0;
    private int airBlockCount = 0;
    private String bounds = "";
    private String mode = "ADD";

    private PanelManager() {
    }

    public static PanelManager getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize panels. Called once on client init.
     */
    public void init() {
        mainPanel = new MainPanel();
        editingPanel = new EditingPanel();
    }

    /**
     * Toggle the main panel open/closed.
     */
    public void toggleMainPanel() {
        mainPanelOpen = !mainPanelOpen;
    }

    /**
     * Open the main panel.
     */
    public void openMainPanel() {
        mainPanelOpen = true;
    }

    /**
     * Close the main panel.
     */
    public void closeMainPanel() {
        mainPanelOpen = false;
    }

    public boolean isMainPanelOpen() {
        return mainPanelOpen;
    }

    public boolean isEditing() {
        return isEditing;
    }

    public void setEditing(boolean editing) {
        this.isEditing = editing;
    }

    /**
     * Update editing info from server packet.
     */
    public void updateEditingInfo(String constructionId, String title, int blockCount,
                                   int solidBlockCount, int airBlockCount, String bounds, String mode) {
        this.editingConstructionId = constructionId;
        this.editingTitle = title;
        this.blockCount = blockCount;
        this.solidBlockCount = solidBlockCount;
        this.airBlockCount = airBlockCount;
        this.bounds = bounds;
        this.mode = mode;
        this.isEditing = true;
    }

    /**
     * Clear editing state (when exiting editing mode).
     */
    public void clearEditingInfo() {
        this.editingConstructionId = "";
        this.editingTitle = "";
        this.blockCount = 0;
        this.solidBlockCount = 0;
        this.airBlockCount = 0;
        this.bounds = "";
        this.mode = "ADD";
        this.isEditing = false;
    }

    // Getters for editing info
    public String getEditingConstructionId() {
        return editingConstructionId;
    }

    public String getEditingTitle() {
        return editingTitle;
    }

    public int getBlockCount() {
        return blockCount;
    }

    public int getSolidBlockCount() {
        return solidBlockCount;
    }

    public int getAirBlockCount() {
        return airBlockCount;
    }

    public String getBounds() {
        return bounds;
    }

    public String getMode() {
        return mode;
    }

    public MainPanel getMainPanel() {
        return mainPanel;
    }

    public EditingPanel getEditingPanel() {
        return editingPanel;
    }

    /**
     * Render all visible panels.
     */
    public void render(GuiGraphics graphics, float tickDelta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            // Don't render HUD panels when a screen is open
            return;
        }

        int mouseX = (int) (mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth());
        int mouseY = (int) (mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight());

        // Calculate positions based on state
        int iconX = 5;
        int iconY = 5;
        int iconSize = 16;

        // Render editing panel if in editing mode
        if (isEditing && editingPanel != null) {
            int editingPanelX;
            if (mainPanelOpen && mainPanel != null) {
                // Place editing panel to the right of main panel
                editingPanelX = iconX + mainPanel.getWidth() + 10;
            } else {
                // Place editing panel next to icon
                editingPanelX = iconX + iconSize + 5;
            }
            editingPanel.render(graphics, editingPanelX, iconY, mouseX, mouseY, tickDelta);
        }

        // Render main panel if open
        if (mainPanelOpen && mainPanel != null) {
            mainPanel.render(graphics, iconX, iconY + iconSize + 5, mouseX, mouseY, tickDelta);
        }
    }

    /**
     * Handle mouse click.
     * @return true if click was consumed by a panel
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Check editing panel first (if visible)
        if (isEditing && editingPanel != null) {
            if (editingPanel.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        // Check main panel
        if (mainPanelOpen && mainPanel != null) {
            if (mainPanel.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Handle mouse scroll.
     * @return true if scroll was consumed by a panel
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mainPanelOpen && mainPanel != null) {
            if (mainPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handle key press.
     * @return true if key was consumed by a panel
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (mainPanelOpen && mainPanel != null) {
            if (mainPanel.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handle character typed.
     * @return true if char was consumed by a panel
     */
    public boolean charTyped(char chr, int modifiers) {
        if (mainPanelOpen && mainPanel != null) {
            if (mainPanel.charTyped(chr, modifiers)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reset state (called on disconnect).
     */
    public void reset() {
        mainPanelOpen = false;
        clearEditingInfo();
        if (mainPanel != null) {
            mainPanel.reset();
        }
    }
}
