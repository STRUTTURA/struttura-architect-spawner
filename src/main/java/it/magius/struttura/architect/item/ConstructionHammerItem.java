package it.magius.struttura.architect.item;

import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.registry.ConstructionRegistry;
import it.magius.struttura.architect.selection.SelectionManager;
import it.magius.struttura.architect.session.EditingSession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;

/**
 * Martello da Costruzione - Item principale per l'editing delle costruzioni.
 *
 * Comportamento:
 * - NON in editing:
 *   - Click destro su aria: apre GUI principale
 *   - Click destro su blocco di costruzione: entra in quella costruzione
 *   - Click destro su blocco normale: apre GUI principale
 *   - Shift + Click destro su aria: apre GUI "Nuova Costruzione"
 *
 * - IN editing:
 *   - Click destro su aria: apre GUI info costruzione
 *   - Click destro su blocco IN costruzione: RIMUOVE dalla costruzione
 *   - Click destro su blocco NON in costruzione: AGGIUNGE alla costruzione
 *   - CTRL + Click destro su aria: apre GUI info costruzione (TODO)
 */
public class ConstructionHammerItem extends Item {

    public ConstructionHammerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // Right-click in air: GUI is handled client-side via mixin
        // Server just acknowledges the interaction
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        ServerPlayer player = (ServerPlayer) context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        BlockPos clickedPos = context.getClickedPos();
        BlockState clickedState = level.getBlockState(clickedPos);
        EditingSession session = EditingSession.getSession(player.getUUID());

        if (session == null) {
            // Non in editing: controlla se il blocco appartiene a una costruzione
            return handleClickOutsideEditing(player, clickedPos);
        } else {
            // In editing: gestione blocchi
            return handleClickInEditing(player, clickedPos, clickedState, session);
        }
    }

    private InteractionResult handleClickOutsideEditing(ServerPlayer player, BlockPos clickedPos) {
        // Cerca se il blocco appartiene a una costruzione esistente
        var registry = ConstructionRegistry.getInstance();

        for (String id : registry.getAllIds()) {
            var construction = registry.get(id);
            if (construction != null && construction.containsBlock(clickedPos)) {
                // Entra nella costruzione
                enterConstruction(player, id);
                return InteractionResult.SUCCESS;
            }
        }

        // Blocco normale: no action needed (clicking on regular blocks does nothing when not editing)
        return InteractionResult.PASS;
    }

    private InteractionResult handleClickInEditing(ServerPlayer player, BlockPos clickedPos,
            BlockState clickedState, EditingSession session) {

        var currentConstruction = session.getConstruction();
        boolean isInRoom = session.isInRoom();

        // Determina se il blocco è nel target corrente (room o construction)
        boolean isInTarget;
        if (isInRoom) {
            var room = session.getCurrentRoomObject();
            isInTarget = room != null && room.hasBlockChange(clickedPos);
        } else {
            isInTarget = currentConstruction.containsBlock(clickedPos);
        }

        if (isInTarget) {
            // Blocco IN target corrente: RIMUOVI
            if (isInRoom) {
                var room = session.getCurrentRoomObject();
                if (room != null) {
                    room.removeBlockChange(clickedPos);
                }
            } else {
                currentConstruction.removeBlock(clickedPos);
            }

            // Messaggio con nome edificio/room, coordinate e totale
            int totalBlocks = isInRoom && session.getCurrentRoomObject() != null
                ? session.getCurrentRoomObject().getChangedBlockCount()
                : currentConstruction.getBlockCount();
            String targetName = formatTargetName(session);
            String coords = String.format("§8[%d, %d, %d]", clickedPos.getX(), clickedPos.getY(), clickedPos.getZ());
            player.sendSystemMessage(Component.literal(targetName + ": §c" +
                    I18n.tr(player, "block.removed") + " " + coords + " §7(" + totalBlocks + ")"));

            NetworkHandler.sendWireframeSync(player);
            NetworkHandler.sendEditingInfo(player);
            NetworkHandler.sendBlockPositions(player);
        } else {
            // Controlla se il blocco appartiene a un'altra costruzione
            var registry = ConstructionRegistry.getInstance();
            String otherConstructionId = null;

            for (String id : registry.getAllIds()) {
                if (id.equals(currentConstruction.getId())) {
                    continue; // Salta la costruzione corrente
                }
                var otherConstruction = registry.get(id);
                if (otherConstruction != null && otherConstruction.containsBlock(clickedPos)) {
                    otherConstructionId = id;
                    break;
                }
            }

            if (otherConstructionId != null) {
                // Blocco appartiene a un'altra costruzione: esci dalla corrente e entra nell'altra
                exitAndEnterConstruction(player, session, otherConstructionId);
            } else {
                // Blocco NON in nessuna costruzione: AGGIUNGI al target corrente
                if (!clickedState.isAir()) {
                    if (isInRoom) {
                        var room = session.getCurrentRoomObject();
                        if (room != null) {
                            room.setBlockChange(clickedPos, clickedState);
                            // Espandi i bounds della costruzione se il blocco è fuori dai bounds attuali
                            currentConstruction.getBounds().expandToInclude(clickedPos);
                        }
                    } else {
                        currentConstruction.addBlock(clickedPos, clickedState);
                    }

                    // Messaggio con nome edificio/room, coordinate e totale
                    int totalBlocks = isInRoom && session.getCurrentRoomObject() != null
                        ? session.getCurrentRoomObject().getChangedBlockCount()
                        : currentConstruction.getBlockCount();
                    String targetName = formatTargetName(session);
                    String coords = String.format("§8[%d, %d, %d]", clickedPos.getX(), clickedPos.getY(), clickedPos.getZ());
                    player.sendSystemMessage(Component.literal(targetName + ": §a" +
                            I18n.tr(player, "block.added") + " " + coords + " §7(" + totalBlocks + ")"));

                    NetworkHandler.sendWireframeSync(player);
                    NetworkHandler.sendEditingInfo(player);
                    NetworkHandler.sendBlockPositions(player);
                }
            }
        }

        return InteractionResult.SUCCESS;
    }

    /**
     * Formatta il nome del target corrente (edificio o edificio/stanza) con colori.
     */
    private String formatTargetName(EditingSession session) {
        String constructionId = session.getConstruction().getId();
        // Estrai solo l'ultima parte dell'ID (dopo l'ultimo punto)
        String shortName = constructionId.contains(".")
            ? constructionId.substring(constructionId.lastIndexOf('.') + 1)
            : constructionId;

        if (session.isInRoom()) {
            var room = session.getCurrentRoomObject();
            String roomName = room != null ? room.getName() : session.getCurrentRoom();
            return "§d" + shortName + "§7/§e" + roomName;
        } else {
            return "§d" + shortName;
        }
    }

    private void exitAndEnterConstruction(ServerPlayer player, EditingSession currentSession, String newConstructionId) {
        // enterConstruction ora gestisce automaticamente l'exit dalla sessione corrente
        enterConstruction(player, newConstructionId);
    }

    private void enterConstruction(ServerPlayer player, String id) {
        var registry = ConstructionRegistry.getInstance();
        var construction = registry.get(id);

        if (construction == null) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "error.construction_not_found", id)));
            return;
        }

        // Se il player è già in editing, esci prima dalla sessione corrente
        if (EditingSession.hasSession(player)) {
            EditingSession currentSession = EditingSession.endSession(player);
            var currentConstruction = currentSession.getConstruction();

            // Salva la costruzione corrente se ha blocchi
            if (currentConstruction.getBlockCount() > 0) {
                ConstructionRegistry.getInstance().register(currentConstruction);
            }

            // Pulisci la selezione
            SelectionManager.getInstance().clearSelection(player);
        }

        EditingSession session = EditingSession.startSession(player, construction);
        NetworkHandler.sendWireframeSync(player);
        NetworkHandler.sendEditingInfo(player);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "enter.success", id)));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);

        tooltip.accept(Component.literal(""));
        tooltip.accept(Component.translatable("item.architect.construction_hammer.tooltip.line1"));
        tooltip.accept(Component.translatable("item.architect.construction_hammer.tooltip.line2"));
        tooltip.accept(Component.translatable("item.architect.construction_hammer.tooltip.header").withStyle(style -> style.withColor(0xAAAAAA)));
        tooltip.accept(Component.translatable("item.architect.construction_hammer.tooltip.line3"));
        tooltip.accept(Component.translatable("item.architect.construction_hammer.tooltip.line4"));
    }
}
