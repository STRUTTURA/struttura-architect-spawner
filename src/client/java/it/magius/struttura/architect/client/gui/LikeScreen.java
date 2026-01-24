package it.magius.struttura.architect.client.gui;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.ingame.InGameClientState;
import it.magius.struttura.architect.network.InGameLikePacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;
import java.net.URI;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/**
 * Screen shown when player clicks the like button in the pause menu.
 * Shows building details and allows the player to like the building.
 */
@Environment(EnvType.CLIENT)
public class LikeScreen extends Screen {

    // Heart sprites from the GUI atlas
    private static final Identifier HEART_FULL = Identifier.fromNamespaceAndPath("minecraft", "hud/heart/full");
    private static final Identifier HEART_WITHERED = Identifier.fromNamespaceAndPath("minecraft", "hud/heart/withered_full");
    private static final int HEART_SIZE = 9;

    // Layout constants
    private static final int LOGO_SIZE_PERCENT = 20; // 20% of screen height (doubled)
    private static final int LOGO_MARGIN = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int LIKE_BUTTON_WIDTH = 70;
    private static final int BACK_BUTTON_WIDTH = 60;
    private static final int BUTTON_SPACING = 10;

    private final Screen parent;
    private final String buildingName;
    private final String author;
    private final String description;
    private final boolean alreadyLiked;
    private final boolean canLike;
    private final String rdns;
    private final long pk;

    private Button likeButton;
    private Button backButton;
    private boolean liked;

    // Link click detection
    private int linkStartX, linkEndX, linkY, linkEndY;
    private String buildingUrl;

    // Cursor handling
    private boolean cursorChanged;

    // Description widget
    private FittingMultiLineTextWidget descriptionWidget;

    public LikeScreen(Screen parent) {
        super(Component.translatable("struttura.like.title"));
        this.parent = parent;

        // Get building info from client state
        InGameClientState state = InGameClientState.getInstance();
        this.buildingName = state.getBuildingName();
        this.author = state.getAuthor();
        this.rdns = state.getRdns();
        this.pk = state.getPk();
        this.alreadyLiked = state.hasLiked();
        this.canLike = state.isInBuilding();
        this.liked = alreadyLiked;

        // Get localized description from server
        this.description = state.getDescription();
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // Calculate layout positions
        int titleY = (int) (this.height * 0.10f);
        int authorY = titleY + 15;
        int descriptionStartY = authorY + (author.isEmpty() ? 0 : 15) + 25;
        int buttonY = this.height - 40;
        int messageY = buttonY - 20;
        int linkY = messageY - 20;

        // Calculate description area
        int descriptionPadding = 20;
        int descriptionWidth = this.width - descriptionPadding * 2;
        int descriptionHeight = Math.max(40, linkY - descriptionStartY - 10);

        // Create description widget - use default text if empty
        String descriptionText = description.isEmpty()
            ? Component.translatable("struttura.like.default_description").getString()
            : description;

        descriptionWidget = new FittingMultiLineTextWidget(
            descriptionPadding,
            descriptionStartY,
            descriptionWidth,
            descriptionHeight,
            Component.literal(descriptionText),
            this.font
        );
        this.addRenderableWidget(descriptionWidget);

        // Buttons at bottom center, on same row: [Back] [Like]
        int totalButtonsWidth = BACK_BUTTON_WIDTH + BUTTON_SPACING + LIKE_BUTTON_WIDTH;
        int buttonsStartX = centerX - totalButtonsWidth / 2;

        // Back button (left)
        backButton = Button.builder(
                Component.translatable("gui.back"),
                button -> onClose()
        ).bounds(buttonsStartX, buttonY, BACK_BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addRenderableWidget(backButton);

        // Like button (right)
        likeButton = Button.builder(
                Component.translatable("struttura.like.button"),
                button -> onLikeClicked()
        ).bounds(buttonsStartX + BACK_BUTTON_WIDTH + BUTTON_SPACING, buttonY, LIKE_BUTTON_WIDTH, BUTTON_HEIGHT).build();

        // Disable if already liked or cannot like
        if (alreadyLiked || !canLike) {
            likeButton.active = false;
        }

        this.addRenderableWidget(likeButton);
    }

    private void onLikeClicked() {
        if (liked || !canLike) {
            return;
        }

        // Send like packet to server
        Architect.LOGGER.info("Sending like for building: {}", rdns);
        ClientPlayNetworking.send(new InGameLikePacket(rdns, pk));

        // Optimistically update local state
        InGameClientState.getInstance().setLiked(true);
        liked = true;

        // Disable button
        likeButton.active = false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Draw semi-transparent dark background
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);

        int centerX = this.width / 2;

        // Calculate logo size (10% of screen height)
        int logoSize = (int) (this.height * LOGO_SIZE_PERCENT / 100f);

        // Render worm logo (left side, top)
        int wormWidth = (int) (logoSize * ((float) GuiAssets.WORM_WIDTH / GuiAssets.WORM_HEIGHT));
        GuiAssets.renderWorm(graphics, LOGO_MARGIN, LOGO_MARGIN, wormWidth, logoSize);

        // Render like icon (right side, top)
        GuiAssets.renderLikeIcon(graphics, this.width - logoSize - LOGO_MARGIN, LOGO_MARGIN, logoSize);

        // Title at top (~10% from top)
        int y = (int) (this.height * 0.10f);
        graphics.drawCenteredString(this.font, buildingName, centerX, y, 0xFFFFAA00);
        y += 15;

        // Author (centered, blue)
        if (!author.isEmpty()) {
            Component authorText = Component.translatable("struttura.like.author", author);
            graphics.drawCenteredString(this.font, authorText, centerX, y, 0xFF88CCFF);
        }

        // Description widget is rendered by super.render() as a renderable widget

        // Calculate link position (below description widget)
        int buttonY = this.height - 40;
        int messageY = buttonY - 20;
        int linkYPos = messageY - 20;

        // Link to STRUTTURA website
        this.buildingUrl = "https://struttura.magius.it/buildings/" + rdns;

        // Store link position for click detection (before rendering)
        Component linkTextForWidth = Component.translatable("struttura.like.website_link");
        int linkWidth = this.font.width(linkTextForWidth);
        int linkX = centerX - linkWidth / 2;
        linkY = linkYPos;
        linkEndY = linkYPos + this.font.lineHeight;
        linkStartX = linkX;
        linkEndX = linkX + linkWidth;

        // Check if mouse is hovering over link
        boolean isHovering = mouseX >= linkStartX && mouseX <= linkEndX
                          && mouseY >= linkY && mouseY <= linkEndY;

        // Update cursor based on hover state
        if (isHovering && !cursorChanged) {
            this.minecraft.getWindow().selectCursor(CursorTypes.POINTING_HAND);
            cursorChanged = true;
        } else if (!isHovering && cursorChanged) {
            this.minecraft.getWindow().selectCursor(CursorTypes.ARROW);
            cursorChanged = false;
        }

        // Change color on hover: cyan -> yellow
        int linkColor = isHovering ? 0xFFFFFF55 : 0xFF55FFFF;
        Component linkText = Component.translatable("struttura.like.website_link")
            .withStyle(Style.EMPTY
                .withColor(linkColor)
                .withUnderlined(true));

        graphics.drawCenteredString(this.font, linkText, centerX, linkYPos, linkColor);

        // Message encouraging to like - position it close to buttons
        Component message = Component.translatable("struttura.like.message");
        graphics.drawCenteredString(this.font, message, centerX, messageY, 0xFFCCCCCC);

        // Render widgets (buttons + description widget)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Draw heart on the like button
        renderLikeButtonHeart(graphics);
    }

    /**
     * Renders the heart icon on the like button.
     */
    private void renderLikeButtonHeart(GuiGraphics graphics) {
        if (likeButton == null) {
            return;
        }

        int buttonX = likeButton.getX();
        int buttonY = likeButton.getY();
        int buttonHeight = likeButton.getHeight();

        // Position heart at the left side of the button with "+" before it
        // Text layout: "+[heart] Like"
        String plusSign = "+";
        int plusWidth = this.font.width(plusSign);

        // Calculate position for the heart (directly after the +, no space)
        int textStartX = buttonX + 4; // Small padding from button edge
        int heartX = textStartX + plusWidth + 1; // Just 1px gap
        int heartY = buttonY + (buttonHeight - HEART_SIZE) / 2;

        // Draw the "+" sign
        int textY = buttonY + (buttonHeight - this.font.lineHeight) / 2 + 1;
        graphics.drawString(this.font, plusSign, textStartX, textY, liked || !canLike ? 0xFF666666 : 0xFFFFFFFF);

        // Choose heart sprite based on state
        Identifier heartSprite;
        if (liked) {
            heartSprite = HEART_FULL;
        } else if (!canLike) {
            heartSprite = HEART_WITHERED;
        } else {
            heartSprite = HEART_FULL;
        }

        // Render the heart
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, heartSprite, heartX, heartY, HEART_SIZE, HEART_SIZE);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean consumed) {
        if (consumed) return true;

        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        // Check if link was clicked - use ConfirmLinkScreen (standard Minecraft way)
        if (button == 0 && buildingUrl != null) {
            if (mouseX >= linkStartX && mouseX <= linkEndX && mouseY >= linkY && mouseY <= linkEndY) {
                this.minecraft.setScreen(new ConfirmLinkScreen(
                    confirmed -> {
                        if (confirmed) {
                            try {
                                Util.getPlatform().openUri(new URI(buildingUrl));
                            } catch (Exception e) {
                                // Ignore URI parsing errors
                            }
                        }
                        this.minecraft.setScreen(this);
                    },
                    buildingUrl,
                    true
                ));
                return true;
            }
        }
        return super.mouseClicked(event, consumed);
    }

    @Override
    public void onClose() {
        // Reset cursor to default
        if (cursorChanged) {
            this.minecraft.getWindow().selectCursor(CursorTypes.ARROW);
            cursorChanged = false;
        }
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
