package it.magius.struttura.architect.mixin.client;

import it.magius.struttura.architect.Architect;
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

        // Heart icon: Unicode heart character
        architect$likeButton = Button.builder(
                Component.literal("\u2764"), // Heart symbol
                button -> architect$onLikeClicked()
        )
        .bounds(buttonX, buttonY, buttonWidth, buttonHeight)
        .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal("Like: " + buildingName)
        ))
        .build();

        this.addRenderableWidget(architect$likeButton);
    }

    /**
     * Gets the name of the building the player is currently visiting.
     * @return The building name, or null if not visiting any building.
     */
    @Unique
    private String architect$getCurrentBuildingName() {
        // TODO: Implement building detection logic
        // This will check if the player is inside a spawned building's bounds
        // For now, return null to hide the button
        return null;
    }

    /**
     * Called when the LIKE button is clicked.
     */
    @Unique
    private void architect$onLikeClicked() {
        // TODO: Implement like functionality
        // This will send a request to the server to like the building
        Architect.LOGGER.info("LIKE button clicked!");
    }
}
