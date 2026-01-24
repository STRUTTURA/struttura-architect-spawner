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
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen for selecting an InGame building list when starting a new world.
 * Shows available lists and allows player to choose one or decline InGame mode.
 */
@Environment(EnvType.CLIENT)
public class InGameSetupScreen extends Screen {

    private static final int CONTENT_WIDTH = 450;
    private static final int LIST_ITEM_HEIGHT = 50;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 8;

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
        int visibleItems = Math.min(lists.size(), MAX_VISIBLE_ITEMS);
        for (int i = 0; i < lists.size(); i++) {
            InGameListsPacket.ListInfo info = lists.get(i);
            final int index = i;

            // Show list name with building count
            String buttonText = info.name() + " (" + info.buildingCount() + ")";
            Button btn = Button.builder(
                Component.literal(buttonText),
                button -> onListSelected(index)
            ).bounds(contentLeft, listStartY + (i * (LIST_ITEM_HEIGHT + SPACING)),
                     CONTENT_WIDTH, LIST_ITEM_HEIGHT - 10)
             .build();

            listButtons.add(btn);
            if (i < MAX_VISIBLE_ITEMS) {
                this.addRenderableWidget(btn);
            }
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
        Architect.LOGGER.info("Selected InGame list: {} (id={})", selected.name(), selected.id());

        // Send selection to server
        ClientPlayNetworking.send(InGameSelectPacket.select(selected.id(), selected.name()));

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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Draw background image
        renderBackground(graphics);

        // Draw semi-transparent dark overlay
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);

        int centerX = this.width / 2;
        int contentLeft = centerX - CONTENT_WIDTH / 2;

        // Draw worm logo in top-left corner (uses centralized rendering for consistent antialiasing)
        GuiAssets.renderWormScaled(graphics, this.height, 0.30f, 10, 10);

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

        // List items
        int listStartY = subtitleY + 30;

        for (int i = 0; i < lists.size() && i < MAX_VISIBLE_ITEMS; i++) {
            int displayIndex = i + scrollOffset;
            if (displayIndex >= lists.size()) break;

            InGameListsPacket.ListInfo info = lists.get(displayIndex);
            int itemY = listStartY + (i * (LIST_ITEM_HEIGHT + SPACING));

            // Draw item background
            graphics.fill(contentLeft - 5, itemY - 5,
                         contentLeft + CONTENT_WIDTH + 5, itemY + LIST_ITEM_HEIGHT - 5,
                         0x60000000);

            // Update button position and visibility
            if (displayIndex < listButtons.size()) {
                Button btn = listButtons.get(displayIndex);
                btn.setY(itemY);
            }

            // Draw description below button
            int descY = itemY + LIST_ITEM_HEIGHT - 18;
            String desc = info.description();
            if (desc.length() > 60) {
                desc = desc.substring(0, 57) + "...";
            }
            graphics.drawString(this.font, desc, contentLeft + 5, descY, 0xFF888888);

            // Draw building count
            String countText = info.buildingCount() + " buildings";
            int countWidth = this.font.width(countText);
            graphics.drawString(this.font, countText,
                              contentLeft + CONTENT_WIDTH - countWidth - 5, descY, 0xFF88FF88);
        }

        // Draw scroll indicators if needed
        if (lists.size() > MAX_VISIBLE_ITEMS) {
            if (scrollOffset > 0) {
                graphics.drawCenteredString(this.font, "▲", centerX, listStartY - 15, 0xFFFFFFFF);
            }
            if (scrollOffset + MAX_VISIBLE_ITEMS < lists.size()) {
                int bottomY = listStartY + (MAX_VISIBLE_ITEMS * (LIST_ITEM_HEIGHT + SPACING));
                graphics.drawCenteredString(this.font, "▼", centerX, bottomY, 0xFFFFFFFF);
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
                return true;
            } else if (scrollY < 0 && scrollOffset + MAX_VISIBLE_ITEMS < lists.size()) {
                scrollOffset++;
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
