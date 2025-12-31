package it.magius.struttura.architect.client.gui.panel;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.gui.PanelManager;
import it.magius.struttura.architect.network.GuiActionPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Main panel showing the list of constructions with actions.
 */
@Environment(EnvType.CLIENT)
public class MainPanel {

    private static final int WIDTH = 180;
    private static final int HEIGHT = 200;
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

    public record ConstructionInfo(String id, String authorName, int blockCount, boolean isBeingEdited) {}

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
                c.authorName().toLowerCase(Locale.ROOT).contains(filter)) {
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
        int listHeight = HEIGHT - PADDING * 4 - SEARCH_HEIGHT - BUTTON_HEIGHT - 20;
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

        // Construction list
        int listHeight = HEIGHT - (currentY - y) - BUTTON_HEIGHT - PADDING * 2;
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

                String detailText = info.authorName() + " - " + info.blockCount() + " blocks";
                if (detailText.length() > 30) {
                    detailText = detailText.substring(0, 27) + "...";
                }
                graphics.drawString(font, detailText, x + PADDING + 3, itemY + 11, 0xFF808080, false);
            }
        }

        currentY = listEndY + PADDING;

        // Action buttons (only if something is selected)
        if (selectedIndex >= 0 && selectedIndex < filteredConstructions.size()) {
            ConstructionInfo selected = filteredConstructions.get(selectedIndex);
            String[] buttons = {"SHOW", "HIDE", "TP", "EDIT", "SHOT"};
            int buttonWidth = (WIDTH - PADDING * 2 - (buttons.length - 1) * 2) / buttons.length;

            for (int i = 0; i < buttons.length; i++) {
                int btnX = x + PADDING + i * (buttonWidth + 2);
                int btnY = currentY;

                boolean btnHovered = mouseX >= btnX && mouseX < btnX + buttonWidth &&
                                     mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT;
                if (btnHovered) {
                    hoveredButton = i;
                }

                int bgColor = btnHovered ? 0xFF505050 : 0xFF303030;
                graphics.fill(btnX, btnY, btnX + buttonWidth, btnY + BUTTON_HEIGHT, bgColor);
                graphics.renderOutline(btnX, btnY, buttonWidth, BUTTON_HEIGHT, 0xFF606060);

                int textWidth = font.width(buttons[i]);
                graphics.drawString(font, buttons[i],
                                   btnX + (buttonWidth - textWidth) / 2, btnY + 4, 0xFFFFFFFF, false);
            }
        }

    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        // Check if click is within panel bounds
        if (mouseX < x || mouseX > x + WIDTH || mouseY < y || mouseY > y + HEIGHT) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

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
        int listHeight = HEIGHT - (currentY - y) - BUTTON_HEIGHT - PADDING * 2;
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

        // Action buttons click
        if (selectedIndex >= 0 && selectedIndex < filteredConstructions.size()) {
            ConstructionInfo selected = filteredConstructions.get(selectedIndex);
            String[] actions = {"show", "hide", "tp", "edit", "shot"};
            int buttonWidth = (WIDTH - PADDING * 2 - (actions.length - 1) * 2) / actions.length;

            for (int i = 0; i < actions.length; i++) {
                int btnX = x + PADDING + i * (buttonWidth + 2);
                int btnY = currentY;

                if (mouseX >= btnX && mouseX < btnX + buttonWidth &&
                    mouseY >= btnY && mouseY < btnY + BUTTON_HEIGHT) {
                    executeAction(actions[i], selected.id());
                    return true;
                }
            }
        }

        return true; // Consume click even if nothing hit within panel
    }

    private void executeAction(String action, String targetId) {
        Architect.LOGGER.debug("GUI action: {} on {}", action, targetId);
        ClientPlayNetworking.send(new GuiActionPacket(action, targetId != null ? targetId : "", ""));
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Check if mouse is over list area
        int currentY = y + PADDING + 12 + SEARCH_HEIGHT + PADDING;
        int listHeight = HEIGHT - (currentY - y) - BUTTON_HEIGHT - PADDING * 2;

        if (mouseX >= x + PADDING && mouseX < x + WIDTH - PADDING &&
            mouseY >= currentY && mouseY < currentY + listHeight) {
            int maxScroll = Math.max(0, filteredConstructions.size() - getVisibleItemCount());
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) verticalAmount));
            return true;
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
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
    }
}
