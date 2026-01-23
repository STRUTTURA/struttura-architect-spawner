package it.magius.struttura.architect.mixin.client;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.ingame.InGameClientState;
import it.magius.struttura.architect.network.InGameLikePacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to add a LIKE button to the Pause screen (Game Menu).
 * The button allows players to like the building they are currently visiting.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    @Unique
    private Button architect$likeButton;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    /**
     * Injects at the end of PauseScreen.init() to add the LIKE button.
     * The button shows a heart icon and the building name (if visiting one).
     */
    @Inject(method = "init", at = @At("RETURN"))
    private void architect$addLikeButton(CallbackInfo ci) {
        // TODO: Check if player is currently visiting a building
        // For now, create a placeholder button that will be implemented later

        String buildingName = architect$getCurrentBuildingName();
        if (buildingName == null) {
            // Not visiting any building, don't show the button
            return;
        }

        int buttonWidth = 20;
        int buttonHeight = 20;

        // Position: top-right corner of the screen
        int buttonX = this.width - buttonWidth - 4;
        int buttonY = 4;

        // Check if already liked
        InGameClientState state = InGameClientState.getInstance();
        boolean alreadyLiked = state.hasLiked();

        // Heart icon: filled heart if liked, empty heart if not
        String heartIcon = alreadyLiked ? "\u2764" : "\u2661";  // ❤ vs ♡
        String tooltipText = alreadyLiked ? "Liked: " + buildingName : "Like: " + buildingName;

        architect$likeButton = Button.builder(
                Component.literal(heartIcon),
                button -> architect$onLikeClicked()
        )
        .bounds(buttonX, buttonY, buttonWidth, buttonHeight)
        .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal(tooltipText)
        ))
        .build();

        // Disable button if already liked
        if (alreadyLiked) {
            architect$likeButton.active = false;
        }

        this.addRenderableWidget(architect$likeButton);
    }

    /**
     * Gets the name of the building the player is currently visiting.
     * @return The building name, or null if not visiting any building.
     */
    @Unique
    private String architect$getCurrentBuildingName() {
        InGameClientState state = InGameClientState.getInstance();
        if (state.isInBuilding()) {
            return state.getBuildingName();
        }
        return null;
    }

    /**
     * Called when the LIKE button is clicked.
     */
    @Unique
    private void architect$onLikeClicked() {
        InGameClientState state = InGameClientState.getInstance();
        if (!state.isInBuilding()) {
            return;
        }

        if (state.hasLiked()) {
            Architect.LOGGER.debug("Building already liked");
            return;
        }

        // Send like packet to server
        Architect.LOGGER.info("Sending like for building: {}", state.getRdns());
        ClientPlayNetworking.send(new InGameLikePacket(state.getRdns(), state.getPk()));

        // Optimistically update local state
        state.setLiked(true);

        // Update button appearance
        if (architect$likeButton != null) {
            architect$likeButton.active = false;
        }
    }
}
