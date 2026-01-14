package it.magius.struttura.architect.client.gui;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.gui.panel.MainPanel;
import it.magius.struttura.architect.client.gui.panel.EditingPanel;
import it.magius.struttura.architect.network.GuiActionPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Modal screen for STRUTTURA GUI.
 * Opens when right-clicking with the Struttura Hammer without targeting a block.
 * Contains both the main construction list panel and editing controls (when in editing mode).
 */
@Environment(EnvType.CLIENT)
public class StrutturaScreen extends Screen {

    private static final int PANEL_SPACING = 10;

    private final MainPanel mainPanel;
    private final EditingPanel editingPanel;

    public StrutturaScreen() {
        super(Component.literal("STRUTTURA"));
        this.mainPanel = PanelManager.getInstance().getMainPanel();
        this.editingPanel = PanelManager.getInstance().getEditingPanel();
    }

    @Override
    protected void init() {
        super.init();
        // Request fresh construction list from server
        ClientPlayNetworking.send(new GuiActionPacket("request_list", "", ""));
        Architect.LOGGER.debug("StrutturaScreen opened");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Don't call renderBackground - it causes blur issues in 1.21.9+
        // Just draw a simple dark overlay
        graphics.fill(0, 0, this.width, this.height, 0x80000000);

        PanelManager pm = PanelManager.getInstance();
        boolean isEditing = pm.isEditing();

        // Calculate panel positions
        int mainPanelWidth = mainPanel.getWidth();
        int editingPanelWidth = isEditing ? editingPanel.getWidth() : 0;
        int totalWidth = mainPanelWidth + (isEditing ? PANEL_SPACING + editingPanelWidth : 0);

        // Center panels horizontally
        int startX = (this.width - totalWidth) / 2;
        int panelY = (this.height - mainPanel.getHeight()) / 2;

        // Render main panel on the left
        mainPanel.render(graphics, startX, panelY, mouseX, mouseY, partialTick);

        // Render editing panel on the right (if in editing mode)
        if (isEditing) {
            int editingX = startX + mainPanelWidth + PANEL_SPACING;
            editingPanel.render(graphics, editingX, panelY, mouseX, mouseY, partialTick);
        }

        // Render modal on top of everything
        mainPanel.renderModal(graphics, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) {
            return true;
        }

        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        // Handle modal clicks first - modal captures all clicks when open
        if (mainPanel.isModalOpen()) {
            return mainPanel.mouseClicked(mouseX, mouseY, button);
        }

        PanelManager pm = PanelManager.getInstance();
        boolean isEditing = pm.isEditing();

        // Handle new room modal clicks (captures all clicks when open)
        if (isEditing && editingPanel.isNewRoomModalOpen()) {
            return editingPanel.mouseClicked(mouseX, mouseY, button);
        }

        // Handle short desc modal clicks (captures all clicks when open)
        if (isEditing && editingPanel.isShortDescModalOpen()) {
            return editingPanel.mouseClicked(mouseX, mouseY, button);
        }

        // Handle editing panel dropdown clicks FIRST (dropdown opens upward and may overlap other panels)
        if (isEditing && editingPanel.isDropdownOpenAt(mouseX, mouseY)) {
            return editingPanel.handleDropdownClick(mouseX, mouseY, button);
        }

        // Handle language dropdown clicks (dropdown opens downward)
        if (isEditing && editingPanel.isLangDropdownOpen()) {
            // Let EditingPanel handle it - it will close dropdown on click outside
            return editingPanel.mouseClicked(mouseX, mouseY, button);
        }

        // Calculate panel positions (same as render)
        int mainPanelWidth = mainPanel.getWidth();
        int editingPanelWidth = isEditing ? editingPanel.getWidth() : 0;
        int totalWidth = mainPanelWidth + (isEditing ? PANEL_SPACING + editingPanelWidth : 0);

        int startX = (this.width - totalWidth) / 2;
        int panelY = (this.height - mainPanel.getHeight()) / 2;

        // Check NEW and PULL buttons first (they're partially outside panel bounds)
        // The buttons are at top-right corner, straddling the panel edge
        int btnSize = 13;
        int btnSpacing = 2;

        // NEW button (rightmost)
        int newBtnX = startX + mainPanelWidth - btnSize / 2 - 2;
        int newBtnY = panelY - btnSize / 2 + 2;
        if (mouseX >= newBtnX && mouseX < newBtnX + btnSize &&
            mouseY >= newBtnY && mouseY < newBtnY + btnSize) {
            if (mainPanel.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        // PULL button (left of NEW)
        int pullBtnX = newBtnX - btnSize - btnSpacing;
        int pullBtnY = newBtnY;
        if (mouseX >= pullBtnX && mouseX < pullBtnX + btnSize &&
            mouseY >= pullBtnY && mouseY < pullBtnY + btnSize) {
            if (mainPanel.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        // Check if click is within main panel bounds
        if (mouseX >= startX && mouseX < startX + mainPanelWidth &&
            mouseY >= panelY && mouseY < panelY + mainPanel.getHeight()) {
            if (mainPanel.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }

        // Check editing panel if visible
        if (isEditing) {
            int editingX = startX + mainPanelWidth + PANEL_SPACING;
            if (mouseX >= editingX && mouseX < editingX + editingPanelWidth &&
                mouseY >= panelY && mouseY < panelY + editingPanel.getHeight()) {
                if (editingPanel.mouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
        }

        return super.mouseClicked(event, consumed);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mainPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }

        // Handle scroll for editing panel dropdown
        PanelManager pm = PanelManager.getInstance();
        if (pm.isEditing() && editingPanel.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        // Handle drag for editing panel scrollbar
        PanelManager pm = PanelManager.getInstance();
        if (pm.isEditing() && editingPanel.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }

        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        // Handle release for editing panel scrollbar
        PanelManager pm = PanelManager.getInstance();
        if (pm.isEditing() && editingPanel.mouseReleased(mouseX, mouseY, button)) {
            return true;
        }

        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        int scanCode = event.scancode();
        int modifiers = event.modifiers();

        PanelManager pm = PanelManager.getInstance();

        // Handle new room modal keys first (modal captures all keys when open)
        if (pm.isEditing() && editingPanel.isNewRoomModalOpen()) {
            return editingPanel.keyPressed(keyCode, scanCode, modifiers);
        }

        // Handle short desc modal keys first (modal captures all keys when open)
        if (pm.isEditing() && editingPanel.isShortDescModalOpen()) {
            return editingPanel.keyPressed(keyCode, scanCode, modifiers);
        }

        // Let panels handle key input first
        if (mainPanel.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        if (pm.isEditing() && editingPanel.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        // Escape closes the screen
        if (keyCode == 256) {
            this.onClose();
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char chr = (char) event.codepoint();
        int modifiers = event.modifiers();

        PanelManager pm = PanelManager.getInstance();

        // Handle new room modal chars first (modal captures all chars when open)
        if (pm.isEditing() && editingPanel.isNewRoomModalOpen()) {
            return editingPanel.charTyped(chr, modifiers);
        }

        // Handle short desc modal chars first (modal captures all chars when open)
        if (pm.isEditing() && editingPanel.isShortDescModalOpen()) {
            return editingPanel.charTyped(chr, modifiers);
        }

        if (mainPanel.charTyped(chr, modifiers)) {
            return true;
        }

        if (pm.isEditing() && editingPanel.charTyped(chr, modifiers)) {
            return true;
        }

        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        // Reset panel state when closing
        mainPanel.reset();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        // Don't pause the game when this screen is open
        return false;
    }
}
