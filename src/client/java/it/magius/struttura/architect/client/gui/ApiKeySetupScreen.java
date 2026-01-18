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
import net.minecraft.util.Util;

import java.net.URI;

/**
 * API Key setup wizard screen.
 * Guides the user through connecting their API key.
 */
@Environment(EnvType.CLIENT)
public class ApiKeySetupScreen extends Screen {

    // Background image (same as title screen)
    private static final Identifier BACKGROUND_TEXTURE = Identifier.fromNamespaceAndPath(
            "architect", "textures/gui/title_background.png");
    private static final int BG_WIDTH = 1536;
    private static final int BG_HEIGHT = 658;

    private static final int CONTENT_WIDTH = 400;
    private static final int LINE_HEIGHT = 12;
    private static final int FIELD_HEIGHT = 16;
    private static final int SPACING = 8;
    private static final int BUTTON_HEIGHT = 20;

    private final Screen parentScreen;

    // Input field for API key
    private EditBoxHelper apiKeyBox;

    // Buttons
    private Button websiteBtn;
    private Button dashboardBtn;
    private Button connectBtn;
    private Button cancelBtn;

    public ApiKeySetupScreen(Screen parentScreen) {
        super(Component.literal("STRUTTURA: ").append(Component.translatable("struttura.apikey.setup.title")));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int contentLeft = centerX - CONTENT_WIDTH / 2;

        // Calculate vertical layout - compact spacing
        int startY = 35;
        int stepHeight = 38; // Reduced from 50

        // Step 1: Website button (right after step 1 text)
        int step1BtnY = startY + LINE_HEIGHT + 4;
        websiteBtn = Button.builder(
            Component.translatable("struttura.apikey.setup.website.btn"),
            button -> openWebsite()
        ).bounds(centerX - 100, step1BtnY, 200, BUTTON_HEIGHT).build();
        this.addRenderableWidget(websiteBtn);

        // Step 2: Dashboard button (right after step 2 text)
        int step2TextY = step1BtnY + BUTTON_HEIGHT + SPACING;
        int step2BtnY = step2TextY + LINE_HEIGHT + 4;
        dashboardBtn = Button.builder(
            Component.translatable("struttura.apikey.setup.dashboard.btn"),
            button -> openDashboard()
        ).bounds(centerX - 100, step2BtnY, 200, BUTTON_HEIGHT).build();
        this.addRenderableWidget(dashboardBtn);

        // Step 3: API Key input (right after step 3 text)
        int step3TextY = step2BtnY + BUTTON_HEIGHT + SPACING;
        int step3FieldY = step3TextY + LINE_HEIGHT + 4;
        int fieldWidth = CONTENT_WIDTH - 20;
        apiKeyBox = new EditBoxHelper(
            this.font, contentLeft + 10, step3FieldY, fieldWidth, FIELD_HEIGHT,
            "us-ak_xxxxx...",
            s -> s.chars().allMatch(c -> c >= 32 && c != '"'),
            null
        );
        apiKeyBox.setMaxLength(512);

        // Step 4: Connect button (right after step 4 text)
        int step4TextY = step3FieldY + FIELD_HEIGHT + SPACING;
        int step4BtnY = step4TextY + LINE_HEIGHT + 4;
        connectBtn = Button.builder(
            Component.translatable("struttura.apikey.setup.connect"),
            button -> onConnect()
        ).bounds(centerX - 100, step4BtnY, 200, BUTTON_HEIGHT).build();
        this.addRenderableWidget(connectBtn);

        // Cancel button at bottom
        cancelBtn = Button.builder(
            Component.translatable("struttura.settings.cancel"),
            button -> this.minecraft.setScreen(this.parentScreen)
        ).bounds(centerX - 50, this.height - 30, 100, BUTTON_HEIGHT).build();
        this.addRenderableWidget(cancelBtn);
    }

    private void openWebsite() {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String www = getWwwUrl(config);
        try {
            Util.getPlatform().openUri(new URI(www));
        } catch (Exception e) {
            // Ignore URI parsing errors
        }
    }

    private void openDashboard() {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String www = getWwwUrl(config);
        String url = www + "/dashboard/api-keys";
        try {
            Util.getPlatform().openUri(new URI(url));
        } catch (Exception e) {
            // Ignore URI parsing errors
        }
    }

    private String getWwwUrl(ArchitectConfig config) {
        String www = config.getWww();
        if (www == null || www.isEmpty()) {
            www = "https://struttura.magius.it";
        }
        // Remove trailing slash if present
        if (www.endsWith("/")) {
            www = www.substring(0, www.length() - 1);
        }
        return www;
    }

    private void onConnect() {
        String apiKey = apiKeyBox.getValue().trim();
        if (apiKey.isEmpty()) {
            return;
        }

        // Save the API key
        ArchitectConfig config = ArchitectConfig.getInstance();
        config.setApikey(apiKey);
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
        graphics.drawCenteredString(this.font, this.title, centerX, 20, 0xFFFFFFFF);

        // Calculate vertical layout (same as in init) - compact spacing
        int startY = 35;

        // Step 1
        String step1Text = Component.translatable("struttura.apikey.setup.step1").getString();
        graphics.drawCenteredString(this.font, "1. " + step1Text, centerX, startY, 0xFFFFFFFF);
        int step1BtnY = startY + LINE_HEIGHT + 4;

        // Step 2
        int step2TextY = step1BtnY + BUTTON_HEIGHT + SPACING;
        String step2Text = Component.translatable("struttura.apikey.setup.step2").getString();
        graphics.drawCenteredString(this.font, "2. " + step2Text, centerX, step2TextY, 0xFFFFFFFF);
        int step2BtnY = step2TextY + LINE_HEIGHT + 4;

        // Step 3
        int step3TextY = step2BtnY + BUTTON_HEIGHT + SPACING;
        String step3Text = Component.translatable("struttura.apikey.setup.step3").getString();
        graphics.drawCenteredString(this.font, "3. " + step3Text, centerX, step3TextY, 0xFFFFFFFF);
        int step3FieldY = step3TextY + LINE_HEIGHT + 4;
        apiKeyBox.setPosition(contentLeft + 10, step3FieldY);
        apiKeyBox.render(graphics, mouseX, mouseY, partialTick);

        // Step 4
        int step4TextY = step3FieldY + FIELD_HEIGHT + SPACING;
        String step4Text = Component.translatable("struttura.apikey.setup.step4").getString();
        graphics.drawCenteredString(this.font, "4. " + step4Text, centerX, step4TextY, 0xFFFFFFFF);

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

        // Handle edit box click
        if (apiKeyBox.mouseClicked(mouseX, mouseY, button)) return true;

        // Unfocus if clicked elsewhere
        apiKeyBox.setFocused(false);

        return super.mouseClicked(event, consumed);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        int scanCode = event.scancode();
        int modifiers = event.modifiers();

        // Handle edit box key input
        if (apiKeyBox.keyPressed(keyCode, scanCode, modifiers)) return true;

        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        char chr = (char) event.codepoint();
        int modifiers = event.modifiers();

        // Handle edit box char input
        if (apiKeyBox.charTyped(chr, modifiers)) return true;

        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parentScreen);
    }
}
