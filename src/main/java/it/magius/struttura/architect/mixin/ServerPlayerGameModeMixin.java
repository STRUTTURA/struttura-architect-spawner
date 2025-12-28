package it.magius.struttura.architect.mixin;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.session.EditingSession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin per intercettare il piazzamento e la rottura dei blocchi.
 */
@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    @Shadow
    protected ServerLevel level;

    /**
     * Intercetta la rottura di un blocco.
     */
    @Inject(
        method = "destroyBlock",
        at = @At("HEAD")
    )
    private void onBlockDestroy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        EditingSession session = EditingSession.getSession(player);
        if (session != null) {
            BlockState previousState = level.getBlockState(pos);
            session.onBlockBroken(pos, previousState);
            Architect.LOGGER.debug("Block broken at {}: {} (mode: {})",
                pos, previousState, session.getMode());
            // Invia sync wireframe per aggiornare i bounds
            NetworkHandler.sendWireframeSync(player);
        }
    }

    /**
     * Intercetta l'uso di un item su un blocco (piazzamento).
     */
    @Inject(
        method = "useItemOn",
        at = @At("RETURN")
    )
    private void onUseItemOn(
        ServerPlayer player,
        Level world,
        ItemStack stack,
        InteractionHand hand,
        BlockHitResult hitResult,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        // Verifica che l'interazione abbia avuto successo
        InteractionResult result = cir.getReturnValue();
        if (result != InteractionResult.SUCCESS && result != InteractionResult.CONSUME) {
            return;
        }

        EditingSession session = EditingSession.getSession(player);
        if (session != null) {
            // Calcola la posizione dove il blocco e' stato piazzato
            BlockPos placedPos = hitResult.getBlockPos().relative(hitResult.getDirection());
            BlockState placedState = world.getBlockState(placedPos);

            // Verifica che sia effettivamente un blocco piazzato (non aria)
            if (!placedState.isAir()) {
                session.onBlockPlaced(placedPos, placedState);
                Architect.LOGGER.debug("Block placed at {}: {} (mode: {})",
                    placedPos, placedState, session.getMode());
                // Invia sync wireframe per aggiornare i bounds
                NetworkHandler.sendWireframeSync(player);
            }
        }
    }
}
