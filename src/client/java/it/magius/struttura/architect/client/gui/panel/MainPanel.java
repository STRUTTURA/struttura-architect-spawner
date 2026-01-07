package it.magius.struttura.architect.client.gui.panel;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.ModValidator;
import it.magius.struttura.architect.client.gui.EditBoxHelper;
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
    private int selectedIndex = -1;
    private int scrollOffset = 0;

    // Construction list (synced from server)
    private List<ConstructionInfo> constructions = new ArrayList<>();
    private List<ConstructionInfo> filteredConstructions = new ArrayList<>();

    // Button hover states
    private int hoveredButton = -1;

    // EditBox helpers for text input
    private EditBoxHelper searchBox;
    private EditBoxHelper newIdBox;
    private EditBoxHelper pullIdBox;
    private boolean editBoxesInitialized = false;

    // Modal state for "NEW" dialog
    private boolean showNewModal = false;

    // Modal state for "PULL" dialog
    private boolean showPullModal = false;

    // Modal state for "MISSING MODS" dialog
    private boolean showMissingModsDialog = false;
    private String pendingPullId = null;
    private Map<String, ModInfo> missingMods = new HashMap<>();
    private int totalMissingBlocks = 0;
    private int missingModsScrollOffset = 0;

    public record ConstructionInfo(String id, String title, int blockCount, int entityCount, boolean isBeingEdited) {}

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
        String filter = searchBox != null ? searchBox.getValue().toLowerCase(Locale.ROOT) : "";
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

    /**
     * Initialize EditBox helpers. Must be called after Minecraft client is ready.
     */
    private void initEditBoxes() {
        if (editBoxesInitialized) return;

        Font font = Minecraft.getInstance().font;

        // Search box
        searchBox = EditBoxHelper.createSearchBox(font, 0, 0, WIDTH - PADDING * 2, SEARCH_HEIGHT,
            text -> applyFilter());

        // New ID modal box
        newIdBox = EditBoxHelper.createIdBox(font, 0, 0, 180, 16, "namespace.category.name");

        // Pull ID modal box
        pullIdBox = EditBoxHelper.createIdBox(font, 0, 0, 180, 16, "it.example.myhouse");

        editBoxesInitialized = true;
    }

    private int getVisibleItemCount() {
        int buttonsAreaHeight = BUTTON_HEIGHT * 2 + 2 + PADDING; // 2 button rows + spacing + padding
        int listHeight = HEIGHT - PADDING * 3 - SEARCH_HEIGHT - buttonsAreaHeight - 12;
        return listHeight / ITEM_HEIGHT;
    }

    public void render(GuiGraphics graphics, int panelX, int panelY, int mouseX, int mouseY, float tickDelta) {
        this.x = panelX;
        this.y = panelY;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        // Initialize EditBoxes if needed
        initEditBoxes();

        // Draw background
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xE0000000);
        graphics.renderOutline(x, y, WIDTH, HEIGHT, 0xFF404040);

        // Title
        graphics.drawString(font, "STRUTTURA", x + PADDING, y + PADDING, 0xFFFFFF00, true);

        int currentY = y + PADDING + 12;

        // Search box using EditBoxHelper
        int searchBoxX = x + PADDING;
        int searchBoxWidth = WIDTH - PADDING * 2;
        searchBox.setPosition(searchBoxX, currentY);
        searchBox.setWidth(searchBoxWidth);
        searchBox.render(graphics, mouseX, mouseY, tickDelta);

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
                int maxTextWidth = WIDTH - PADDING * 2 - 6;
                String idText = truncateToWidth(font, info.id(), maxTextWidth);
                int idColor = info.isBeingEdited() ? 0xFFFFAA00 : 0xFFFFFFFF;
                graphics.drawString(font, idText, x + PADDING + 3, itemY + 2, idColor, false);

                // Title + block/entity count: "title - Xb/Ye" or "title - Xb"
                String titleText = info.title().isEmpty() ? "(no title)" : info.title();
                String countPart = info.entityCount() > 0
                    ? info.blockCount() + "b/" + info.entityCount() + "e"
                    : info.blockCount() + "b";
                String separator = " - ";
                String suffix = separator + countPart;
                // Truncate title to fit, keeping suffix always visible
                int suffixWidth = font.width(suffix);
                int availableForTitle = maxTextWidth - suffixWidth;
                String truncatedTitle = truncateToWidth(font, titleText, availableForTitle);
                String detailText = truncatedTitle + suffix;
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

            // Row 1: EDIT, SHOT, DEL, PUSH (4 pulsanti)
            String[] buttons1 = {"EDIT", "SHOT", "DEL", "PUSH"};
            int buttonWidth1 = (WIDTH - PADDING * 2 - (buttons1.length - 1) * 2) / buttons1.length;

            for (int i = 0; i < buttons1.length; i++) {
                int btnX = x + PADDING + i * (buttonWidth1 + 2);
                int btnY = currentY;

                boolean btnHovered = mouseX >= btnX && mouseX < btnX + buttonWidth1 &&
                                     mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT;

                // PUSH is disabled if construction is being edited
                boolean isPush = buttons1[i].equals("PUSH");
                boolean btnDisabled = isPush && selected.isBeingEdited();

                if (btnHovered && !btnDisabled) {
                    hoveredButton = i;
                }

                int bgColor;
                int textColor;
                if (btnDisabled) {
                    bgColor = 0xFF202020;
                    textColor = 0xFF606060;
                } else {
                    bgColor = btnHovered ? 0xFF505050 : 0xFF303030;
                    textColor = 0xFFFFFFFF;
                }

                graphics.fill(btnX, btnY, btnX + buttonWidth1, btnY + BUTTON_HEIGHT, bgColor);
                graphics.renderOutline(btnX, btnY, buttonWidth1, BUTTON_HEIGHT, btnDisabled ? 0xFF404040 : 0xFF606060);

                int textWidth = font.width(buttons1[i]);
                graphics.drawString(font, buttons1[i],
                                   btnX + (buttonWidth1 - textWidth) / 2, btnY + 4, textColor, false);
            }

            // Row 2: TP, SHOW, HIDE, SPAWN, MOVE (5 pulsanti)
            int row2Y = currentY + BUTTON_HEIGHT + 2;
            String[] buttons2 = {"TP", "SHOW", "HIDE", "SPAWN", "MOVE"};
            int buttonWidth2 = (WIDTH - PADDING * 2 - (buttons2.length - 1) * 2) / buttons2.length;

            for (int i = 0; i < buttons2.length; i++) {
                int btnX = x + PADDING + i * (buttonWidth2 + 2);
                int btnY = row2Y;

                boolean btnHovered = mouseX >= btnX && mouseX < btnX + buttonWidth2 &&
                                     mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT;

                // MOVE is disabled if construction is being edited
                boolean isMove = buttons2[i].equals("MOVE");
                boolean btnDisabled = isMove && selected.isBeingEdited();

                if (btnHovered && !btnDisabled) {
                    hoveredButton = buttons1.length + i;
                }

                int bgColor;
                int textColor;
                if (btnDisabled) {
                    bgColor = 0xFF202020;
                    textColor = 0xFF606060;
                } else {
                    bgColor = btnHovered ? 0xFF505050 : 0xFF303030;
                    textColor = 0xFFFFFFFF;
                }

                graphics.fill(btnX, btnY, btnX + buttonWidth2, btnY + BUTTON_HEIGHT, bgColor);
                graphics.renderOutline(btnX, btnY, buttonWidth2, BUTTON_HEIGHT, btnDisabled ? 0xFF404040 : 0xFF606060);

                int textWidth = font.width(buttons2[i]);
                graphics.drawString(font, buttons2[i],
                                   btnX + (buttonWidth2 - textWidth) / 2, btnY + 4, textColor, false);
            }
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

        // ID input box using EditBoxHelper
        int inputY = modalY + 25;
        int inputWidth = modalWidth - 20;
        newIdBox.setPosition(modalX + 10, inputY);
        newIdBox.setWidth(inputWidth);
        newIdBox.render(graphics, mouseX, mouseY, 0);

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

        // ID input box using EditBoxHelper
        int inputY = modalY + 25;
        int inputWidth = modalWidth - 20;
        pullIdBox.setPosition(modalX + 10, inputY);
        pullIdBox.setWidth(inputWidth);
        pullIdBox.render(graphics, mouseX, mouseY, 0);

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
            newIdBox.clear();
            newIdBox.setFocused(true);
            searchBox.setFocused(false);
            return true;
        }

        // Check PULL button click (left of NEW)
        int pullBtnX = newBtnX - btnSize - btnSpacing;
        int pullBtnY = newBtnY;
        if (mouseX >= pullBtnX && mouseX < pullBtnX + btnSize &&
            mouseY >= pullBtnY && mouseY < pullBtnY + btnSize) {
            showPullModal = true;
            pullIdBox.clear();
            pullIdBox.setFocused(true);
            searchBox.setFocused(false);
            return true;
        }

        // Check if click is within panel bounds
        if (mouseX < x || mouseX > x + WIDTH || mouseY < y || mouseY > y + HEIGHT) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();

        int currentY = y + PADDING + 12;

        // Search box click - check if clicking on search box
        if (searchBox.mouseClicked(mouseX, mouseY, button)) {
            return true;
        } else {
            searchBox.setFocused(false);
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

        // Action buttons click - Row 1: EDIT, SHOT, DEL, PUSH
        if (selectedIndex >= 0 && selectedIndex < filteredConstructions.size()) {
            ConstructionInfo selected = filteredConstructions.get(selectedIndex);
            String[] actions1 = {"edit", "shot", "destroy", "push"};
            int buttonWidth1 = (WIDTH - PADDING * 2 - (actions1.length - 1) * 2) / actions1.length;

            for (int i = 0; i < actions1.length; i++) {
                int btnX = x + PADDING + i * (buttonWidth1 + 2);
                int btnY = currentY;

                if (mouseX >= btnX && mouseX < btnX + buttonWidth1 &&
                    mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
                    // PUSH is disabled if construction is being edited
                    if (actions1[i].equals("push") && selected.isBeingEdited()) {
                        return true; // Consume click but don't execute
                    }
                    executeAction(actions1[i], selected.id());
                    return true;
                }
            }

            // Action buttons click - Row 2: TP, SHOW, HIDE, SPAWN, MOVE
            int row2Y = currentY + BUTTON_HEIGHT + 2;
            String[] actions2 = {"tp", "show", "hide", "spawn", "move"};
            int buttonWidth2 = (WIDTH - PADDING * 2 - (actions2.length - 1) * 2) / actions2.length;

            for (int i = 0; i < actions2.length; i++) {
                int btnX = x + PADDING + i * (buttonWidth2 + 2);
                int btnY = row2Y;

                if (mouseX >= btnX && mouseX < btnX + buttonWidth2 &&
                    mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
                    // MOVE is disabled if construction is being edited
                    if (actions2[i].equals("move") && selected.isBeingEdited()) {
                        return true; // Consume click but don't execute
                    }
                    executeAction(actions2[i], selected.id());
                    return true;
                }
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
        if (newIdBox.mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }

        // Check CREATE button
        int btnWidth = 60;
        int btnY = modalY + 50;
        int createX = modalX + modalWidth / 2 - btnWidth - 5;
        if (mouseX >= createX && mouseX < createX + btnWidth &&
            mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
            if (!newIdBox.getValue().isEmpty()) {
                executeAction("edit", newIdBox.getValue());
                showNewModal = false;
                newIdBox.clear();
            }
            return true;
        }

        // Check CANCEL button
        int cancelX = modalX + modalWidth / 2 + 5;
        if (mouseX >= cancelX && mouseX < cancelX + btnWidth &&
            mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
            showNewModal = false;
            newIdBox.clear();
            return true;
        }

        // Click outside modal closes it
        if (mouseX < modalX || mouseX > modalX + modalWidth ||
            mouseY < modalY || mouseY > modalY + modalHeight) {
            showNewModal = false;
            newIdBox.clear();
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
        if (pullIdBox.mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }

        // Check PULL button
        int btnWidth = 60;
        int btnY = modalY + 50;
        int pullX = modalX + modalWidth / 2 - btnWidth - 5;
        if (mouseX >= pullX && mouseX < pullX + btnWidth &&
            mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
            if (!pullIdBox.getValue().isEmpty()) {
                // First check mod requirements, then pull if all mods present
                executeAction("pull_check", pullIdBox.getValue());
                showPullModal = false;
                pullIdBox.clear();
            }
            return true;
        }

        // Check CANCEL button
        int cancelX = modalX + modalWidth / 2 + 5;
        if (mouseX >= cancelX && mouseX < cancelX + btnWidth &&
            mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
            showPullModal = false;
            pullIdBox.clear();
            return true;
        }

        // Click outside modal closes it
        if (mouseX < modalX || mouseX > modalX + modalWidth ||
            mouseY < modalY || mouseY > modalY + modalHeight) {
            showPullModal = false;
            pullIdBox.clear();
            return true;
        }

        return true; // Consume click within modal
    }

    private void executeAction(String action, String targetId) {
        Architect.LOGGER.debug("GUI action: {} on {}", action, targetId);

        // Chiudi GUI per azioni che spostano il player
        if (action.equals("tp")) {
            Minecraft.getInstance().setScreen(null);
        }

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
        if (showNewModal && newIdBox.isFocused()) {
            // Escape - close modal
            if (keyCode == 256) {
                showNewModal = false;
                newIdBox.clear();
                return true;
            }
            // Enter - create
            if (keyCode == 257) {
                if (!newIdBox.getValue().isEmpty()) {
                    executeAction("edit", newIdBox.getValue());
                    showNewModal = false;
                    newIdBox.clear();
                }
                return true;
            }
            // Let EditBox handle other keys
            return newIdBox.keyPressed(keyCode, scanCode, modifiers);
        }

        // Handle PULL modal input
        if (showPullModal && pullIdBox.isFocused()) {
            // Escape - close modal
            if (keyCode == 256) {
                showPullModal = false;
                pullIdBox.clear();
                return true;
            }
            // Enter - pull (check mods first)
            if (keyCode == 257) {
                if (!pullIdBox.getValue().isEmpty()) {
                    executeAction("pull_check", pullIdBox.getValue());
                    showPullModal = false;
                    pullIdBox.clear();
                }
                return true;
            }
            // Let EditBox handle other keys
            return pullIdBox.keyPressed(keyCode, scanCode, modifiers);
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

        // Handle search box
        if (searchBox.isFocused()) {
            // Escape - unfocus
            if (keyCode == 256) {
                searchBox.setFocused(false);
                return true;
            }
            // Enter - unfocus
            if (keyCode == 257) {
                searchBox.setFocused(false);
                return true;
            }
            // Let EditBox handle other keys
            return searchBox.keyPressed(keyCode, scanCode, modifiers);
        }

        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        // Handle NEW modal input first
        if (showNewModal && newIdBox.isFocused()) {
            return newIdBox.charTyped(chr, modifiers);
        }

        // Handle PULL modal input
        if (showPullModal && pullIdBox.isFocused()) {
            return pullIdBox.charTyped(chr, modifiers);
        }

        // Handle search box
        if (searchBox.isFocused()) {
            return searchBox.charTyped(chr, modifiers);
        }

        return false;
    }

    public void reset() {
        if (searchBox != null) {
            searchBox.clear();
            searchBox.setFocused(false);
        }
        if (newIdBox != null) {
            newIdBox.clear();
            newIdBox.setFocused(false);
        }
        if (pullIdBox != null) {
            pullIdBox.clear();
            pullIdBox.setFocused(false);
        }
        selectedIndex = -1;
        scrollOffset = 0;
        constructions.clear();
        filteredConstructions.clear();
        showNewModal = false;
        showPullModal = false;
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

    /**
     * Truncates a string to fit within the given pixel width, adding "..." if truncated.
     */
    private String truncateToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        int availableWidth = maxWidth - ellipsisWidth;
        if (availableWidth <= 0) {
            return ellipsis;
        }
        // Binary search or simple iteration to find max length
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            sb.append(text.charAt(i));
            if (font.width(sb.toString()) > availableWidth) {
                sb.deleteCharAt(sb.length() - 1);
                break;
            }
        }
        return sb.toString() + ellipsis;
    }
}
