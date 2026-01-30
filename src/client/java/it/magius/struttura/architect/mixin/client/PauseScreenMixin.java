package it.magius.struttura.architect.mixin.client;

import it.magius.struttura.architect.client.gui.LikeButton;
import it.magius.struttura.architect.client.gui.LikeScreen;
import it.magius.struttura.architect.client.ingame.InGameClientState;
import net.minecraft.client.gui.GuiGraphics;
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
    private LikeButton architect$likeButton;

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

        // Create the like button - always clickable to open the LikeScreen
        // The button shows the current like state but opens a detail screen on click
        InGameClientState state = InGameClientState.getInstance();
        boolean alreadyLiked = state.hasLiked();
        boolean canLike = state.canLike();  // False if owner or already liked

        architect$likeButton = LikeButton.create(
                this.width,
                buildingName,
                alreadyLiked,
                canLike,  // Shows withered heart if owner
                button -> architect$openLikeScreen()
        );

        // Make button always active (clickable) to open the like screen for more info
        architect$likeButton.getButton().active = true;

        this.addRenderableWidget(architect$likeButton.getButton());
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
     * Opens the LikeScreen to show building details and allow liking.
     */
    @Unique
    private void architect$openLikeScreen() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new LikeScreen((PauseScreen) (Object) this));
        }
    }

    /**
     * Injects at the end of render to draw the heart icon on the button.
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void architect$renderLikeHeart(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (architect$likeButton != null) {
            architect$likeButton.renderHeart(graphics);
        }
    }
}
