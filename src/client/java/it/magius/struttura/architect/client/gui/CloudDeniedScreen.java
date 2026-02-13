package it.magius.struttura.architect.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Screen shown when cloud access is denied and no update path is available.
 * Informs the user that cloud features are disabled for this session.
 */
@Environment(EnvType.CLIENT)
public class CloudDeniedScreen extends Screen {

    private static final int CONTENT_WIDTH = 380;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 200;
    private static final int TITLE_Y = 10;

    private final Screen parentScreen;

    public CloudDeniedScreen(Screen parentScreen) {
        super(Component.translatable("struttura.cloud_denied.title"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // Button at bottom
        int bottomMargin = 10;
        int buttonY = this.height - BUTTON_HEIGHT - bottomMargin;

        // Text area: between title and button
        int textPadding = (this.width - CONTENT_WIDTH) / 2;
        int textStartY = TITLE_Y + 14;
        int textHeight = Math.max(40, buttonY - textStartY - 8);

        // Scrollable message
        String message = Component.translatable("struttura.cloud_denied.message").getString();
        FittingMultiLineTextWidget messageWidget = new FittingMultiLineTextWidget(
            textPadding,
            textStartY,
            CONTENT_WIDTH,
            textHeight,
            Component.literal(message),
            this.font
        );
        this.addRenderableWidget(messageWidget);

        // OK button
        this.addRenderableWidget(Button.builder(
            Component.translatable("struttura.cloud_denied.btn.ok"),
            button -> this.minecraft.setScreen(this.parentScreen)
        ).bounds(centerX - BUTTON_WIDTH / 2, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiAssets.renderBackground(graphics, this.width, this.height);
        graphics.fill(0, 0, this.width, this.height, 0xA0000000);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFFFFF);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) { // ESC
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
        this.minecraft.setScreen(this.parentScreen);
    }
}
