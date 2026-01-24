package it.magius.struttura.architect.client.gui;

import it.magius.struttura.architect.config.ArchitectConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Screen for configuring overlay position settings.
 * Accessible from StrutturaSettingsScreen.
 */
@Environment(EnvType.CLIENT)
public class OverlayPositionScreen extends Screen {

    // Background image (same as title screen)
    private static final Identifier BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
            "architect", "textures/gui/title_background.png");
    private static final int BG_WIDTH = 1536;
    private static final int BG_HEIGHT = 658;

    private static final int CONTENT_WIDTH = 300;
    private static final int LINE_HEIGHT = 12;
    private static final int FIELD_HEIGHT = 16;
    private static final int SPACING = 6;
    private static final int BUTTON_HEIGHT = 20;
    private static final int LABEL_WIDTH = 70;

    private final Screen parentScreen;

    // Input fields
    private EditBoxHelper offsetXBox;
    private EditBoxHelper offsetYBox;

    // Toggle states (loaded from config)
    private String anchorV = "TOP";
    private String anchorH = "HCENTER";

    // Toggle buttons for anchor
    private Button btnTop, btnVCenter, btnBottom;
    private Button btnLeft, btnHCenter, btnRight;

    public OverlayPositionScreen(Screen parentScreen) {
        super(Component.translatable("struttura.settings.overlay"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();

        // Load current config values
        ArchitectConfig config = ArchitectConfig.getInstance();
        anchorV = config.getOverlayAnchorV();
        anchorH = config.getOverlayAnchorH();

        int centerX = this.width / 2;
        int contentLeft = centerX - CONTENT_WIDTH / 2;
        int currentY = 50;

        // Vertical anchor buttons (label + buttons on same row)
        int btnWidth = 60;
        int btnSpacing = 4;
        int btnStartX = contentLeft + LABEL_WIDTH;

        btnTop = Button.builder(Component.literal("Top"), b -> {
            anchorV = "TOP";
            updateAnchorButtons();
        }).bounds(btnStartX, currentY, btnWidth, BUTTON_HEIGHT).build();

        btnVCenter = Button.builder(Component.literal("VCenter"), b -> {
            anchorV = "VCENTER";
            updateAnchorButtons();
        }).bounds(btnStartX + btnWidth + btnSpacing, currentY, btnWidth, BUTTON_HEIGHT).build();

        btnBottom = Button.builder(Component.literal("Bottom"), b -> {
            anchorV = "BOTTOM";
            updateAnchorButtons();
        }).bounds(btnStartX + (btnWidth + btnSpacing) * 2, currentY, btnWidth, BUTTON_HEIGHT).build();

        this.addRenderableWidget(btnTop);
        this.addRenderableWidget(btnVCenter);
        this.addRenderableWidget(btnBottom);
        currentY += BUTTON_HEIGHT + SPACING;

        // Horizontal anchor buttons (label + buttons on same row)
        btnLeft = Button.builder(Component.literal("Left"), b -> {
            anchorH = "LEFT";
            updateAnchorButtons();
        }).bounds(btnStartX, currentY, btnWidth, BUTTON_HEIGHT).build();

        btnHCenter = Button.builder(Component.literal("HCenter"), b -> {
            anchorH = "HCENTER";
            updateAnchorButtons();
        }).bounds(btnStartX + btnWidth + btnSpacing, currentY, btnWidth, BUTTON_HEIGHT).build();

        btnRight = Button.builder(Component.literal("Right"), b -> {
            anchorH = "RIGHT";
            updateAnchorButtons();
        }).bounds(btnStartX + (btnWidth + btnSpacing) * 2, currentY, btnWidth, BUTTON_HEIGHT).build();

        this.addRenderableWidget(btnLeft);
        this.addRenderableWidget(btnHCenter);
        this.addRenderableWidget(btnRight);
        currentY += BUTTON_HEIGHT + SPACING + 4;

        // Offset X and Y fields
        currentY += LINE_HEIGHT + 4;
        int offsetFieldWidth = 50;
        int offsetLabelWidth = 50;

        offsetXBox = new EditBoxHelper(
            this.font, contentLeft + offsetLabelWidth, currentY, offsetFieldWidth, FIELD_HEIGHT,
            "0",
            s -> s.chars().allMatch(Character::isDigit),
            null
        );
        offsetXBox.setValue(String.valueOf(config.getOverlayOffsetX()));
        offsetXBox.setMaxLength(3);

        offsetYBox = new EditBoxHelper(
            this.font, contentLeft + offsetLabelWidth + offsetFieldWidth + 30 + offsetLabelWidth, currentY, offsetFieldWidth, FIELD_HEIGHT,
            "0",
            s -> s.chars().allMatch(Character::isDigit),
            null
        );
        offsetYBox.setValue(String.valueOf(config.getOverlayOffsetY()));
        offsetYBox.setMaxLength(3);

        // Update anchor button visual states
        updateAnchorButtons();

        // === Bottom Buttons ===
        int bottomButtonWidth = 100;
        int bottomButtonSpacing = 10;

        // Save button
        this.addRenderableWidget(Button.builder(
            Component.translatable("struttura.settings.save"),
            button -> onSave()
        ).bounds(centerX - bottomButtonWidth - bottomButtonSpacing / 2, this.height - 27, bottomButtonWidth, BUTTON_HEIGHT).build());

        // Cancel button
        this.addRenderableWidget(Button.builder(
            Component.translatable("struttura.settings.cancel"),
            button -> this.minecraft.setScreen(this.parentScreen)
        ).bounds(centerX + bottomButtonSpacing / 2, this.height - 27, bottomButtonWidth, BUTTON_HEIGHT).build());
    }

    private void updateAnchorButtons() {
        // Visual feedback for selected state using alpha/color
        btnTop.setAlpha("TOP".equals(anchorV) ? 1.0f : 0.6f);
        btnVCenter.setAlpha("VCENTER".equals(anchorV) ? 1.0f : 0.6f);
        btnBottom.setAlpha("BOTTOM".equals(anchorV) ? 1.0f : 0.6f);

        btnLeft.setAlpha("LEFT".equals(anchorH) ? 1.0f : 0.6f);
        btnHCenter.setAlpha("HCENTER".equals(anchorH) ? 1.0f : 0.6f);
        btnRight.setAlpha("RIGHT".equals(anchorH) ? 1.0f : 0.6f);
    }

    private void onSave() {
        ArchitectConfig config = ArchitectConfig.getInstance();

        // Update overlay settings
        config.setOverlayAnchorV(anchorV);
        config.setOverlayAnchorH(anchorH);

        // Parse offsets with validation
        try {
            int offsetX = Integer.parseInt(offsetXBox.getValue());
            config.setOverlayOffsetX(offsetX);
        } catch (NumberFormatException e) {
            // Keep existing value
        }
        try {
            int offsetY = Integer.parseInt(offsetYBox.getValue());
            config.setOverlayOffsetY(offsetY);
        } catch (NumberFormatException e) {
            // Keep existing value
        }

        // Save to file
        config.save();

        // Return to parent screen
        this.minecraft.setScreen(this.parentScreen);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Draw background image
        renderBackground(graphics);

        // Draw semi-transparent dark overlay for readability
        graphics.fill(0, 0, this.width, this.height, 0xA0000000);

        int centerX = this.width / 2;
        int contentLeft = centerX - CONTENT_WIDTH / 2;

        // Draw title centered at top
        graphics.drawCenteredString(this.font, this.title, centerX, 15, 0xFFFFFFFF);

        int currentY = 50;

        // Vertical anchor label (aligned with buttons)
        graphics.drawString(this.font, "Vertical:", contentLeft, currentY + 6, 0xFFAAAAAA, false);
        currentY += BUTTON_HEIGHT + SPACING;

        // Horizontal anchor label (aligned with buttons)
        graphics.drawString(this.font, "Horizontal:", contentLeft, currentY + 6, 0xFFAAAAAA, false);
        currentY += BUTTON_HEIGHT + SPACING + 4;

        // Offset labels and fields
        graphics.drawString(this.font, Component.translatable("struttura.settings.offset").getString(), contentLeft, currentY, 0xFFFFFFFF, false);
        currentY += LINE_HEIGHT + 4;

        int offsetLabelWidth = 50;
        int offsetFieldWidth = 50;
        graphics.drawString(this.font, "X (%):", contentLeft, currentY + 4, 0xFFAAAAAA, false);
        offsetXBox.setPosition(contentLeft + offsetLabelWidth, currentY);
        offsetXBox.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(this.font, "Y (%):", contentLeft + offsetLabelWidth + offsetFieldWidth + 30, currentY + 4, 0xFFAAAAAA, false);
        offsetYBox.setPosition(contentLeft + offsetLabelWidth + offsetFieldWidth + 30 + offsetLabelWidth, currentY);
        offsetYBox.render(graphics, mouseX, mouseY, partialTick);

        // Render buttons manually
        btnTop.render(graphics, mouseX, mouseY, partialTick);
        btnVCenter.render(graphics, mouseX, mouseY, partialTick);
        btnBottom.render(graphics, mouseX, mouseY, partialTick);
        btnLeft.render(graphics, mouseX, mouseY, partialTick);
        btnHCenter.render(graphics, mouseX, mouseY, partialTick);
        btnRight.render(graphics, mouseX, mouseY, partialTick);

        // Render Save/Cancel buttons
        for (var child : this.children()) {
            if (child instanceof Button btn && btn != btnTop && btn != btnVCenter && btn != btnBottom
                    && btn != btnLeft && btn != btnHCenter && btn != btnRight) {
                btn.render(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    /**
     * Renders the background image (same as title screen).
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
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return true;

        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        // Unfocus all boxes first, then let the clicked one gain focus
        offsetXBox.setFocused(false);
        offsetYBox.setFocused(false);

        // Handle edit box clicks
        if (offsetXBox.mouseClicked(mouseX, mouseY, button)) return true;
        if (offsetYBox.mouseClicked(mouseX, mouseY, button)) return true;

        return super.mouseClicked(event, consumed);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        int scanCode = event.scancode();
        int modifiers = event.modifiers();

        // Handle edit box key input
        if (offsetXBox.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (offsetYBox.keyPressed(keyCode, scanCode, modifiers)) return true;

        // Tab to cycle focus
        if (keyCode == 258) { // Tab
            cycleFocus();
            return true;
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char chr = (char) event.codepoint();
        int modifiers = event.modifiers();

        // Handle edit box char input
        if (offsetXBox.charTyped(chr, modifiers)) return true;
        if (offsetYBox.charTyped(chr, modifiers)) return true;

        return super.charTyped(event);
    }

    private void cycleFocus() {
        EditBoxHelper[] boxes = {offsetXBox, offsetYBox};
        int focusedIndex = -1;
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i].isFocused()) {
                focusedIndex = i;
                boxes[i].setFocused(false);
                break;
            }
        }
        int nextIndex = (focusedIndex + 1) % boxes.length;
        boxes[nextIndex].setFocused(true);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parentScreen);
    }
}
