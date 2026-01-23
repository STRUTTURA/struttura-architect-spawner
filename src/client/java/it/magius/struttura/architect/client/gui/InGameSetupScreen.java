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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen for selecting an InGame building list when starting a new world.
 * Shows available lists and allows player to choose one or decline InGame mode.
 */
@Environment(EnvType.CLIENT)
public class InGameSetupScreen extends Screen {

    // Background image
    private static final Identifier BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
            "architect", "textures/gui/title_background.png");
    private static final int BG_WIDTH = 1536;
    private static final int BG_HEIGHT = 658;

    private static final int CONTENT_WIDTH = 450;
    private static final int LIST_ITEM_HEIGHT = 50;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACING = 8;

    private final List<InGameListsPacket.ListInfo> lists;
    private final boolean isNewWorld;

    // List buttons
    private final List<Button> listButtons = new ArrayList<>();
    private Button declineBtn;

    // Scroll state
    private int scrollOffset = 0;
    private static final int MAX_VISIBLE_ITEMS = 4;

    public InGameSetupScreen(List<InGameListsPacket.ListInfo> lists, boolean isNewWorld) {
        super(Component.translatable("struttura.ingame.setup.title"));
        this.lists = lists;
        this.isNewWorld = isNewWorld;
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

        // Decline button at bottom
        int bottomY = this.height - 40;
        declineBtn = Button.builder(
            Component.translatable("struttura.ingame.setup.decline"),
            button -> onDecline()
        ).bounds(centerX - 100, bottomY, 200, BUTTON_HEIGHT).build();
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

        // Send decline to server
        ClientPlayNetworking.send(InGameSelectPacket.decline());

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

        // Title
        int titleY = 30;
        graphics.drawCenteredString(this.font, this.title, centerX, titleY, 0xFFFFAA00);

        // Subtitle
        int subtitleY = titleY + 15;
        Component subtitle = Component.translatable("struttura.ingame.setup.subtitle");
        graphics.drawCenteredString(this.font, subtitle, centerX, subtitleY, 0xFFCCCCCC);

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

        // Info text at bottom
        if (lists.isEmpty()) {
            int noListsY = this.height / 2;
            graphics.drawCenteredString(this.font,
                Component.translatable("struttura.ingame.setup.nolists"),
                centerX, noListsY, 0xFFFF8888);
        }

        // Render widgets (buttons)
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Renders the background image.
     */
    private void renderBackground(GuiGraphics graphics) {
        int screenWidth = this.width;
        int screenHeight = this.height;

        float imageAspect = (float) BG_WIDTH / BG_HEIGHT;
        float screenAspect = (float) screenWidth / screenHeight;

        float u = 0, v = 0;
        int regionWidth = BG_WIDTH, regionHeight = BG_HEIGHT;

        if (screenAspect > imageAspect) {
            int visibleTextureHeight = (int) (BG_WIDTH / screenAspect);
            v = (BG_HEIGHT - visibleTextureHeight) / 2f;
            regionHeight = visibleTextureHeight;
        } else {
            int visibleTextureWidth = (int) (BG_HEIGHT * screenAspect);
            u = (BG_WIDTH - visibleTextureWidth) / 2f;
            regionWidth = visibleTextureWidth;
        }

        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                BACKGROUND_TEXTURE,
                0, 0,
                u, v,
                screenWidth, screenHeight,
                regionWidth, regionHeight,
                BG_WIDTH, BG_HEIGHT
        );
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
