package it.magius.struttura.architect.client.gui;

import it.magius.struttura.architect.config.ArchitectConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * STRUTTURA mod settings screen.
 * Contains settings for API key and timeout.
 * Overlay position settings are in a separate screen.
 */
@Environment(EnvType.CLIENT)
public class StrutturaSettingsScreen extends Screen {

    // Background image (same as title screen)
    private static final Identifier BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
            "architect", "textures/gui/title_background.png");
    private static final int BG_WIDTH = 1536;
    private static final int BG_HEIGHT = 658;

    private static final int CONTENT_WIDTH = 350;
    private static final int LINE_HEIGHT = 12;
    private static final int FIELD_HEIGHT = 16;
    private static final int SPACING = 6;
    private static final int BUTTON_HEIGHT = 20;
    private static final int LABEL_WIDTH = 160;

    private final Screen parentScreen;

    // Input fields
    private EditBoxHelper apiKeyBox;
    private EditBoxHelper timeoutBox;

    // Buttons
    private Button overlayPositionBtn;
    private Button requestApiKeyBtn;

    // Pre-wrapped disclaimer lines
    private List<String> disclaimerWrapped = new ArrayList<>();

    public StrutturaSettingsScreen(Screen parentScreen) {
        super(Component.translatable("struttura.settings.title"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();

        // Load current config values
        ArchitectConfig config = ArchitectConfig.getInstance();

        int centerX = this.width / 2;
        int contentLeft = centerX - CONTENT_WIDTH / 2;

        // Calculate disclaimer height (word-wrapped to fit screen width)
        String disclaimer = getDisclaimerText();
        disclaimerWrapped = wrapText(disclaimer, this.width - 20);
        int disclaimerHeight = disclaimerWrapped.size() * (LINE_HEIGHT + 2);

        int currentY = 30 + disclaimerHeight + 10; // Start below title and disclaimer

        // === API Key Field (label + field + button on same line) ===
        int apiKeyBtnWidth = 20;
        int apiKeyFieldWidth = CONTENT_WIDTH - LABEL_WIDTH - apiKeyBtnWidth - 4;
        apiKeyBox = new EditBoxHelper(
            this.font, contentLeft + LABEL_WIDTH, currentY, apiKeyFieldWidth, FIELD_HEIGHT,
            "Enter API Key...",
            s -> s.chars().allMatch(c -> c >= 32 && c != '"'),
            null
        );
        apiKeyBox.setValue(config.getApikey());
        apiKeyBox.setMaxLength(512);

        // Button to open API key setup wizard
        requestApiKeyBtn = Button.builder(
            Component.literal("\uD83D\uDD11"), // Key emoji 🔑
            button -> this.minecraft.setScreen(new ApiKeySetupScreen(this))
        ).bounds(contentLeft + LABEL_WIDTH + apiKeyFieldWidth + 4, currentY, apiKeyBtnWidth, FIELD_HEIGHT).build();
        this.addRenderableWidget(requestApiKeyBtn);
        currentY += FIELD_HEIGHT + SPACING + 2;

        // === Request Timeout Field (label + field on same line) ===
        timeoutBox = new EditBoxHelper(
            this.font, contentLeft + LABEL_WIDTH, currentY, 60, FIELD_HEIGHT,
            "60",
            s -> s.chars().allMatch(Character::isDigit),
            null
        );
        timeoutBox.setValue(String.valueOf(config.getRequestTimeout()));
        timeoutBox.setMaxLength(4);
        currentY += FIELD_HEIGHT + SPACING + 10;

        // === Overlay Position Button (shows current state) ===
        int overlayBtnWidth = CONTENT_WIDTH;
        String overlayLabel = getOverlayButtonLabel(config);
        overlayPositionBtn = Button.builder(
            Component.literal(overlayLabel),
            button -> this.minecraft.setScreen(new OverlayPositionScreen(this))
        ).bounds(contentLeft, currentY, overlayBtnWidth, BUTTON_HEIGHT).build();
        this.addRenderableWidget(overlayPositionBtn);

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

    private void onSave() {
        ArchitectConfig config = ArchitectConfig.getInstance();

        // Update API settings
        config.setApikey(apiKeyBox.getValue());

        // Parse timeout with validation
        try {
            int timeout = Integer.parseInt(timeoutBox.getValue());
            timeout = Math.max(1, Math.min(300, timeout)); // Clamp 1-300
            config.setRequestTimeout(timeout);
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
        // Draw background image (same as title screen)
        renderBackground(graphics);

        // Draw semi-transparent dark overlay for readability
        graphics.fill(0, 0, this.width, this.height, 0xA0000000);

        int centerX = this.width / 2;
        int contentLeft = centerX - CONTENT_WIDTH / 2;

        // Draw title centered at top
        graphics.drawCenteredString(this.font, this.title, centerX, 10, 0xFFFFFFFF);

        // Draw disclaimer from config (word-wrapped with centered alignment)
        int disclaimerY = 26;
        for (String line : disclaimerWrapped) {
            graphics.drawCenteredString(this.font, line, centerX, disclaimerY, 0xFFAAAAAA);
            disclaimerY += LINE_HEIGHT + 2;
        }

        int currentY = 30 + disclaimerWrapped.size() * (LINE_HEIGHT + 2) + 10;

        // === API Key (label + field on same line) ===
        graphics.drawString(this.font, Component.translatable("struttura.settings.apikey").getString(), contentLeft, currentY + 4, 0xFFFFFFFF, false);
        apiKeyBox.setPosition(contentLeft + LABEL_WIDTH, currentY);
        apiKeyBox.render(graphics, mouseX, mouseY, partialTick);
        currentY += FIELD_HEIGHT + SPACING + 2;

        // === Timeout (label + field on same line) ===
        graphics.drawString(this.font, Component.translatable("struttura.settings.timeout").getString(), contentLeft, currentY + 4, 0xFFFFFFFF, false);
        timeoutBox.setPosition(contentLeft + LABEL_WIDTH, currentY);
        timeoutBox.render(graphics, mouseX, mouseY, partialTick);

        // Render all buttons
        for (var child : this.children()) {
            if (child instanceof Button btn) {
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

        // Calculate aspect ratio to cover the entire screen (like CSS background-size: cover)
        float imageAspect = (float) BG_WIDTH / BG_HEIGHT;
        float screenAspect = (float) screenWidth / screenHeight;

        float u = 0, v = 0;
        int regionWidth = BG_WIDTH, regionHeight = BG_HEIGHT;

        if (screenAspect > imageAspect) {
            // Screen is wider than image - crop top/bottom
            int visibleTextureHeight = (int) (BG_WIDTH / screenAspect);
            v = (BG_HEIGHT - visibleTextureHeight) / 2f;
            regionHeight = visibleTextureHeight;
        } else {
            // Screen is taller than image - crop left/right
            int visibleTextureWidth = (int) (BG_HEIGHT * screenAspect);
            u = (BG_WIDTH - visibleTextureWidth) / 2f;
            regionWidth = visibleTextureWidth;
        }

        // Draw the background image
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

        // Handle edit box clicks
        if (apiKeyBox.mouseClicked(mouseX, mouseY, button)) return true;
        if (timeoutBox.mouseClicked(mouseX, mouseY, button)) return true;

        // Unfocus all if clicked elsewhere
        apiKeyBox.setFocused(false);
        timeoutBox.setFocused(false);

        return super.mouseClicked(event, consumed);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        int scanCode = event.scancode();
        int modifiers = event.modifiers();

        // Handle edit box key input
        if (apiKeyBox.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (timeoutBox.keyPressed(keyCode, scanCode, modifiers)) return true;

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
        if (apiKeyBox.charTyped(chr, modifiers)) return true;
        if (timeoutBox.charTyped(chr, modifiers)) return true;

        return super.charTyped(event);
    }

    private void cycleFocus() {
        EditBoxHelper[] boxes = {apiKeyBox, timeoutBox};
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

    /**
     * Gets the disclaimer text from config, or falls back to localized default.
     * Uses the current Minecraft language setting.
     */
    private String getDisclaimerText() {
        ArchitectConfig config = ArchitectConfig.getInstance();

        // Get current language code from Minecraft (e.g., "en_us", "it_it")
        String langCode = Minecraft.getInstance().getLanguageManager().getSelected();
        // Convert Minecraft format (en_us) to BCP 47 (en-US)
        String bcp47Lang = langCode.replace('_', '-');

        // Try to get disclaimer from config
        String disclaimer = config.getDisclaimerForLanguage(bcp47Lang);

        // If empty, use localized fallback
        if (disclaimer == null || disclaimer.isEmpty()) {
            disclaimer = Component.translatable("struttura.settings.disclaimer.default").getString();
        }

        return disclaimer;
    }

    /**
     * Generates the overlay button label showing current state.
     * Format: "Overlay Position: Top/HCenter 0%,0%"
     */
    private String getOverlayButtonLabel(ArchitectConfig config) {
        String baseLabel = Component.translatable("struttura.settings.overlay").getString();
        String anchorV = config.getOverlayAnchorV();
        String anchorH = config.getOverlayAnchorH();
        int offsetX = config.getOverlayOffsetX();
        int offsetY = config.getOverlayOffsetY();
        return baseLabel + ": " + anchorV + "/" + anchorH + " " + offsetX + "%," + offsetY + "%";
    }

    /**
     * Wraps text to fit within maxWidth pixels, respecting explicit newlines.
     */
    private List<String> wrapText(String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        String[] paragraphs = text.split("\n");

        for (String paragraph : paragraphs) {
            if (paragraph.trim().isEmpty()) {
                result.add("");
                continue;
            }

            String[] words = paragraph.split(" ");
            StringBuilder currentLine = new StringBuilder();

            for (String word : words) {
                if (currentLine.isEmpty()) {
                    currentLine.append(word);
                } else {
                    String test = currentLine + " " + word;
                    if (this.font.width(test) <= maxWidth) {
                        currentLine.append(" ").append(word);
                    } else {
                        result.add(currentLine.toString());
                        currentLine = new StringBuilder(word);
                    }
                }
            }

            if (!currentLine.isEmpty()) {
                result.add(currentLine.toString());
            }
        }

        return result;
    }

}
