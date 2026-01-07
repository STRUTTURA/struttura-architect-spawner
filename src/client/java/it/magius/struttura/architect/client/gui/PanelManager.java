package it.magius.struttura.architect.client.gui;

import it.magius.struttura.architect.client.gui.panel.EditingPanel;
import it.magius.struttura.architect.client.gui.panel.MainPanel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

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
    private int entityCount = 0;
    private int mobCount = 0;
    private String bounds = "";
    private String mode = "ADD";
    private String shortDesc = "";

    // Room editing info
    private boolean inRoom = false;
    private String currentRoomId = "";
    private String currentRoomName = "";
    private int roomCount = 0;
    private int roomBlockChanges = 0;
    private List<RoomInfo> roomList = new ArrayList<>();

    // Block list for editing (synced from server)
    private List<BlockInfo> blockList = new ArrayList<>();

    // Entity list for editing (synced from server)
    private List<EntityInfo> entityList = new ArrayList<>();

    // Translations (all languages)
    private java.util.Map<String, String> allTitles = new java.util.HashMap<>();
    private java.util.Map<String, String> allShortDescriptions = new java.util.HashMap<>();

    /**
     * Info about a block type in the construction.
     */
    public record BlockInfo(String blockId, String displayName, int count) {}

    /**
     * Info about an entity type in the construction (grouped by type with count).
     */
    public record EntityInfo(String entityType, String displayName, int count) {}

    /**
     * Info about a room in the construction.
     */
    public record RoomInfo(String id, String name, int blockCount, int entityCount) {}

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
                                   int solidBlockCount, int airBlockCount, int entityCount,
                                   int mobCount, String bounds, String mode, String shortDesc,
                                   boolean inRoom, String currentRoomId, String currentRoomName,
                                   int roomCount, int roomBlockChanges, List<RoomInfo> roomList) {
        this.editingConstructionId = constructionId;
        this.editingTitle = title;
        this.blockCount = blockCount;
        this.solidBlockCount = solidBlockCount;
        this.airBlockCount = airBlockCount;
        this.entityCount = entityCount;
        this.mobCount = mobCount;
        this.bounds = bounds;
        this.mode = mode;
        this.shortDesc = shortDesc;
        this.inRoom = inRoom;
        this.currentRoomId = currentRoomId;
        this.currentRoomName = currentRoomName;
        this.roomCount = roomCount;
        this.roomBlockChanges = roomBlockChanges;
        this.roomList = roomList != null ? new ArrayList<>(roomList) : new ArrayList<>();
        this.isEditing = true;
        // Update EditingPanel with shortDesc
        if (editingPanel != null) {
            editingPanel.setShortDescText(shortDesc);
        }
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
        this.entityCount = 0;
        this.mobCount = 0;
        this.bounds = "";
        this.mode = "ADD";
        this.shortDesc = "";
        this.inRoom = false;
        this.currentRoomId = "";
        this.currentRoomName = "";
        this.roomCount = 0;
        this.roomBlockChanges = 0;
        this.roomList.clear();
        this.isEditing = false;
        this.blockList.clear();
        this.entityList.clear();
        this.allTitles.clear();
        this.allShortDescriptions.clear();
        // Clear EditingPanel
        if (editingPanel != null) {
            editingPanel.setShortDescText("");
            editingPanel.clearTranslations();
        }
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

    public int getEntityCount() {
        return entityCount;
    }

    public int getMobCount() {
        return mobCount;
    }

    public String getBounds() {
        return bounds;
    }

    public String getMode() {
        return mode;
    }

    public String getShortDesc() {
        return shortDesc;
    }

    // Room getters
    public boolean isInRoom() {
        return inRoom;
    }

    public String getCurrentRoomId() {
        return currentRoomId;
    }

    public String getCurrentRoomName() {
        return currentRoomName;
    }

    public int getRoomCount() {
        return roomCount;
    }

    public int getRoomBlockChanges() {
        return roomBlockChanges;
    }

    /**
     * Get the list of rooms in the current construction.
     */
    public List<RoomInfo> getRoomList() {
        return roomList;
    }

    /**
     * Get the list of block types in the current construction.
     */
    public List<BlockInfo> getBlockList() {
        return blockList;
    }

    /**
     * Update the block list from server packet.
     * Blocks are sorted alphabetically by display name.
     */
    public void updateBlockList(List<BlockInfo> blocks) {
        this.blockList = new ArrayList<>(blocks);
        // Sort alphabetically by display name
        this.blockList.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
    }

    /**
     * Clear the block list.
     */
    public void clearBlockList() {
        this.blockList.clear();
    }

    /**
     * Get the list of entities in the current construction/room.
     */
    public List<EntityInfo> getEntityList() {
        return entityList;
    }

    /**
     * Update the entity list from server packet.
     * Entities are sorted alphabetically by display name.
     */
    public void updateEntityList(List<EntityInfo> entities) {
        this.entityList = new ArrayList<>(entities);
        // Sort alphabetically by display name
        this.entityList.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
    }

    /**
     * Clear the entity list.
     */
    public void clearEntityList() {
        this.entityList.clear();
    }

    /**
     * Update translations from server packet.
     */
    public void updateTranslations(java.util.Map<String, String> titles, java.util.Map<String, String> shortDescriptions) {
        this.allTitles = new java.util.HashMap<>(titles);
        this.allShortDescriptions = new java.util.HashMap<>(shortDescriptions);
        // Update EditingPanel
        if (editingPanel != null) {
            editingPanel.updateTranslations(titles, shortDescriptions);
        }
    }

    /**
     * Get all titles (langId -> title).
     */
    public java.util.Map<String, String> getAllTitles() {
        return allTitles;
    }

    /**
     * Get all short descriptions (langId -> shortDesc).
     */
    public java.util.Map<String, String> getAllShortDescriptions() {
        return allShortDescriptions;
    }

    /**
     * Get title for specific language.
     */
    public String getTitle(String langId) {
        return allTitles.getOrDefault(langId, "");
    }

    /**
     * Get short description for specific language.
     */
    public String getShortDescription(String langId) {
        return allShortDescriptions.getOrDefault(langId, "");
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
