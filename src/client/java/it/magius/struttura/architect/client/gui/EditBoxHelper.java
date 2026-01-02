package it.magius.struttura.architect.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Helper class that wraps EditBox for use in custom panels.
 * Provides placeholder text support and consistent styling.
 */
@Environment(EnvType.CLIENT)
public class EditBoxHelper {

    private final EditBox editBox;
    private final String placeholder;
    private int x, y, width, height;

    /**
     * Create a new EditBoxHelper with the given configuration.
     *
     * @param font        The font to use for rendering
     * @param x           X position
     * @param y           Y position
     * @param width       Width of the edit box
     * @param height      Height of the edit box
     * @param placeholder Placeholder text to show when empty and unfocused
     * @param filter      Character filter predicate (null for no filtering)
     * @param responder   Callback when text changes (null for no callback)
     */
    public EditBoxHelper(Font font, int x, int y, int width, int height,
                         String placeholder, Predicate<String> filter, Consumer<String> responder) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.placeholder = placeholder;

        this.editBox = new EditBox(font, x, y, width, height, Component.empty());
        this.editBox.setBordered(false); // We handle border rendering ourselves
        this.editBox.setMaxLength(256);

        if (filter != null) {
            this.editBox.setFilter(filter);
        }
        if (responder != null) {
            this.editBox.setResponder(responder);
        }
    }

    /**
     * Update the position of the edit box.
     * Call this each render frame since panel positions can change.
     */
    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
        this.editBox.setX(x + 3);
        this.editBox.setY(y + (height - 8) / 2);
    }

    /**
     * Update the width of the edit box.
     */
    public void setWidth(int width) {
        this.width = width;
        this.editBox.setWidth(width - 6);
    }

    /**
     * Get the current text value.
     */
    public String getValue() {
        return editBox.getValue();
    }

    /**
     * Set the text value.
     */
    public void setValue(String value) {
        editBox.setValue(value);
    }

    /**
     * Check if the edit box is focused.
     */
    public boolean isFocused() {
        return editBox.isFocused();
    }

    /**
     * Set focus state.
     */
    public void setFocused(boolean focused) {
        editBox.setFocused(focused);
    }

    /**
     * Render the edit box with custom styling.
     *
     * @param graphics GuiGraphics instance
     * @param mouseX   Mouse X position
     * @param mouseY   Mouse Y position
     * @param tickDelta Partial tick
     */
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float tickDelta) {
        Font font = Minecraft.getInstance().font;

        // Update internal EditBox position
        editBox.setX(x + 3);
        editBox.setY(y + (height - 8) / 2);
        editBox.setWidth(width - 6);

        // Draw background
        int bgColor = isFocused() ? 0xFF404040 : 0xFF202020;
        graphics.fill(x, y, x + width, y + height, bgColor);

        // Draw border
        int borderColor = isFocused() ? 0xFFFFFFFF : 0xFF606060;
        graphics.renderOutline(x, y, width, height, borderColor);

        // Draw placeholder if empty and not focused
        if (editBox.getValue().isEmpty() && !isFocused()) {
            graphics.drawString(font, placeholder, x + 3, y + (height - 8) / 2, 0xFF808080, false);
        } else {
            // Render the EditBox content
            editBox.render(graphics, mouseX, mouseY, tickDelta);
        }
    }

    /**
     * Handle mouse click events.
     *
     * @return true if the click was handled
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean inBounds = mouseX >= x && mouseX < x + width &&
                          mouseY >= y && mouseY < y + height;

        if (inBounds && button == 0) {
            editBox.setFocused(true);
            // Create MouseButtonEvent for the new API (MC 1.21.9+)
            // MouseButtonInfo(button, modifiers), MouseButtonEvent(x, y, buttonInfo)
            MouseButtonInfo buttonInfo = new MouseButtonInfo(button, 0);
            MouseButtonEvent event = new MouseButtonEvent(mouseX, mouseY, buttonInfo);
            editBox.mouseClicked(event, false);
            return true;
        }

        return false;
    }

    /**
     * Handle key press events.
     *
     * @return true if the key was handled
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused()) return false;

        // Escape - unfocus
        if (keyCode == 256) {
            setFocused(false);
            return true;
        }

        // Create KeyEvent for the new API
        KeyEvent event = new KeyEvent(keyCode, scanCode, modifiers);
        return editBox.keyPressed(event);
    }

    /**
     * Handle character typed events.
     *
     * @return true if the character was handled
     */
    public boolean charTyped(char chr, int modifiers) {
        if (!isFocused()) return false;
        // Create CharacterEvent for the new API (MC 1.21.9+)
        // CharacterEvent(codepoint, modifiers)
        CharacterEvent event = new CharacterEvent((int) chr, modifiers);
        return editBox.charTyped(event);
    }

    /**
     * Check if mouse is over this edit box.
     */
    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width &&
               mouseY >= y && mouseY < y + height;
    }

    /**
     * Get the underlying EditBox for advanced configuration.
     */
    public EditBox getEditBox() {
        return editBox;
    }

    /**
     * Set the maximum length of the text.
     */
    public void setMaxLength(int maxLength) {
        editBox.setMaxLength(maxLength);
    }

    /**
     * Clear the text value.
     */
    public void clear() {
        editBox.setValue("");
    }

    // Static factory methods for common configurations

    /**
     * Create an edit box for search text.
     */
    public static EditBoxHelper createSearchBox(Font font, int x, int y, int width, int height, Consumer<String> onTextChanged) {
        return new EditBoxHelper(font, x, y, width, height, "Search...",
            s -> s.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == ' '),
            onTextChanged);
    }

    /**
     * Create an edit box for construction IDs.
     */
    public static EditBoxHelper createIdBox(Font font, int x, int y, int width, int height, String placeholder) {
        return new EditBoxHelper(font, x, y, width, height, placeholder,
            s -> s.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '.' || c == '_'),
            null);
    }

    /**
     * Create an edit box for construction IDs (lowercase only).
     */
    public static EditBoxHelper createLowercaseIdBox(Font font, int x, int y, int width, int height, String placeholder) {
        EditBoxHelper helper = new EditBoxHelper(font, x, y, width, height, placeholder,
            s -> s.chars().allMatch(c -> (c >= 'a' && c <= 'z') || Character.isDigit(c) || c == '.' || c == '_'),
            null);
        // Override charTyped behavior to force lowercase
        return helper;
    }

    /**
     * Create an edit box for free text (name/title).
     */
    public static EditBoxHelper createTextBox(Font font, int x, int y, int width, int height, String placeholder) {
        return new EditBoxHelper(font, x, y, width, height, placeholder,
            s -> s.chars().allMatch(c -> c >= 32),
            null);
    }
}
