package it.magius.struttura.architect.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EditMode;
import it.magius.struttura.architect.model.Room;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.registry.ConstructionRegistry;
import it.magius.struttura.architect.registry.ModItems;
import it.magius.struttura.architect.selection.SelectionManager;
import it.magius.struttura.architect.session.EditingSession;
import it.magius.struttura.architect.vanilla.VanillaBatchPushState;
import it.magius.struttura.architect.vanilla.VanillaStructureLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Comando principale /struttura con tutti i subcomandi.
 */
public class StrutturaCommand {

    // Set delle costruzioni attualmente visibili nel mondo
    private static final java.util.Set<String> VISIBLE_CONSTRUCTIONS = new java.util.HashSet<>();

    // Set delle costruzioni attualmente in fase di pull (locked)
    private static final java.util.Set<String> PULLING_CONSTRUCTIONS = new java.util.HashSet<>();

    /**
     * Verifica se una costruzione è attualmente in fase di pull.
     */
    public static boolean isConstructionBeingPulled(String constructionId) {
        return PULLING_CONSTRUCTIONS.contains(constructionId);
    }

    // Suggerimenti per gli ID delle costruzioni esistenti (incluse quelle in editing)
    private static final SuggestionProvider<CommandSourceStack> CONSTRUCTION_ID_SUGGESTIONS =
        (context, builder) -> {
            java.util.Set<String> ids = new java.util.HashSet<>();
            // Aggiungi dal registry
            ids.addAll(ConstructionRegistry.getInstance().getAllIds());
            // Aggiungi dalle sessioni attive
            for (EditingSession session : EditingSession.getAllSessions()) {
                ids.add(session.getConstruction().getId());
            }
            return SharedSuggestionProvider.suggest(ids, builder);
        };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("struttura")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) // Richiede livello OP 2 (Game Master)
                .then(Commands.literal("edit")
                    .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(CONSTRUCTION_ID_SUGGESTIONS)
                        .executes(StrutturaCommand::executeEdit)
                    )
                )
                .then(Commands.literal("done")
                    .executes(StrutturaCommand::executeDone)
                    .then(Commands.literal("nomob")
                        .executes(StrutturaCommand::executeDoneNoMob)
                    )
                )
                .then(Commands.literal("mode")
                    .then(Commands.literal("add")
                        .executes(ctx -> executeMode(ctx, true))
                    )
                    .then(Commands.literal("remove")
                        .executes(ctx -> executeMode(ctx, false))
                    )
                )
                .then(Commands.literal("info")
                    .executes(StrutturaCommand::executeInfo)
                )
                .then(Commands.literal("list")
                    .executes(StrutturaCommand::executeList)
                )
                .then(Commands.literal("tp")
                    .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(CONSTRUCTION_ID_SUGGESTIONS)
                        .executes(StrutturaCommand::executeTp)
                    )
                )
                .then(Commands.literal("remove")
                    .then(Commands.argument("block_id", StringArgumentType.greedyString())
                        .suggests(BLOCK_ID_SUGGESTIONS)
                        .executes(StrutturaCommand::executeRemoveBlockType)
                    )
                )
                .then(Commands.literal("spawn")
                    .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(CONSTRUCTION_ID_SUGGESTIONS)
                        .executes(StrutturaCommand::executeSpawn)
                    )
                )
                .then(Commands.literal("show")
                    .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(CONSTRUCTION_ID_SUGGESTIONS)
                        .executes(StrutturaCommand::executeShow)
                    )
                )
                .then(Commands.literal("hide")
                    .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(CONSTRUCTION_ID_SUGGESTIONS)
                        .executes(StrutturaCommand::executeHide)
                    )
                )
                .then(Commands.literal("select")
                    .then(Commands.literal("pos1")
                        .executes(StrutturaCommand::executeSelectPos1)
                    )
                    .then(Commands.literal("pos2")
                        .executes(StrutturaCommand::executeSelectPos2)
                    )
                    .then(Commands.literal("apply")
                        .executes(ctx -> executeSelectApply(ctx, false))
                    )
                    .then(Commands.literal("applyall")
                        .executes(ctx -> executeSelectApply(ctx, true))
                    )
                    .then(Commands.literal("clear")
                        .executes(StrutturaCommand::executeSelectClear)
                    )
                    .then(Commands.literal("info")
                        .executes(StrutturaCommand::executeSelectInfo)
                    )
                )
                .then(Commands.literal("push")
                    .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(CONSTRUCTION_ID_SUGGESTIONS)
                        .executes(StrutturaCommand::executePush)
                    )
                )
                .then(Commands.literal("title")
                    .then(Commands.argument("lang", StringArgumentType.word())
                        .suggests(LANGUAGE_SUGGESTIONS)
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                            .executes(StrutturaCommand::executeTitle)
                        )
                    )
                )
                .then(Commands.literal("desc")
                    .then(Commands.argument("lang", StringArgumentType.word())
                        .suggests(LANGUAGE_SUGGESTIONS)
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                            .executes(StrutturaCommand::executeDesc)
                        )
                    )
                )
                .then(Commands.literal("desc_full")
                    .then(Commands.argument("lang", StringArgumentType.word())
                        .suggests(LANGUAGE_SUGGESTIONS)
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                            .executes(StrutturaCommand::executeDescFull)
                        )
                    )
                )
                .then(Commands.literal("shot")
                    // In editing mode: /struttura shot [title]
                    .executes(StrutturaCommand::executeShotInEditing)
                    // Outside editing mode: /struttura shot <id> [title]
                    .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(CONSTRUCTION_ID_SUGGESTIONS)
                        .executes(StrutturaCommand::executeShotWithId)
                        .then(Commands.argument("title", StringArgumentType.greedyString())
                            .executes(StrutturaCommand::executeShotWithIdAndTitle)
                        )
                    )
                )
                .then(Commands.literal("give")
                    .executes(StrutturaCommand::executeGive)
                )
                .then(Commands.literal("destroy")
                    // In editing mode: /struttura destroy (destroys current construction)
                    .executes(StrutturaCommand::executeDestroyInEditing)
                    // Outside editing mode: /struttura destroy <id>
                    .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(CONSTRUCTION_ID_SUGGESTIONS)
                        .executes(StrutturaCommand::executeDestroyWithId)
                    )
                )
                .then(Commands.literal("pull")
                    .then(Commands.argument("id", StringArgumentType.string())
                        .executes(StrutturaCommand::executePull)
                    )
                )
                .then(Commands.literal("move")
                    .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(CONSTRUCTION_ID_SUGGESTIONS)
                        .executes(StrutturaCommand::executeMove)
                    )
                )
                .then(Commands.literal("room")
                    .then(Commands.literal("edit")
                        .then(Commands.argument("room_id", StringArgumentType.string())
                            .suggests(ROOM_ID_SUGGESTIONS)
                            .executes(StrutturaCommand::executeRoomEdit)
                        )
                    )
                    .then(Commands.literal("done")
                        .executes(StrutturaCommand::executeRoomDone)
                    )
                    .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                            .executes(StrutturaCommand::executeRoomCreate)
                        )
                    )
                    .then(Commands.literal("delete")
                        .then(Commands.argument("room_id", StringArgumentType.string())
                            .suggests(ROOM_ID_SUGGESTIONS)
                            .executes(StrutturaCommand::executeRoomDelete)
                        )
                    )
                    .then(Commands.literal("list")
                        .executes(StrutturaCommand::executeRoomList)
                    )
                    .then(Commands.literal("rename")
                        .then(Commands.argument("room_id", StringArgumentType.string())
                            .suggests(ROOM_ID_SUGGESTIONS)
                            .then(Commands.argument("new_name", StringArgumentType.greedyString())
                                .executes(StrutturaCommand::executeRoomRename)
                            )
                        )
                    )
                )
                .then(Commands.literal("vanilla")
                    .then(Commands.literal("list")
                        .executes(StrutturaCommand::executeVanillaList)
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                            .executes(StrutturaCommand::executeVanillaListFiltered)
                        )
                    )
                    .then(Commands.literal("batchpush")
                        .executes(StrutturaCommand::executeVanillaBatchPush)
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                            .executes(StrutturaCommand::executeVanillaBatchPushFiltered)
                        )
                    )
                )
        );
    }

    // Suggerimenti per i codici lingua supportati
    private static final SuggestionProvider<CommandSourceStack> LANGUAGE_SUGGESTIONS =
        (context, builder) -> SharedSuggestionProvider.suggest(
            java.util.List.of("en", "it", "de", "fr", "es", "pt", "ru", "zh", "ja", "ko"),
            builder
        );

    // Suggerimenti per gli ID delle stanze nella costruzione corrente
    private static final SuggestionProvider<CommandSourceStack> ROOM_ID_SUGGESTIONS =
        (context, builder) -> {
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            if (context.getSource().getEntity() instanceof ServerPlayer player) {
                EditingSession session = EditingSession.getSession(player);
                if (session != null) {
                    ids.addAll(session.getConstruction().getRooms().keySet());
                }
            }
            return SharedSuggestionProvider.suggest(ids, builder);
        };

    // Suggerimenti per gli ID dei blocchi (solo quelli presenti nella costruzione corrente)
    private static final SuggestionProvider<CommandSourceStack> BLOCK_ID_SUGGESTIONS =
        (context, builder) -> {
            java.util.Set<String> suggestions = new java.util.LinkedHashSet<>();

            // Suggerisci solo i tipi di blocco presenti nella costruzione corrente
            if (context.getSource().getEntity() instanceof ServerPlayer player) {
                EditingSession session = EditingSession.getSession(player);
                if (session != null) {
                    for (net.minecraft.world.level.block.state.BlockState state : session.getConstruction().getBlocks().values()) {
                        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(state.getBlock())
                            .toString();
                        suggestions.add(blockId);
                    }
                }
            }

            return SharedSuggestionProvider.suggest(suggestions, builder);
        };

    private static int executeEdit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        // Verifica che sia un giocatore
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Verifica che non sia gia' in editing
        if (EditingSession.hasSession(player)) {
            EditingSession session = EditingSession.getSession(player);
            source.sendFailure(Component.literal(
                I18n.tr(player, "edit.already_editing", session.getConstruction().getId())
            ));
            return 0;
        }

        String id = StringArgumentType.getString(ctx, "id");

        // Valida formato ID
        if (!Construction.isValidId(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "edit.invalid_id", id)));
            return 0;
        }

        // Verifica che la costruzione non sia in fase di pull
        if (isConstructionBeingPulled(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "edit.being_pulled", id)));
            return 0;
        }

        // Verifica che un altro giocatore non stia gia' modificando questa costruzione
        EditingSession existingSession = getSessionForConstruction(id);
        if (existingSession != null) {
            String otherPlayerName = existingSession.getPlayer().getName().getString();
            source.sendFailure(Component.literal(
                I18n.tr(player, "edit.already_in_use", id, otherPlayerName)
            ));
            return 0;
        }

        // Verifica se esiste gia' una costruzione con questo ID nel registro
        Construction construction;
        ConstructionRegistry registry = ConstructionRegistry.getInstance();

        if (registry.exists(id)) {
            // Carica la costruzione esistente
            construction = registry.get(id);
            Architect.LOGGER.info("Player {} re-editing existing construction: {}", player.getName().getString(), id);
        } else {
            // Crea nuova costruzione
            construction = new Construction(
                id,
                player.getUUID(),
                player.getName().getString()
            );
            Architect.LOGGER.info("Player {} created new construction: {}", player.getName().getString(), id);
        }

        // Avvia sessione di editing
        EditingSession.startSession(player, construction);

        // Invia sync wireframe al client
        NetworkHandler.sendWireframeSync(player);

        // Invia info editing al client per la GUI
        NetworkHandler.sendEditingInfo(player);

        source.sendSuccess(() -> Component.literal(I18n.tr(player, "edit.success", id)), false);

        return 1;
    }

    private static int executeDone(CommandContext<CommandSourceStack> ctx) {
        return executeDoneCommon(ctx, true);
    }

    private static int executeDoneNoMob(CommandContext<CommandSourceStack> ctx) {
        return executeDoneCommon(ctx, false);
    }

    private static int executeDoneCommon(CommandContext<CommandSourceStack> ctx, boolean saveEntities) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        if (!EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.not_editing")));
            return 0;
        }

        EditingSession session = EditingSession.endSession(player);
        Construction construction = session.getConstruction();

        // Se nomob, rimuovi le entità dalla costruzione prima di salvare
        if (!saveEntities) {
            construction.clearEntities();
            Architect.LOGGER.info("Cleared entities from construction {} (done nomob)",
                construction.getId());
        }

        // Registra la costruzione nel registro (solo se ha blocchi)
        if (construction.getBlockCount() > 0) {
            ConstructionRegistry.getInstance().register(construction);
            Architect.LOGGER.info("Construction {} registered with {} blocks, {} entities",
                construction.getId(), construction.getBlockCount(), construction.getEntityCount());
        }

        Architect.LOGGER.info("Player {} finished editing construction: {} ({} blocks, {} entities)",
            player.getName().getString(),
            construction.getId(),
            construction.getBlockCount(),
            construction.getEntityCount()
        );

        // Pulisci anche la selezione
        SelectionManager.getInstance().clearSelection(player);

        // Invia sync wireframe vuoto al client
        NetworkHandler.sendEmptyWireframe(player);

        // Invia stato editing vuoto al client per la GUI
        NetworkHandler.sendEditingInfoEmpty(player);

        // Invia lista costruzioni aggiornata
        NetworkHandler.sendConstructionList(player);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "done.success", construction.getId(), construction.getBlockCount())
        ), false);

        return 1;
    }

    private static int executeMode(CommandContext<CommandSourceStack> ctx, boolean addMode) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        if (!EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.not_editing_hint")));
            return 0;
        }

        EditingSession session = EditingSession.getSession(player);

        if (addMode) {
            session.setMode(it.magius.struttura.architect.model.EditMode.ADD);
            source.sendSuccess(() -> Component.literal(I18n.tr(player, "mode.add.success")), false);
        } else {
            session.setMode(it.magius.struttura.architect.model.EditMode.REMOVE);
            source.sendSuccess(() -> Component.literal(I18n.tr(player, "mode.remove.success")), false);
        }

        // Aggiorna la preview della selezione (dipende dalla modalità)
        NetworkHandler.sendWireframeSync(player);

        return 1;
    }

    private static int executeInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        if (!EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.not_editing")));
            return 0;
        }

        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();

        StringBuilder info = new StringBuilder();
        info.append(I18n.tr(player, "info.header"));
        info.append("\n").append(I18n.tr(player, "info.construction", construction.getId()));

        // Mostra titoli multilingua
        if (construction.getTitles().isEmpty()) {
            info.append("\n").append(I18n.tr(player, "info.titles", I18n.tr(player, "info.not_set")));
        } else {
            info.append("\n").append(I18n.tr(player, "info.titles_header"));
            for (var entry : construction.getTitles().entrySet()) {
                info.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue());
            }
        }

        // Mostra descrizioni brevi multilingua
        if (construction.getShortDescriptions().isEmpty()) {
            info.append("\n").append(I18n.tr(player, "info.short_descriptions", I18n.tr(player, "info.not_set")));
        } else {
            info.append("\n").append(I18n.tr(player, "info.short_descriptions_header"));
            for (var entry : construction.getShortDescriptions().entrySet()) {
                info.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue());
            }
        }

        // Mostra descrizioni complete multilingua
        if (construction.getDescriptions().isEmpty()) {
            info.append("\n").append(I18n.tr(player, "info.descriptions", I18n.tr(player, "info.not_set")));
        } else {
            info.append("\n").append(I18n.tr(player, "info.descriptions_header"));
            for (var entry : construction.getDescriptions().entrySet()) {
                info.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue());
            }
        }

        info.append("\n").append(I18n.tr(player, "info.mode", session.getMode().name()));
        info.append("\n").append(I18n.tr(player, "info.blocks_tracked", construction.getBlockCount()));
        info.append("\n").append(I18n.tr(player, "info.blocks_solid", construction.getSolidBlockCount()));
        info.append("\n").append(I18n.tr(player, "info.bounds", construction.getBounds().toString()));

        if (session.isInRoom()) {
            info.append("\n").append(I18n.tr(player, "info.current_room", session.getCurrentRoom()));
        }

        final String finalInfo = info.toString();
        source.sendSuccess(() -> Component.literal(finalInfo), false);

        return 1;
    }

    private static int executeList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        // Ottiene tutte le costruzioni, incluse quelle in editing
        Collection<Construction> constructions = getAllConstructionsIncludingEditing();

        // Per list, usiamo la lingua del giocatore se disponibile
        ServerPlayer player = source.getEntity() instanceof ServerPlayer sp ? sp : null;

        if (constructions.isEmpty()) {
            if (player != null) {
                source.sendSuccess(() -> Component.literal(
                    I18n.tr(player, "list.header") + "\n" + I18n.tr(player, "list.empty")
                ), false);
            } else {
                source.sendSuccess(() -> Component.literal(
                    I18n.tr("list.header") + "\n" + I18n.tr("list.empty")
                ), false);
            }
            return 1;
        }

        StringBuilder sb = new StringBuilder();
        if (player != null) {
            sb.append(I18n.tr(player, "list.header")).append("\n");
            sb.append(I18n.tr(player, "list.total", constructions.size())).append("\n");

            for (Construction c : constructions) {
                BlockPos pos = getConstructionPosition(c.getId());
                String posStr = pos != null
                    ? String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ())
                    : I18n.tr(player, "list.position_unavailable");

                // Indica se in editing
                boolean inEditing = isConstructionBeingEdited(c.getId());
                String editingMarker = inEditing ? " [EDITING]" : "";

                sb.append("\n- ").append(c.getId()).append(editingMarker);
                sb.append("\n  ").append(I18n.tr(player, "list.author", c.getAuthorName()));
                sb.append("\n  ").append(I18n.tr(player, "list.blocks", c.getBlockCount()));
                sb.append("\n  ").append(I18n.tr(player, "list.position", posStr));
            }
        } else {
            sb.append(I18n.tr("list.header")).append("\n");
            sb.append(I18n.tr("list.total", constructions.size())).append("\n");

            for (Construction c : constructions) {
                BlockPos pos = getConstructionPosition(c.getId());
                String posStr = pos != null
                    ? String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ())
                    : I18n.tr("list.position_unavailable");

                // Indica se in editing
                boolean inEditing = isConstructionBeingEdited(c.getId());
                String editingMarker = inEditing ? " [EDITING]" : "";

                sb.append("\n- ").append(c.getId()).append(editingMarker);
                sb.append("\n  ").append(I18n.tr("list.author", c.getAuthorName()));
                sb.append("\n  ").append(I18n.tr("list.blocks", c.getBlockCount()));
                sb.append("\n  ").append(I18n.tr("list.position", posStr));
            }
        }

        final String list = sb.toString();
        source.sendSuccess(() -> Component.literal(list), false);

        return 1;
    }

    private static int executeTp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        // Solo i giocatori possono usare questo comando
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        String id = StringArgumentType.getString(ctx, "id");

        // Verifica che la costruzione esista (incluse quelle in editing)
        if (!constructionExistsIncludingEditing(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "tp.not_found", id)));
            return 0;
        }

        // Ottieni la costruzione per avere accesso ai bounds
        Construction construction = getConstructionIncludingEditing(id);
        if (construction == null || !construction.getBounds().isValid()) {
            source.sendFailure(Component.literal(I18n.tr(player, "tp.no_position", id)));
            return 0;
        }

        // Calcola il centro della costruzione
        BlockPos center = construction.getBounds().getCenter();
        double centerX = center.getX() + 0.5;
        double centerY = center.getY() + 0.5;
        double centerZ = center.getZ() + 0.5;

        // Posizione di teleport (sul bordo della costruzione, lato sud)
        BlockPos min = construction.getBounds().getMin();
        double tpX = centerX;
        double tpY = min.getY();
        double tpZ = construction.getBounds().getMax().getZ() + 2; // 2 blocchi fuori dal bordo sud

        // Calcola yaw per guardare verso il centro (nord = verso -Z)
        double dx = centerX - tpX;
        double dz = centerZ - tpZ;
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);

        // Calcola pitch per guardare verso il centro
        double dy = centerY - (tpY + 1.6); // 1.6 = altezza occhi
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) (-Math.atan2(dy, horizontalDist) * 180.0 / Math.PI);

        // Teleporta con rotazione
        player.teleportTo(
            (net.minecraft.server.level.ServerLevel) player.level(),
            tpX, tpY, tpZ,
            java.util.Set.of(), // No relative flags
            yaw, pitch,
            false // Non forzare il dismount
        );

        BlockPos pos = new BlockPos((int) tpX, (int) tpY, (int) tpZ);

        Architect.LOGGER.info("Teleported {} to construction {} at [{}, {}, {}] facing center",
            player.getName().getString(), id, pos.getX(), pos.getY(), pos.getZ());

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "tp.success_self", id, pos.getX(), pos.getY(), pos.getZ())
        ), false);

        return 1;
    }

    private static int executeRemoveBlockType(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        if (!EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.not_editing_hint")));
            return 0;
        }

        String blockId = StringArgumentType.getString(ctx, "block_id");

        // Normalizza l'ID del blocco (aggiungi minecraft: se mancante)
        if (!blockId.contains(":")) {
            blockId = "minecraft:" + blockId;
        }

        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();

        // Conta prima quanti blocchi ci sono di quel tipo
        int countBefore = construction.countBlocksByType(blockId);

        if (countBefore == 0) {
            final String finalBlockId = blockId;
            source.sendFailure(Component.literal(I18n.tr(player, "remove.no_blocks", finalBlockId)));
            return 0;
        }

        // Rimuovi i blocchi
        int removed = construction.removeBlocksByType(blockId);

        // Invia sync wireframe aggiornato (bounds potrebbero essere cambiati)
        NetworkHandler.sendWireframeSync(player);

        final String finalBlockId = blockId;
        Architect.LOGGER.info("Player {} removed {} blocks of type {} from construction {}",
            player.getName().getString(), removed, finalBlockId, construction.getId());

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "remove.success", removed, finalBlockId, construction.getBlockCount())
        ), false);

        return 1;
    }

    private static int executeSpawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        String id = StringArgumentType.getString(ctx, "id");

        // Verifica che la costruzione esista (incluse quelle in editing)
        if (!constructionExistsIncludingEditing(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "spawn.not_found", id)));
            return 0;
        }

        Construction construction = getConstructionIncludingEditing(id);
        if (construction == null || construction.getBlockCount() == 0) {
            source.sendFailure(Component.literal(I18n.tr(player, "spawn.empty", id)));
            return 0;
        }

        // Usa la funzione centralizzata per spawnare la costruzione
        // updateConstruction=false: non modifica le coordinate della costruzione nel registry
        int placedCount = NetworkHandler.spawnConstructionInFrontOfPlayer(player, construction, false);

        if (placedCount > 0) {
            Architect.LOGGER.info("Player {} spawned construction {} ({} blocks)",
                player.getName().getString(), id, placedCount);

            final int finalCount = placedCount;
            source.sendSuccess(() -> Component.literal(
                I18n.tr(player, "spawn.success_simple", id, finalCount)
            ), true);
        } else {
            source.sendFailure(Component.literal(I18n.tr(player, "spawn.failed", id)));
            return 0;
        }

        return 1;
    }

    private static int executeShow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        String id = StringArgumentType.getString(ctx, "id");
        ConstructionRegistry registry = ConstructionRegistry.getInstance();

        // Verifica che la costruzione esista
        if (!registry.exists(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "show.not_found", id)));
            return 0;
        }

        // Verifica che la costruzione non sia in modalita' editing
        if (isConstructionBeingEdited(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "show.in_editing", id)));
            return 0;
        }

        // Verifica che non sia gia' visibile
        if (VISIBLE_CONSTRUCTIONS.contains(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "show.already_visible", id)));
            return 0;
        }

        Construction construction = registry.get(id);
        if (construction == null || construction.getBlockCount() == 0) {
            source.sendFailure(Component.literal(I18n.tr(player, "show.empty", id)));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();

        // Usa la funzione centralizzata per mostrare la costruzione nella sua posizione originale
        int placedCount = NetworkHandler.showConstructionInPlace(level, construction);

        VISIBLE_CONSTRUCTIONS.add(id);

        final int finalCount = placedCount;
        Architect.LOGGER.info("Player {} showed construction {} ({} blocks)",
            player.getName().getString(), id, finalCount);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "show.success", id, finalCount)
        ), true);

        return 1;
    }

    private static int executeHide(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        String id = StringArgumentType.getString(ctx, "id");
        ConstructionRegistry registry = ConstructionRegistry.getInstance();

        // Verifica che la costruzione esista
        if (!registry.exists(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "hide.not_found", id)));
            return 0;
        }

        // Verifica che la costruzione non sia in modalita' editing
        if (isConstructionBeingEdited(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "hide.in_editing", id)));
            return 0;
        }

        Construction construction = registry.get(id);
        if (construction == null || construction.getBlockCount() == 0) {
            source.sendFailure(Component.literal(I18n.tr(player, "hide.not_found", id)));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();

        // Usa la funzione centralizzata per rimuovere tutti i blocchi
        int removedCount = NetworkHandler.hideConstructionFromWorld(level, construction);

        VISIBLE_CONSTRUCTIONS.remove(id);

        final int finalCount = removedCount;
        Architect.LOGGER.info("Player {} hid construction {} ({} blocks removed)",
            player.getName().getString(), id, finalCount);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "hide.success", id, finalCount)
        ), true);

        return 1;
    }

    /**
     * Verifica se una costruzione è attualmente in modalità editing da qualche giocatore.
     */
    private static boolean isConstructionBeingEdited(String constructionId) {
        return getSessionForConstruction(constructionId) != null;
    }

    /**
     * Ottiene la sessione di editing per una costruzione specifica.
     * @return la sessione se esiste, null altrimenti
     */
    private static EditingSession getSessionForConstruction(String constructionId) {
        for (EditingSession session : EditingSession.getAllSessions()) {
            if (session.getConstruction().getId().equals(constructionId)) {
                return session;
            }
        }
        return null;
    }

    /**
     * Ottiene tutte le sessioni di editing attive.
     */
    private static Collection<EditingSession> getActiveSessions() {
        return EditingSession.getAllSessions();
    }

    /**
     * Ottiene una costruzione dal registry O da una sessione attiva.
     * Utile per list, tp, spawn che devono poter accedere anche a costruzioni in editing.
     */
    private static Construction getConstructionIncludingEditing(String id) {
        // Prima controlla nel registry
        ConstructionRegistry registry = ConstructionRegistry.getInstance();
        if (registry.exists(id)) {
            return registry.get(id);
        }

        // Altrimenti cerca nelle sessioni attive
        EditingSession session = getSessionForConstruction(id);
        if (session != null) {
            return session.getConstruction();
        }

        return null;
    }

    /**
     * Verifica se una costruzione esiste nel registry O in una sessione attiva.
     */
    private static boolean constructionExistsIncludingEditing(String id) {
        return ConstructionRegistry.getInstance().exists(id) || getSessionForConstruction(id) != null;
    }

    /**
     * Ottiene tutte le costruzioni (dal registry + sessioni attive non salvate).
     */
    private static Collection<Construction> getAllConstructionsIncludingEditing() {
        Map<String, Construction> all = new HashMap<>();

        // Aggiungi tutte dal registry
        for (Construction c : ConstructionRegistry.getInstance().getAll()) {
            all.put(c.getId(), c);
        }

        // Aggiungi quelle in editing che non sono ancora nel registry
        for (EditingSession session : EditingSession.getAllSessions()) {
            Construction c = session.getConstruction();
            if (!all.containsKey(c.getId())) {
                all.put(c.getId(), c);
            }
        }

        return all.values();
    }

    /**
     * Ottiene la posizione di una costruzione (dal registry o dai bounds).
     */
    private static BlockPos getConstructionPosition(String id) {
        // Prima prova dal registry
        BlockPos pos = ConstructionRegistry.getInstance().getPosition(id);
        if (pos != null) {
            return pos;
        }

        // Se in editing, usa i bounds della costruzione
        EditingSession session = getSessionForConstruction(id);
        if (session != null) {
            Construction c = session.getConstruction();
            if (c.getBounds().isValid()) {
                return c.getBounds().getCenter();
            }
        }

        return null;
    }

    // ===== Comando Push =====

    private static int executePush(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        String id = StringArgumentType.getString(ctx, "id");

        // Verifica che la costruzione NON sia in modalità editing
        if (isConstructionBeingEdited(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "push.in_editing", id)));
            return 0;
        }

        // Verifica che la costruzione esista nel registry
        ConstructionRegistry registry = ConstructionRegistry.getInstance();
        if (!registry.exists(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "push.not_found", id)));
            return 0;
        }

        // Verifica che non ci sia già una richiesta in corso
        if (ApiClient.isRequestInProgress()) {
            source.sendFailure(Component.literal(I18n.tr(player, "push.request_in_progress")));
            return 0;
        }

        Construction construction = registry.get(id);
        if (construction == null || construction.getBlockCount() == 0) {
            source.sendFailure(Component.literal(I18n.tr(player, "push.empty", id)));
            return 0;
        }

        // Verifica che la costruzione abbia un titolo (obbligatorio per l'API)
        if (!construction.hasValidTitle()) {
            source.sendFailure(Component.literal(I18n.tr(player, "push.no_title", id)));
            return 0;
        }

        // Messaggio di invio
        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "push.sending", id, construction.getBlockCount())
        ), false);

        Architect.LOGGER.info("Player {} pushing construction {} ({} blocks)",
            player.getName().getString(), id, construction.getBlockCount());

        // Cattura il server per il callback sul main thread
        var server = ((ServerLevel) player.level()).getServer();

        // Esegui push asincrono
        boolean started = ApiClient.pushConstruction(construction, response -> {
            // Callback viene eseguito su thread async, schedula sul main thread
            if (server != null) {
                server.execute(() -> {
                    if (response.success()) {
                        player.sendSystemMessage(Component.literal(
                            I18n.tr(player, "push.success", id, response.statusCode(), response.message())
                        ));
                        Architect.LOGGER.info("Push successful for {}: {} - {}",
                            id, response.statusCode(), response.message());
                    } else {
                        player.sendSystemMessage(Component.literal(
                            I18n.tr(player, "push.failed", id, response.statusCode(), response.message())
                        ));
                        Architect.LOGGER.warn("Push failed for {}: {} - {}",
                            id, response.statusCode(), response.message());
                    }
                });
            }
        });

        if (!started) {
            source.sendFailure(Component.literal(I18n.tr(player, "push.request_in_progress")));
            return 0;
        }

        return 1;
    }

    // ===== Comandi Select =====

    private static int executeSelectPos1(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Il giocatore deve essere in modalita' editing
        if (!EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.not_editing_hint")));
            return 0;
        }

        // Usa la posizione del blocco su cui sta guardando o la posizione del player
        BlockPos pos = getTargetBlockPos(player);

        SelectionManager.getInstance().setPos1(player, pos);

        // Invia sync wireframe con la nuova selezione
        NetworkHandler.sendWireframeSync(player);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "select.pos1.success", pos.getX(), pos.getY(), pos.getZ())
        ), false);

        // Mostra info sulla selezione se completa
        showSelectionInfo(player, source);

        return 1;
    }

    private static int executeSelectPos2(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Il giocatore deve essere in modalita' editing
        if (!EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.not_editing_hint")));
            return 0;
        }

        // Usa la posizione del blocco su cui sta guardando o la posizione del player
        BlockPos pos = getTargetBlockPos(player);

        SelectionManager.getInstance().setPos2(player, pos);

        // Invia sync wireframe con la nuova selezione
        NetworkHandler.sendWireframeSync(player);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "select.pos2.success", pos.getX(), pos.getY(), pos.getZ())
        ), false);

        // Mostra info sulla selezione se completa
        showSelectionInfo(player, source);

        return 1;
    }

    private static int executeSelectApply(CommandContext<CommandSourceStack> ctx, boolean includeAir) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        if (!EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.not_editing_hint")));
            return 0;
        }

        // Delegate to centralized handler in NetworkHandler
        NetworkHandler.handleApply(player, EditingSession.getSession(player), includeAir);
        return 1;
    }

    private static int executeSelectClear(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        SelectionManager.getInstance().clearSelection(player);

        // Invia sync wireframe (selezione pulita)
        NetworkHandler.sendWireframeSync(player);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "select.clear.success")
        ), false);

        return 1;
    }

    private static int executeSelectInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        SelectionManager.Selection selection = SelectionManager.getInstance().getSelection(player);

        if (selection == null) {
            source.sendSuccess(() -> Component.literal(
                I18n.tr(player, "select.info.none")
            ), false);
            return 1;
        }

        StringBuilder info = new StringBuilder();
        info.append(I18n.tr(player, "select.info.header"));

        if (selection.hasPos1()) {
            BlockPos p1 = selection.getPos1();
            info.append("\n").append(I18n.tr(player, "select.info.pos1", p1.getX(), p1.getY(), p1.getZ()));
        } else {
            info.append("\n").append(I18n.tr(player, "select.info.pos1_missing"));
        }

        if (selection.hasPos2()) {
            BlockPos p2 = selection.getPos2();
            info.append("\n").append(I18n.tr(player, "select.info.pos2", p2.getX(), p2.getY(), p2.getZ()));
        } else {
            info.append("\n").append(I18n.tr(player, "select.info.pos2_missing"));
        }

        if (selection.isComplete()) {
            int[] dims = selection.getDimensions();
            info.append("\n").append(I18n.tr(player, "select.info.size", dims[0], dims[1], dims[2]));
            info.append("\n").append(I18n.tr(player, "select.info.volume", selection.getVolume()));
        }

        final String finalInfo = info.toString();
        source.sendSuccess(() -> Component.literal(finalInfo), false);

        return 1;
    }

    /**
     * Ottiene la posizione del blocco su cui il giocatore sta guardando.
     * Se non sta guardando un blocco, usa il blocco sotto i piedi del giocatore.
     */
    private static BlockPos getTargetBlockPos(ServerPlayer player) {
        // Raycast per trovare il blocco su cui sta guardando
        net.minecraft.world.phys.HitResult hitResult = player.pick(5.0, 0.0f, false);

        if (hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            net.minecraft.world.phys.BlockHitResult blockHit = (net.minecraft.world.phys.BlockHitResult) hitResult;
            return blockHit.getBlockPos();
        }

        // Fallback: blocco sotto i piedi del giocatore
        // player.blockPosition() e' la posizione dei piedi (aria), below() e' il blocco solido
        return player.blockPosition().below();
    }

    /**
     * Mostra informazioni sulla selezione se completa.
     */
    private static void showSelectionInfo(ServerPlayer player, CommandSourceStack source) {
        SelectionManager.Selection selection = SelectionManager.getInstance().getSelection(player);
        if (selection != null && selection.isComplete()) {
            int[] dims = selection.getDimensions();
            source.sendSuccess(() -> Component.literal(
                I18n.tr(player, "select.complete", dims[0], dims[1], dims[2], selection.getVolume())
            ), false);
        }
    }

    // ===== Comandi Title e Desc =====

    private static int executeTitle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        if (!EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.not_editing_hint")));
            return 0;
        }

        String lang = StringArgumentType.getString(ctx, "lang").toLowerCase();
        String text = StringArgumentType.getString(ctx, "text");
        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();

        construction.setTitle(lang, text);

        Architect.LOGGER.info("Player {} set title [{}] for construction {}: {}",
            player.getName().getString(), lang, construction.getId(), text);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "title.success", lang, text)
        ), false);

        return 1;
    }

    private static int executeDesc(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        if (!EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.not_editing_hint")));
            return 0;
        }

        String lang = StringArgumentType.getString(ctx, "lang").toLowerCase();
        String text = StringArgumentType.getString(ctx, "text");
        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();

        construction.setShortDescription(lang, text);

        Architect.LOGGER.info("Player {} set short description [{}] for construction {}: {}",
            player.getName().getString(), lang, construction.getId(), text);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "desc.success", lang, text)
        ), false);

        return 1;
    }

    private static int executeDescFull(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        if (!EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.not_editing_hint")));
            return 0;
        }

        String lang = StringArgumentType.getString(ctx, "lang").toLowerCase();
        String text = StringArgumentType.getString(ctx, "text");
        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();

        construction.setDescription(lang, text);

        Architect.LOGGER.info("Player {} set full description [{}] for construction {}: {}",
            player.getName().getString(), lang, construction.getId(), text);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "desc_full.success", lang, text)
        ), false);

        return 1;
    }

    // ===== Comando Shot =====

    /**
     * Esegue /struttura shot senza argomenti (in editing mode).
     * Usa l'ID della costruzione corrente e titolo default.
     */
    private static int executeShotInEditing(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Verifica che il giocatore sia in editing mode
        if (!EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "shot.no_id")));
            return 0;
        }

        EditingSession session = EditingSession.getSession(player);
        String constructionId = session.getConstruction().getId();

        return executeShotCommon(player, source, constructionId, null);
    }

    /**
     * Esegue /struttura shot <id> (con ID esplicito, senza titolo).
     */
    private static int executeShotWithId(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        String constructionId = StringArgumentType.getString(ctx, "id");
        return executeShotCommon(player, source, constructionId, null);
    }

    /**
     * Esegue /struttura shot <id> <title> (con ID esplicito e titolo).
     */
    private static int executeShotWithIdAndTitle(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        String constructionId = StringArgumentType.getString(ctx, "id");
        String title = StringArgumentType.getString(ctx, "title");
        return executeShotCommon(player, source, constructionId, title);
    }

    /**
     * Logica comune per il comando shot.
     */
    private static int executeShotCommon(ServerPlayer player, CommandSourceStack source,
                                         String constructionId, String title) {
        // Verifica che non ci sia già una richiesta in corso
        if (ApiClient.isRequestInProgress()) {
            source.sendFailure(Component.literal(I18n.tr(player, "push.request_in_progress")));
            return 0;
        }

        // Usa titolo default se non specificato
        String screenshotTitle = (title != null && !title.isEmpty()) ? title : "Screenshot";

        // Messaggio di cattura
        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "shot.in_editing", constructionId)
        ), false);

        Architect.LOGGER.info("Player {} taking screenshot for construction {}",
            player.getName().getString(), constructionId);

        // Invia richiesta screenshot al client
        NetworkHandler.sendScreenshotRequest(player, constructionId, screenshotTitle);

        return 1;
    }

    /**
     * Esegue /struttura give - da' il Martello da Costruzione al giocatore.
     */
    private static int executeGive(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Crea l'item e aggiungilo all'inventario
        ItemStack hammerStack = new ItemStack(ModItems.CONSTRUCTION_HAMMER);

        if (player.getInventory().add(hammerStack)) {
            source.sendSuccess(() -> Component.literal(
                "§a[Struttura] §f" + I18n.tr(player, "give.success")
            ), false);
            return 1;
        } else {
            source.sendFailure(Component.literal(
                "§c[Struttura] §f" + I18n.tr(player, "give.inventory_full")
            ));
            return 0;
        }
    }

    // ===== Comando Destroy =====

    /**
     * Esegue /struttura destroy senza argomenti (in editing mode).
     * Distrugge la costruzione correntemente in editing.
     */
    private static int executeDestroyInEditing(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Verifica che il giocatore sia in editing mode
        if (!EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "destroy.no_id")));
            return 0;
        }

        EditingSession session = EditingSession.getSession(player);
        String constructionId = session.getConstruction().getId();

        return executeDestroyCommon(player, source, constructionId);
    }

    /**
     * Esegue /struttura destroy <id> (con ID esplicito).
     */
    private static int executeDestroyWithId(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        String constructionId = StringArgumentType.getString(ctx, "id");
        return executeDestroyCommon(player, source, constructionId);
    }

    /**
     * Logica comune per il comando destroy.
     * 1. Se la costruzione è visibile (SHOW), ripristina i blocchi originali salvati
     * 2. Se il giocatore è in editing di questa costruzione, termina la sessione
     * 3. Rimuove la costruzione dal registry e dal filesystem
     */
    private static int executeDestroyCommon(ServerPlayer player, CommandSourceStack source, String constructionId) {
        ConstructionRegistry registry = ConstructionRegistry.getInstance();

        // Verifica che la costruzione esista (in registry o in editing)
        if (!constructionExistsIncludingEditing(constructionId)) {
            source.sendFailure(Component.literal(I18n.tr(player, "destroy.not_found", constructionId)));
            return 0;
        }

        // Verifica se un ALTRO giocatore sta editando questa costruzione
        EditingSession existingSession = getSessionForConstruction(constructionId);
        if (existingSession != null && !existingSession.getPlayer().getUUID().equals(player.getUUID())) {
            String otherPlayerName = existingSession.getPlayer().getName().getString();
            source.sendFailure(Component.literal(
                I18n.tr(player, "destroy.in_editing_by_other", constructionId, otherPlayerName)
            ));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();
        Construction construction = getConstructionIncludingEditing(constructionId);

        // 1. Rimuovi entità, svuota container e rimuovi blocchi
        int blocksRemoved = NetworkHandler.clearConstructionFromWorld(level, construction);
        if (blocksRemoved > 0) {
            Architect.LOGGER.info("Destroy: removed {} blocks for construction {}", blocksRemoved, constructionId);
        }

        // 2. Pulisci i dati di visibilità
        VISIBLE_CONSTRUCTIONS.remove(constructionId);

        // 3. Se il giocatore corrente sta editando questa costruzione, termina la sessione
        if (existingSession != null && existingSession.getPlayer().getUUID().equals(player.getUUID())) {
            EditingSession.endSession(player);

            // Pulisci la selezione
            SelectionManager.getInstance().clearSelection(player);

            // Invia sync wireframe vuoto al client
            NetworkHandler.sendEmptyWireframe(player);

            // Invia stato editing vuoto al client per la GUI
            NetworkHandler.sendEditingInfoEmpty(player);

            Architect.LOGGER.info("Destroy: ended editing session for {}", constructionId);
        }

        // 4. Rimuovi la costruzione dal registry (e dal filesystem)
        registry.unregister(constructionId);

        Architect.LOGGER.info("Player {} destroyed construction: {}", player.getName().getString(), constructionId);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "destroy.success", constructionId)
        ), true);

        return 1;
    }

    // ===== Comando Pull =====

    /**
     * Esegue /struttura pull <id>
     * Scarica una costruzione dal server e la piazza di fronte al giocatore.
     */
    private static int executePull(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        String id = StringArgumentType.getString(ctx, "id");

        // Verifica che l'ID sia valido
        if (!Construction.isValidId(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "edit.invalid_id", id)));
            return 0;
        }

        // Verifica che la costruzione non sia in editing
        if (isConstructionBeingEdited(id)) {
            EditingSession existingSession = getSessionForConstruction(id);
            String otherPlayerName = existingSession != null
                ? existingSession.getPlayer().getName().getString()
                : "unknown";
            source.sendFailure(Component.literal(
                I18n.tr(player, "pull.in_editing", id, otherPlayerName)
            ));
            return 0;
        }

        // Verifica che non sia già in corso un pull per questa costruzione
        if (PULLING_CONSTRUCTIONS.contains(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "pull.already_pulling", id)));
            return 0;
        }

        // Verifica che non ci sia già una richiesta API in corso
        if (ApiClient.isRequestInProgress()) {
            source.sendFailure(Component.literal(I18n.tr(player, "push.request_in_progress")));
            return 0;
        }

        // Blocca la costruzione per il pull
        PULLING_CONSTRUCTIONS.add(id);

        // Messaggio di inizio
        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "pull.starting", id)
        ), false);

        Architect.LOGGER.info("Player {} pulling construction {}", player.getName().getString(), id);

        // Cattura il server per il callback sul main thread
        var server = ((ServerLevel) player.level()).getServer();

        // Esegui pull asincrono
        boolean started = ApiClient.pullConstruction(id, response -> {
            // Callback viene eseguito su thread async, schedula sul main thread
            if (server != null) {
                server.execute(() -> {
                    // Sblocca la costruzione
                    PULLING_CONSTRUCTIONS.remove(id);

                    if (response.success() && response.construction() != null) {
                        Construction construction = response.construction();

                        // Registra la costruzione nel registry
                        ConstructionRegistry.getInstance().register(construction);

                        // Piazza la costruzione di fronte al giocatore usando la funzione centralizzata
                        // updateConstruction=true perché PULL deve aggiornare le coordinate della costruzione
                        int placedCount = NetworkHandler.spawnConstructionInFrontOfPlayer(player, construction, true);

                        player.sendSystemMessage(Component.literal(
                            I18n.tr(player, "pull.success", id, placedCount)
                        ));

                        Architect.LOGGER.info("Pull successful for {}: {} blocks placed",
                            id, placedCount);
                    } else {
                        player.sendSystemMessage(Component.literal(
                            I18n.tr(player, "pull.failed", id, response.statusCode(), response.message())
                        ));
                        Architect.LOGGER.warn("Pull failed for {}: {} - {}",
                            id, response.statusCode(), response.message());
                    }
                });
            } else {
                // Sblocca comunque se il server non è disponibile
                PULLING_CONSTRUCTIONS.remove(id);
            }
        });

        if (!started) {
            PULLING_CONSTRUCTIONS.remove(id);
            source.sendFailure(Component.literal(I18n.tr(player, "push.request_in_progress")));
            return 0;
        }

        return 1;
    }

    // ===== Comando Move =====

    /**
     * Esegue /struttura move <id>
     * Sposta fisicamente una costruzione davanti al giocatore.
     * La costruzione viene rimossa dalla posizione attuale e riposizionata.
     */
    private static int executeMove(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        String id = StringArgumentType.getString(ctx, "id");

        // Verifica che la costruzione esista
        if (!constructionExistsIncludingEditing(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "move.not_found", id)));
            return 0;
        }

        // Verifica che la costruzione non sia in editing
        if (isConstructionBeingEdited(id)) {
            EditingSession existingSession = getSessionForConstruction(id);
            String otherPlayerName = existingSession != null
                ? existingSession.getPlayer().getName().getString()
                : "unknown";
            source.sendFailure(Component.literal(
                I18n.tr(player, "move.in_editing", id, otherPlayerName)
            ));
            return 0;
        }

        // Verifica che non sia in fase di pull
        if (isConstructionBeingPulled(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "move.being_pulled", id)));
            return 0;
        }

        Construction construction = getConstructionIncludingEditing(id);
        if (construction == null || construction.getBlockCount() == 0) {
            source.sendFailure(Component.literal(I18n.tr(player, "move.empty", id)));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();
        var bounds = construction.getBounds();

        // 1. Salva i vecchi bounds PRIMA di modificare la costruzione
        int oldMinX = bounds.getMinX();
        int oldMinY = bounds.getMinY();
        int oldMinZ = bounds.getMinZ();
        int oldMaxX = bounds.getMaxX();
        int oldMaxY = bounds.getMaxY();
        int oldMaxZ = bounds.getMaxZ();

        // 2. Calcola la nuova posizione davanti al giocatore
        int sizeX = bounds.getSizeX();
        int sizeZ = bounds.getSizeZ();

        float yaw = player.getYRot();
        yaw = ((yaw % 360) + 360) % 360;

        int offsetX, offsetZ;
        BlockPos playerPos = player.blockPosition();

        if (yaw >= 315 || yaw < 45) {
            // Sud (+Z)
            offsetX = playerPos.getX() - bounds.getMinX() - (sizeX / 2);
            offsetZ = playerPos.getZ() + 2 - bounds.getMinZ();
        } else if (yaw >= 45 && yaw < 135) {
            // Ovest (-X)
            offsetX = playerPos.getX() - 2 - bounds.getMaxX();
            offsetZ = playerPos.getZ() - bounds.getMinZ() - (sizeZ / 2);
        } else if (yaw >= 135 && yaw < 225) {
            // Nord (-Z)
            offsetX = playerPos.getX() - bounds.getMinX() - (sizeX / 2);
            offsetZ = playerPos.getZ() - 2 - bounds.getMaxZ();
        } else {
            // Est (+X)
            offsetX = playerPos.getX() + 2 - bounds.getMinX();
            offsetZ = playerPos.getZ() - bounds.getMinZ() - (sizeZ / 2);
        }

        int offsetY = playerPos.getY() - bounds.getMinY();

        // 3. Crea una nuova mappa di blocchi con le posizioni aggiornate
        Map<BlockPos, BlockState> newBlocks = new HashMap<>();
        int placedCount = 0;

        // Ordina i blocchi per Y crescente per piazzare prima i blocchi di supporto
        // (es. trapdoor prima dei carpet che ci stanno sopra)
        java.util.List<Map.Entry<BlockPos, BlockState>> sortedBlocks =
            new java.util.ArrayList<>(construction.getBlocks().entrySet());
        sortedBlocks.sort((a, b) -> Integer.compare(a.getKey().getY(), b.getKey().getY()));

        // Usa UPDATE_CLIENTS | UPDATE_SKIP_ON_PLACE per preservare l'orientamento delle rotaie
        int placementFlags = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE;
        for (Map.Entry<BlockPos, BlockState> entry : sortedBlocks) {
            BlockPos originalPos = entry.getKey();
            BlockState state = entry.getValue();

            BlockPos newPos = originalPos.offset(offsetX, offsetY, offsetZ);

            // Piazza il blocco nel mondo
            if (!state.isAir()) {
                level.setBlock(newPos, state, placementFlags);
                placedCount++;
            }

            // Aggiungi alla nuova mappa (inclusi i blocchi aria per mantenere la struttura)
            newBlocks.put(newPos, state);
        }

        // 4. DESTROY: Rimuovi i blocchi dalla vecchia posizione usando i bounds salvati
        NetworkHandler.clearAreaFromWorld(level, oldMinX, oldMinY, oldMinZ, oldMaxX, oldMaxY, oldMaxZ);

        // 5. Aggiorna la costruzione con le nuove posizioni
        construction.getBlocks().clear();
        construction.getBounds().reset();
        for (Map.Entry<BlockPos, BlockState> entry : newBlocks.entrySet()) {
            construction.addBlock(entry.getKey(), entry.getValue());
        }

        // 6. Salva la costruzione aggiornata
        ConstructionRegistry.getInstance().register(construction);

        // 7. Aggiorna lo stato di visibilità
        VISIBLE_CONSTRUCTIONS.add(id);

        Architect.LOGGER.info("Player {} moved construction {} from old position to ({}, {}, {})",
            player.getName().getString(), id, playerPos.getX(), playerPos.getY(), playerPos.getZ());

        final int finalPlacedCount = placedCount;
        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "move.success", id, finalPlacedCount)
        ), true);

        return 1;
    }

    // ===== Comandi Room =====

    /**
     * Esegue /struttura room edit <room_id>
     * Entra in modalità editing di una stanza esistente.
     */
    private static int executeRoomEdit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Verifica che sia in editing
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.not_editing")));
            return 0;
        }

        String roomId = StringArgumentType.getString(ctx, "room_id");

        // Verifica che la stanza esista
        if (!session.getConstruction().hasRoom(roomId)) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.not_found", roomId)));
            return 0;
        }

        // Entra in editing della stanza
        if (session.enterRoom(roomId)) {
            Room room = session.getConstruction().getRoom(roomId);
            source.sendSuccess(() -> Component.literal(
                I18n.tr(player, "room.editing", room.getName(), roomId)
            ), false);
            return 1;
        } else {
            source.sendFailure(Component.literal(I18n.tr(player, "room.edit_failed", roomId)));
            return 0;
        }
    }

    /**
     * Esegue /struttura room done
     * Termina l'editing della stanza corrente e torna all'editing base.
     */
    private static int executeRoomDone(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Verifica che sia in editing
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.not_editing")));
            return 0;
        }

        // Verifica che sia in una stanza
        if (!session.isInRoom()) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.not_in_room")));
            return 0;
        }

        String roomId = session.getCurrentRoom();
        session.exitRoom();

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "room.done", roomId)
        ), false);

        return 1;
    }

    /**
     * Esegue /struttura room create <name>
     * Crea una nuova stanza e ci entra.
     */
    private static int executeRoomCreate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Verifica che sia in editing
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.not_editing")));
            return 0;
        }

        // Verifica limite stanze
        if (session.getConstruction().getRoomCount() >= 50) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.max_reached")));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");

        // Verifica lunghezza nome
        if (name.length() > 100) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.name_too_long")));
            return 0;
        }

        // Crea la stanza
        Room room = session.createRoom(name);
        if (room != null) {
            source.sendSuccess(() -> Component.literal(
                I18n.tr(player, "room.created", room.getName(), room.getId())
            ), true);
            return 1;
        } else {
            source.sendFailure(Component.literal(I18n.tr(player, "room.create_failed")));
            return 0;
        }
    }

    /**
     * Esegue /struttura room delete <room_id>
     * Elimina una stanza esistente.
     */
    private static int executeRoomDelete(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Verifica che sia in editing
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.not_editing")));
            return 0;
        }

        String roomId = StringArgumentType.getString(ctx, "room_id");

        // Verifica che la stanza esista
        if (!session.getConstruction().hasRoom(roomId)) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.not_found", roomId)));
            return 0;
        }

        // Elimina la stanza
        if (session.deleteRoom(roomId)) {
            source.sendSuccess(() -> Component.literal(
                I18n.tr(player, "room.deleted", roomId)
            ), true);
            return 1;
        } else {
            source.sendFailure(Component.literal(I18n.tr(player, "room.delete_failed", roomId)));
            return 0;
        }
    }

    /**
     * Esegue /struttura room list
     * Elenca tutte le stanze della costruzione corrente.
     */
    private static int executeRoomList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Verifica che sia in editing
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.not_editing")));
            return 0;
        }

        Construction construction = session.getConstruction();
        Map<String, Room> rooms = construction.getRooms();

        if (rooms.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                I18n.tr(player, "room.list_empty")
            ), false);
            return 1;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(I18n.tr(player, "room.list_header", rooms.size()));

        for (Room room : rooms.values()) {
            sb.append("\n  - ");
            sb.append(room.getName());
            sb.append(" (");
            sb.append(room.getId());
            sb.append(") - ");
            sb.append(I18n.tr(player, "room.block_changes", room.getChangedBlockCount()));

            // Indica se e' la stanza corrente
            if (room.getId().equals(session.getCurrentRoom())) {
                sb.append(" [*]");
            }
        }

        final String message = sb.toString();
        source.sendSuccess(() -> Component.literal(message), false);

        return 1;
    }

    /**
     * Esegue /struttura room rename <room_id> <new_name>
     * Rinomina una stanza esistente.
     */
    private static int executeRoomRename(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Verifica che sia in editing
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.not_editing")));
            return 0;
        }

        String roomId = StringArgumentType.getString(ctx, "room_id");
        String newName = StringArgumentType.getString(ctx, "new_name");

        // Verifica che la stanza esista
        if (!session.getConstruction().hasRoom(roomId)) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.not_found", roomId)));
            return 0;
        }

        // Verifica lunghezza nome
        if (newName.length() > 100) {
            source.sendFailure(Component.literal(I18n.tr(player, "room.name_too_long")));
            return 0;
        }

        // Rinomina la stanza (restituisce il nuovo ID o null se fallisce)
        String newId = session.renameRoom(roomId, newName);
        if (newId != null) {
            source.sendSuccess(() -> Component.literal(
                I18n.tr(player, "room.renamed", roomId, newName) +
                (newId.equals(roomId) ? "" : "\nNew ID: " + newId)
            ), true);
            return 1;
        } else {
            source.sendFailure(Component.literal(I18n.tr(player, "room.rename_failed", roomId)));
            return 0;
        }
    }

    // ===== Vanilla Commands =====

    /**
     * Executes /struttura vanilla list
     * Lists all available vanilla structure templates.
     */
    private static int executeVanillaList(CommandContext<CommandSourceStack> ctx) {
        return executeVanillaListCommon(ctx, null);
    }

    /**
     * Executes /struttura vanilla list <filter>
     * Lists vanilla structure templates matching the filter.
     */
    private static int executeVanillaListFiltered(CommandContext<CommandSourceStack> ctx) {
        String filter = StringArgumentType.getString(ctx, "filter");
        return executeVanillaListCommon(ctx, filter);
    }

    /**
     * Common logic for vanilla list command.
     */
    private static int executeVanillaListCommon(CommandContext<CommandSourceStack> ctx, String filter) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();

        // Discover or filter structures
        java.util.List<VanillaStructureLoader.VanillaStructureInfo> structures;
        if (filter != null && !filter.isEmpty()) {
            structures = VanillaStructureLoader.searchStructures(level, filter);
        } else {
            structures = VanillaStructureLoader.discoverStructures(level);
        }

        if (structures.isEmpty()) {
            if (filter != null) {
                source.sendSuccess(() -> Component.literal(
                    I18n.tr(player, "vanilla.list.no_match", filter)
                ), false);
            } else {
                source.sendSuccess(() -> Component.literal(
                    I18n.tr(player, "vanilla.list.empty")
                ), false);
            }
            return 1;
        }

        // Build list message
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.tr(player, "vanilla.list.header", structures.size()));

        // Show first 20 structures (to avoid chat spam)
        int shown = 0;
        for (VanillaStructureLoader.VanillaStructureInfo info : structures) {
            if (shown >= 20) {
                sb.append("\n... ").append(I18n.tr(player, "vanilla.list.more", structures.size() - 20));
                break;
            }
            sb.append("\n  - ").append(info.templateId().getPath());
            shown++;
        }

        if (filter == null) {
            sb.append("\n").append(I18n.tr(player, "vanilla.list.hint"));
        }

        final String message = sb.toString();
        source.sendSuccess(() -> Component.literal(message), false);

        return 1;
    }

    /**
     * Executes /struttura vanilla batchpush
     * Pushes all vanilla structures to the server.
     */
    private static int executeVanillaBatchPush(CommandContext<CommandSourceStack> ctx) {
        return executeVanillaBatchPushCommon(ctx, null);
    }

    /**
     * Executes /struttura vanilla batchpush <filter>
     * Pushes vanilla structures matching the filter.
     */
    private static int executeVanillaBatchPushFiltered(CommandContext<CommandSourceStack> ctx) {
        String filter = StringArgumentType.getString(ctx, "filter");
        return executeVanillaBatchPushCommon(ctx, filter);
    }

    /**
     * Common logic for vanilla batchpush command.
     * Two-phase approach:
     * 1. Load all constructions into memory (not spawned in world)
     * 2. For each: spawn -> screenshot -> destroy -> async upload -> next
     */
    private static int executeVanillaBatchPushCommon(CommandContext<CommandSourceStack> ctx, String filter) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Player must not be in editing mode
        if (EditingSession.hasSession(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "vanilla.batchpush.exit_editing")));
            return 0;
        }

        // Check if player already has an active batch push
        if (VanillaBatchPushState.hasActiveState(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "vanilla.batchpush.already_running")));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();

        // Discover or filter structures
        java.util.List<VanillaStructureLoader.VanillaStructureInfo> structures;
        if (filter != null && !filter.isEmpty()) {
            structures = VanillaStructureLoader.searchStructures(level, filter);
        } else {
            structures = VanillaStructureLoader.discoverStructures(level);
        }

        if (structures.isEmpty()) {
            if (filter != null) {
                source.sendFailure(Component.literal(I18n.tr(player, "vanilla.list.no_match", filter)));
            } else {
                source.sendFailure(Component.literal(I18n.tr(player, "vanilla.list.empty")));
            }
            return 0;
        }

        // Create state
        VanillaBatchPushState state = VanillaBatchPushState.start(player, level, new java.util.ArrayList<>(structures));
        state.setState(VanillaBatchPushState.State.LOADING_ALL);

        // Phase 1: Load all constructions into memory
        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "vanilla.batchpush.loading", structures.size())
        ), false);

        Architect.LOGGER.info("Player {} starting vanilla batchpush - loading {} structures",
            player.getName().getString(), structures.size());

        loadAllConstructions(state);

        return 1;
    }

    /**
     * Phase 1: Load all constructions into memory without spawning them.
     * After loading, starts phase 2 (processing).
     */
    private static void loadAllConstructions(VanillaBatchPushState state) {
        ServerPlayer player = state.getPlayer();
        ServerLevel level = state.getLevel();
        ConstructionRegistry registry = ConstructionRegistry.getInstance();

        int loadedCount = 0;
        int failedCount = 0;

        for (VanillaStructureLoader.VanillaStructureInfo info : state.getStructures()) {
            String constructionId = info.constructionId();

            // Remove existing if present
            if (registry.exists(constructionId)) {
                registry.unregister(constructionId);
            }

            // Load the structure (skip loot chests)
            VanillaStructureLoader.LoadResult result = VanillaStructureLoader.loadStructure(level, info, true);

            if (!result.success()) {
                Architect.LOGGER.warn("Failed to load vanilla structure {}: {}", info.templateId(), result.message());
                failedCount++;
                continue;
            }

            Construction construction = result.construction();

            // Determine screenshot title
            String screenshotTitle = construction.getTitle("en");
            if (screenshotTitle == null || screenshotTitle.isEmpty()) {
                screenshotTitle = info.templateId().getPath();
            }

            // Store for phase 2 (don't register yet - will register when spawning)
            state.addLoadedConstruction(new VanillaBatchPushState.LoadedConstruction(
                info, construction, screenshotTitle
            ));
            loadedCount++;
        }

        Architect.LOGGER.info("Loaded {} constructions ({} failed)", loadedCount, failedCount);

        if (loadedCount == 0) {
            player.sendSystemMessage(Component.literal(
                I18n.tr(player, "vanilla.batchpush.load_failed")
            ));
            state.fail();
            return;
        }

        // Start phase 2: processing
        player.sendSystemMessage(Component.literal(
            I18n.tr(player, "vanilla.batchpush.starting", loadedCount)
        ));

        state.setState(VanillaBatchPushState.State.PROCESSING);
        state.setCurrentIndex(0);

        // Start processing first construction
        processNextConstruction(state);
    }

    /**
     * Phase 2: Process constructions one by one.
     * Flow: spawn -> request screenshot -> wait for client response
     * (continuation in onVanillaScreenshotReceived)
     */
    private static void processNextConstruction(VanillaBatchPushState state) {
        ServerPlayer player = state.getPlayer();
        ServerLevel level = state.getLevel();

        // Check for async upload errors
        if (state.hasUploadError()) {
            player.sendSystemMessage(Component.literal(
                "§c[Struttura] Upload error: " + state.getUploadErrorMessage()
            ));
            Architect.LOGGER.error("Batch push aborted due to upload error: {}", state.getUploadErrorMessage());
            state.fail();
            return;
        }

        // Check if batch is complete
        if (state.isComplete()) {
            player.sendSystemMessage(Component.literal(
                I18n.tr(player, "vanilla.batchpush.complete", state.getSuccessCount(), state.getFailCount())
            ));
            Architect.LOGGER.info("Vanilla batchpush complete: {} success, {} failed",
                state.getSuccessCount(), state.getFailCount());
            state.complete();
            return;
        }

        VanillaBatchPushState.LoadedConstruction loaded = state.getCurrentLoadedConstruction();
        if (loaded == null) {
            state.fail();
            return;
        }

        String constructionId = loaded.info().constructionId();
        Construction construction = loaded.construction();

        state.setCurrentConstructionId(constructionId);
        state.setCurrentConstruction(construction);

        // Send progress message
        player.sendSystemMessage(Component.literal(
            I18n.tr(player, "vanilla.batchpush.processing",
                state.getCurrentIndex() + 1, state.getLoadedCount(), constructionId)
        ));

        // Spawn the construction in front of the player
        BlockPos spawnPos = spawnConstructionForScreenshot(player, level, construction);
        state.setSpawnPosition(spawnPos);

        // Set state to waiting for screenshot and request it immediately
        // The client will respond when ready (after rendering)
        state.setState(VanillaBatchPushState.State.WAITING_SCREENSHOT);
        NetworkHandler.sendScreenshotRequest(player, constructionId, loaded.screenshotTitle());
        Architect.LOGGER.debug("Requested screenshot for vanilla structure: {}", constructionId);
    }

    /**
     * Spawns a construction in front of the player at a distance optimized for screenshot.
     * Returns the spawn position (min corner).
     */
    private static BlockPos spawnConstructionForScreenshot(ServerPlayer player, ServerLevel level, Construction construction) {
        if (construction.getBlockCount() == 0) {
            return player.blockPosition();
        }

        var bounds = construction.getBounds();
        int sizeX = bounds.getSizeX();
        int sizeY = bounds.getSizeY();
        int sizeZ = bounds.getSizeZ();

        int originalMinX = bounds.getMinX();
        int originalMinY = bounds.getMinY();
        int originalMinZ = bounds.getMinZ();

        // Calculate spawn position in front of player
        float yaw = player.getYRot();
        double radians = Math.toRadians(yaw);
        double dirX = -Math.sin(radians);
        double dirZ = Math.cos(radians);

        // Calculate distance for screenshot - structure should fit in view
        // Use max dimension to ensure structure fits in frame
        int maxSize = Math.max(Math.max(sizeX, sizeY), sizeZ);
        // Distance = half structure size + some margin for good framing
        int distance = (maxSize / 2) + Math.max(5, maxSize / 3);

        int offsetX = (int) Math.round(player.getX() + dirX * distance) - originalMinX - sizeX / 2;
        int offsetY = (int) player.getY() - originalMinY;
        int offsetZ = (int) Math.round(player.getZ() + dirZ * distance) - originalMinZ - sizeZ / 2;

        // Place blocks
        int placementFlags = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE;

        java.util.List<java.util.Map.Entry<BlockPos, BlockState>> sortedBlocks =
            new java.util.ArrayList<>(construction.getBlocks().entrySet());
        sortedBlocks.sort((a, b) -> Integer.compare(a.getKey().getY(), b.getKey().getY()));

        for (java.util.Map.Entry<BlockPos, BlockState> entry : sortedBlocks) {
            BlockPos originalPos = entry.getKey();
            BlockState blockState = entry.getValue();
            BlockPos newPos = originalPos.offset(offsetX, offsetY, offsetZ);
            if (!blockState.isAir()) {
                level.setBlock(newPos, blockState, placementFlags);

                // Apply block entity NBT if present
                net.minecraft.nbt.CompoundTag blockNbt = construction.getBlockEntityNbt(originalPos);
                if (blockNbt != null) {
                    net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(newPos);
                    if (blockEntity != null) {
                        net.minecraft.nbt.CompoundTag nbtCopy = blockNbt.copy();
                        nbtCopy.putInt("x", newPos.getX());
                        nbtCopy.putInt("y", newPos.getY());
                        nbtCopy.putInt("z", newPos.getZ());
                        net.minecraft.world.level.storage.ValueInput input = net.minecraft.world.level.storage.TagValueInput.create(
                            net.minecraft.util.ProblemReporter.DISCARDING,
                            level.registryAccess(),
                            nbtCopy
                        );
                        blockEntity.loadCustomOnly(input);
                        blockEntity.setChanged();
                    }
                }
            }
        }

        // Spawn entities
        for (var entityData : construction.getEntities()) {
            try {
                net.minecraft.world.phys.Vec3 relativePos = entityData.getRelativePos();
                double worldX = relativePos.x + originalMinX + offsetX;
                double worldY = relativePos.y + originalMinY + offsetY;
                double worldZ = relativePos.z + originalMinZ + offsetZ;

                // Prepare NBT for entity creation
                net.minecraft.nbt.CompoundTag nbt = entityData.getNbt();
                if (nbt == null) {
                    nbt = new net.minecraft.nbt.CompoundTag();
                } else {
                    nbt = nbt.copy();
                }

                // Ensure NBT contains the entity type id
                if (!nbt.contains("id")) {
                    nbt.putString("id", entityData.getEntityType());
                }

                // Remove UUID to avoid conflicts
                nbt.remove("UUID");

                // Update block_pos for hanging entities (paintings, item frames, etc.)
                // MC 1.21+ uses "block_pos" (CompoundTag with X, Y, Z)
                if (nbt.contains("block_pos")) {
                    nbt.getCompound("block_pos").ifPresent(blockPos -> {
                        int bpX = blockPos.getIntOr("X", 0);
                        int bpY = blockPos.getIntOr("Y", 0);
                        int bpZ = blockPos.getIntOr("Z", 0);
                        // Add offset to convert from relative to world coordinates
                        blockPos.putInt("X", bpX + offsetX);
                        blockPos.putInt("Y", bpY + offsetY);
                        blockPos.putInt("Z", bpZ + offsetZ);
                    });
                }
                // Fallback for old formats (TileX/Y/Z)
                else if (nbt.contains("TileX") && nbt.contains("TileY") && nbt.contains("TileZ")) {
                    int tileX = nbt.getIntOr("TileX", 0);
                    int tileY = nbt.getIntOr("TileY", 0);
                    int tileZ = nbt.getIntOr("TileZ", 0);
                    nbt.putInt("TileX", tileX + offsetX);
                    nbt.putInt("TileY", tileY + offsetY);
                    nbt.putInt("TileZ", tileZ + offsetZ);
                }

                // Create entity from NBT using loadEntityRecursive
                net.minecraft.world.entity.Entity entity = net.minecraft.world.entity.EntityType.loadEntityRecursive(
                    nbt, level, net.minecraft.world.entity.EntitySpawnReason.LOAD, e -> e);

                if (entity != null) {
                    entity.setPos(worldX, worldY, worldZ);
                    entity.setYRot(entityData.getYaw());
                    entity.setXRot(entityData.getPitch());
                    entity.setUUID(java.util.UUID.randomUUID());
                    level.addFreshEntity(entity);
                }
            } catch (Exception e) {
                Architect.LOGGER.warn("Failed to spawn entity for vanilla structure: {}", e.getMessage());
            }
        }

        return new BlockPos(originalMinX + offsetX, originalMinY + offsetY, originalMinZ + offsetZ);
    }

    /**
     * Called when screenshot is received for vanilla batch push.
     * New flow: destroy immediately -> queue async upload -> continue to next.
     */
    public static void onVanillaScreenshotReceived(VanillaBatchPushState state, byte[] screenshotData) {
        ServerPlayer player = state.getPlayer();
        ServerLevel level = state.getLevel();
        Construction construction = state.getCurrentConstruction();
        String constructionId = state.getCurrentConstructionId();

        // 1. Immediately destroy the spawned construction
        cleanupSpawnedConstruction(state);

        // 2. Queue async upload (building + screenshot) - don't wait for response
        queueAsyncUpload(state, construction, constructionId, screenshotData);

        // 3. Move to next structure immediately
        state.incrementSuccessCount();
        state.nextStructure();

        // 4. Continue with next construction on next tick
        level.getServer().execute(() -> processNextConstruction(state));
    }

    /**
     * Queues the building push and screenshot upload asynchronously.
     * Does not block - errors are tracked in state and checked at next iteration.
     */
    private static void queueAsyncUpload(VanillaBatchPushState state, Construction construction,
                                          String constructionId, byte[] screenshotData) {
        ServerLevel level = state.getLevel();
        ServerPlayer player = state.getPlayer();

        // Run uploads in a separate thread to not block the main thread
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // Push construction (synchronous in this thread)
                java.util.concurrent.CountDownLatch pushLatch = new java.util.concurrent.CountDownLatch(1);
                final boolean[] pushSuccess = {false};
                final String[] pushError = {null};

                boolean pushStarted = ApiClient.pushConstruction(construction, true, response -> {
                    pushSuccess[0] = response.success();
                    if (!response.success()) {
                        pushError[0] = "Push failed: " + response.statusCode() + " - " + response.message();
                    }
                    pushLatch.countDown();
                });

                if (!pushStarted) {
                    // Retry after a short delay
                    Thread.sleep(100);
                    pushStarted = ApiClient.pushConstruction(construction, true, response -> {
                        pushSuccess[0] = response.success();
                        if (!response.success()) {
                            pushError[0] = "Push failed: " + response.statusCode() + " - " + response.message();
                        }
                        pushLatch.countDown();
                    });
                }

                if (!pushStarted) {
                    state.setUploadError("API busy, could not push " + constructionId);
                    return;
                }

                // Wait for push to complete
                pushLatch.await();

                if (!pushSuccess[0]) {
                    state.setUploadError(pushError[0]);
                    return;
                }

                Architect.LOGGER.debug("Async pushed: {}", constructionId);

                // Upload screenshot if available
                if (screenshotData != null && screenshotData.length > 0) {
                    java.util.concurrent.CountDownLatch screenshotLatch = new java.util.concurrent.CountDownLatch(1);
                    final boolean[] screenshotSuccess = {false};
                    final String[] screenshotError = {null};

                    String screenshotTitle = construction.getTitle("en");
                    if (screenshotTitle == null || screenshotTitle.isEmpty()) {
                        screenshotTitle = "Screenshot";
                    }
                    String filename = "screenshot_" + System.currentTimeMillis() + ".jpg";

                    boolean screenshotStarted = ApiClient.uploadScreenshot(
                        constructionId, screenshotData, filename, screenshotTitle, true, response -> {
                            screenshotSuccess[0] = response.success();
                            if (!response.success()) {
                                screenshotError[0] = "Screenshot upload failed: " + response.statusCode() + " - " + response.message();
                            }
                            screenshotLatch.countDown();
                        });

                    if (!screenshotStarted) {
                        // Retry after a short delay
                        Thread.sleep(100);
                        screenshotStarted = ApiClient.uploadScreenshot(
                            constructionId, screenshotData, filename, screenshotTitle, true, response -> {
                                screenshotSuccess[0] = response.success();
                                if (!response.success()) {
                                    screenshotError[0] = "Screenshot upload failed: " + response.statusCode() + " - " + response.message();
                                }
                                screenshotLatch.countDown();
                            });
                    }

                    if (screenshotStarted) {
                        screenshotLatch.await();
                        if (!screenshotSuccess[0]) {
                            // Screenshot failure is not fatal, just log it
                            Architect.LOGGER.warn("Screenshot upload failed for {}: {}", constructionId, screenshotError[0]);
                        } else {
                            Architect.LOGGER.debug("Async uploaded screenshot: {}", constructionId);
                        }
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                state.setUploadError("Upload interrupted for " + constructionId);
            } catch (Exception e) {
                Architect.LOGGER.error("Async upload error for {}", constructionId, e);
                state.setUploadError("Upload error: " + e.getMessage());
            }
        });
    }

    /**
     * Cleans up the spawned construction (removes blocks and entities).
     * Does NOT unregister from registry (constructions are not registered in new flow).
     */
    private static void cleanupSpawnedConstruction(VanillaBatchPushState state) {
        ServerLevel level = state.getLevel();
        Construction construction = state.getCurrentConstruction();
        BlockPos spawnPos = state.getSpawnPosition();

        if (construction == null || spawnPos == null) {
            return;
        }

        var bounds = construction.getBounds();
        int offsetX = spawnPos.getX() - bounds.getMinX();
        int offsetY = spawnPos.getY() - bounds.getMinY();
        int offsetZ = spawnPos.getZ() - bounds.getMinZ();

        // 1. First remove entities BEFORE blocks to prevent item drops
        var aabb = new net.minecraft.world.phys.AABB(
            spawnPos.getX() - 1, spawnPos.getY() - 1, spawnPos.getZ() - 1,
            spawnPos.getX() + bounds.getSizeX() + 1,
            spawnPos.getY() + bounds.getSizeY() + 1,
            spawnPos.getZ() + bounds.getSizeZ() + 1
        );
        java.util.List<net.minecraft.world.entity.Entity> entities = level.getEntities(
            (net.minecraft.world.entity.Entity) null, aabb,
            e -> !(e instanceof net.minecraft.world.entity.player.Player)
        );
        for (net.minecraft.world.entity.Entity entity : entities) {
            entity.discard();
        }

        // 2. Clear container contents to prevent item drops
        for (BlockPos originalPos : construction.getBlocks().keySet()) {
            BlockPos worldPos = originalPos.offset(offsetX, offsetY, offsetZ);
            net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(worldPos);
            if (blockEntity instanceof net.minecraft.world.Clearable clearable) {
                clearable.clearContent();
            }
        }

        // 3. Remove blocks with flags that prevent particles and drops
        int removeFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos originalPos : construction.getBlocks().keySet()) {
            BlockPos worldPos = originalPos.offset(offsetX, offsetY, offsetZ);
            level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), removeFlags);
        }
    }

    /**
     * Called when screenshot fails or times out for vanilla batch push.
     */
    public static void onVanillaScreenshotFailed(VanillaBatchPushState state, String error) {
        ServerPlayer player = state.getPlayer();
        String constructionId = state.getCurrentConstructionId();

        Architect.LOGGER.warn("Screenshot failed for vanilla structure {}: {}", constructionId, error);
        player.sendSystemMessage(Component.literal(
            I18n.tr(player, "vanilla.batchpush.screenshot_failed", constructionId)
        ));

        // Continue without screenshot - still push the structure (with null screenshot data)
        onVanillaScreenshotReceived(state, null);
    }
}
