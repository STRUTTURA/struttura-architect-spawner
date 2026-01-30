package it.magius.struttura.architect.client.toast;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import it.magius.struttura.architect.Architect;

/**
 * Custom toast notification for STRUTTURA mod.
 * Displays tutorial hints and notifications with vanilla-style appearance.
 */
@Environment(EnvType.CLIENT)
public class StrutturaToast implements Toast {

    // Vanilla toast background sprite
    private static final Identifier BACKGROUND_SPRITE = Identifier.withDefaultNamespace("toast/tutorial");

    // Heart sprite from vanilla HUD (same as player health)
    private static final Identifier HEART_FULL = Identifier.fromNamespaceAndPath("minecraft", "hud/heart/full");
    private static final int HEART_SIZE = 18;  // Doubled from 9

    private final Component title;
    private final Component description;
    private final long displayTime;
    private long startTime = -1;
    private boolean finished = false;

    /**
     * Creates a new STRUTTURA toast.
     * @param title the toast title
     * @param description the toast description
     * @param displayTimeMs how long to show the toast in milliseconds
     */
    public StrutturaToast(Component title, Component description, long displayTimeMs) {
        this.title = title;
        this.description = description;
        this.displayTime = displayTimeMs;
    }

    /**
     * Creates a toast with default display time (5 seconds).
     */
    public StrutturaToast(Component title, Component description) {
        this(title, description, 5000L);
    }

    @Override
    public void render(GuiGraphics graphics, Font font, long timeSinceLastVisible) {
        // Draw vanilla toast background (stretched to our width)
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_SPRITE, 0, 0, this.width(), this.height());

        // Draw heart icon centered vertically on the left side
        int heartX = 6;
        int heartY = (this.height() - HEART_SIZE) / 2;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_FULL, heartX, heartY, HEART_SIZE, HEART_SIZE);

        // Text starts after heart with padding
        int textX = heartX + HEART_SIZE + 6;

        // Draw title in vanilla recipe toast purple/magenta color (same as "New Recipe(s) Unlocked")
        graphics.drawString(font, this.title, textX, 7, 0xFF5000B2, false);

        // Draw description in black (vanilla style)
        graphics.drawString(font, this.description, textX, 18, 0xFF000000, false);
    }

    @Override
    public void update(ToastManager manager, long time) {
        if (this.startTime < 0) {
            this.startTime = time;
        }

        long elapsed = time - this.startTime;
        if (elapsed >= this.displayTime) {
            this.finished = true;
        }
    }

    @Override
    public Visibility getWantedVisibility() {
        return this.finished ? Visibility.HIDE : Visibility.SHOW;
    }

    @Override
    public int width() {
        return 200;  // Wider to fit text
    }

    @Override
    public int height() {
        return 32;
    }

    /**
     * Shows this toast to the player.
     */
    public void show() {
        Minecraft.getInstance().getToastManager().addToast(this);
    }

    // ==================== Static factory methods ====================

    /**
     * Shows the "like tutorial" toast when player enters a building for the first time.
     */
    public static void showLikeTutorial() {
        StrutturaToast toast = new StrutturaToast(
            Component.literal("STRUTTURA"),
            Component.translatable("struttura.toast.like_tutorial"),
            7000L  // 7 seconds to give time to read
        );
        toast.show();
        Architect.LOGGER.debug("Showing like tutorial toast");
    }

    /**
     * Shows a generic STRUTTURA notification.
     * @param title the title
     * @param message the message
     */
    public static void showNotification(String title, String message) {
        StrutturaToast toast = new StrutturaToast(
            Component.literal(title),
            Component.literal(message),
            5000L
        );
        toast.show();
    }
}
