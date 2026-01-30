package it.magius.struttura.architect.mixin;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.placement.BlockUtils;
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

import java.util.List;

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
     * NOTE: We capture multi-block positions BEFORE destruction because
     * Minecraft automatically destroys both halves of doors, beds, etc.
     */
    @Inject(
        method = "destroyBlock",
        at = @At("HEAD")
    )
    private void onBlockDestroy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        EditingSession session = EditingSession.getSession(player);
        if (session != null) {
            BlockState previousState = level.getBlockState(pos);

            // Get all positions for multi-block structures BEFORE destruction
            List<BlockPos> allPositions = BlockUtils.getMultiBlockPositions(level, pos);

            for (BlockPos blockPos : allPositions) {
                BlockState state = level.getBlockState(blockPos);
                if (!state.isAir()) {
                    session.onBlockBroken(blockPos, state);
                    Architect.LOGGER.debug("Block broken at {}: {} (mode: {})",
                        blockPos, state, session.getMode());
                }
            }

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
                // Get all positions for multi-block structures (doors, beds, tall plants, etc.)
                List<BlockPos> allPositions = BlockUtils.getMultiBlockPositions(world, placedPos);

                for (BlockPos pos : allPositions) {
                    BlockState state = world.getBlockState(pos);
                    if (!state.isAir()) {
                        session.onBlockPlaced(pos, state);
                        Architect.LOGGER.debug("Block placed at {}: {} (mode: {})",
                            pos, state, session.getMode());
                    }
                }

                // Invia sync wireframe per aggiornare i bounds
                NetworkHandler.sendWireframeSync(player);
            }
        }
    }
}
