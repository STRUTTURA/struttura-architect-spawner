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
 * Opens when right-clicking with the Construction Hammer without targeting a block.
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

        // Calculate panel positions (same as render)
        int mainPanelWidth = mainPanel.getWidth();
        int editingPanelWidth = isEditing ? editingPanel.getWidth() : 0;
        int totalWidth = mainPanelWidth + (isEditing ? PANEL_SPACING + editingPanelWidth : 0);

        int startX = (this.width - totalWidth) / 2;
        int panelY = (this.height - mainPanel.getHeight()) / 2;

        // Check NEW button first (it's partially outside panel bounds)
        // The button is at top-right corner, straddling the panel edge
        int newBtnSize = 13;
        int newBtnX = startX + mainPanelWidth - newBtnSize / 2 - 2;
        int newBtnY = panelY - newBtnSize / 2 + 2;
        if (mouseX >= newBtnX && mouseX < newBtnX + newBtnSize &&
            mouseY >= newBtnY && mouseY < newBtnY + newBtnSize) {
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
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        int scanCode = event.scancode();
        int modifiers = event.modifiers();

        // Let panels handle key input first
        if (mainPanel.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        PanelManager pm = PanelManager.getInstance();
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

        if (mainPanel.charTyped(chr, modifiers)) {
            return true;
        }

        PanelManager pm = PanelManager.getInstance();
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
