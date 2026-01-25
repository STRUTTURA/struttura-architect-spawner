package it.magius.struttura.architect.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.i18n.LanguageUtils;
import it.magius.struttura.architect.ingame.InGameManager;
import it.magius.struttura.architect.ingame.InGameState;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import it.magius.struttura.architect.ingame.model.SpawnableList;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ConstructionBounds;
import it.magius.struttura.architect.model.EditMode;
import it.magius.struttura.architect.model.Room;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.placement.ConstructionOperations;
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
                        .executes(ctx -> executeSpawn(ctx, false, null))  // Default: InGameSpawn
                        .then(Commands.argument("architectMode", BoolArgumentType.bool())
                            .executes(ctx -> executeSpawn(ctx, BoolArgumentType.getBool(ctx, "architectMode"), null))
                            .then(Commands.argument("rooms", StringArgumentType.greedyString())
                                .executes(ctx -> executeSpawn(ctx, BoolArgumentType.getBool(ctx, "architectMode"),
                                    StringArgumentType.getString(ctx, "rooms")))
                            )
                        )
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
                // Options command - sends packet to client to open settings screen
                .then(Commands.literal("options")
                    .executes(ctx -> {
                        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
                            NetworkHandler.sendOpenOptions(player);
                        }
                        return 1;
                    })
                )
                // Adventure commands for InGame mode
                .then(Commands.literal("adventure")
                    .then(Commands.literal("init")
                        .executes(StrutturaCommand::executeAdventureInit)
                    )
                    .then(Commands.literal("status")
                        .executes(StrutturaCommand::executeAdventureStatus)
                    )
                    .then(Commands.literal("list")
                        .executes(StrutturaCommand::executeAdventureList)
                    )
                    .then(Commands.literal("force")
                        .then(Commands.argument("rdns", StringArgumentType.string())
                            .suggests(ADVENTURE_BUILDING_SUGGESTIONS)
                            .executes(StrutturaCommand::executeAdventureForce)
                        )
                    )
                    .then(Commands.literal("reset")
                        .executes(StrutturaCommand::executeAdventureReset)
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

        // Use centralized teleport logic
        BlockPos pos = NetworkHandler.teleportToConstruction(player, construction);

        Architect.LOGGER.info("Teleported {} to construction {} at [{}, {}, {}]{}",
            player.getName().getString(), id, pos.getX(), pos.getY(), pos.getZ(),
            construction.getAnchors().hasEntrance() ? " (entrance)" : " facing center");

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

    private static int executeSpawn(CommandContext<CommandSourceStack> ctx, boolean architectMode, String roomsParam) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        String id = StringArgumentType.getString(ctx, "id");

        // Verify construction exists (including those being edited)
        if (!constructionExistsIncludingEditing(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "spawn.not_found", id)));
            return 0;
        }

        Construction construction = getConstructionIncludingEditing(id);
        if (construction == null || construction.getBlockCount() == 0) {
            source.sendFailure(Component.literal(I18n.tr(player, "spawn.empty", id)));
            return 0;
        }

        // Parse forced rooms list (only valid for architectMode)
        java.util.List<String> forcedRoomIds = null;
        if (roomsParam != null && !roomsParam.isEmpty()) {
            if (!architectMode) {
                source.sendFailure(Component.literal(I18n.tr(player, "spawn.rooms_requires_architect")));
                return 0;
            }
            // Parse comma-separated room IDs
            forcedRoomIds = java.util.Arrays.stream(roomsParam.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        }

        if (architectMode) {
            return executeArchitectSpawn(player, construction, source, forcedRoomIds);
        } else {
            return executeInGameSpawn(player, construction, source);
        }
    }

    /**
     * ArchitectSpawn: Spawns construction with random room selection for testing.
     * Used in architect mode to test room variations.
     *
     * @param forcedRoomIds Optional list of room IDs to force spawn (100% probability).
     *                      Rooms still won't spawn if they overlap with each other.
     */
    private static int executeArchitectSpawn(ServerPlayer player, Construction construction,
                                              CommandSourceStack source, java.util.List<String> forcedRoomIds) {
        // Delegate to ConstructionOperations.architectSpawn
        var result = ConstructionOperations.architectSpawn(
            player, construction, player.getYRot(), forcedRoomIds
        );

        if (result.blocksPlaced() > 0) {
            Architect.LOGGER.info("Player {} architect-spawned {} ({} blocks, {} entities, {} rooms)",
                player.getName().getString(), construction.getId(),
                result.blocksPlaced(), result.entitiesSpawned(), result.roomsSpawned());

            source.sendSuccess(() -> Component.literal(
                I18n.tr(player, "spawn.architect_success",
                    construction.getId(), result.blocksPlaced(), result.roomsSpawned())
            ), true);
            return 1;
        } else {
            source.sendFailure(Component.literal(I18n.tr(player, "spawn.failed", construction.getId())));
            return 0;
        }
    }

    /**
     * InGameSpawn: Reserved for future natural world spawning.
     * Currently shows a TODO message.
     */
    private static int executeInGameSpawn(ServerPlayer player, Construction construction, CommandSourceStack source) {
        // For now, fallback to regular spawn behavior
        // In the future, this will be used by the spawning engine with world-specific criteria
        var result = ConstructionOperations.placeConstruction(
            player, construction, ConstructionOperations.PlacementMode.SPAWN, false,
            null, player.getYRot(), false
        );

        if (result.blocksPlaced() > 0) {
            Architect.LOGGER.info("Player {} spawned construction {} ({} blocks, {} entities)",
                player.getName().getString(), construction.getId(), result.blocksPlaced(), result.entitiesSpawned());

            source.sendSuccess(() -> Component.literal(
                I18n.tr(player, "spawn.success_simple", construction.getId(), result.blocksPlaced())
            ), true);
            return 1;
        } else {
            source.sendFailure(Component.literal(I18n.tr(player, "spawn.failed", construction.getId())));
            return 0;
        }
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

        // Use centralized placement function with SHOW mode (original position)
        var result = ConstructionOperations.placeConstruction(
            player, construction, ConstructionOperations.PlacementMode.SHOW, false
        );

        VISIBLE_CONSTRUCTIONS.add(id);

        Architect.LOGGER.info("Player {} showed construction {} ({} blocks, {} entities)",
            player.getName().getString(), id, result.blocksPlaced(), result.entitiesSpawned());

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "show.success", id, result.blocksPlaced())
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

        // Use centralized removal function with HIDE mode
        var result = ConstructionOperations.removeConstruction(
            level, construction, ConstructionOperations.RemovalMode.HIDE, null
        );

        VISIBLE_CONSTRUCTIONS.remove(id);

        Architect.LOGGER.info("Player {} hid construction {} ({} blocks removed)",
            player.getName().getString(), id, result.blocksRemoved());

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "hide.success", id, result.blocksRemoved())
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

        // Refresh entities from world before push to capture any modifications
        ServerLevel level = (ServerLevel) player.level();
        int refreshed = construction.refreshEntitiesFromWorld(level, level.registryAccess());
        if (refreshed > 0) {
            Architect.LOGGER.info("Refreshed {} entities from world before push", refreshed);
        }

        // Messaggio di invio
        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "push.sending", id, construction.getBlockCount())
        ), false);

        Architect.LOGGER.info("Player {} pushing construction {} ({} blocks)",
            player.getName().getString(), id, construction.getBlockCount());

        // Cattura il server per il callback sul main thread
        var server = level.getServer();

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

        String langInput = StringArgumentType.getString(ctx, "lang");
        if (!LanguageUtils.isValid(langInput)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.invalid_lang",
                langInput, String.join(", ", LanguageUtils.getSupportedLanguages()))));
            return 0;
        }

        String lang = LanguageUtils.toBcp47(langInput);
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

        String langInput = StringArgumentType.getString(ctx, "lang");
        if (!LanguageUtils.isValid(langInput)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.invalid_lang",
                langInput, String.join(", ", LanguageUtils.getSupportedLanguages()))));
            return 0;
        }

        String lang = LanguageUtils.toBcp47(langInput);
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

        String langInput = StringArgumentType.getString(ctx, "lang");
        if (!LanguageUtils.isValid(langInput)) {
            source.sendFailure(Component.literal(I18n.tr(player, "command.invalid_lang",
                langInput, String.join(", ", LanguageUtils.getSupportedLanguages()))));
            return 0;
        }

        String lang = LanguageUtils.toBcp47(langInput);
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
     * Esegue /struttura give - gives all Struttura tools to the player.
     */
    private static int executeGive(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        // Create items and add to inventory
        ItemStack hammerStack = new ItemStack(ModItems.CONSTRUCTION_HAMMER);
        // TODO: Re-enable tape when keystone feature is implemented
        // ItemStack tapeStack = new ItemStack(ModItems.MEASURING_TAPE);

        boolean hammerAdded = player.getInventory().add(hammerStack);
        // boolean tapeAdded = player.getInventory().add(tapeStack);

        if (hammerAdded) {
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

        // 1. Use centralized removal function with DESTROY mode
        var result = ConstructionOperations.removeConstruction(
            level, construction, ConstructionOperations.RemovalMode.DESTROY, null
        );
        if (result.blocksRemoved() > 0) {
            Architect.LOGGER.info("Destroy: removed {} blocks for construction {}", result.blocksRemoved(), constructionId);
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

                        // Register the construction in the registry
                        ConstructionRegistry.getInstance().register(construction);

                        // Use centralized placement with PULL mode (updates construction coordinates)
                        var placementResult = ConstructionOperations.placeConstruction(
                            player, construction, ConstructionOperations.PlacementMode.PULL, true,
                            null, player.getYRot(), false
                        );

                        player.sendSystemMessage(Component.literal(
                            I18n.tr(player, "pull.success", id, placementResult.blocksPlaced())
                        ));

                        Architect.LOGGER.info("Pull successful for {}: {} blocks placed",
                            id, placementResult.blocksPlaced());
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

        // 1. Save old bounds BEFORE removing the construction
        ConstructionBounds oldBounds = construction.getBounds().copy();

        // 2. Remove from old position first (use MOVE_CLEAR mode with old bounds)
        ConstructionOperations.removeConstruction(
            level, construction, ConstructionOperations.RemovalMode.MOVE_CLEAR, oldBounds
        );

        // 3. Place at new position in front of player (updates construction coordinates)
        var placementResult = ConstructionOperations.placeConstruction(
            player, construction, ConstructionOperations.PlacementMode.MOVE, true,
            null, player.getYRot(), false
        );

        // 4. Save the updated construction
        ConstructionRegistry.getInstance().register(construction);

        // 5. Update visibility state
        VISIBLE_CONSTRUCTIONS.add(id);

        BlockPos playerPos = player.blockPosition();
        Architect.LOGGER.info("Player {} moved construction {} from old position to ({}, {}, {})",
            player.getName().getString(), id, playerPos.getX(), playerPos.getY(), playerPos.getZ());

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "move.success", id, placementResult.blocksPlaced())
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
     * Uses centralized ConstructionOperations.placeConstructionAt for consistent behavior.
     * Returns the spawn position (min corner).
     */
    private static BlockPos spawnConstructionForScreenshot(ServerPlayer player, ServerLevel level, Construction construction) {
        if (construction.getBlockCount() == 0) {
            return player.blockPosition();
        }

        // Calculate screenshot-optimized position
        BlockPos targetPos = calculateScreenshotPosition(player, construction.getBounds());

        // Use centralized placement (no coordinate update needed for temporary screenshot spawn)
        var result = ConstructionOperations.placeConstructionAt(level, construction, targetPos, false);

        return result.newOrigin();
    }

    /**
     * Calculates a position optimized for screenshot framing.
     * The construction is placed at a distance that allows it to fit in the camera view.
     */
    private static BlockPos calculateScreenshotPosition(ServerPlayer player, ConstructionBounds bounds) {
        int sizeX = bounds.getSizeX();
        int sizeY = bounds.getSizeY();
        int sizeZ = bounds.getSizeZ();

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

        int targetX = (int) Math.round(player.getX() + dirX * distance) - sizeX / 2;
        int targetY = (int) player.getY();
        int targetZ = (int) Math.round(player.getZ() + dirZ * distance) - sizeZ / 2;

        return new BlockPos(targetX, targetY, targetZ);
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
     * Uses centralized ConstructionOperations.removeConstruction for consistent behavior.
     * Does NOT unregister from registry (constructions are not registered in new flow).
     */
    private static void cleanupSpawnedConstruction(VanillaBatchPushState state) {
        ServerLevel level = state.getLevel();
        Construction construction = state.getCurrentConstruction();
        BlockPos spawnPos = state.getSpawnPosition();

        if (construction == null || spawnPos == null) {
            return;
        }

        // Create bounds at the spawn position
        var originalBounds = construction.getBounds();
        ConstructionBounds spawnedBounds = new ConstructionBounds(
            spawnPos.getX(),
            spawnPos.getY(),
            spawnPos.getZ(),
            spawnPos.getX() + originalBounds.getSizeX() - 1,
            spawnPos.getY() + originalBounds.getSizeY() - 1,
            spawnPos.getZ() + originalBounds.getSizeZ() - 1
        );

        // Use centralized removal with DESTROY mode (removes all entities except players)
        ConstructionOperations.removeConstruction(
            level, construction, ConstructionOperations.RemovalMode.DESTROY, spawnedBounds
        );
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

    // ===== Adventure Commands =====

    // Suggestion provider for buildings in the active spawnable list
    private static final SuggestionProvider<CommandSourceStack> ADVENTURE_BUILDING_SUGGESTIONS =
        (context, builder) -> {
            java.util.Set<String> suggestions = new java.util.LinkedHashSet<>();
            InGameManager manager = InGameManager.getInstance();
            SpawnableList list = manager.getSpawnableList();
            if (list != null) {
                for (SpawnableBuilding building : list.getBuildings()) {
                    suggestions.add(building.getRdns());
                }
            }
            return SharedSuggestionProvider.suggest(suggestions, builder);
        };

    /**
     * /struttura adventure init - (Re)initialize InGame mode
     */
    private static int executeAdventureInit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        // Verify it's a player
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        InGameManager manager = InGameManager.getInstance();

        if (!manager.isReady()) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.not_ready")));
            return 0;
        }

        // Check if the list is locked to this world - cannot change list
        if (manager.isListLocked()) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.list_locked",
                manager.getState().getListName())));
            return 0;
        }

        // Check if already initialized with a list
        if (manager.isActive()) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.already_initialized",
                manager.getState().getListName())));
            return 0;
        }

        // If declined, reset and allow re-initialization
        if (manager.isInitialized() && manager.getState().isDeclined()) {
            manager.reset();
        }

        // Send setup screen to player with retry (forces re-fetch if there was a connection error)
        manager.sendSetupScreenWithRetry(player);

        return 1;
    }

    /**
     * /struttura adventure status - Show current InGame state
     */
    private static int executeAdventureStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        // Verify it's a player
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        InGameManager manager = InGameManager.getInstance();

        if (!manager.isReady()) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.not_ready")));
            return 0;
        }

        InGameState state = manager.getState();

        if (!state.isInitialized()) {
            source.sendSuccess(() -> Component.literal(I18n.tr(player, "adventure.status.not_initialized")), false);
            return 1;
        }

        if (state.isDeclined()) {
            source.sendSuccess(() -> Component.literal(I18n.tr(player, "adventure.status.declined")), false);
            return 1;
        }

        // Active state - show details
        String listName = state.getListName() != null ? state.getListName() : "Unknown";
        String listId = state.getListId();
        InGameState.AuthType authType = state.getAuthType();

        SpawnableList spawnableList = manager.getSpawnableList();
        int buildingCount = spawnableList != null ? spawnableList.getBuildingCount() : 0;
        double spawnPercentage = spawnableList != null ? spawnableList.getSpawningPercentage() * 100 : 0;

        source.sendSuccess(() -> Component.literal(I18n.tr(player, "adventure.status.active")), false);
        source.sendSuccess(() -> Component.literal(I18n.tr(player, "adventure.status.list", listName, listId)), false);
        source.sendSuccess(() -> Component.literal(I18n.tr(player, "adventure.status.auth", authType != null ? authType.name() : "N/A")), false);
        source.sendSuccess(() -> Component.literal(I18n.tr(player, "adventure.status.buildings", buildingCount)), false);
        source.sendSuccess(() -> Component.literal(I18n.tr(player, "adventure.status.spawn_rate", String.format("%.1f", spawnPercentage))), false);

        return 1;
    }

    /**
     * /struttura adventure list - List buildings in the active list
     */
    private static int executeAdventureList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        // Verify it's a player
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        InGameManager manager = InGameManager.getInstance();

        if (!manager.isActive()) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.not_active")));
            return 0;
        }

        SpawnableList list = manager.getSpawnableList();
        if (list == null || !list.hasBuildings()) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.list.empty")));
            return 0;
        }

        source.sendSuccess(() -> Component.literal(I18n.tr(player, "adventure.list.header", list.getBuildingCount())), false);

        for (SpawnableBuilding building : list.getBuildings()) {
            String limit = building.getXWorld() == 0 ? "∞" : String.valueOf(building.getXWorld());
            source.sendSuccess(() -> Component.literal(
                I18n.tr(player, "adventure.list.entry",
                    building.getRdns(),
                    building.getSizeX() + "x" + building.getSizeY() + "x" + building.getSizeZ(),
                    building.getSpawnedCount() + "/" + limit)
            ), false);
        }

        return 1;
    }

    /**
     * /struttura adventure force <rdns> - Force spawn a building in player's chunk
     * Uses normal spawn evaluation to find a valid position.
     */
    private static int executeAdventureForce(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        // Verify it's a player
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        InGameManager manager = InGameManager.getInstance();

        if (!manager.isActive()) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.not_active")));
            return 0;
        }

        if (!manager.isSpawnerReady()) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.not_ready")));
            return 0;
        }

        SpawnableList list = manager.getSpawnableList();
        if (list == null) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.list.empty")));
            return 0;
        }

        String rdns = StringArgumentType.getString(ctx, "rdns");
        SpawnableBuilding building = list.getBuildingByRdns(rdns);

        if (building == null) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.force.not_found", rdns)));
            return 0;
        }

        // Get player's current chunk
        ServerLevel level = (ServerLevel) player.level();
        net.minecraft.world.level.ChunkPos chunkPos = new net.minecraft.world.level.ChunkPos(player.blockPosition());
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);

        if (chunk == null) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.force.chunk_not_loaded")));
            return 0;
        }

        // Get biome at player position
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biomeHolder = level.getBiome(player.blockPosition());
        String biomeId = biomeHolder.unwrapKey()
            .map(net.minecraft.resources.ResourceKey::toString)
            .orElse("unknown");

        // Find applicable spawn rule for this biome
        SpawnRule rule = building.findRuleForBiome(biomeId);
        if (rule == null) {
            if (building.getRules().isEmpty()) {
                rule = SpawnRule.createDefault();
            } else {
                source.sendFailure(Component.literal(I18n.tr(player, "adventure.force.no_rule", biomeId)));
                return 0;
            }
        }

        // Use PositionValidator to find a valid spawn position with detailed failure info
        it.magius.struttura.architect.ingame.spawn.PositionValidator validator =
            it.magius.struttura.architect.ingame.spawn.PositionValidator.forType(rule.getType());

        java.util.Random random = new java.util.Random();

        // For force command, use the detailed version that reports failures
        it.magius.struttura.architect.ingame.spawn.PositionValidator.FindResult findResult =
            validator.findPositionWithDetails(level, chunkPos, building, rule, random);

        if (findResult.position() == null) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.force.no_position", rdns)));
            // Show detailed failure reasons
            for (String reason : findResult.failureReasons()) {
                source.sendFailure(Component.literal("  " + reason));
            }
            return 0;
        }

        java.util.Optional<it.magius.struttura.architect.ingame.spawn.SpawnPosition> positionOpt =
            java.util.Optional.of(findResult.position());

        it.magius.struttura.architect.ingame.spawn.SpawnPosition position = positionOpt.get();

        // Spawn the building
        it.magius.struttura.architect.ingame.spawn.InGameBuildingSpawner.spawn(level, chunk, building, position);

        source.sendSuccess(() -> Component.literal(I18n.tr(player, "adventure.force.success",
            rdns, position.blockPos().toShortString())), false);

        return 1;
    }

    /**
     * /struttura adventure reset - Reset InGame state
     */
    private static int executeAdventureReset(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        // Verify it's a player
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(I18n.tr("command.player_only")));
            return 0;
        }

        InGameManager manager = InGameManager.getInstance();

        if (!manager.isReady()) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.not_ready")));
            return 0;
        }

        if (!manager.isInitialized()) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.status.not_initialized")));
            return 0;
        }

        // Check if the list is locked to this world - cannot reset
        if (manager.isListLocked()) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.list_locked",
                manager.getState().getListName())));
            return 0;
        }

        boolean success = manager.reset();
        if (!success) {
            source.sendFailure(Component.literal(I18n.tr(player, "adventure.reset.failed")));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(I18n.tr(player, "adventure.reset.success")), false);

        return 1;
    }
}
