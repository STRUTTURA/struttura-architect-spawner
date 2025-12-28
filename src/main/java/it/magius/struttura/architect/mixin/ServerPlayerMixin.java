package it.magius.struttura.architect.mixin;

import it.magius.struttura.architect.i18n.PlayerLanguageTracker;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin per intercettare le impostazioni del client (inclusa la lingua).
 */
@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

    /**
     * Intercetta quando il client invia le sue informazioni (lingua, view distance, ecc).
     */
    @Inject(
        method = "updateOptions",
        at = @At("HEAD")
    )
    private void onUpdateOptions(ClientInformation clientInformation, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        String language = clientInformation.language();
        PlayerLanguageTracker.getInstance().setPlayerLanguage(self, language);
    }
}
