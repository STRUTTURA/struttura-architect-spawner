package it.magius.struttura.architect.client.gui.panel;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.gui.EditBoxHelper;
import it.magius.struttura.architect.client.gui.PanelManager;
import it.magius.struttura.architect.network.GuiActionPacket;
import it.magius.struttura.architect.network.SelectionKeyPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;

/**
 * Panel showing editing information and controls.
 * Visible only when player is in editing mode.
 */
@Environment(EnvType.CLIENT)
public class EditingPanel {

    private static final int WIDTH = 180;
    private static final int PADDING = 5;
    private static final int BUTTON_HEIGHT = 16;
    private static final int LINE_HEIGHT = 11;

    private int x, y;
    private int height;
    private int hoveredButton = -1;

    // EditBox helpers for editable fields
    private EditBoxHelper rdnsBox;
    private EditBoxHelper nameBox;
    private boolean editBoxesInitialized = false;

    // Track which field was being edited to restore value on escape
    private String originalRdns = "";
    private String originalName = "";

    public int getWidth() {
        return WIDTH;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Initialize EditBox helpers. Must be called after Minecraft client is ready.
     */
    private void initEditBoxes(Font font) {
        if (editBoxesInitialized) return;

        // RDNS box - lowercase only ID characters
        rdnsBox = new EditBoxHelper(font, 0, 0, WIDTH - PADDING * 2, LINE_HEIGHT,
            "namespace.category.name",
            s -> s.chars().allMatch(c -> (c >= 'a' && c <= 'z') || Character.isDigit(c) || c == '.' || c == '_'),
            null);

        // Name box - free text
        nameBox = EditBoxHelper.createTextBox(font, 0, 0, WIDTH - PADDING * 2, LINE_HEIGHT, "(not set)");

        editBoxesInitialized = true;
    }

    public void render(GuiGraphics graphics, int x, int y, int mouseX, int mouseY, float tickDelta) {
        this.x = x;
        this.y = y;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PanelManager pm = PanelManager.getInstance();

        // Initialize EditBoxes if needed
        initEditBoxes(font);

        // Calculate height based on content (compact stats: 4 lines instead of 5)
        height = PADDING * 2 + LINE_HEIGHT * 7 + BUTTON_HEIGHT * 4 + PADDING * 5;

        // Draw background
        graphics.fill(x, y, x + WIDTH, y + height, 0xE0000000);
        graphics.renderOutline(x, y, WIDTH, height, 0xFF404040);

        int currentY = y + PADDING;
        hoveredButton = -1;

        // Title: EDITING
        graphics.drawString(font, "EDITING", x + PADDING, currentY, 0xFFFFAA00, true);
        currentY += LINE_HEIGHT + 2;

        // Construction ID (editable)
        String idLabel = "ID: ";
        int idLabelWidth = font.width(idLabel);
        graphics.drawString(font, idLabel, x + PADDING, currentY, 0xFF808080, false);

        // ID field using EditBoxHelper
        int fieldX = x + PADDING + idLabelWidth;
        int fieldWidth = WIDTH - PADDING * 2 - idLabelWidth;

        // Update EditBox with current value if not focused (sync from server)
        if (!rdnsBox.isFocused()) {
            String currentId = pm.getEditingConstructionId();
            if (!rdnsBox.getValue().equals(currentId)) {
                rdnsBox.setValue(currentId);
            }
        }

        rdnsBox.setPosition(fieldX, currentY - 1);
        rdnsBox.setWidth(fieldWidth);
        rdnsBox.render(graphics, mouseX, mouseY, tickDelta);
        currentY += LINE_HEIGHT + 2;

        // Name (editable)
        String nameLabel = "Name: ";
        int nameLabelWidth = font.width(nameLabel);
        graphics.drawString(font, nameLabel, x + PADDING, currentY, 0xFF808080, false);

        // Name field using EditBoxHelper
        fieldX = x + PADDING + nameLabelWidth;
        fieldWidth = WIDTH - PADDING * 2 - nameLabelWidth;

        // Update EditBox with current value if not focused (sync from server)
        if (!nameBox.isFocused()) {
            String currentTitle = pm.getEditingTitle();
            if (!nameBox.getValue().equals(currentTitle)) {
                nameBox.setValue(currentTitle);
            }
        }

        nameBox.setPosition(fieldX, currentY - 1);
        nameBox.setWidth(fieldWidth);
        nameBox.render(graphics, mouseX, mouseY, tickDelta);
        currentY += LINE_HEIGHT + PADDING;

        // Stats (compact layout)
        graphics.drawString(font, "Stats:", x + PADDING, currentY, 0xFF808080, false);
        currentY += LINE_HEIGHT;

        // Row 1: Blocks | Solid
        String row1 = "  Blocks: " + pm.getBlockCount() + " | Solid: " + pm.getSolidBlockCount();
        graphics.drawString(font, row1, x + PADDING, currentY, 0xFFCCCCCC, false);
        currentY += LINE_HEIGHT;

        // Row 2: Air | Entities
        String row2 = "  Air: " + pm.getAirBlockCount() + " | Entities: " + pm.getEntityCount();
        graphics.drawString(font, row2, x + PADDING, currentY, 0xFFCCCCCC, false);
        currentY += LINE_HEIGHT;

        // Row 3: Bounds
        graphics.drawString(font, "  Bounds: " + pm.getBounds(), x + PADDING, currentY, 0xFFCCCCCC, false);
        currentY += LINE_HEIGHT + PADDING;

        // Mode toggle button
        String modeText = "Mode: " + pm.getMode();
        int modeBtnY = currentY;
        boolean modeHovered = mouseX >= x + PADDING && mouseX < x + WIDTH - PADDING &&
                             mouseY >= modeBtnY && mouseY < modeBtnY + BUTTON_HEIGHT;

        int modeBgColor = modeHovered ? 0xFF505050 : 0xFF303030;
        int modeAccentColor = pm.getMode().equals("ADD") ? 0xFF60A060 : 0xFFA06060;
        graphics.fill(x + PADDING, modeBtnY, x + WIDTH - PADDING, modeBtnY + BUTTON_HEIGHT, modeBgColor);
        graphics.renderOutline(x + PADDING, modeBtnY, WIDTH - PADDING * 2, BUTTON_HEIGHT, modeAccentColor);
        int modeTextWidth = font.width(modeText);
        graphics.drawString(font, modeText, x + (WIDTH - modeTextWidth) / 2, modeBtnY + 4,
                           pm.getMode().equals("ADD") ? 0xFF88FF88 : 0xFFFF8888, false);
        currentY += BUTTON_HEIGHT + PADDING;

        // Selection section
        graphics.drawString(font, "Selection:", x + PADDING, currentY, 0xFF808080, false);
        currentY += LINE_HEIGHT;

        // Selection buttons row 1: POS1, POS2, CLEAR
        String[] selBtns1 = {"POS1", "POS2", "CLEAR"};
        int selBtnWidth = (WIDTH - PADDING * 2 - 4) / 3;
        for (int i = 0; i < selBtns1.length; i++) {
            int btnX = x + PADDING + i * (selBtnWidth + 2);
            int btnY = currentY;

            boolean btnHovered = mouseX >= btnX && mouseX < btnX + selBtnWidth &&
                                mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT;

            int bgColor = btnHovered ? 0xFF505050 : 0xFF303030;
            graphics.fill(btnX, btnY, btnX + selBtnWidth, btnY + BUTTON_HEIGHT, bgColor);
            graphics.renderOutline(btnX, btnY, selBtnWidth, BUTTON_HEIGHT, 0xFF606060);

            int textWidth = font.width(selBtns1[i]);
            graphics.drawString(font, selBtns1[i], btnX + (selBtnWidth - textWidth) / 2, btnY + 4, 0xFFFFFFFF, false);
        }
        currentY += BUTTON_HEIGHT + 2;

        // Selection buttons row 2: APPLY, APPLY ALL
        String[] selBtns2 = {"APPLY", "APPLY ALL"};
        int selBtn2Width = (WIDTH - PADDING * 2 - 2) / 2;
        for (int i = 0; i < selBtns2.length; i++) {
            int btnX = x + PADDING + i * (selBtn2Width + 2);
            int btnY = currentY;

            boolean btnHovered = mouseX >= btnX && mouseX < btnX + selBtn2Width &&
                                mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT;

            int bgColor = btnHovered ? 0xFF505050 : 0xFF303030;
            graphics.fill(btnX, btnY, btnX + selBtn2Width, btnY + BUTTON_HEIGHT, bgColor);
            graphics.renderOutline(btnX, btnY, selBtn2Width, BUTTON_HEIGHT, 0xFF606060);

            int textWidth = font.width(selBtns2[i]);
            graphics.drawString(font, selBtns2[i], btnX + (selBtn2Width - textWidth) / 2, btnY + 4, 0xFFFFFFFF, false);
        }
        currentY += BUTTON_HEIGHT + PADDING;

        // Exit button
        int exitY = currentY;
        boolean exitHovered = mouseX >= x + PADDING && mouseX < x + WIDTH - PADDING &&
                             mouseY >= exitY && mouseY < exitY + BUTTON_HEIGHT;

        int exitBgColor = exitHovered ? 0xFF604040 : 0xFF403030;
        graphics.fill(x + PADDING, exitY, x + WIDTH - PADDING, exitY + BUTTON_HEIGHT, exitBgColor);
        graphics.renderOutline(x + PADDING, exitY, WIDTH - PADDING * 2, BUTTON_HEIGHT, 0xFF806060);

        String exitText = "EXIT EDITING";
        int exitTextWidth = font.width(exitText);
        graphics.drawString(font, exitText, x + (WIDTH - exitTextWidth) / 2, exitY + 4, 0xFFFFAAAA, false);

        // Update actual height
        height = currentY + BUTTON_HEIGHT + PADDING - y;
    }

    private String truncate(String text, int maxWidth, Font font) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        int availableWidth = maxWidth - ellipsisWidth;

        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (font.width(sb.toString() + c) > availableWidth) {
                break;
            }
            sb.append(c);
        }
        return sb + ellipsis;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        // Check if click is within panel bounds
        if (mouseX < x || mouseX > x + WIDTH || mouseY < y || mouseY > y + height) {
            // Click outside - cancel editing and restore values
            if (rdnsBox != null && rdnsBox.isFocused()) {
                rdnsBox.setValue(originalRdns);
                rdnsBox.setFocused(false);
            }
            if (nameBox != null && nameBox.isFocused()) {
                nameBox.setValue(originalName);
                nameBox.setFocused(false);
            }
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PanelManager pm = PanelManager.getInstance();

        int currentY = y + PADDING + LINE_HEIGHT + 2;

        // ID field click
        if (rdnsBox.mouseClicked(mouseX, mouseY, button)) {
            // Save original value for cancel
            originalRdns = pm.getEditingConstructionId();
            nameBox.setFocused(false);
            return true;
        }
        currentY += LINE_HEIGHT + 2;

        // Name field click
        if (nameBox.mouseClicked(mouseX, mouseY, button)) {
            // Save original value for cancel
            originalName = pm.getEditingTitle();
            rdnsBox.setFocused(false);
            return true;
        }

        // Cancel field editing if clicking elsewhere in panel
        if (rdnsBox.isFocused()) {
            rdnsBox.setValue(originalRdns);
            rdnsBox.setFocused(false);
        }
        if (nameBox.isFocused()) {
            nameBox.setValue(originalName);
            nameBox.setFocused(false);
        }

        // Skip stats section (Stats label + 3 data rows)
        currentY += LINE_HEIGHT + PADDING + LINE_HEIGHT * 4 + PADDING;

        // Mode button
        if (mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT &&
            mouseX >= x + PADDING && mouseX < x + WIDTH - PADDING) {
            // Toggle mode
            sendSelectionAction(SelectionKeyPacket.Action.MODE_TOGGLE);
            return true;
        }
        currentY += BUTTON_HEIGHT + PADDING + LINE_HEIGHT;

        // Selection buttons row 1
        String[] selActions1 = {"pos1", "pos2", "clear"};
        int selBtnWidth = (WIDTH - PADDING * 2 - 4) / 3;
        for (int i = 0; i < selActions1.length; i++) {
            int btnX = x + PADDING + i * (selBtnWidth + 2);
            if (mouseX >= btnX && mouseX < btnX + selBtnWidth &&
                mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT) {
                switch (selActions1[i]) {
                    case "pos1" -> sendSelectionAction(SelectionKeyPacket.Action.POS1);
                    case "pos2" -> sendSelectionAction(SelectionKeyPacket.Action.POS2);
                    case "clear" -> sendSelectionAction(SelectionKeyPacket.Action.CLEAR);
                }
                return true;
            }
        }
        currentY += BUTTON_HEIGHT + 2;

        // Selection buttons row 2
        int selBtn2Width = (WIDTH - PADDING * 2 - 2) / 2;
        if (mouseX >= x + PADDING && mouseX < x + PADDING + selBtn2Width &&
            mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT) {
            sendSelectionAction(SelectionKeyPacket.Action.APPLY);
            return true;
        }
        if (mouseX >= x + PADDING + selBtn2Width + 2 && mouseX < x + WIDTH - PADDING &&
            mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT) {
            sendSelectionAction(SelectionKeyPacket.Action.APPLYALL);
            return true;
        }
        currentY += BUTTON_HEIGHT + PADDING;

        // Exit button
        if (mouseY >= currentY && mouseY < currentY + BUTTON_HEIGHT &&
            mouseX >= x + PADDING && mouseX < x + WIDTH - PADDING) {
            ClientPlayNetworking.send(new GuiActionPacket("exit", "", ""));
            return true;
        }

        return true; // Consume click within panel
    }

    private void sendSelectionAction(SelectionKeyPacket.Action action) {
        ClientPlayNetworking.send(new SelectionKeyPacket(action));
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Handle RDNS box
        if (rdnsBox != null && rdnsBox.isFocused()) {
            // Escape - cancel editing and restore
            if (keyCode == 256) {
                rdnsBox.setValue(originalRdns);
                rdnsBox.setFocused(false);
                return true;
            }
            // Enter - confirm editing
            if (keyCode == 257) {
                ClientPlayNetworking.send(new GuiActionPacket("rename", rdnsBox.getValue(), ""));
                rdnsBox.setFocused(false);
                return true;
            }
            return rdnsBox.keyPressed(keyCode, scanCode, modifiers);
        }

        // Handle Name box
        if (nameBox != null && nameBox.isFocused()) {
            // Escape - cancel editing and restore
            if (keyCode == 256) {
                nameBox.setValue(originalName);
                nameBox.setFocused(false);
                return true;
            }
            // Enter - confirm editing
            if (keyCode == 257) {
                ClientPlayNetworking.send(new GuiActionPacket("title", "", nameBox.getValue()));
                nameBox.setFocused(false);
                return true;
            }
            return nameBox.keyPressed(keyCode, scanCode, modifiers);
        }

        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        // Handle RDNS box - force lowercase
        if (rdnsBox != null && rdnsBox.isFocused()) {
            return rdnsBox.charTyped(Character.toLowerCase(chr), modifiers);
        }

        // Handle Name box
        if (nameBox != null && nameBox.isFocused()) {
            return nameBox.charTyped(chr, modifiers);
        }

        return false;
    }
}
