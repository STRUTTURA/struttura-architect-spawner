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
 * Modal disclaimer screen shown after the first push of a building (version 1).
 * Informs the user that the building was saved as private, and reminds them
 * to upload screenshots and add multilingual titles/descriptions.
 */
@Environment(EnvType.CLIENT)
public class FirstPushDisclaimerScreen extends Screen {

    private static final int CONTENT_WIDTH = 380;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 100;
    private static final int TITLE_Y = 20;

    public FirstPushDisclaimerScreen() {
        super(Component.translatable("struttura.first_push.title"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // OK button at the bottom
        int bottomMargin = 20;
        int buttonY = this.height - BUTTON_HEIGHT - bottomMargin;

        // Text area: between title and button
        int textPadding = (this.width - CONTENT_WIDTH) / 2;
        int textStartY = TITLE_Y + 16;
        int textHeight = Math.max(40, buttonY - textStartY - 10);

        // Scrollable text widget
        String message = Component.translatable("struttura.first_push.message").getString();
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
            Component.literal("OK"),
            button -> onClose()
        ).bounds(centerX - BUTTON_WIDTH / 2, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Background
        GuiAssets.renderBackground(graphics, this.width, this.height);
        graphics.fill(0, 0, this.width, this.height, 0xA0000000);

        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, 0xFFFFAA00);

        // Render all widgets
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Close on Escape or Enter
        if (event.key() == 256 || event.key() == 257) {
            onClose();
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
        this.minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
