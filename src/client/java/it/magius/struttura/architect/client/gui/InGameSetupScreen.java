package it.magius.struttura.architect.client.gui;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.network.InGameListsPacket;
import it.magius.struttura.architect.network.InGameSelectPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Screen for selecting an InGame building list when starting a new world.
 * Shows available lists and allows player to choose one or decline InGame mode.
 */
@Environment(EnvType.CLIENT)
public class InGameSetupScreen extends Screen {

    private static final int CONTENT_WIDTH = 350;
    private static final int LIST_ITEM_HEIGHT = 24;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 0;
    private static final int ICON_SIZE = 16;  // Standard item icon size

    // Default fallback icon
    private static final ItemStack DEFAULT_ICON = new ItemStack(Items.BOOK);

    // Cache for resolved ItemStacks from icon IDs
    private final Map<String, ItemStack> iconCache = new HashMap<>();

    private final List<InGameListsPacket.ListInfo> lists;
    private final boolean isNewWorld;
    private final boolean connectionError;

    // List buttons
    private final List<Button> listButtons = new ArrayList<>();
    private Button declineBtn;
    private Button skipBtn;

    // Scroll state
    private int scrollOffset = 0;
    private static final int MAX_VISIBLE_ITEMS = 4;
    private static final int SCROLL_BUTTON_SIZE = 20;

    // Scroll buttons
    private Button scrollUpBtn;
    private Button scrollDownBtn;

    public InGameSetupScreen(List<InGameListsPacket.ListInfo> lists, boolean isNewWorld, boolean connectionError) {
        super(Component.translatable("struttura.ingame.setup.title"));
        this.lists = lists;
        this.isNewWorld = isNewWorld;
        this.connectionError = connectionError;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int contentLeft = centerX - CONTENT_WIDTH / 2;

        // Clear previous buttons
        listButtons.clear();

        // Calculate vertical layout
        int titleY = 30;
        int subtitleY = titleY + 15;
        int listStartY = subtitleY + 30;

        // Create list item buttons
        // Get current Minecraft language for localization
        String langCode = this.minecraft != null ? this.minecraft.getLanguageManager().getSelected() : "en_us";

        int visibleItems = Math.min(lists.size(), MAX_VISIBLE_ITEMS);
        for (int i = 0; i < lists.size(); i++) {
            InGameListsPacket.ListInfo info = lists.get(i);
            final int index = i;

            // Show localized list name with building count (only if count > 0)
            String localizedName = info.getLocalizedName(langCode);
            String buttonText = info.buildingCount() > 0
                ? localizedName + " (" + info.buildingCount() + ")"
                : localizedName;
            Button btn = Button.builder(
                Component.literal(buttonText),
                button -> onListSelected(index)
            ).bounds(contentLeft, listStartY + (i * (LIST_ITEM_HEIGHT + SPACING)),
                     CONTENT_WIDTH, BUTTON_HEIGHT)
             .build();

            listButtons.add(btn);
            // Add all buttons as widgets, visibility will be managed in render
            this.addRenderableWidget(btn);
        }

        // Scroll buttons (only if more than MAX_VISIBLE_ITEMS lists)
        if (lists.size() > MAX_VISIBLE_ITEMS) {
            int listEndY = listStartY + (MAX_VISIBLE_ITEMS * (LIST_ITEM_HEIGHT + SPACING));
            int scrollBtnX = contentLeft + CONTENT_WIDTH + 5;

            // Scroll up button
            scrollUpBtn = Button.builder(
                Component.literal("\u25B2"),
                button -> scrollUp()
            ).bounds(scrollBtnX, listStartY, SCROLL_BUTTON_SIZE, SCROLL_BUTTON_SIZE).build();
            this.addRenderableWidget(scrollUpBtn);

            // Scroll down button
            scrollDownBtn = Button.builder(
                Component.literal("\u25BC"),
                button -> scrollDown()
            ).bounds(scrollBtnX, listEndY - SCROLL_BUTTON_SIZE - 3, SCROLL_BUTTON_SIZE, SCROLL_BUTTON_SIZE).build();
            this.addRenderableWidget(scrollDownBtn);

            updateScrollButtonStates();
        }

        // Bottom buttons - single row with two buttons
        int bottomY = this.height - 40;
        int buttonWidth = 180;
        int buttonSpacing = 10;
        int totalWidth = (buttonWidth * 2) + buttonSpacing;
        int buttonsLeft = centerX - totalWidth / 2;

        // Skip button (left) - "Start World and retry later"
        skipBtn = Button.builder(
            Component.translatable("struttura.ingame.setup.skip"),
            button -> onSkip()
        ).bounds(buttonsLeft, bottomY, buttonWidth, BUTTON_HEIGHT).build();
        this.addRenderableWidget(skipBtn);

        // Decline button (right) - "Disable Adventure Mode" with red/cancel color
        declineBtn = Button.builder(
            Component.translatable("struttura.ingame.setup.decline").withStyle(style -> style.withColor(0xFF6666)),
            button -> onDecline()
        ).bounds(buttonsLeft + buttonWidth + buttonSpacing, bottomY, buttonWidth, BUTTON_HEIGHT).build();
        this.addRenderableWidget(declineBtn);
    }

    private void onListSelected(int index) {
        if (index < 0 || index >= lists.size()) {
            return;
        }

        InGameListsPacket.ListInfo selected = lists.get(index);
        String langCode = this.minecraft != null ? this.minecraft.getLanguageManager().getSelected() : "en_us";
        String localizedName = selected.getLocalizedName(langCode);
        Architect.LOGGER.info("Selected InGame list: {} (id={})", localizedName, selected.id());

        // Send selection to server
        ClientPlayNetworking.send(InGameSelectPacket.select(selected.id(), localizedName));

        // Close screen
        this.onClose();
    }

    private void onDecline() {
        Architect.LOGGER.info("Declined InGame mode");

        // Send decline to server (permanently disables adventure mode)
        ClientPlayNetworking.send(InGameSelectPacket.decline());

        // Close screen
        this.onClose();
    }

    private void onSkip() {
        Architect.LOGGER.info("Skipped InGame mode for now");

        // Send skip to server (will retry next world load)
        ClientPlayNetworking.send(InGameSelectPacket.skip());

        // Close screen
        this.onClose();
    }

    private void scrollUp() {
        if (scrollOffset > 0) {
            scrollOffset--;
            updateScrollButtonStates();
        }
    }

    private void scrollDown() {
        if (scrollOffset + MAX_VISIBLE_ITEMS < lists.size()) {
            scrollOffset++;
            updateScrollButtonStates();
        }
    }

    private void updateScrollButtonStates() {
        if (scrollUpBtn != null) {
            scrollUpBtn.active = scrollOffset > 0;
        }
        if (scrollDownBtn != null) {
            scrollDownBtn.active = scrollOffset + MAX_VISIBLE_ITEMS < lists.size();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Draw background image
        renderBackground(graphics);

        // Draw semi-transparent dark overlay
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);

        int centerX = this.width / 2;
        int contentLeft = centerX - CONTENT_WIDTH / 2;

        // Draw worm logo in top-left corner (uses centralized rendering for consistent antialiasing)
        GuiAssets.renderWormScaled(graphics, this.height, 0.30f, 10, 0);

        // Title - "The adventure begins!"
        int titleY = 30;
        graphics.drawCenteredString(this.font, this.title, centerX, titleY, 0xFFFFAA00);

        // Subtitle - show error message in red if connection error
        int subtitleY = titleY + 15;
        if (connectionError) {
            Component subtitle = Component.translatable("struttura.ingame.setup.subtitle.error");
            graphics.drawCenteredString(this.font, subtitle, centerX, subtitleY, 0xFFFF6666);
        } else {
            Component subtitle = Component.translatable("struttura.ingame.setup.subtitle");
            graphics.drawCenteredString(this.font, subtitle, centerX, subtitleY, 0xFFCCCCCC);
        }

        // List items - update button positions and visibility based on scroll
        int listStartY = subtitleY + 30;

        for (int i = 0; i < listButtons.size(); i++) {
            Button btn = listButtons.get(i);
            int visibleIndex = i - scrollOffset;

            if (visibleIndex >= 0 && visibleIndex < MAX_VISIBLE_ITEMS) {
                // Button is visible
                int itemY = listStartY + (visibleIndex * (LIST_ITEM_HEIGHT + SPACING));
                btn.setY(itemY);
                btn.visible = true;
            } else {
                // Button is not visible (scrolled out)
                btn.visible = false;
            }
        }


        // Info text in center area
        if (lists.isEmpty()) {
            int messageY = this.height / 2 - 30;

            if (connectionError) {
                // Connection error details
                graphics.drawCenteredString(this.font,
                    Component.translatable("struttura.ingame.setup.connection_error.line1"),
                    centerX, messageY, 0xFFCCCCCC);

                messageY += 14;
                graphics.drawCenteredString(this.font,
                    Component.translatable("struttura.ingame.setup.connection_error.line2"),
                    centerX, messageY, 0xFFCCCCCC);

                messageY += 20;
                graphics.drawCenteredString(this.font,
                    Component.translatable("struttura.ingame.setup.connection_error.url"),
                    centerX, messageY, 0xFF88AAFF);

                messageY += 20;
                graphics.drawCenteredString(this.font,
                    Component.translatable("struttura.ingame.setup.connection_error.retry"),
                    centerX, messageY, 0xFF888888);
            } else {
                // No lists available
                graphics.drawCenteredString(this.font,
                    Component.translatable("struttura.ingame.setup.nolists"),
                    centerX, messageY, 0xFFFF8888);
            }
        }

        // Draw hint below buttons
        int hintY = this.height - 40 + BUTTON_HEIGHT + 5;
        graphics.drawCenteredString(this.font,
            Component.translatable("struttura.ingame.setup.decline.hint"),
            centerX, hintY, 0xFF666666);

        // Render widgets (buttons)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render decoration icons for lists (at left and right edges of button)
        for (int i = 0; i < listButtons.size(); i++) {
            Button btn = listButtons.get(i);
            if (!btn.visible) continue;

            InGameListsPacket.ListInfo info = lists.get(i);

            // Get icon from the list info (resolved dynamically from API)
            ItemStack icon = getIconForList(info.icon());

            int btnX = btn.getX();
            int btnY = btn.getY();
            int btnWidth = btn.getWidth();
            int btnHeight = btn.getHeight();

            // Vertical center
            int iconY = btnY + (btnHeight - ICON_SIZE) / 2;

            // Render icon on the left edge (with small padding)
            int leftIconX = btnX + 4;
            graphics.renderItem(icon, leftIconX, iconY);

            // Render icon on the right edge (with small padding)
            int rightIconX = btnX + btnWidth - ICON_SIZE - 4;
            graphics.renderItem(icon, rightIconX, iconY);
        }
    }

    /**
     * Resolves a Minecraft item ID (e.g., "minecraft:bell") to an ItemStack.
     * Caches results for performance.
     */
    private ItemStack getIconForList(String iconId) {
        if (iconId == null || iconId.isEmpty()) {
            return DEFAULT_ICON;
        }

        // Check cache first
        if (iconCache.containsKey(iconId)) {
            return iconCache.get(iconId);
        }

        // Try to resolve the item from registry
        try {
            Identifier itemId = Identifier.tryParse(iconId);
            if (itemId != null) {
                var itemOptional = BuiltInRegistries.ITEM.get(itemId);
                if (itemOptional.isPresent()) {
                    Item item = itemOptional.get().value();
                    if (item != Items.AIR) {
                        ItemStack stack = new ItemStack(item);
                        iconCache.put(iconId, stack);
                        return stack;
                    }
                }
            }
        } catch (Exception e) {
            Architect.LOGGER.warn("Failed to resolve icon '{}': {}", iconId, e.getMessage());
        }

        // Fallback to default icon
        iconCache.put(iconId, DEFAULT_ICON);
        return DEFAULT_ICON;
    }

    /**
     * Renders the background image.
     */
    private void renderBackground(GuiGraphics graphics) {
        GuiAssets.renderBackground(graphics, this.width, this.height);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (lists.size() > MAX_VISIBLE_ITEMS) {
            if (scrollY > 0 && scrollOffset > 0) {
                scrollOffset--;
                updateScrollButtonStates();
                return true;
            } else if (scrollY < 0 && scrollOffset + MAX_VISIBLE_ITEMS < lists.size()) {
                scrollOffset++;
                updateScrollButtonStates();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Don't allow closing with ESC - must make a choice
        return false;
    }

    @Override
    public void onClose() {
        // Return to game (no parent screen)
        this.minecraft.setScreen(null);
    }
}
