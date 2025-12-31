package it.magius.struttura.architect.mixin.client;

import it.magius.struttura.architect.client.gui.StrutturaScreen;
import it.magius.struttura.architect.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept right-click in air with the Construction Hammer.
 * Opens the Struttura modal screen instead of normal item use.
 */
@Mixin(Minecraft.class)
public class HammerGuiMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void onStartUseItem(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;

        if (mc.player == null) {
            return;
        }

        // Only trigger when NOT targeting a block (looking at air)
        if (mc.hitResult != null && mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            return;
        }

        // Check if holding the construction hammer
        ItemStack mainHand = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        ItemStack offHand = mc.player.getItemInHand(InteractionHand.OFF_HAND);

        boolean hasHammer = mainHand.is(ModItems.CONSTRUCTION_HAMMER) ||
                           offHand.is(ModItems.CONSTRUCTION_HAMMER);

        if (!hasHammer) {
            return;
        }

        // Open the modal screen
        mc.setScreen(new StrutturaScreen());

        // Cancel normal item use
        ci.cancel();
    }
}
