package it.magius.struttura.architect.client.gui;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.config.ArchitectConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.net.URI;

/**
 * Welcome screen shown once on first Minecraft launch with STRUTTURA installed.
 * Displays a scrollable welcome message fetched from the server (or a default fallback)
 * using vanilla FittingMultiLineTextWidget (same as LikeScreen).
 * Offers three options: visit the website, set up API key, or close.
 */
@Environment(EnvType.CLIENT)
public class WelcomeScreen extends Screen {

    private static final int CONTENT_WIDTH = 380;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_SPACING = 4;
    private static final int TITLE_Y = 10;

    private final Screen parentScreen;

    public WelcomeScreen(Screen parentScreen) {
        super(Component.translatable("struttura.welcome.title"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // Fixed layout: buttons at bottom
        int totalButtonsHeight = 3 * BUTTON_HEIGHT + 2 * BUTTON_SPACING;
        int bottomMargin = 10;
        int buttonsStartY = this.height - totalButtonsHeight - bottomMargin;

        // Text area: between title and buttons
        int textPadding = (this.width - CONTENT_WIDTH) / 2;
        int textStartY = TITLE_Y + 14;
        int textHeight = Math.max(40, buttonsStartY - textStartY - 8);

        // Scrollable text widget (vanilla component, same as LikeScreen)
        String message = getWelcomeText();
        FittingMultiLineTextWidget messageWidget = new FittingMultiLineTextWidget(
            textPadding,
            textStartY,
            CONTENT_WIDTH,
            textHeight,
            Component.literal(message),
            this.font
        );
        this.addRenderableWidget(messageWidget);

        // Button 1: Visit STRUTTURA website
        this.addRenderableWidget(Button.builder(
            Component.translatable("struttura.welcome.btn.website"),
            button -> openWebsite()
        ).bounds(centerX - BUTTON_WIDTH / 2, buttonsStartY, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // Button 2: Set up personal key (opens settings)
        this.addRenderableWidget(Button.builder(
            Component.translatable("struttura.welcome.btn.setup"),
            button -> {
                markAsShown();
                this.minecraft.setScreen(new StrutturaSettingsScreen(this.parentScreen));
            }
        ).bounds(centerX - BUTTON_WIDTH / 2, buttonsStartY + BUTTON_HEIGHT + BUTTON_SPACING, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // Button 3: Close
        this.addRenderableWidget(Button.builder(
            Component.translatable("struttura.welcome.btn.close"),
            button -> {
                markAsShown();
                this.minecraft.setScreen(this.parentScreen);
            }
        ).bounds(centerX - BUTTON_WIDTH / 2, buttonsStartY + 2 * (BUTTON_HEIGHT + BUTTON_SPACING), BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Background
        GuiAssets.renderBackground(graphics, this.width, this.height);
        graphics.fill(0, 0, this.width, this.height, 0xA0000000);

        // Title (fixed)
        graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF);

        // Render all widgets (FittingMultiLineTextWidget handles its own scroll + buttons)
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void openWebsite() {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String www = config.getWww();
        if (www == null || www.isEmpty()) {
            www = "https://struttura.magius.it";
        }
        if (www.endsWith("/")) {
            www = www.substring(0, www.length() - 1);
        }
        String url = www;
        this.minecraft.setScreen(new ConfirmLinkScreen(
            confirmed -> {
                if (confirmed) {
                    try {
                        Util.getPlatform().openUri(new URI(url));
                    } catch (Exception e) {
                        // Ignore URI parsing errors
                    }
                }
                this.minecraft.setScreen(this);
            },
            url,
            true
        ));
    }

    private void markAsShown() {
        ArchitectConfig config = ArchitectConfig.getInstance();
        config.setWelcomeMessageShown(true);
        config.save();
        Architect.LOGGER.info("Welcome message marked as shown");
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            markAsShown();
            this.minecraft.setScreen(this.parentScreen);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return true;
        return super.mouseClicked(event, consumed);
    }

    @Override
    public void onClose() {
        markAsShown();
        this.minecraft.setScreen(this.parentScreen);
    }

    /**
     * Gets the welcome message text from config, or falls back to localized default.
     */
    private String getWelcomeText() {
        ArchitectConfig config = ArchitectConfig.getInstance();

        // Get current language code from Minecraft (e.g., "en_us", "it_it")
        String langCode = Minecraft.getInstance().getLanguageManager().getSelected();
        // Convert Minecraft format (en_us) to BCP 47 (en-US)
        String bcp47Lang = langCode.replace('_', '-');

        String text = config.getWelcomeMessageForLanguage(bcp47Lang);

        if (text == null || text.isEmpty()) {
            text = Component.translatable("struttura.welcome.default_message").getString();
        }

        return text;
    }
}
