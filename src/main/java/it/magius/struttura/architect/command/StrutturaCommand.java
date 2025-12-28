package it.magius.struttura.architect.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.registry.ConstructionRegistry;
import it.magius.struttura.architect.selection.SelectionManager;
import it.magius.struttura.architect.session.EditingSession;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Comando principale /struttura con tutti i subcomandi.
 */
public class StrutturaCommand {

    // Mappa per memorizzare i blocchi originali del mondo quando si usa "show"
    // Chiave: ID costruzione, Valore: mappa posizione -> stato blocco originale
    private static final Map<String, Map<BlockPos, BlockState>> ORIGINAL_WORLD_BLOCKS = new HashMap<>();

    // Set delle costruzioni attualmente visibili nel mondo
    private static final java.util.Set<String> VISIBLE_CONSTRUCTIONS = new java.util.HashSet<>();

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
                .then(Commands.literal("enter")
                    .then(Commands.argument("id", StringArgumentType.string())
                        .suggests(CONSTRUCTION_ID_SUGGESTIONS)
                        .executes(StrutturaCommand::executeEnter)
                    )
                )
                .then(Commands.literal("exit")
                    .executes(StrutturaCommand::executeExit)
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
                    .then(Commands.literal("add")
                        .executes(StrutturaCommand::executeSelectAdd)
                    )
                    .then(Commands.literal("clear")
                        .executes(StrutturaCommand::executeSelectClear)
                    )
                    .then(Commands.literal("info")
                        .executes(StrutturaCommand::executeSelectInfo)
                    )
                )
        );
    }

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

    private static int executeEnter(CommandContext<CommandSourceStack> ctx) {
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
                I18n.tr(player, "enter.already_editing", session.getConstruction().getId())
            ));
            return 0;
        }

        String id = StringArgumentType.getString(ctx, "id");

        // Valida formato ID
        if (!Construction.isValidId(id)) {
            source.sendFailure(Component.literal(I18n.tr(player, "enter.invalid_id", id)));
            return 0;
        }

        // Verifica che un altro giocatore non stia gia' modificando questa costruzione
        EditingSession existingSession = getSessionForConstruction(id);
        if (existingSession != null) {
            String otherPlayerName = existingSession.getPlayer().getName().getString();
            source.sendFailure(Component.literal(
                I18n.tr(player, "enter.already_in_use", id, otherPlayerName)
            ));
            return 0;
        }

        // Verifica se esiste gia' una costruzione con questo ID nel registro
        Construction construction;
        ConstructionRegistry registry = ConstructionRegistry.getInstance();

        if (registry.exists(id)) {
            // Carica la costruzione esistente
            construction = registry.get(id);
            Architect.LOGGER.info("Player {} re-entered existing construction: {}", player.getName().getString(), id);
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

        source.sendSuccess(() -> Component.literal(I18n.tr(player, "enter.success", id)), false);

        return 1;
    }

    private static int executeExit(CommandContext<CommandSourceStack> ctx) {
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

        // Registra la costruzione nel registro (solo se ha blocchi)
        if (construction.getBlockCount() > 0) {
            ConstructionRegistry.getInstance().register(construction);
            Architect.LOGGER.info("Construction {} registered with {} blocks",
                construction.getId(), construction.getBlockCount());
        }

        Architect.LOGGER.info("Player {} exited construction: {} ({} blocks)",
            player.getName().getString(),
            construction.getId(),
            construction.getBlockCount()
        );

        // Pulisci anche la selezione
        SelectionManager.getInstance().clearSelection(player);

        // Invia sync wireframe vuoto al client
        NetworkHandler.sendEmptyWireframe(player);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "exit.success", construction.getId(), construction.getBlockCount())
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

        // Ottieni dimensioni della costruzione
        var bounds = construction.getBounds();
        int sizeX = bounds.getSizeX();
        int sizeZ = bounds.getSizeZ();

        // Ottieni la direzione in cui il player sta guardando (solo asse orizzontale)
        float yaw = player.getYRot();
        // Normalizza yaw a 0-360
        yaw = ((yaw % 360) + 360) % 360;

        // Determina la direzione cardinale principale
        // yaw: 0 = sud (+Z), 90 = ovest (-X), 180 = nord (-Z), 270 = est (+X)
        int offsetX, offsetZ;
        BlockPos playerPos = player.blockPosition();

        if (yaw >= 315 || yaw < 45) {
            // Sud (+Z): costruzione davanti al player, player sul lato -Z
            offsetX = playerPos.getX() - bounds.getMinX() - (sizeX / 2); // Centra su X
            offsetZ = playerPos.getZ() + 1 - bounds.getMinZ(); // Davanti al player
        } else if (yaw >= 45 && yaw < 135) {
            // Ovest (-X): costruzione davanti al player, player sul lato +X
            offsetX = playerPos.getX() - 1 - bounds.getMaxX(); // Davanti al player (verso -X)
            offsetZ = playerPos.getZ() - bounds.getMinZ() - (sizeZ / 2); // Centra su Z
        } else if (yaw >= 135 && yaw < 225) {
            // Nord (-Z): costruzione davanti al player, player sul lato +Z
            offsetX = playerPos.getX() - bounds.getMinX() - (sizeX / 2); // Centra su X
            offsetZ = playerPos.getZ() - 1 - bounds.getMaxZ(); // Davanti al player (verso -Z)
        } else {
            // Est (+X): costruzione davanti al player, player sul lato -X
            offsetX = playerPos.getX() + 1 - bounds.getMinX(); // Davanti al player (verso +X)
            offsetZ = playerPos.getZ() - bounds.getMinZ() - (sizeZ / 2); // Centra su Z
        }

        // Offset Y: la costruzione parte dal livello del player
        int offsetY = playerPos.getY() - bounds.getMinY();

        ServerLevel level = (ServerLevel) player.level();
        int placedCount = 0;

        // Piazza i blocchi nel mondo con l'offset
        for (Map.Entry<BlockPos, BlockState> entry : construction.getBlocks().entrySet()) {
            BlockPos originalPos = entry.getKey();
            BlockState state = entry.getValue();

            // Salta i blocchi aria
            if (state.isAir()) continue;

            BlockPos newPos = originalPos.offset(offsetX, offsetY, offsetZ);
            level.setBlock(newPos, state, 3); // 3 = notify neighbors + update
            placedCount++;
        }

        // Calcola la posizione effettiva dove è stata spawnata (per il messaggio)
        BlockPos spawnMin = bounds.getMin().offset(offsetX, offsetY, offsetZ);

        final int finalCount = placedCount;
        Architect.LOGGER.info("Player {} spawned construction {} at {} ({} blocks)",
            player.getName().getString(), id, spawnMin, finalCount);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "spawn.success", id, finalCount, spawnMin.getX(), spawnMin.getY(), spawnMin.getZ())
        ), true);

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

        // Salva i blocchi originali del mondo
        Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
        for (BlockPos pos : construction.getBlocks().keySet()) {
            originalBlocks.put(pos.immutable(), level.getBlockState(pos));
        }
        ORIGINAL_WORLD_BLOCKS.put(id, originalBlocks);

        // Piazza i blocchi della costruzione nel mondo
        int placedCount = 0;
        for (Map.Entry<BlockPos, BlockState> entry : construction.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            level.setBlock(pos, state, 3);
            placedCount++;
        }

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

        // Se abbiamo i blocchi originali salvati (da un show precedente), usali
        // Altrimenti, semplicemente rimuovi i blocchi della costruzione mettendo aria
        Map<BlockPos, BlockState> originalBlocks = ORIGINAL_WORLD_BLOCKS.get(id);

        int restoredCount = 0;
        for (Map.Entry<BlockPos, BlockState> entry : construction.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();

            if (originalBlocks != null && originalBlocks.containsKey(pos)) {
                // Ripristina il blocco originale salvato
                level.setBlock(pos, originalBlocks.get(pos), 3);
            } else {
                // Nessun blocco originale salvato, metti aria
                level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            }
            restoredCount++;
        }

        // Pulisci i dati
        ORIGINAL_WORLD_BLOCKS.remove(id);
        VISIBLE_CONSTRUCTIONS.remove(id);

        final int finalCount = restoredCount;
        Architect.LOGGER.info("Player {} hid construction {} ({} blocks cleared)",
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

    private static int executeSelectAdd(CommandContext<CommandSourceStack> ctx) {
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

        // Verifica che la selezione sia completa
        if (!SelectionManager.getInstance().hasCompleteSelection(player)) {
            source.sendFailure(Component.literal(I18n.tr(player, "select.incomplete")));
            return 0;
        }

        SelectionManager.Selection selection = SelectionManager.getInstance().getSelection(player);
        BlockPos min = selection.getMin();
        BlockPos max = selection.getMax();

        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();
        ServerLevel level = (ServerLevel) player.level();

        int addedCount = 0;
        int skippedAir = 0;

        // Itera su tutti i blocchi nell'area selezionata
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    // Salta i blocchi aria (opzionale - potremmo volerli includere)
                    if (state.isAir()) {
                        skippedAir++;
                        continue;
                    }

                    // Aggiungi il blocco alla costruzione
                    construction.addBlock(pos, state);
                    addedCount++;
                }
            }
        }

        // Pulisci la selezione dopo l'aggiunta
        SelectionManager.getInstance().clearSelection(player);

        // Invia sync wireframe (bounds costruzione aggiornati, selezione pulita)
        NetworkHandler.sendWireframeSync(player);

        final int finalAdded = addedCount;
        final int finalSkipped = skippedAir;
        final int totalBlocks = construction.getBlockCount();

        Architect.LOGGER.info("Player {} added {} blocks from selection to construction {} (skipped {} air blocks)",
            player.getName().getString(), finalAdded, construction.getId(), finalSkipped);

        source.sendSuccess(() -> Component.literal(
            I18n.tr(player, "select.add.success", finalAdded, finalSkipped, totalBlocks)
        ), false);

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
}
