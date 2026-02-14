package it.magius.struttura.architect.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A small button with a heart icon for liking buildings.
 * - Empty heart outline: like not yet given, clickable
 * - Full red heart: already liked, not clickable
 * - Gray/black heart: cannot like, not clickable
 */
@Environment(EnvType.CLIENT)
public class LikeButton {

    // Heart sprites from the GUI atlas (registered by Minecraft)
    // These are sprite identifiers, not texture paths
    private static final Identifier HEART_FULL = Identifier.fromNamespaceAndPath("minecraft", "hud/heart/full");
    private static final Identifier HEART_HALF = Identifier.fromNamespaceAndPath("minecraft", "hud/heart/half");
    private static final Identifier HEART_WITHERED = Identifier.fromNamespaceAndPath("minecraft", "hud/heart/withered_full");

    private static final int HEART_SIZE = 9;

    private final Button button;
    private HeartState heartState;
    private final String buildingName;

    public enum HeartState {
        HALF,       // Can like, not yet liked (clickable, half heart)
        FULL,       // Already liked (not clickable, full red heart)
        DISABLED    // Cannot like (not clickable, withered heart)
    }

    private LikeButton(Button button, HeartState heartState, String buildingName) {
        this.button = button;
        this.heartState = heartState;
        this.buildingName = buildingName;
    }

    /**
     * Gets the underlying button widget.
     */
    public Button getButton() {
        return button;
    }

    /**
     * Marks the button as liked (changes heart to full and disables button).
     */
    public void setLiked() {
        this.heartState = HeartState.FULL;
        this.button.active = false;
    }

    /**
     * Renders the heart icon on top of the button.
     * Call this AFTER the button is rendered (in render method after super.render).
     */
    public void renderHeart(GuiGraphics graphics) {
        int x = button.getX();
        int y = button.getY();
        int width = button.getWidth();
        int height = button.getHeight();

        // Draw heart at native size (9x9) centered in button
        int heartX = x + (width - HEART_SIZE) / 2;
        int heartY = y + (height - HEART_SIZE) / 2;

        // Choose which heart sprite to render based on state
        Identifier sprite;
        switch (heartState) {
            case FULL:
                // Full red heart (liked)
                sprite = HEART_FULL;
                break;
            case DISABLED:
                // Withered heart (black, cannot like)
                sprite = HEART_WITHERED;
                break;
            case HALF:
            default:
                // Half heart (can like, not yet liked)
                sprite = HEART_HALF;
                break;
        }

        // Render the heart sprite from the GUI atlas
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, heartX, heartY, HEART_SIZE, HEART_SIZE);
    }

    /**
     * Creates a LikeButton positioned in the top-center of the screen.
     *
     * @param screenWidth Screen width for positioning
     * @param buildingName Building name for tooltip
     * @param alreadyLiked Whether the building was already liked
     * @param canLike Whether the player can like (false if not in building or other restriction)
     * @param onPress Action when clicked
     */
    public static LikeButton create(int screenWidth, String buildingName, boolean alreadyLiked, boolean canLike, Button.OnPress onPress) {
        int buttonSize = 20; // Same size as accessibility/language buttons

        // Position in top-center of the screen
        int buttonX = (screenWidth - buttonSize) / 2;
        int buttonY = 4;

        // Determine heart state
        HeartState state;
        if (alreadyLiked) {
            state = HeartState.FULL;
        } else if (canLike) {
            state = HeartState.HALF;
        } else {
            state = HeartState.DISABLED;
        }

        // Build tooltip text
        String tooltipText;
        if (alreadyLiked) {
            tooltipText = "Liked: " + buildingName;
        } else if (canLike) {
            tooltipText = "Like: " + buildingName;
        } else {
            tooltipText = buildingName;
        }

        // Create button with empty text (we render heart manually)
        Button button = Button.builder(Component.empty(), onPress)
                .bounds(buttonX, buttonY, buttonSize, buttonSize)
                .tooltip(Tooltip.create(Component.literal(tooltipText)))
                .build();

        // Disable button if already liked or cannot like
        if (alreadyLiked || !canLike) {
            button.active = false;
        }

        return new LikeButton(button, state, buildingName);
    }
}
