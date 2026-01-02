package it.magius.struttura.architect.client.gui.panel;

import it.magius.struttura.architect.Architect;
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

    // Editable fields state
    private boolean editingRdns = false;
    private boolean editingName = false;
    private String rdnsEditText = "";
    private String nameEditText = "";

    public int getWidth() {
        return WIDTH;
    }

    public int getHeight() {
        return height;
    }

    public void render(GuiGraphics graphics, int x, int y, int mouseX, int mouseY, float tickDelta) {
        this.x = x;
        this.y = y;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PanelManager pm = PanelManager.getInstance();

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
        String idValue = pm.getEditingConstructionId();
        if (editingRdns) {
            idValue = rdnsEditText + "_";
        }
        int idLabelWidth = font.width(idLabel);

        graphics.drawString(font, idLabel, x + PADDING, currentY, 0xFF808080, false);

        // ID field background
        int fieldX = x + PADDING + idLabelWidth;
        int fieldWidth = WIDTH - PADDING * 2 - idLabelWidth;
        boolean idHovered = mouseX >= fieldX && mouseX < fieldX + fieldWidth &&
                           mouseY >= currentY - 1 && mouseY < currentY + LINE_HEIGHT;
        int idBgColor = editingRdns ? 0xFF404040 : (idHovered ? 0xFF303030 : 0x00000000);
        if (idBgColor != 0) {
            graphics.fill(fieldX - 1, currentY - 1, fieldX + fieldWidth, currentY + LINE_HEIGHT - 1, idBgColor);
        }
        graphics.drawString(font, truncate(idValue, fieldWidth - 2, font), fieldX, currentY, 0xFFFFFFFF, false);
        currentY += LINE_HEIGHT + 2;

        // Name (editable)
        String nameLabel = "Name: ";
        String nameValue = pm.getEditingTitle();
        if (nameValue.isEmpty()) {
            nameValue = "(not set)";
        }
        if (editingName) {
            nameValue = nameEditText + "_";
        }
        int nameLabelWidth = font.width(nameLabel);

        graphics.drawString(font, nameLabel, x + PADDING, currentY, 0xFF808080, false);

        fieldX = x + PADDING + nameLabelWidth;
        fieldWidth = WIDTH - PADDING * 2 - nameLabelWidth;
        boolean nameHovered = mouseX >= fieldX && mouseX < fieldX + fieldWidth &&
                             mouseY >= currentY - 1 && mouseY < currentY + LINE_HEIGHT;
        int nameBgColor = editingName ? 0xFF404040 : (nameHovered ? 0xFF303030 : 0x00000000);
        if (nameBgColor != 0) {
            graphics.fill(fieldX - 1, currentY - 1, fieldX + fieldWidth, currentY + LINE_HEIGHT - 1, nameBgColor);
        }
        int nameColor = pm.getEditingTitle().isEmpty() && !editingName ? 0xFF606060 : 0xFFFFFFFF;
        graphics.drawString(font, truncate(nameValue, fieldWidth - 2, font), fieldX, currentY, nameColor, false);
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
            // Click outside - cancel editing
            editingRdns = false;
            editingName = false;
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PanelManager pm = PanelManager.getInstance();

        int currentY = y + PADDING + LINE_HEIGHT + 2;

        // ID field click
        String idLabel = "ID: ";
        int idLabelWidth = font.width(idLabel);
        int fieldX = x + PADDING + idLabelWidth;
        int fieldWidth = WIDTH - PADDING * 2 - idLabelWidth;

        if (mouseX >= fieldX && mouseX < fieldX + fieldWidth &&
            mouseY >= currentY - 1 && mouseY < currentY + LINE_HEIGHT) {
            editingRdns = true;
            editingName = false;
            rdnsEditText = pm.getEditingConstructionId();
            return true;
        }
        currentY += LINE_HEIGHT + 2;

        // Name field click
        String nameLabel = "Name: ";
        int nameLabelWidth = font.width(nameLabel);
        fieldX = x + PADDING + nameLabelWidth;
        fieldWidth = WIDTH - PADDING * 2 - nameLabelWidth;

        if (mouseX >= fieldX && mouseX < fieldX + fieldWidth &&
            mouseY >= currentY - 1 && mouseY < currentY + LINE_HEIGHT) {
            editingName = true;
            editingRdns = false;
            nameEditText = pm.getEditingTitle();
            return true;
        }

        // Cancel field editing if clicking elsewhere
        editingRdns = false;
        editingName = false;

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
        if (!editingRdns && !editingName) return false;

        // Backspace
        if (keyCode == 259) {
            if (editingRdns && !rdnsEditText.isEmpty()) {
                rdnsEditText = rdnsEditText.substring(0, rdnsEditText.length() - 1);
            } else if (editingName && !nameEditText.isEmpty()) {
                nameEditText = nameEditText.substring(0, nameEditText.length() - 1);
            }
            return true;
        }

        // Escape - cancel editing
        if (keyCode == 256) {
            editingRdns = false;
            editingName = false;
            return true;
        }

        // Enter - confirm editing
        if (keyCode == 257) {
            if (editingRdns) {
                // Send rename packet
                ClientPlayNetworking.send(new GuiActionPacket("rename", rdnsEditText, ""));
                editingRdns = false;
            } else if (editingName) {
                // Send title update packet
                ClientPlayNetworking.send(new GuiActionPacket("title", "", nameEditText));
                editingName = false;
            }
            return true;
        }

        return true;
    }

    public boolean charTyped(char chr, int modifiers) {
        if (editingRdns) {
            // Only allow valid ID characters
            if (Character.isLetterOrDigit(chr) || chr == '.' || chr == '_') {
                rdnsEditText += Character.toLowerCase(chr);
            }
            return true;
        } else if (editingName) {
            // Allow most characters for name
            if (chr >= 32) {
                nameEditText += chr;
            }
            return true;
        }
        return false;
    }
}
