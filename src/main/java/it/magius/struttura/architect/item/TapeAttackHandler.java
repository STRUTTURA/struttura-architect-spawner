package it.magius.struttura.architect.item;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.registry.ModItems;
import it.magius.struttura.architect.session.EditingSession;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Handles left-click (attack) interactions with the Measuring Tape on blocks.
 * Shows an error message when the tape is used on blocks that are not part
 * of a construction in editing mode.
 */
public class TapeAttackHandler {

    private static TapeAttackHandler INSTANCE;

    private TapeAttackHandler() {
    }

    public static TapeAttackHandler getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new TapeAttackHandler();
        }
        return INSTANCE;
    }

    /**
     * Registers the attack callback.
     * Called during mod initialization.
     */
    public void register() {
        AttackBlockCallback.EVENT.register(this::onAttackBlock);
        Architect.LOGGER.info("TapeAttackHandler registered");
    }

    /**
     * Called when a player attacks (left-clicks) a block.
     */
    private InteractionResult onAttackBlock(Player player, Level level, InteractionHand hand, BlockPos pos,
            Direction direction) {
        // Only process on server side
        if (level.isClientSide()) {
            return InteractionResult.PASS;
        }

        // Check if holding the Measuring Tape
        if (!player.getItemInHand(hand).is(ModItems.MEASURING_TAPE)) {
            return InteractionResult.PASS;
        }

        ServerPlayer serverPlayer = (ServerPlayer) player;
        EditingSession session = EditingSession.getSession(serverPlayer.getUUID());

        // Check if in editing mode
        if (session == null) {
            serverPlayer.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(serverPlayer, "tape.error.not_editing")));
            return InteractionResult.FAIL;
        }

        // Check if editing a room - tape only works on base construction
        if (session.isInRoom()) {
            serverPlayer.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(serverPlayer, "tape.error.not_in_room")));
            return InteractionResult.FAIL;
        }

        // Check if block is part of the current construction
        Construction construction = session.getConstruction();
        boolean isInConstruction = construction.containsBlock(pos);

        if (!isInConstruction) {
            serverPlayer.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(serverPlayer, "tape.error.not_in_construction")));
            return InteractionResult.FAIL;
        }

        // Block is in construction - allow normal interaction (for future Tape features)
        return InteractionResult.PASS;
    }
}
