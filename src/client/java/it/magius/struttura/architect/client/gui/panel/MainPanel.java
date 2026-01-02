package it.magius.struttura.architect.client.gui.panel;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.ModValidator;
import it.magius.struttura.architect.client.gui.PanelManager;
import it.magius.struttura.architect.model.ModInfo;
import it.magius.struttura.architect.network.GuiActionPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Main panel showing the list of constructions with actions.
 */
@Environment(EnvType.CLIENT)
public class MainPanel {

    private static final int WIDTH = 180;
    private static final int HEIGHT = 220;
    private static final int PADDING = 5;
    private static final int BUTTON_HEIGHT = 16;
    private static final int ITEM_HEIGHT = 20;
    private static final int SEARCH_HEIGHT = 16;

    private int x, y;
    private String searchText = "";
    private boolean searchFocused = false;
    private int selectedIndex = -1;
    private int scrollOffset = 0;

    // Construction list (synced from server)
    private List<ConstructionInfo> constructions = new ArrayList<>();
    private List<ConstructionInfo> filteredConstructions = new ArrayList<>();

    // Button hover states
    private int hoveredButton = -1;

    // Modal state for "NEW" dialog
    private boolean showNewModal = false;
    private String newIdText = "";
    private boolean newIdFocused = true;

    // Modal state for "PULL" dialog
    private boolean showPullModal = false;
    private String pullIdText = "";
    private boolean pullIdFocused = true;

    // Modal state for "MISSING MODS" dialog
    private boolean showMissingModsDialog = false;
    private String pendingPullId = null;
    private Map<String, ModInfo> missingMods = new HashMap<>();
    private int totalMissingBlocks = 0;
    private int missingModsScrollOffset = 0;

    public record ConstructionInfo(String id, String title, int blockCount, boolean isBeingEdited) {}

    public int getWidth() {
        return WIDTH;
    }

    public int getHeight() {
        return HEIGHT;
    }

    /**
     * Update the construction list from server.
     */
    public void updateConstructionList(List<ConstructionInfo> constructions) {
        this.constructions = new ArrayList<>(constructions);
        applyFilter();
    }

    private void applyFilter() {
        filteredConstructions.clear();
        String filter = searchText.toLowerCase(Locale.ROOT);
        for (ConstructionInfo c : constructions) {
            if (filter.isEmpty() || c.id().toLowerCase(Locale.ROOT).contains(filter) ||
                c.title().toLowerCase(Locale.ROOT).contains(filter)) {
                filteredConstructions.add(c);
            }
        }
        // Reset selection if it's out of bounds
        if (selectedIndex >= filteredConstructions.size()) {
            selectedIndex = filteredConstructions.isEmpty() ? -1 : filteredConstructions.size() - 1;
        }
        // Reset scroll if needed
        if (scrollOffset > Math.max(0, filteredConstructions.size() - getVisibleItemCount())) {
            scrollOffset = Math.max(0, filteredConstructions.size() - getVisibleItemCount());
        }
    }

    private int getVisibleItemCount() {
        int buttonsAreaHeight = BUTTON_HEIGHT * 2 + 2 + PADDING; // 2 button rows + spacing + padding
        int listHeight = HEIGHT - PADDING * 3 - SEARCH_HEIGHT - buttonsAreaHeight - 12;
        return listHeight / ITEM_HEIGHT;
    }

    public void render(GuiGraphics graphics, int x, int y, int mouseX, int mouseY, float tickDelta) {
        this.x = x;
        this.y = y;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        // Draw background
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xE0000000);
        graphics.renderOutline(x, y, WIDTH, HEIGHT, 0xFF404040);

        // Title
        graphics.drawString(font, "STRUTTURA", x + PADDING, y + PADDING, 0xFFFFFF00, true);

        int currentY = y + PADDING + 12;

        // Search box
        int searchBoxX = x + PADDING;
        int searchBoxWidth = WIDTH - PADDING * 2;
        graphics.fill(searchBoxX, currentY, searchBoxX + searchBoxWidth, currentY + SEARCH_HEIGHT,
                     searchFocused ? 0xFF404040 : 0xFF202020);
        graphics.renderOutline(searchBoxX, currentY, searchBoxWidth, SEARCH_HEIGHT,
                              searchFocused ? 0xFFFFFFFF : 0xFF606060);

        String displayText = searchText.isEmpty() && !searchFocused ? "Search..." : searchText;
        int textColor = searchText.isEmpty() && !searchFocused ? 0xFF808080 : 0xFFFFFFFF;
        graphics.drawString(font, displayText + (searchFocused ? "_" : ""),
                           searchBoxX + 3, currentY + 4, textColor, false);

        currentY += SEARCH_HEIGHT + PADDING;

        // Construction list - leave space for 2 rows of buttons at bottom
        int buttonsAreaHeight = BUTTON_HEIGHT * 2 + 2 + PADDING; // 2 button rows + spacing + padding
        int listHeight = HEIGHT - (currentY - y) - buttonsAreaHeight - PADDING;
        int listEndY = currentY + listHeight;

        graphics.fill(x + PADDING, currentY, x + WIDTH - PADDING, listEndY, 0xFF101010);

        int visibleCount = listHeight / ITEM_HEIGHT;
        hoveredButton = -1;

        if (filteredConstructions.isEmpty()) {
            graphics.drawString(font, "No constructions", x + PADDING + 5, currentY + 5, 0xFF808080, false);
        } else {
            for (int i = 0; i < visibleCount && (i + scrollOffset) < filteredConstructions.size(); i++) {
                int index = i + scrollOffset;
                ConstructionInfo info = filteredConstructions.get(index);

                int itemY = currentY + i * ITEM_HEIGHT;
                boolean itemHovered = mouseX >= x + PADDING && mouseX < x + WIDTH - PADDING &&
                                      mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;
                boolean selected = index == selectedIndex;

                // Background
                if (selected) {
                    graphics.fill(x + PADDING, itemY, x + WIDTH - PADDING, itemY + ITEM_HEIGHT, 0xFF304060);
                } else if (itemHovered) {
                    graphics.fill(x + PADDING, itemY, x + WIDTH - PADDING, itemY + ITEM_HEIGHT, 0xFF252525);
                }

                // Construction info
                String idText = info.id();
                if (idText.length() > 28) {
                    idText = idText.substring(0, 25) + "...";
                }
                int idColor = info.isBeingEdited() ? 0xFFFFAA00 : 0xFFFFFFFF;
                graphics.drawString(font, idText, x + PADDING + 3, itemY + 2, idColor, false);

                // Title (max 30 chars) + block count
                String titleText = info.title().isEmpty() ? "(no title)" : info.title();
                if (titleText.length() > 30) {
                    titleText = titleText.substring(0, 27) + "...";
                }
                String detailText = titleText + " - " + info.blockCount() + " blk";
                if (detailText.length() > 35) {
                    detailText = detailText.substring(0, 32) + "...";
                }
                graphics.drawString(font, detailText, x + PADDING + 3, itemY + 11, 0xFF808080, false);
            }
        }

        currentY = listEndY + PADDING;

        // Top-right corner buttons: PULL (download arrow) and NEW (+)
        int btnSize = 13;
        int btnSpacing = 2;

        // NEW button (rightmost, straddling the panel edge)
        int newBtnX = x + WIDTH - btnSize / 2 - 2;
        int newBtnY = y - btnSize / 2 + 2;
        boolean newBtnHovered = mouseX >= newBtnX && mouseX < newBtnX + btnSize &&
                                mouseY >= newBtnY && mouseY < newBtnY + btnSize;
        int newBgColor = newBtnHovered ? 0xFF406040 : 0xFF305030;
        graphics.fill(newBtnX, newBtnY, newBtnX + btnSize, newBtnY + btnSize, newBgColor);
        graphics.renderOutline(newBtnX, newBtnY, btnSize, btnSize, 0xFF60A060);
        int newTextWidth = font.width("+");
        graphics.drawString(font, "+", newBtnX + (btnSize - newTextWidth) / 2 + 1, newBtnY + 3, 0xFF88FF88, false);

        // PULL button (left of NEW, with down arrow symbol)
        int pullBtnX = newBtnX - btnSize - btnSpacing;
        int pullBtnY = newBtnY;
        boolean pullBtnHovered = mouseX >= pullBtnX && mouseX < pullBtnX + btnSize &&
                                 mouseY >= pullBtnY && mouseY < pullBtnY + btnSize;
        int pullBgColor = pullBtnHovered ? 0xFF404060 : 0xFF303050;
        graphics.fill(pullBtnX, pullBtnY, pullBtnX + btnSize, pullBtnY + btnSize, pullBgColor);
        graphics.renderOutline(pullBtnX, pullBtnY, btnSize, btnSize, 0xFF6060A0);
        // Draw down arrow centered (adjusted +1px right and +1px down)
        String pullIcon = "\u2193"; // Down arrow ↓
        int pullTextWidth = font.width(pullIcon);
        int pullIconX = pullBtnX + (btnSize - pullTextWidth) / 2 + 1;
        int pullIconY = pullBtnY + (btnSize - 8) / 2 + 1;
        graphics.drawString(font, pullIcon, pullIconX, pullIconY, 0xFF8888FF, false);

        // Action buttons (only if something is selected)
        if (selectedIndex >= 0 && selectedIndex < filteredConstructions.size()) {
            ConstructionInfo selected = filteredConstructions.get(selectedIndex);
            // Row 1: SHOW, HIDE, TP, EDIT, SHOT, DEL
            String[] buttons1 = {"SHOW", "HIDE", "TP", "EDIT", "SHOT", "DEL"};
            int buttonWidth1 = (WIDTH - PADDING * 2 - (buttons1.length - 1) * 2) / buttons1.length;

            for (int i = 0; i < buttons1.length; i++) {
                int btnX = x + PADDING + i * (buttonWidth1 + 2);
                int btnY = currentY;

                boolean btnHovered = mouseX >= btnX && mouseX < btnX + buttonWidth1 &&
                                     mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT;
                if (btnHovered) {
                    hoveredButton = i;
                }

                int bgColor = btnHovered ? 0xFF505050 : 0xFF303030;
                graphics.fill(btnX, btnY, btnX + buttonWidth1, btnY + BUTTON_HEIGHT, bgColor);
                graphics.renderOutline(btnX, btnY, buttonWidth1, BUTTON_HEIGHT, 0xFF606060);

                int textWidth = font.width(buttons1[i]);
                graphics.drawString(font, buttons1[i],
                                   btnX + (buttonWidth1 - textWidth) / 2, btnY + 4, 0xFFFFFFFF, false);
            }

            // Row 2: PUSH only (full width)
            int row2Y = currentY + BUTTON_HEIGHT + 2;
            int pushBtnX = x + PADDING;
            int pushBtnWidth = WIDTH - PADDING * 2;

            boolean pushHovered = mouseX >= pushBtnX && mouseX < pushBtnX + pushBtnWidth &&
                                  mouseY >= row2Y && mouseY < row2Y + BUTTON_HEIGHT;

            // PUSH is disabled if construction is being edited
            boolean pushDisabled = selected.isBeingEdited();

            if (pushHovered && !pushDisabled) {
                hoveredButton = buttons1.length; // Index after row 1 buttons
            }

            int pushBgColor;
            int pushTextColor;
            if (pushDisabled) {
                pushBgColor = 0xFF202020;
                pushTextColor = 0xFF606060;
            } else {
                pushBgColor = pushHovered ? 0xFF505050 : 0xFF303030;
                pushTextColor = 0xFFFFFFFF;
            }

            graphics.fill(pushBtnX, row2Y, pushBtnX + pushBtnWidth, row2Y + BUTTON_HEIGHT, pushBgColor);
            graphics.renderOutline(pushBtnX, row2Y, pushBtnWidth, BUTTON_HEIGHT, pushDisabled ? 0xFF404040 : 0xFF606060);

            int pushTextWidth = font.width("PUSH");
            graphics.drawString(font, "PUSH",
                               pushBtnX + (pushBtnWidth - pushTextWidth) / 2, row2Y + 4, pushTextColor, false);
        }

        // Modal is rendered separately via renderModal() to ensure it's on top
    }

    /**
     * Check if any modal/dialog is currently open.
     */
    public boolean isModalOpen() {
        return showNewModal || showPullModal || showMissingModsDialog;
    }

    /**
     * Render the modal dialog on top of everything.
     * Should be called after all panels are rendered.
     */
    public void renderModal(GuiGraphics graphics, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        if (showNewModal) {
            renderNewModal(graphics, font, mouseX, mouseY);
        } else if (showPullModal) {
            renderPullModal(graphics, font, mouseX, mouseY);
        } else if (showMissingModsDialog) {
            renderMissingModsDialog(graphics, font, mouseX, mouseY);
        }
    }

    private void renderNewModal(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int modalWidth = 200;
        int modalHeight = 80;
        int modalX = (screenWidth - modalWidth) / 2;
        int modalY = (screenHeight - modalHeight) / 2;

        // Dark overlay behind modal
        graphics.fill(0, 0, screenWidth, screenHeight, 0x80000000);

        // Modal background
        graphics.fill(modalX, modalY, modalX + modalWidth, modalY + modalHeight, 0xF0202020);
        graphics.renderOutline(modalX, modalY, modalWidth, modalHeight, 0xFF60A060);

        // Title
        graphics.drawString(font, "New Construction", modalX + 10, modalY + 8, 0xFF88FF88, false);

        // ID input box
        int inputY = modalY + 25;
        int inputWidth = modalWidth - 20;
        graphics.fill(modalX + 10, inputY, modalX + 10 + inputWidth, inputY + 16,
                     newIdFocused ? 0xFF404040 : 0xFF303030);
        graphics.renderOutline(modalX + 10, inputY, inputWidth, 16,
                              newIdFocused ? 0xFFFFFFFF : 0xFF606060);

        String inputDisplay = newIdText.isEmpty() && !newIdFocused ? "namespace.category.name" : newIdText;
        int inputColor = newIdText.isEmpty() && !newIdFocused ? 0xFF606060 : 0xFFFFFFFF;
        graphics.drawString(font, inputDisplay + (newIdFocused ? "_" : ""),
                           modalX + 13, inputY + 4, inputColor, false);

        // Buttons: CREATE and CANCEL
        int btnWidth = 60;
        int btnY = modalY + 50;

        // CREATE button
        int createX = modalX + modalWidth / 2 - btnWidth - 5;
        boolean createHovered = mouseX >= createX && mouseX < createX + btnWidth &&
                                mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT;
        graphics.fill(createX, btnY, createX + btnWidth, btnY + BUTTON_HEIGHT,
                     createHovered ? 0xFF406040 : 0xFF305030);
        graphics.renderOutline(createX, btnY, btnWidth, BUTTON_HEIGHT, 0xFF60A060);
        int createTextW = font.width("CREATE");
        graphics.drawString(font, "CREATE", createX + (btnWidth - createTextW) / 2, btnY + 4, 0xFF88FF88, false);

        // CANCEL button
        int cancelX = modalX + modalWidth / 2 + 5;
        boolean cancelHovered = mouseX >= cancelX && mouseX < cancelX + btnWidth &&
                                mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT;
        graphics.fill(cancelX, btnY, cancelX + btnWidth, btnY + BUTTON_HEIGHT,
                     cancelHovered ? 0xFF604040 : 0xFF503030);
        graphics.renderOutline(cancelX, btnY, btnWidth, BUTTON_HEIGHT, 0xFFA06060);
        int cancelTextW = font.width("CANCEL");
        graphics.drawString(font, "CANCEL", cancelX + (btnWidth - cancelTextW) / 2, btnY + 4, 0xFFFF8888, false);
    }

    private void renderPullModal(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int modalWidth = 200;
        int modalHeight = 80;
        int modalX = (screenWidth - modalWidth) / 2;
        int modalY = (screenHeight - modalHeight) / 2;

        // Dark overlay behind modal
        graphics.fill(0, 0, screenWidth, screenHeight, 0x80000000);

        // Modal background
        graphics.fill(modalX, modalY, modalX + modalWidth, modalY + modalHeight, 0xF0202020);
        graphics.renderOutline(modalX, modalY, modalWidth, modalHeight, 0xFF6060A0);

        // Title with down arrow
        graphics.drawString(font, "\u2193 Pull Construction", modalX + 10, modalY + 8, 0xFF8888FF, false);

        // ID input box
        int inputY = modalY + 25;
        int inputWidth = modalWidth - 20;
        graphics.fill(modalX + 10, inputY, modalX + 10 + inputWidth, inputY + 16,
                     pullIdFocused ? 0xFF404040 : 0xFF303030);
        graphics.renderOutline(modalX + 10, inputY, inputWidth, 16,
                              pullIdFocused ? 0xFFFFFFFF : 0xFF606060);

        // Show placeholder when text is empty, cursor when focused
        if (pullIdText.isEmpty()) {
            graphics.drawString(font, "it.example.myhouse", modalX + 13, inputY + 4, 0xFF606060, false);
            if (pullIdFocused) {
                // Draw cursor at the start
                graphics.drawString(font, "_", modalX + 13, inputY + 4, 0xFFFFFFFF, false);
            }
        } else {
            graphics.drawString(font, pullIdText + (pullIdFocused ? "_" : ""),
                               modalX + 13, inputY + 4, 0xFFFFFFFF, false);
        }

        // Buttons: PULL and CANCEL
        int btnWidth = 60;
        int btnY = modalY + 50;

        // PULL button
        int pullX = modalX + modalWidth / 2 - btnWidth - 5;
        boolean pullHovered = mouseX >= pullX && mouseX < pullX + btnWidth &&
                              mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT;
        graphics.fill(pullX, btnY, pullX + btnWidth, btnY + BUTTON_HEIGHT,
                     pullHovered ? 0xFF404060 : 0xFF303050);
        graphics.renderOutline(pullX, btnY, btnWidth, BUTTON_HEIGHT, 0xFF6060A0);
        int pullTextW = font.width("PULL");
        graphics.drawString(font, "PULL", pullX + (btnWidth - pullTextW) / 2, btnY + 4, 0xFF8888FF, false);

        // CANCEL button
        int cancelX = modalX + modalWidth / 2 + 5;
        boolean cancelHovered = mouseX >= cancelX && mouseX < cancelX + btnWidth &&
                                mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT;
        graphics.fill(cancelX, btnY, cancelX + btnWidth, btnY + BUTTON_HEIGHT,
                     cancelHovered ? 0xFF604040 : 0xFF503030);
        graphics.renderOutline(cancelX, btnY, btnWidth, BUTTON_HEIGHT, 0xFFA06060);
        int cancelTextW = font.width("CANCEL");
        graphics.drawString(font, "CANCEL", cancelX + (btnWidth - cancelTextW) / 2, btnY + 4, 0xFFFF8888, false);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        // Handle modal clicks first if modal is open
        if (showNewModal) {
            return handleNewModalClick(mouseX, mouseY);
        }
        if (showPullModal) {
            return handlePullModalClick(mouseX, mouseY);
        }
        if (showMissingModsDialog) {
            return handleMissingModsDialogClick(mouseX, mouseY);
        }

        // Top-right corner buttons (straddling panel edge - partially outside bounds)
        int btnSize = 13;
        int btnSpacing = 2;

        // Check NEW button click (rightmost)
        int newBtnX = x + WIDTH - btnSize / 2 - 2;
        int newBtnY = y - btnSize / 2 + 2;
        if (mouseX >= newBtnX && mouseX < newBtnX + btnSize &&
            mouseY >= newBtnY && mouseY < newBtnY + btnSize) {
            showNewModal = true;
            newIdText = "";
            newIdFocused = true;
            searchFocused = false;
            return true;
        }

        // Check PULL button click (left of NEW)
        int pullBtnX = newBtnX - btnSize - btnSpacing;
        int pullBtnY = newBtnY;
        if (mouseX >= pullBtnX && mouseX < pullBtnX + btnSize &&
            mouseY >= pullBtnY && mouseY < pullBtnY + btnSize) {
            showPullModal = true;
            pullIdText = "";
            pullIdFocused = true;
            searchFocused = false;
            return true;
        }

        // Check if click is within panel bounds
        if (mouseX < x || mouseX > x + WIDTH || mouseY < y || mouseY > y + HEIGHT) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();

        int currentY = y + PADDING + 12;

        // Search box click
        int searchBoxX = x + PADDING;
        int searchBoxWidth = WIDTH - PADDING * 2;
        if (mouseX >= searchBoxX && mouseX < searchBoxX + searchBoxWidth &&
            mouseY >= currentY && mouseY < currentY + SEARCH_HEIGHT) {
            searchFocused = true;
            return true;
        } else {
            searchFocused = false;
        }

        currentY += SEARCH_HEIGHT + PADDING;

        // List click
        int buttonsAreaHeight = BUTTON_HEIGHT * 2 + 2 + PADDING; // 2 button rows + spacing + padding
        int listHeight = HEIGHT - (currentY - y) - buttonsAreaHeight - PADDING;
        int listEndY = currentY + listHeight;

        if (mouseX >= x + PADDING && mouseX < x + WIDTH - PADDING &&
            mouseY >= currentY && mouseY < listEndY) {
            int clickedIndex = (int) ((mouseY - currentY) / ITEM_HEIGHT) + scrollOffset;
            if (clickedIndex >= 0 && clickedIndex < filteredConstructions.size()) {
                selectedIndex = clickedIndex;
            }
            return true;
        }

        currentY = listEndY + PADDING;

        // Action buttons click - Row 1
        if (selectedIndex >= 0 && selectedIndex < filteredConstructions.size()) {
            ConstructionInfo selected = filteredConstructions.get(selectedIndex);
            String[] actions1 = {"show", "hide", "tp", "edit", "shot", "destroy"};
            int buttonWidth1 = (WIDTH - PADDING * 2 - (actions1.length - 1) * 2) / actions1.length;

            for (int i = 0; i < actions1.length; i++) {
                int btnX = x + PADDING + i * (buttonWidth1 + 2);
                int btnY = currentY;

                if (mouseX >= btnX && mouseX < btnX + buttonWidth1 &&
                    mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
                    executeAction(actions1[i], selected.id());
                    return true;
                }
            }

            // Action buttons click - Row 2 (PUSH only, full width)
            int row2Y = currentY + BUTTON_HEIGHT + 2;
            int pushBtnX = x + PADDING;
            int pushBtnWidth = WIDTH - PADDING * 2;

            if (mouseX >= pushBtnX && mouseX < pushBtnX + pushBtnWidth &&
                mouseY >= row2Y && mouseY < row2Y + BUTTON_HEIGHT) {
                // PUSH is disabled if construction is being edited
                if (selected.isBeingEdited()) {
                    return true; // Consume click but don't execute
                }
                executeAction("push", selected.id());
                return true;
            }
        }

        return true; // Consume click even if nothing hit within panel
    }

    private boolean handleNewModalClick(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int modalWidth = 200;
        int modalHeight = 80;
        int modalX = (screenWidth - modalWidth) / 2;
        int modalY = (screenHeight - modalHeight) / 2;

        // Check input field click
        int inputY = modalY + 25;
        int inputWidth = modalWidth - 20;
        if (mouseX >= modalX + 10 && mouseX < modalX + 10 + inputWidth &&
            mouseY >= inputY && mouseY < inputY + 16) {
            newIdFocused = true;
            return true;
        }

        // Check CREATE button
        int btnWidth = 60;
        int btnY = modalY + 50;
        int createX = modalX + modalWidth / 2 - btnWidth - 5;
        if (mouseX >= createX && mouseX < createX + btnWidth &&
            mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
            if (!newIdText.isEmpty()) {
                executeAction("edit", newIdText);
                showNewModal = false;
                newIdText = "";
            }
            return true;
        }

        // Check CANCEL button
        int cancelX = modalX + modalWidth / 2 + 5;
        if (mouseX >= cancelX && mouseX < cancelX + btnWidth &&
            mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
            showNewModal = false;
            newIdText = "";
            return true;
        }

        // Click outside modal closes it
        if (mouseX < modalX || mouseX > modalX + modalWidth ||
            mouseY < modalY || mouseY > modalY + modalHeight) {
            showNewModal = false;
            newIdText = "";
            return true;
        }

        return true; // Consume click within modal
    }

    private boolean handlePullModalClick(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int modalWidth = 200;
        int modalHeight = 80;
        int modalX = (screenWidth - modalWidth) / 2;
        int modalY = (screenHeight - modalHeight) / 2;

        // Check input field click
        int inputY = modalY + 25;
        int inputWidth = modalWidth - 20;
        if (mouseX >= modalX + 10 && mouseX < modalX + 10 + inputWidth &&
            mouseY >= inputY && mouseY < inputY + 16) {
            pullIdFocused = true;
            return true;
        }

        // Check PULL button
        int btnWidth = 60;
        int btnY = modalY + 50;
        int pullX = modalX + modalWidth / 2 - btnWidth - 5;
        if (mouseX >= pullX && mouseX < pullX + btnWidth &&
            mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
            if (!pullIdText.isEmpty()) {
                // First check mod requirements, then pull if all mods present
                executeAction("pull_check", pullIdText);
                showPullModal = false;
                pullIdText = "";
            }
            return true;
        }

        // Check CANCEL button
        int cancelX = modalX + modalWidth / 2 + 5;
        if (mouseX >= cancelX && mouseX < cancelX + btnWidth &&
            mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
            showPullModal = false;
            pullIdText = "";
            return true;
        }

        // Click outside modal closes it
        if (mouseX < modalX || mouseX > modalX + modalWidth ||
            mouseY < modalY || mouseY > modalY + modalHeight) {
            showPullModal = false;
            pullIdText = "";
            return true;
        }

        return true; // Consume click within modal
    }

    private void executeAction(String action, String targetId) {
        Architect.LOGGER.debug("GUI action: {} on {}", action, targetId);
        ClientPlayNetworking.send(new GuiActionPacket(action, targetId != null ? targetId : "", ""));
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Handle missing mods dialog scroll
        if (showMissingModsDialog && missingMods.size() > 4) {
            int maxScroll = missingMods.size() - 4;
            missingModsScrollOffset = Math.max(0, Math.min(maxScroll, missingModsScrollOffset - (int) verticalAmount));
            return true;
        }

        // Check if mouse is over list area
        int currentY = y + PADDING + 12 + SEARCH_HEIGHT + PADDING;
        int buttonsAreaHeight = BUTTON_HEIGHT * 2 + 2 + PADDING; // 2 button rows + spacing + padding
        int listHeight = HEIGHT - (currentY - y) - buttonsAreaHeight - PADDING;

        if (mouseX >= x + PADDING && mouseX < x + WIDTH - PADDING &&
            mouseY >= currentY && mouseY < currentY + listHeight) {
            int maxScroll = Math.max(0, filteredConstructions.size() - getVisibleItemCount());
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) verticalAmount));
            return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Handle NEW modal input first
        if (showNewModal && newIdFocused) {
            // Backspace
            if (keyCode == 259 && !newIdText.isEmpty()) {
                newIdText = newIdText.substring(0, newIdText.length() - 1);
                return true;
            }
            // Escape - close modal
            if (keyCode == 256) {
                showNewModal = false;
                newIdText = "";
                return true;
            }
            // Enter - create
            if (keyCode == 257) {
                if (!newIdText.isEmpty()) {
                    executeAction("edit", newIdText);
                    showNewModal = false;
                    newIdText = "";
                }
                return true;
            }
            return true; // Consume all key presses when modal is open
        }

        // Handle PULL modal input
        if (showPullModal && pullIdFocused) {
            // Backspace
            if (keyCode == 259 && !pullIdText.isEmpty()) {
                pullIdText = pullIdText.substring(0, pullIdText.length() - 1);
                return true;
            }
            // Escape - close modal
            if (keyCode == 256) {
                showPullModal = false;
                pullIdText = "";
                return true;
            }
            // Enter - pull (check mods first)
            if (keyCode == 257) {
                if (!pullIdText.isEmpty()) {
                    executeAction("pull_check", pullIdText);
                    showPullModal = false;
                    pullIdText = "";
                }
                return true;
            }
            return true; // Consume all key presses when modal is open
        }

        // Handle Missing Mods dialog
        if (showMissingModsDialog) {
            // Escape - close dialog
            if (keyCode == 256) {
                showMissingModsDialog = false;
                pendingPullId = null;
                missingMods.clear();
                return true;
            }
            return true; // Consume all key presses when modal is open
        }

        if (!searchFocused) return false;

        // Backspace
        if (keyCode == 259 && !searchText.isEmpty()) {
            searchText = searchText.substring(0, searchText.length() - 1);
            applyFilter();
            return true;
        }

        // Escape - unfocus
        if (keyCode == 256) {
            searchFocused = false;
            return true;
        }

        // Enter - unfocus
        if (keyCode == 257) {
            searchFocused = false;
            return true;
        }

        return true; // Consume all key presses when focused
    }

    public boolean charTyped(char chr, int modifiers) {
        // Handle NEW modal input first
        if (showNewModal && newIdFocused) {
            if (Character.isLetterOrDigit(chr) || chr == '.' || chr == '_') {
                newIdText += chr;
                return true;
            }
            return true; // Consume all chars when modal is open
        }

        // Handle PULL modal input
        if (showPullModal && pullIdFocused) {
            if (Character.isLetterOrDigit(chr) || chr == '.' || chr == '_') {
                pullIdText += chr;
                return true;
            }
            return true; // Consume all chars when modal is open
        }

        if (!searchFocused) return false;

        if (Character.isLetterOrDigit(chr) || chr == '.' || chr == '_' || chr == ' ') {
            searchText += chr;
            applyFilter();
            return true;
        }
        return true; // Consume all chars when focused
    }

    public void reset() {
        searchText = "";
        searchFocused = false;
        selectedIndex = -1;
        scrollOffset = 0;
        constructions.clear();
        filteredConstructions.clear();
        showNewModal = false;
        newIdText = "";
        showPullModal = false;
        pullIdText = "";
        showMissingModsDialog = false;
        pendingPullId = null;
        missingMods.clear();
        totalMissingBlocks = 0;
        missingModsScrollOffset = 0;
    }

    /**
     * Handle mod requirements received from server before pull.
     * Shows missing mods dialog if any mods are missing, otherwise proceeds with pull.
     */
    public void handleModRequirements(String constructionId, Map<String, ModInfo> requiredMods) {
        Map<String, ModInfo> missing = ModValidator.getMissingMods(requiredMods);

        if (missing.isEmpty()) {
            // All mods present, proceed with pull immediately
            executeAction("pull_confirm", constructionId);
        } else {
            // Show missing mods dialog
            pendingPullId = constructionId;
            missingMods = missing;
            totalMissingBlocks = ModValidator.getTotalMissingBlocks(missing);
            missingModsScrollOffset = 0;
            showMissingModsDialog = true;
            showPullModal = false; // Close the pull modal if open
        }
    }

    private void renderMissingModsDialog(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int modalWidth = 280;
        int itemHeight = 28;
        int visibleItems = Math.min(4, missingMods.size());
        int listHeight = visibleItems * itemHeight;
        int modalHeight = 90 + listHeight;
        int modalX = (screenWidth - modalWidth) / 2;
        int modalY = (screenHeight - modalHeight) / 2;

        // Dark overlay behind modal
        graphics.fill(0, 0, screenWidth, screenHeight, 0x80000000);

        // Modal background
        graphics.fill(modalX, modalY, modalX + modalWidth, modalY + modalHeight, 0xF0202020);
        graphics.renderOutline(modalX, modalY, modalWidth, modalHeight, 0xFFFF6060);

        // Title with warning icon
        graphics.drawString(font, "\u26A0 Missing Mods", modalX + 10, modalY + 8, 0xFFFF6666, false);

        // Mod list
        int listY = modalY + 25;
        int listX = modalX + 10;
        int listWidth = modalWidth - 20;

        // List background
        graphics.fill(listX, listY, listX + listWidth, listY + listHeight, 0xFF303030);
        graphics.renderOutline(listX, listY, listWidth, listHeight, 0xFF505050);

        // Render visible mods
        int index = 0;
        for (Map.Entry<String, ModInfo> entry : missingMods.entrySet()) {
            if (index < missingModsScrollOffset) {
                index++;
                continue;
            }
            if (index >= missingModsScrollOffset + visibleItems) {
                break;
            }

            ModInfo mod = entry.getValue();
            int itemY = listY + (index - missingModsScrollOffset) * itemHeight;

            // Mod name with version
            String modNameText = mod.getDisplayName();
            if (mod.getVersion() != null && !mod.getVersion().isEmpty()) {
                modNameText += " v" + mod.getVersion();
            } else {
                Architect.LOGGER.debug("Mod {} has no version info", mod.getModId());
            }
            graphics.drawString(font, modNameText, listX + 5, itemY + 4, 0xFFFFFFFF, false);

            // Block count
            String blockText = mod.getBlockCount() + " blocks";
            graphics.drawString(font, blockText, listX + 5, itemY + 15, 0xFF888888, false);

            // Download link (if available)
            if (mod.getDownloadUrl() != null && !mod.getDownloadUrl().isEmpty()) {
                String linkText = "[Download]";
                int linkWidth = font.width(linkText);
                int linkX = listX + listWidth - linkWidth - 5;
                // Use full item height for hover detection to match click area
                boolean linkHovered = mouseX >= linkX && mouseX < linkX + linkWidth &&
                                     mouseY >= itemY && mouseY < itemY + itemHeight;
                graphics.drawString(font, linkText, linkX, itemY + 4,
                    linkHovered ? 0xFF88AAFF : 0xFF6688CC, false);
            }

            index++;
        }

        // Scroll indicator if needed
        if (missingMods.size() > visibleItems) {
            int scrollY = listY + 2;
            int scrollHeight = listHeight - 4;
            int thumbHeight = Math.max(10, scrollHeight * visibleItems / missingMods.size());
            int maxScroll = missingMods.size() - visibleItems;
            int thumbY = scrollY + (scrollHeight - thumbHeight) * missingModsScrollOffset / maxScroll;

            graphics.fill(listX + listWidth - 5, scrollY, listX + listWidth - 2, scrollY + scrollHeight, 0xFF404040);
            graphics.fill(listX + listWidth - 5, thumbY, listX + listWidth - 2, thumbY + thumbHeight, 0xFF808080);
        }

        // Warning text
        int warningY = listY + listHeight + 8;
        String warningText = "\u26A0 " + totalMissingBlocks + " blocks will become air";
        graphics.drawString(font, warningText, modalX + 10, warningY, 0xFFFFAA00, false);

        // Buttons: CANCEL and PROCEED ANYWAY
        int btnWidth = 90;
        int btnY = warningY + 18;

        // CANCEL button
        int cancelX = modalX + modalWidth / 2 - btnWidth - 10;
        boolean cancelHovered = mouseX >= cancelX && mouseX < cancelX + btnWidth &&
                                mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT;
        graphics.fill(cancelX, btnY, cancelX + btnWidth, btnY + BUTTON_HEIGHT,
                     cancelHovered ? 0xFF404060 : 0xFF303050);
        graphics.renderOutline(cancelX, btnY, btnWidth, BUTTON_HEIGHT, 0xFF6060A0);
        int cancelTextW = font.width("CANCEL");
        graphics.drawString(font, "CANCEL", cancelX + (btnWidth - cancelTextW) / 2, btnY + 4, 0xFF8888FF, false);

        // PROCEED ANYWAY button
        int proceedX = modalX + modalWidth / 2 + 10;
        boolean proceedHovered = mouseX >= proceedX && mouseX < proceedX + btnWidth &&
                                 mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT;
        graphics.fill(proceedX, btnY, proceedX + btnWidth, btnY + BUTTON_HEIGHT,
                     proceedHovered ? 0xFF604040 : 0xFF503030);
        graphics.renderOutline(proceedX, btnY, btnWidth, BUTTON_HEIGHT, 0xFFA06060);
        int proceedTextW = font.width("PROCEED");
        graphics.drawString(font, "PROCEED", proceedX + (btnWidth - proceedTextW) / 2, btnY + 4, 0xFFFF8888, false);
    }

    private boolean handleMissingModsDialogClick(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int modalWidth = 280;
        int itemHeight = 28;
        int visibleItems = Math.min(4, missingMods.size());
        int listHeight = visibleItems * itemHeight;
        int modalHeight = 90 + listHeight;
        int modalX = (screenWidth - modalWidth) / 2;
        int modalY = (screenHeight - modalHeight) / 2;

        Font font = mc.font;

        // Check download link clicks
        int listY = modalY + 25;
        int listX = modalX + 10;
        int listWidth = modalWidth - 20;

        int index = 0;
        for (Map.Entry<String, ModInfo> entry : missingMods.entrySet()) {
            if (index < missingModsScrollOffset) {
                index++;
                continue;
            }
            if (index >= missingModsScrollOffset + visibleItems) {
                break;
            }

            ModInfo mod = entry.getValue();
            int itemY = listY + (index - missingModsScrollOffset) * itemHeight;

            // Check download link click
            if (mod.getDownloadUrl() != null && !mod.getDownloadUrl().isEmpty()) {
                String linkText = "[Download]";
                int linkWidth = font.width(linkText);
                int linkX = listX + listWidth - linkWidth - 5;
                // Expand click area to full item height for easier clicking
                if (mouseX >= linkX && mouseX < linkX + linkWidth &&
                    mouseY >= itemY && mouseY < itemY + itemHeight) {
                    // Open URL in browser
                    try {
                        Util.getPlatform().openUri(new URI(mod.getDownloadUrl()));
                    } catch (Exception e) {
                        Architect.LOGGER.warn("Failed to open URL: {}", mod.getDownloadUrl(), e);
                    }
                    return true;
                }
            }

            index++;
        }

        // Check buttons
        int warningY = listY + listHeight + 8;
        int btnWidth = 90;
        int btnY = warningY + 18;

        // CANCEL button
        int cancelX = modalX + modalWidth / 2 - btnWidth - 10;
        if (mouseX >= cancelX && mouseX < cancelX + btnWidth &&
            mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
            showMissingModsDialog = false;
            pendingPullId = null;
            missingMods.clear();
            return true;
        }

        // PROCEED ANYWAY button
        int proceedX = modalX + modalWidth / 2 + 10;
        if (mouseX >= proceedX && mouseX < proceedX + btnWidth &&
            mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
            if (pendingPullId != null) {
                executeAction("pull_confirm", pendingPullId);
            }
            showMissingModsDialog = false;
            pendingPullId = null;
            missingMods.clear();
            return true;
        }

        // Click outside modal closes it
        if (mouseX < modalX || mouseX > modalX + modalWidth ||
            mouseY < modalY || mouseY > modalY + modalHeight) {
            showMissingModsDialog = false;
            pendingPullId = null;
            missingMods.clear();
            return true;
        }

        return true; // Consume click within modal
    }
}
