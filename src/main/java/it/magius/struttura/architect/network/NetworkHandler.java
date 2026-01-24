package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ConstructionBounds;
import it.magius.struttura.architect.model.EditMode;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.model.ModInfo;
import it.magius.struttura.architect.model.Room;
import it.magius.struttura.architect.placement.ConstructionOperations;
import it.magius.struttura.architect.registry.ConstructionRegistry;
import it.magius.struttura.architect.registry.ModItems;
import it.magius.struttura.architect.selection.SelectionManager;
import it.magius.struttura.architect.session.EditingSession;
import it.magius.struttura.architect.vanilla.VanillaBatchPushState;
import it.magius.struttura.architect.command.StrutturaCommand;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.UUID;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gestisce la registrazione e l'invio dei packet di rete.
 */
public class NetworkHandler {

    // Set delle costruzioni attualmente visibili nel mondo
    private static final Set<String> VISIBLE_CONSTRUCTIONS = new HashSet<>();

    /**
     * Registra i payload types lato server.
     */
    public static void registerServer() {
        // Registra il packet per la sincronizzazione wireframe (S2C = Server to Client)
        PayloadTypeRegistry.playS2C().register(WireframeSyncPacket.TYPE, WireframeSyncPacket.STREAM_CODEC);
        // Registra il packet per le posizioni dei blocchi (S2C)
        PayloadTypeRegistry.playS2C().register(BlockPositionsSyncPacket.TYPE, BlockPositionsSyncPacket.STREAM_CODEC);
        // Registra il packet per richiedere uno screenshot (S2C)
        PayloadTypeRegistry.playS2C().register(ScreenshotRequestPacket.TYPE, ScreenshotRequestPacket.STREAM_CODEC);
        // Registra il packet per ricevere i dati dello screenshot (C2S)
        PayloadTypeRegistry.playC2S().register(ScreenshotDataPacket.TYPE, ScreenshotDataPacket.STREAM_CODEC);
        // Registra il packet per le azioni di selezione via keybinding (C2S)
        PayloadTypeRegistry.playC2S().register(SelectionKeyPacket.TYPE, SelectionKeyPacket.STREAM_CODEC);
        // Registra i nuovi packet GUI
        PayloadTypeRegistry.playC2S().register(GuiActionPacket.TYPE, GuiActionPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(EditingInfoPacket.TYPE, EditingInfoPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ConstructionListPacket.TYPE, ConstructionListPacket.STREAM_CODEC);
        // Registra il packet per i mod richiesti (S2C)
        PayloadTypeRegistry.playS2C().register(ModRequirementsPacket.TYPE, ModRequirementsPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(BlockListPacket.TYPE, BlockListPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(TranslationsPacket.TYPE, TranslationsPacket.STREAM_CODEC);
        // InGame building packets
        PayloadTypeRegistry.playS2C().register(InGameBuildingPacket.TYPE, InGameBuildingPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(InGameLikePacket.TYPE, InGameLikePacket.STREAM_CODEC);
        // InGame setup packets
        PayloadTypeRegistry.playS2C().register(InGameListsPacket.TYPE, InGameListsPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(InGameSelectPacket.TYPE, InGameSelectPacket.STREAM_CODEC);
        // Open options screen packet (for /struttura options command)
        PayloadTypeRegistry.playS2C().register(OpenOptionsPacket.TYPE, OpenOptionsPacket.STREAM_CODEC);

        // Registra il receiver per i dati dello screenshot
        ServerPlayNetworking.registerGlobalReceiver(ScreenshotDataPacket.TYPE, NetworkHandler::handleScreenshotData);
        // Registra il receiver per le azioni di selezione via keybinding
        ServerPlayNetworking.registerGlobalReceiver(SelectionKeyPacket.TYPE, NetworkHandler::handleSelectionKey);
        // Registra il receiver per le azioni GUI
        ServerPlayNetworking.registerGlobalReceiver(GuiActionPacket.TYPE, NetworkHandler::handleGuiAction);
        // Registra il receiver per i like InGame
        ServerPlayNetworking.registerGlobalReceiver(InGameLikePacket.TYPE, NetworkHandler::handleInGameLike);
        // Registra il receiver per la selezione lista InGame
        ServerPlayNetworking.registerGlobalReceiver(InGameSelectPacket.TYPE, NetworkHandler::handleInGameSelect);

        Architect.LOGGER.info("Registered network packets");
    }

    /**
     * Sends a packet to the client to open the options/settings screen.
     * Called when the player executes /struttura options.
     */
    public static void sendOpenOptions(ServerPlayer player) {
        ServerPlayNetworking.send(player, new OpenOptionsPacket());
        Architect.LOGGER.debug("Sent open options packet to {}", player.getName().getString());
    }

    /**
     * Invia i dati del wireframe al client.
     * Chiamato quando cambia lo stato di editing o selezione.
     */
    public static void sendWireframeSync(ServerPlayer player) {
        EditingSession session = EditingSession.getSession(player);
        SelectionManager.Selection selection = SelectionManager.getInstance().getSelection(player);

        WireframeSyncPacket packet = buildPacket(session, selection);
        ServerPlayNetworking.send(player, packet);

        // Invia anche le posizioni dei blocchi per l'overlay
        sendBlockPositions(player);

        Architect.LOGGER.debug("Sent wireframe sync to {}: construction={}, selection={}",
            player.getName().getString(),
            packet.constructionActive(),
            packet.selectionActive());
    }

    /**
     * Costruisce il packet dai dati correnti.
     */
    private static WireframeSyncPacket buildPacket(EditingSession session, SelectionManager.Selection selection) {
        // Dati costruzione
        boolean constructionActive = false;
        BlockPos constructionMin = BlockPos.ZERO;
        BlockPos constructionMax = BlockPos.ZERO;
        String constructionId = "";

        if (session != null) {
            ConstructionBounds bounds = session.getConstruction().getBounds();
            if (bounds.isValid()) {
                constructionActive = true;
                constructionMin = bounds.getMin();
                constructionMax = bounds.getMax();
                constructionId = session.getConstruction().getId();
            }
        }

        // Dati selezione
        boolean selectionActive = selection != null;
        BlockPos selectionPos1 = BlockPos.ZERO;
        BlockPos selectionPos2 = BlockPos.ZERO;
        boolean hasPos1 = false;
        boolean hasPos2 = false;

        if (selection != null) {
            hasPos1 = selection.hasPos1();
            hasPos2 = selection.hasPos2();
            if (hasPos1) selectionPos1 = selection.getPos1();
            if (hasPos2) selectionPos2 = selection.getPos2();
        }

        return new WireframeSyncPacket(
            constructionActive, constructionMin, constructionMax, constructionId,
            selectionActive, selectionPos1, selectionPos2, hasPos1, hasPos2
        );
    }

    /**
     * Invia un packet vuoto (nessun wireframe).
     */
    public static void sendEmptyWireframe(ServerPlayer player) {
        ServerPlayNetworking.send(player, WireframeSyncPacket.empty());
        ServerPlayNetworking.send(player, BlockPositionsSyncPacket.empty());
        Architect.LOGGER.debug("Sent empty wireframe sync to {}", player.getName().getString());
    }

    /**
     * Invia le posizioni dei blocchi della costruzione in editing.
     * Include anche i blocchi di anteprima (quelli nella selezione che verranno aggiunti)
     * e i blocchi della room corrente (se in editing room).
     */
    public static void sendBlockPositions(ServerPlayer player) {
        EditingSession session = EditingSession.getSession(player);

        if (session == null) {
            ServerPlayNetworking.send(player, BlockPositionsSyncPacket.empty());
            return;
        }

        Construction construction = session.getConstruction();
        Map<BlockPos, BlockState> blocks = construction.getBlocks();

        List<BlockPos> solidBlocks = new ArrayList<>();
        List<BlockPos> airBlocks = new ArrayList<>();

        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            if (entry.getValue().isAir()) {
                airBlocks.add(entry.getKey());
            } else {
                solidBlocks.add(entry.getKey());
            }
        }

        // Calcola i blocchi di anteprima (nella selezione ma non nel target - costruzione o room)
        // La stessa funzione gestisce sia building che room editing in modo centralizzato
        List<BlockPos> previewBlocks = calculatePreviewBlocks(player, session);

        // Calcola i blocchi della room corrente (se in editing room)
        List<BlockPos> roomBlocks = new ArrayList<>();
        if (session.isInRoom()) {
            Room room = session.getCurrentRoomObject();
            if (room != null) {
                roomBlocks.addAll(room.getBlockChanges().keySet());
            }
        }

        BlockPositionsSyncPacket packet = new BlockPositionsSyncPacket(solidBlocks, airBlocks, previewBlocks, roomBlocks);
        ServerPlayNetworking.send(player, packet);

        Architect.LOGGER.debug("Sent block positions to {}: {} solid, {} air, {} preview, {} room",
            player.getName().getString(), solidBlocks.size(), airBlocks.size(), previewBlocks.size(), roomBlocks.size());
    }

    /**
     * Calcola i blocchi che verranno modificati con select apply.
     * Gestisce sia building editing che room editing in modo centralizzato.
     *
     * In mode ADD: blocchi nell'area che NON sono già nel target (verranno aggiunti).
     *              Include tutti i blocchi (anche aria) perché l'esclusione dell'aria avviene
     *              solo al momento dell'APPLY (con o senza includeAir flag).
     * In mode REMOVE: blocchi nell'area che SONO nel target (verranno rimossi).
     *
     * @param player il giocatore
     * @param session la sessione di editing (contiene construction e eventuale room)
     */
    private static List<BlockPos> calculatePreviewBlocks(ServerPlayer player, EditingSession session) {
        SelectionManager.Selection selection = SelectionManager.getInstance().getSelection(player);

        // Se non c'è selezione completa, nessun blocco di anteprima
        if (selection == null || !selection.isComplete()) {
            return List.of();
        }

        EditMode mode = session.getMode();
        boolean inRoom = session.isInRoom();
        Room room = inRoom ? session.getCurrentRoomObject() : null;
        Construction construction = session.getConstruction();

        BlockPos min = selection.getMin();
        BlockPos max = selection.getMax();

        List<BlockPos> previewBlocks = new ArrayList<>();

        // Itera su tutti i blocchi nell'area selezionata
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);

                    if (mode == EditMode.ADD) {
                        // Mode ADD: mostra TUTTI i blocchi che verranno aggiunti
                        // Salta solo se già nel target (room o construction)
                        // L'aria viene mostrata perché l'utente può scegliere APPLY ALL per includerla
                        if (inRoom && room != null) {
                            // In room editing: controlla se è già nella room
                            if (room.hasBlockChange(pos)) {
                                continue;
                            }
                        } else {
                            // In building editing: controlla se è già nella construction
                            if (construction.containsBlock(pos)) {
                                continue;
                            }
                        }
                        previewBlocks.add(pos);
                    } else {
                        // Mode REMOVE: mostra blocchi che verranno rimossi
                        // Mostra solo se è nel target (room o construction)
                        if (inRoom && room != null) {
                            // In room editing: controlla se è nella room
                            if (room.hasBlockChange(pos)) {
                                previewBlocks.add(pos);
                            }
                        } else {
                            // In building editing: controlla se è nella construction
                            if (construction.containsBlock(pos)) {
                                previewBlocks.add(pos);
                            }
                        }
                    }
                }
            }
        }

        return previewBlocks;
    }

    /**
     * Invia una richiesta di screenshot al client.
     */
    public static void sendScreenshotRequest(ServerPlayer player, String constructionId, String title) {
        ScreenshotRequestPacket packet = new ScreenshotRequestPacket(constructionId, title);
        ServerPlayNetworking.send(player, packet);
        Architect.LOGGER.debug("Sent screenshot request to {} for construction {}",
            player.getName().getString(), constructionId);
    }

    /**
     * Gestisce i dati dello screenshot ricevuti dal client.
     */
    private static void handleScreenshotData(ScreenshotDataPacket packet, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        String constructionId = packet.constructionId();
        String title = packet.title();
        byte[] imageData = packet.imageData();

        Architect.LOGGER.info("Received screenshot data from {} for {}: {} bytes",
            player.getName().getString(), constructionId, imageData.length);

        // Check if this is part of a vanilla batch push
        VanillaBatchPushState batchState = VanillaBatchPushState.getState(player);
        if (batchState != null && batchState.getState() == VanillaBatchPushState.State.WAITING_SCREENSHOT) {
            // This screenshot is for the vanilla batch push - delegate to StrutturaCommand
            Architect.LOGGER.debug("Screenshot is for vanilla batch push, delegating to batch handler");
            StrutturaCommand.onVanillaScreenshotReceived(batchState, imageData);
            return;
        }

        // Normal screenshot flow (not part of batch push)
        // Genera un nome file unico basato sul timestamp
        String filename = "screenshot_" + System.currentTimeMillis() + ".jpg";

        // Messaggio di caricamento
        player.sendSystemMessage(Component.literal(
            I18n.tr(player, "shot.sending", constructionId)
        ));

        // Cattura il server per il callback sul main thread
        var server = ((ServerLevel) player.level()).getServer();

        // Upload asincrono al server API
        boolean started = ApiClient.uploadScreenshot(constructionId, imageData, filename, title, response -> {
            // Callback eseguito su thread async, schedula sul main thread
            server.execute(() -> {
                if (response.success()) {
                    player.sendSystemMessage(Component.literal(
                        I18n.tr(player, "shot.success", constructionId, response.message())
                    ));
                    Architect.LOGGER.info("Screenshot upload successful for {}: {} - {}",
                        constructionId, response.statusCode(), response.message());
                } else {
                    player.sendSystemMessage(Component.literal(
                        I18n.tr(player, "shot.failed", constructionId, response.message())
                    ));
                    Architect.LOGGER.warn("Screenshot upload failed for {}: {} - {}",
                        constructionId, response.statusCode(), response.message());
                }
            });
        });

        if (!started) {
            player.sendSystemMessage(Component.literal(
                I18n.tr(player, "push.request_in_progress")
            ));
        }
    }

    /**
     * Gestisce le azioni di selezione ricevute dal client (via keybinding).
     */
    private static void handleSelectionKey(SelectionKeyPacket packet, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        SelectionKeyPacket.Action action = packet.action();

        // Controlla se è in editing
        EditingSession session = EditingSession.getSession(player.getUUID());
        if (session == null) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "error.not_in_editing")));
            return;
        }

        switch (action) {
            case POS1 -> handlePos1(player);
            case POS2 -> handlePos2(player);
            case CLEAR -> handleClear(player);
            case APPLY -> handleApply(player, session, false);
            case APPLYALL -> handleApply(player, session, true);
            case MODE_TOGGLE -> handleModeToggle(player, session);
        }
    }

    private static void handleModeToggle(ServerPlayer player, EditingSession session) {
        EditMode currentMode = session.getMode();
        EditMode newMode = currentMode == EditMode.ADD ? EditMode.REMOVE : EditMode.ADD;
        session.setMode(newMode);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "mode.changed", newMode.name())));

        // Update wireframe preview and editing info
        sendWireframeSync(player);
        sendEditingInfo(player);
    }

    private static void handlePos1(ServerPlayer player) {
        // Usa le coordinate precise del giocatore, funziona anche in spectator mode
        BlockPos targetPos = BlockPos.containing(player.getX(), player.getY(), player.getZ());
        SelectionManager.getInstance().setPos1(player, targetPos);
        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "selection.pos1_set", targetPos.getX(), targetPos.getY(), targetPos.getZ())));
        sendWireframeSync(player);
    }

    private static void handlePos2(ServerPlayer player) {
        // Usa le coordinate precise del giocatore, funziona anche in spectator mode
        BlockPos targetPos = BlockPos.containing(player.getX(), player.getY(), player.getZ());
        SelectionManager.getInstance().setPos2(player, targetPos);
        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "selection.pos2_set", targetPos.getX(), targetPos.getY(), targetPos.getZ())));
        sendWireframeSync(player);
    }

    private static void handleClear(ServerPlayer player) {
        SelectionManager.getInstance().clearSelection(player);
        player.sendSystemMessage(Component.literal("§e[Struttura] §f" +
                I18n.tr(player, "selection.cleared")));
        sendWireframeSync(player);
    }

    public static void handleApply(ServerPlayer player, EditingSession session, boolean includeAir) {
        // Controlla se ha una selezione completa
        SelectionManager.Selection selection = SelectionManager.getInstance().getSelection(player);
        if (selection == null || !selection.isComplete()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "error.selection_incomplete")));
            return;
        }

        BlockPos min = selection.getMin();
        BlockPos max = selection.getMax();

        Construction construction = session.getConstruction();
        ServerLevel level = (ServerLevel) player.level();
        EditMode mode = session.getMode();

        // Check if we're editing a room
        boolean inRoom = session.isInRoom();
        Room room = inRoom ? session.getCurrentRoomObject() : null;

        if (mode == EditMode.ADD) {
            // Mode ADD: add blocks to construction or room
            int addedCount = 0;
            int skippedAir = 0;
            int skippedExisting = 0;

            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);

                        // Skip air blocks if not includeAir
                        if (!includeAir && state.isAir()) {
                            skippedAir++;
                            continue;
                        }

                        // Check if block already exists in target
                        boolean alreadyExists;
                        if (inRoom && room != null) {
                            alreadyExists = room.hasBlockChange(pos);
                        } else {
                            alreadyExists = construction.containsBlock(pos);
                        }

                        if (alreadyExists) {
                            skippedExisting++;
                            continue;
                        }

                        // Add block to construction or room
                        if (inRoom && room != null) {
                            room.setBlockChange(pos, state);
                            construction.getBounds().expandToInclude(pos);
                        } else {
                            construction.addBlock(pos, state);
                        }
                        addedCount++;
                    }
                }
            }

            // Capture entities in the selected area
            AABB entityArea = new AABB(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1
            );

            List<Entity> worldEntities = level.getEntities(
                (Entity) null,
                entityArea,
                EntityData::shouldSaveEntity
            );

            int entitiesAdded = 0;
            var bounds = construction.getBounds();

            for (Entity entity : worldEntities) {
                // Skip if entity is already tracked in the session
                if (session.isEntityTracked(entity.getUUID())) {
                    continue;
                }

                EntityData data = EntityData.fromEntity(entity, bounds, level.registryAccess());
                int newIndex;
                if (inRoom && room != null) {
                    newIndex = room.addEntity(data);
                } else {
                    newIndex = construction.addEntity(data);
                }
                session.trackEntity(entity.getUUID(), newIndex);
                entitiesAdded++;
            }

            // Clear selection after adding
            SelectionManager.getInstance().clearSelection(player);
            sendWireframeSync(player);
            sendBlockList(player);

            int totalBlocks = inRoom && room != null ? room.getChangedBlockCount() : construction.getBlockCount();
            String entityMsg = entitiesAdded > 0 ? " +" + entitiesAdded + " entities" : "";
            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "select.apply.add_success", addedCount, skippedAir, totalBlocks) + entityMsg));
        } else {
            // Mode REMOVE: rimuovi i blocchi dalla costruzione o stanza
            int removedCount = 0;
            int skippedNotInTarget = 0;

            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);

                        // Rimuovi solo se il blocco è nel target (costruzione o stanza)
                        if (inRoom && room != null) {
                            if (room.hasBlockChange(pos)) {
                                room.removeBlockChange(pos);
                                removedCount++;
                            } else {
                                skippedNotInTarget++;
                            }
                        } else {
                            if (construction.containsBlock(pos)) {
                                construction.removeBlock(pos);
                                removedCount++;
                            } else {
                                skippedNotInTarget++;
                            }
                        }
                    }
                }
            }

            // Pulisci la selezione dopo la rimozione
            SelectionManager.getInstance().clearSelection(player);
            sendWireframeSync(player);
            sendBlockList(player);

            int totalBlocks = inRoom && room != null ? room.getChangedBlockCount() : construction.getBlockCount();
            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "select.apply.remove_success", removedCount, skippedNotInTarget, totalBlocks)));
        }
    }

    // ===== GUI Action Handler =====

    /**
     * Gestisce le azioni GUI ricevute dal client.
     */
    private static void handleGuiAction(GuiActionPacket packet, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        String action = packet.action();
        String targetId = packet.targetId();
        String extraData = packet.extraData();

        Architect.LOGGER.debug("GUI action from {}: action={}, target={}, extra={}",
                player.getName().getString(), action, targetId, extraData);

        switch (action) {
            case "show" -> handleGuiShow(player, targetId);
            case "hide" -> handleGuiHide(player, targetId);
            case "tp" -> handleGuiTp(player, targetId);
            case "edit" -> handleGuiEdit(player, targetId);
            case "exit" -> handleGuiDone(player, true);  // Legacy compatibility
            case "done" -> handleGuiDone(player, true);
            case "done_nomob" -> handleGuiDone(player, false);
            case "give" -> handleGuiGive(player);
            case "shot" -> handleGuiShot(player, targetId, extraData);
            case "title" -> handleGuiTitle(player, targetId, extraData);  // targetId = langId
            case "rename" -> handleGuiRename(player, targetId);
            case "destroy" -> handleGuiDestroy(player, targetId);
            case "request_list" -> sendConstructionList(player);
            case "push" -> handleGuiPush(player, targetId);
            case "pull" -> handleGuiPull(player, targetId);
            case "pull_check" -> handleGuiPullCheck(player, targetId);
            case "pull_confirm" -> handleGuiPullConfirm(player, targetId);
            case "spawn" -> handleGuiSpawn(player, targetId);
            case "move" -> handleGuiMove(player, targetId);
            case "remove_block" -> handleGuiRemoveBlock(player, targetId);
            case "remove_entity" -> handleGuiRemoveEntity(player, targetId);  // targetId = entityType (e.g., "minecraft:pig")
            case "short_desc" -> handleGuiShortDesc(player, targetId, extraData);  // targetId = langId
            case "room_create" -> handleGuiRoomCreate(player, targetId, extraData);  // targetId = roomId, extraData = roomName
            case "room_edit" -> handleGuiRoomEdit(player, targetId);
            case "room_delete" -> handleGuiRoomDelete(player, targetId);
            case "room_rename" -> handleGuiRoomRename(player, targetId, extraData);  // targetId = oldId, extraData = newId|newName
            case "room_exit" -> handleGuiRoomExit(player, true);  // Exit room, return to building editing
            case "room_exit_nomob" -> handleGuiRoomExit(player, false);  // Exit room without saving entities
            case "set_entrance" -> handleGuiSetEntrance(player);
            case "tp_entrance" -> handleGuiTpEntrance(player);
            default -> Architect.LOGGER.warn("Unknown GUI action: {}", action);
        }
    }

    private static void handleGuiShow(ServerPlayer player, String id) {
        ConstructionRegistry registry = ConstructionRegistry.getInstance();

        if (!registry.exists(id)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "show.not_found", id)));
            return;
        }

        if (isConstructionBeingEdited(id)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "show.in_editing", id)));
            return;
        }

        if (VISIBLE_CONSTRUCTIONS.contains(id)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "show.already_visible", id)));
            return;
        }

        Construction construction = registry.get(id);
        if (construction == null || construction.getBlockCount() == 0) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "show.empty", id)));
            return;
        }

        // Use centralized placement with SHOW mode (original position)
        var result = ConstructionOperations.placeConstruction(
            player, construction, ConstructionOperations.PlacementMode.SHOW, false
        );

        // Validate coherence after show
        ServerLevel level = (ServerLevel) player.level();
        it.magius.struttura.architect.validation.CoherenceChecker.validateConstruction(
            level, construction, true);

        VISIBLE_CONSTRUCTIONS.add(id);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "show.success", id, result.blocksPlaced())));
    }

    private static void handleGuiHide(ServerPlayer player, String id) {
        ConstructionRegistry registry = ConstructionRegistry.getInstance();

        if (!registry.exists(id)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "hide.not_found", id)));
            return;
        }

        if (isConstructionBeingEdited(id)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "hide.in_editing", id)));
            return;
        }

        Construction construction = registry.get(id);
        if (construction == null || construction.getBlockCount() == 0) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "hide.not_found", id)));
            return;
        }

        ServerLevel level = (ServerLevel) player.level();

        // Use centralized removal with HIDE mode
        var result = ConstructionOperations.removeConstruction(
            level, construction, ConstructionOperations.RemovalMode.HIDE, null
        );

        // Validate registry coherence after hide (blocks no longer in world)
        it.magius.struttura.architect.validation.CoherenceChecker.validateConstruction(
            level, construction, false);

        VISIBLE_CONSTRUCTIONS.remove(id);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "hide.success", id, result.blocksRemoved())));
    }

    private static void handleGuiTp(ServerPlayer player, String id) {
        Construction construction = getConstructionIncludingEditing(id);
        if (construction == null || !construction.getBounds().isValid()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "tp.not_found", id)));
            return;
        }

        BlockPos pos = teleportToConstruction(player, construction);
        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "tp.success_self", id, pos.getX(), pos.getY(), pos.getZ())));
    }

    private static void handleGuiEdit(ServerPlayer player, String id) {
        // Valida formato ID
        if (!Construction.isValidId(id)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "edit.invalid_id", id)));
            return;
        }

        // Verifica che un altro giocatore non stia già modificando questa costruzione
        if (isConstructionBeingEdited(id)) {
            EditingSession existingSession = getSessionForConstruction(id);
            // Solo blocca se un ALTRO giocatore sta modificando questa costruzione
            if (existingSession != null && !existingSession.getPlayer().getUUID().equals(player.getUUID())) {
                String otherPlayerName = existingSession.getPlayer().getName().getString();
                player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                        I18n.tr(player, "edit.already_in_use", id, otherPlayerName)));
                return;
            }
        }

        // Se il player è già in editing, esci prima dalla sessione corrente
        if (EditingSession.hasSession(player)) {
            EditingSession currentSession = EditingSession.endSession(player);
            Construction currentConstruction = currentSession.getConstruction();

            // Salva la costruzione corrente se ha blocchi
            if (currentConstruction.getBlockCount() > 0) {
                ConstructionRegistry.getInstance().register(currentConstruction);
            }

            // Pulisci la selezione
            SelectionManager.getInstance().clearSelection(player);
        }

        // Carica o crea la costruzione
        Construction construction;
        ConstructionRegistry registry = ConstructionRegistry.getInstance();

        if (registry.exists(id)) {
            construction = registry.get(id);
        } else {
            construction = new Construction(id, player.getUUID(), player.getName().getString());
        }

        // Avvia sessione di editing
        EditingSession.startSession(player, construction);

        // Invia sync al client
        sendWireframeSync(player);
        sendEditingInfo(player);
        sendConstructionList(player);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "edit.success", id)));
    }

    private static void handleGuiDone(ServerPlayer player, boolean saveEntities) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
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
        }

        // Pulisci la selezione
        SelectionManager.getInstance().clearSelection(player);

        // Invia sync al client
        sendEmptyWireframe(player);
        sendEditingInfoEmpty(player);
        sendConstructionList(player);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "done.success", construction.getId(), construction.getBlockCount())));
    }

    private static void handleGuiGive(ServerPlayer player) {
        ItemStack hammerStack = new ItemStack(ModItems.CONSTRUCTION_HAMMER);

        if (player.getInventory().add(hammerStack)) {
            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "give.success")));
        } else {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "give.inventory_full")));
        }
    }

    private static void handleGuiShot(ServerPlayer player, String constructionId, String title) {
        if (constructionId.isEmpty()) {
            // Use current editing session if no ID provided
            EditingSession session = EditingSession.getSession(player);
            if (session == null) {
                player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                        I18n.tr(player, "shot.no_id")));
                return;
            }
            constructionId = session.getConstruction().getId();
        }

        String screenshotTitle = (title != null && !title.isEmpty()) ? title : "Screenshot";

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "shot.in_editing", constructionId)));

        sendScreenshotRequest(player, constructionId, screenshotTitle);
    }

    private static void handleGuiTitle(ServerPlayer player, String langId, String title) {
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        // Use provided langId, fallback to player's language or "en"
        String lang = langId;
        if (lang == null || lang.isEmpty()) {
            lang = I18n.getPlayerLanguage(player);
            if (lang == null || lang.isEmpty()) {
                lang = "en";
            }
        }

        session.getConstruction().setTitle(lang, title);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "title.success", lang, title)));

        // Update translations on client
        sendTranslations(player);
    }

    private static void handleGuiRename(ServerPlayer player, String newId) {
        // Il giocatore deve essere in editing
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        String oldId = session.getConstruction().getId();

        // Se l'ID non è cambiato, ignora
        if (oldId.equals(newId)) {
            return;
        }

        // Valida il nuovo ID
        if (!Construction.isValidId(newId)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "edit.invalid_id", newId)));
            return;
        }

        // Verifica che il nuovo ID non esista già (nel registry o in altre sessioni)
        if (ConstructionRegistry.getInstance().exists(newId)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "rename.id_exists", newId)));
            return;
        }

        // Verifica che un altro giocatore non stia già modificando una costruzione con il nuovo ID
        for (EditingSession otherSession : EditingSession.getAllSessions()) {
            if (otherSession != session && otherSession.getConstruction().getId().equals(newId)) {
                player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                        I18n.tr(player, "rename.id_exists", newId)));
                return;
            }
        }

        // Crea la nuova costruzione con il nuovo ID
        Construction oldConstruction = session.getConstruction();
        Construction newConstruction = oldConstruction.copyWithNewId(newId);

        // Aggiorna la sessione con la nuova costruzione
        session.setConstruction(newConstruction);

        // Rimuovi la vecchia costruzione dal registry se esisteva
        if (ConstructionRegistry.getInstance().exists(oldId)) {
            ConstructionRegistry.getInstance().unregister(oldId);
        }

        Architect.LOGGER.info("Player {} renamed construction from {} to {}",
                player.getName().getString(), oldId, newId);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "rename.success", oldId, newId)));

        // Aggiorna le info di editing e la lista costruzioni sul client
        sendEditingInfo(player);
        sendConstructionList(player);
    }

    private static void handleGuiDestroy(ServerPlayer player, String id) {
        ConstructionRegistry registry = ConstructionRegistry.getInstance();

        // Verifica che la costruzione esista (in registry o in editing)
        if (!registry.exists(id) && getSessionForConstruction(id) == null) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "destroy.not_found", id)));
            return;
        }

        // Verifica se un ALTRO giocatore sta editando questa costruzione
        EditingSession existingSession = getSessionForConstruction(id);
        if (existingSession != null && !existingSession.getPlayer().getUUID().equals(player.getUUID())) {
            String otherPlayerName = existingSession.getPlayer().getName().getString();
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "destroy.in_editing_by_other", id, otherPlayerName)));
            return;
        }

        ServerLevel level = (ServerLevel) player.level();
        Construction construction = getConstructionIncludingEditing(id);

        // 1. Use centralized removal with DESTROY mode
        var result = ConstructionOperations.removeConstruction(
            level, construction, ConstructionOperations.RemovalMode.DESTROY, null
        );
        if (result.blocksRemoved() > 0) {
            Architect.LOGGER.info("GUI Destroy: removed {} blocks for construction {}", result.blocksRemoved(), id);
        }

        // 2. Clean visibility data
        VISIBLE_CONSTRUCTIONS.remove(id);

        // 3. Se il giocatore corrente sta editando questa costruzione, termina la sessione
        if (existingSession != null && existingSession.getPlayer().getUUID().equals(player.getUUID())) {
            EditingSession.endSession(player);

            // Pulisci la selezione
            SelectionManager.getInstance().clearSelection(player);

            // Invia sync wireframe vuoto al client
            sendEmptyWireframe(player);

            // Invia stato editing vuoto al client per la GUI
            sendEditingInfoEmpty(player);

            Architect.LOGGER.info("GUI Destroy: ended editing session for {}", id);
        }

        // 4. Rimuovi la costruzione dal registry (e dal filesystem)
        registry.unregister(id);

        // 5. Aggiorna la lista delle costruzioni per il client
        sendConstructionList(player);

        Architect.LOGGER.info("Player {} destroyed construction via GUI: {}", player.getName().getString(), id);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "destroy.success", id)));
    }

    private static void handleGuiPush(ServerPlayer player, String id) {
        // Verifica che la costruzione NON sia in modalità editing
        if (isConstructionBeingEdited(id)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "push.in_editing", id)));
            return;
        }

        // Verifica che la costruzione esista nel registry
        ConstructionRegistry registry = ConstructionRegistry.getInstance();
        if (!registry.exists(id)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "push.not_found", id)));
            return;
        }

        // Verifica che non ci sia già una richiesta in corso
        if (ApiClient.isRequestInProgress()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "push.request_in_progress")));
            return;
        }

        Construction construction = registry.get(id);
        if (construction == null || construction.getBlockCount() == 0) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "push.empty", id)));
            return;
        }

        // Verifica che la costruzione abbia un titolo (obbligatorio per l'API)
        if (!construction.hasValidTitle()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "push.no_title", id)));
            return;
        }

        // Refresh entities from world before push to capture any modifications
        ServerLevel level = (ServerLevel) player.level();
        int refreshed = construction.refreshEntitiesFromWorld(level, level.registryAccess());
        if (refreshed > 0) {
            Architect.LOGGER.info("Refreshed {} entities from world before push", refreshed);
        }

        // Messaggio di invio
        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "push.sending", id, construction.getBlockCount())));

        Architect.LOGGER.info("Player {} pushing construction {} ({} blocks) via GUI",
            player.getName().getString(), id, construction.getBlockCount());

        // Cattura il server per il callback sul main thread
        var server = level.getServer();

        // Esegui push asincrono
        boolean started = ApiClient.pushConstruction(construction, response -> {
            // Callback viene eseguito su thread async, schedula sul main thread
            if (server != null) {
                server.execute(() -> {
                    if (response.success()) {
                        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                            I18n.tr(player, "push.success", id, response.statusCode(), response.message())
                        ));
                        Architect.LOGGER.info("Push successful for {}: {} - {}",
                            id, response.statusCode(), response.message());
                    } else {
                        player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                            I18n.tr(player, "push.failed", id, response.statusCode(), response.message())
                        ));
                        Architect.LOGGER.warn("Push failed for {}: {} - {}",
                            id, response.statusCode(), response.message());
                    }
                });
            }
        });

        if (!started) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "push.request_in_progress")));
        }
    }

    // Set per tracciare le costruzioni attualmente in pull
    private static final Set<String> PULLING_CONSTRUCTIONS = new HashSet<>();

    private static void handleGuiPull(ServerPlayer player, String id) {
        // Verifica che la costruzione non sia in editing
        if (isConstructionBeingEdited(id)) {
            EditingSession existingSession = getSessionForConstruction(id);
            String otherPlayerName = existingSession != null
                ? existingSession.getPlayer().getName().getString()
                : "unknown";
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "pull.in_editing", id, otherPlayerName)));
            return;
        }

        // Verifica che non sia già in corso un pull per questa costruzione
        if (PULLING_CONSTRUCTIONS.contains(id)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "pull.already_pulling", id)));
            return;
        }

        // Verifica che non ci sia già una richiesta API in corso
        if (ApiClient.isRequestInProgress()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "push.request_in_progress")));
            return;
        }

        // Blocca la costruzione per il pull
        PULLING_CONSTRUCTIONS.add(id);

        // Messaggio di inizio
        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "pull.starting", id)));

        Architect.LOGGER.info("Player {} pulling construction {} via GUI", player.getName().getString(), id);

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

                        // Validate coherence after pull (blocks should be in world now)
                        ServerLevel level = (ServerLevel) player.level();
                        it.magius.struttura.architect.validation.CoherenceChecker.validateConstruction(
                            level, construction, true);

                        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                            I18n.tr(player, "pull.success", id, placementResult.blocksPlaced())
                        ));

                        // Update construction list
                        sendConstructionList(player);

                        Architect.LOGGER.info("Pull successful for {}: {} blocks placed",
                            id, placementResult.blocksPlaced());
                    } else {
                        player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
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
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "push.request_in_progress")));
        }
    }

    /**
     * Piazza una costruzione di fronte al giocatore e aggiorna le coordinate interne.
     * Usato da PULL per aggiornare la costruzione dopo il download.
     */
    private static int spawnConstructionInFrontOfPlayer(ServerPlayer player, Construction construction) {
        return spawnConstructionInFrontOfPlayer(player, construction, true);
    }

    /**
     * Piazza una costruzione di fronte al giocatore.
     *
     * @param player Il giocatore
     * @param construction La costruzione da piazzare
     * @param updateConstruction Se true, aggiorna la costruzione con le nuove coordinate (usato per PULL).
     *                           Se false, piazza solo i blocchi senza modificare la costruzione (usato per SPAWN).
     * @return Il numero di blocchi piazzati
     */
    public static int spawnConstructionInFrontOfPlayer(ServerPlayer player, Construction construction, boolean updateConstruction) {
        if (construction.getBlockCount() == 0) {
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();
        var bounds = construction.getBounds();
        int sizeX = bounds.getSizeX();
        int sizeZ = bounds.getSizeZ();

        // Salva i bounds originali PRIMA di modificare la costruzione
        int originalMinX = bounds.getMinX();
        int originalMinY = bounds.getMinY();
        int originalMinZ = bounds.getMinZ();

        // Calcola la posizione di spawn di fronte al giocatore
        float yaw = player.getYRot();
        double radians = Math.toRadians(yaw);
        double dirX = -Math.sin(radians);
        double dirZ = Math.cos(radians);

        // Calcola la distanza minima per garantire che il player sia fuori dai bounds di 2 blocchi
        // La costruzione viene centrata sulla direzione del player, quindi dobbiamo considerare
        // metà della dimensione nella direzione di sguardo + metà della dimensione perpendicolare
        // per coprire il caso peggiore (angoli)
        int halfSizeX = (sizeX + 1) / 2;
        int halfSizeZ = (sizeZ + 1) / 2;

        // Distanza dal centro della costruzione al bordo più vicino nella direzione del player
        // Usiamo il massimo delle due metà per sicurezza
        int halfSize = Math.max(halfSizeX, halfSizeZ);

        // Distanza totale: metà dimensione + 2 blocchi di margine
        int distance = halfSize + 2;

        int offsetX = (int) Math.round(player.getX() + dirX * distance) - originalMinX;
        int offsetY = (int) player.getY() - originalMinY;
        int offsetZ = (int) Math.round(player.getZ() + dirZ * distance) - originalMinZ;

        // Piazza i blocchi
        // Usa UPDATE_CLIENTS | UPDATE_SKIP_ON_PLACE per preservare l'orientamento delle rotaie
        // UPDATE_SKIP_ON_PLACE previene che onPlace() venga chiamato, evitando che le rotaie
        // si auto-connettano ai vicini durante il piazzamento
        int placementFlags = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE;

        java.util.Map<BlockPos, BlockState> newBlocks = new java.util.HashMap<>();
        int placedCount = 0;
        int blockEntityCount = 0;

        // Ordina i blocchi per Y crescente per piazzare prima i blocchi di supporto
        // (es. trapdoor prima dei carpet che ci stanno sopra)
        java.util.List<java.util.Map.Entry<BlockPos, BlockState>> sortedBlocks =
            new java.util.ArrayList<>(construction.getBlocks().entrySet());
        sortedBlocks.sort((a, b) -> Integer.compare(a.getKey().getY(), b.getKey().getY()));

        for (java.util.Map.Entry<BlockPos, BlockState> entry : sortedBlocks) {
            BlockPos originalPos = entry.getKey();
            BlockState state = entry.getValue();
            BlockPos newPos = originalPos.offset(offsetX, offsetY, offsetZ);
            newBlocks.put(newPos, state);
            if (!state.isAir()) {
                level.setBlock(newPos, state, placementFlags);
                placedCount++;

                // Applica l'NBT del block entity se presente (casse, furnace, etc.)
                CompoundTag blockNbt = construction.getBlockEntityNbt(originalPos);
                if (blockNbt != null) {
                    net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(newPos);
                    if (blockEntity != null) {
                        // Crea una copia dell'NBT e aggiorna le coordinate
                        CompoundTag nbtCopy = blockNbt.copy();
                        nbtCopy.putInt("x", newPos.getX());
                        nbtCopy.putInt("y", newPos.getY());
                        nbtCopy.putInt("z", newPos.getZ());
                        // MC 1.21.11: usa TagValueInput per creare un ValueInput dal CompoundTag
                        net.minecraft.world.level.storage.ValueInput input = net.minecraft.world.level.storage.TagValueInput.create(
                            net.minecraft.util.ProblemReporter.DISCARDING,
                            level.registryAccess(),
                            nbtCopy
                        );
                        blockEntity.loadCustomOnly(input);
                        blockEntity.setChanged();
                        blockEntityCount++;
                    }
                }
            }
        }

        if (blockEntityCount > 0) {
            Architect.LOGGER.info("Applied NBT to {} block entities", blockEntityCount);
        }

        // Aggiorna la costruzione con le nuove coordinate assolute (solo se richiesto)
        if (updateConstruction) {
            // Crea una nuova mappa per l'NBT con le coordinate aggiornate
            java.util.Map<BlockPos, CompoundTag> newBlockEntityNbt = new java.util.HashMap<>();
            for (java.util.Map.Entry<BlockPos, BlockState> entry : construction.getBlocks().entrySet()) {
                BlockPos originalPos = entry.getKey();
                CompoundTag nbt = construction.getBlockEntityNbt(originalPos);
                if (nbt != null) {
                    BlockPos newPos = originalPos.offset(offsetX, offsetY, offsetZ);
                    // Aggiorna le coordinate nell'NBT
                    CompoundTag nbtCopy = nbt.copy();
                    nbtCopy.putInt("x", newPos.getX());
                    nbtCopy.putInt("y", newPos.getY());
                    nbtCopy.putInt("z", newPos.getZ());
                    newBlockEntityNbt.put(newPos, nbtCopy);
                }
            }

            // Aggiorna i blocchi
            construction.getBlocks().clear();
            construction.getBounds().reset();
            for (java.util.Map.Entry<BlockPos, BlockState> entry : newBlocks.entrySet()) {
                construction.addBlock(entry.getKey(), entry.getValue());
            }

            // Aggiorna l'NBT dei block entity
            construction.getBlockEntityNbtMap().clear();
            construction.getBlockEntityNbtMap().putAll(newBlockEntityNbt);

            // Aggiorna anche le coordinate delle room (traslate insieme alla costruzione)
            for (Room room : construction.getRooms().values()) {
                java.util.Map<BlockPos, BlockState> newRoomBlocks = new java.util.HashMap<>();
                java.util.Map<BlockPos, CompoundTag> newRoomNbt = new java.util.HashMap<>();

                for (java.util.Map.Entry<BlockPos, BlockState> entry : room.getBlockChanges().entrySet()) {
                    BlockPos originalPos = entry.getKey();
                    BlockPos newPos = originalPos.offset(offsetX, offsetY, offsetZ);
                    newRoomBlocks.put(newPos, entry.getValue());

                    CompoundTag nbt = room.getBlockEntityNbt(originalPos);
                    if (nbt != null) {
                        CompoundTag nbtCopy = nbt.copy();
                        nbtCopy.putInt("x", newPos.getX());
                        nbtCopy.putInt("y", newPos.getY());
                        nbtCopy.putInt("z", newPos.getZ());
                        newRoomNbt.put(newPos, nbtCopy);
                    }
                }

                // Sostituisci i blocchi della room con quelli traslati
                room.getBlockChanges().clear();
                room.getBlockChanges().putAll(newRoomBlocks);
                room.getBlockEntityNbtMap().clear();
                room.getBlockEntityNbtMap().putAll(newRoomNbt);
            }
        }

        // Spawna le entità con l'offset appropriato
        // Le coordinate delle entità sono relative ai bounds minimi originali,
        // quindi usiamo l'offset + bounds originali = posizione assoluta nel mondo
        int originX = offsetX + originalMinX;
        int originY = offsetY + originalMinY;
        int originZ = offsetZ + originalMinZ;
        spawnConstructionEntities(construction, level, originX, originY, originZ);

        return placedCount;
    }

    /**
     * Mostra una costruzione nella sua posizione originale (usato per SHOW dopo HIDE).
     * Piazza i blocchi nelle coordinate memorizzate, applica NBT dei block entity e spawna le entità.
     *
     * @param level Il ServerLevel
     * @param construction La costruzione da mostrare
     * @return Il numero di blocchi piazzati
     */
    public static int showConstructionInPlace(ServerLevel level, Construction construction) {
        if (construction == null || construction.getBlockCount() == 0) {
            return 0;
        }

        var bounds = construction.getBounds();
        if (!bounds.isValid()) {
            return 0;
        }

        // Piazza i blocchi nelle loro posizioni originali
        // Usa UPDATE_CLIENTS | UPDATE_SKIP_ON_PLACE per preservare l'orientamento delle rotaie
        int placementFlags = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE;
        int placedCount = 0;
        int blockEntityCount = 0;

        // Ordina i blocchi per Y crescente per piazzare prima i blocchi di supporto
        java.util.List<java.util.Map.Entry<BlockPos, BlockState>> sortedBlocks =
            new java.util.ArrayList<>(construction.getBlocks().entrySet());
        sortedBlocks.sort((a, b) -> Integer.compare(a.getKey().getY(), b.getKey().getY()));

        for (java.util.Map.Entry<BlockPos, BlockState> entry : sortedBlocks) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            if (!state.isAir()) {
                level.setBlock(pos, state, placementFlags);
                placedCount++;

                // Applica l'NBT del block entity se presente (casse, furnace, etc.)
                CompoundTag blockNbt = construction.getBlockEntityNbt(pos);
                if (blockNbt != null) {
                    net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity != null) {
                        // L'NBT è già nelle coordinate corrette
                        net.minecraft.world.level.storage.ValueInput input = net.minecraft.world.level.storage.TagValueInput.create(
                            net.minecraft.util.ProblemReporter.DISCARDING,
                            level.registryAccess(),
                            blockNbt
                        );
                        blockEntity.loadCustomOnly(input);
                        blockEntity.setChanged();
                        blockEntityCount++;
                    }
                }
            }
        }

        if (blockEntityCount > 0) {
            Architect.LOGGER.info("ShowInPlace: Applied NBT to {} block entities", blockEntityCount);
        }

        // Spawna le entità nelle loro posizioni originali
        // Le coordinate delle entità sono relative ai bounds minimi
        int entityCount = spawnConstructionEntities(construction, level,
            bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());

        Architect.LOGGER.info("ShowInPlace: placed {} blocks, {} entities", placedCount, entityCount);

        return placedCount;
    }

    // ===== GUI Spawn/Move handlers =====

    /**
     * Handles spawn action via GUI.
     * Uses ArchitectSpawn mode which includes random room selection.
     * The GUI is used by architects/builders to test their constructions.
     */
    private static void handleGuiSpawn(ServerPlayer player, String id) {
        // Verify the construction exists (including those being edited)
        Construction construction = getConstructionIncludingEditing(id);
        if (construction == null) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "spawn.not_found", id)));
            return;
        }

        if (construction.getBlockCount() == 0) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "spawn.empty", id)));
            return;
        }

        // Use ArchitectSpawn mode for GUI (allows testing room variations)
        var result = ConstructionOperations.architectSpawn(
            player, construction, player.getYRot()
        );

        Architect.LOGGER.info("Player {} architect-spawned {} via GUI ({} blocks, {} rooms)",
            player.getName().getString(), id, result.blocksPlaced(), result.roomsSpawned());

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "spawn.architect_success", id, result.blocksPlaced(), result.roomsSpawned())));
    }

    /**
     * Gestisce l'azione short_desc via GUI.
     * Salva la descrizione breve della costruzione.
     */
    private static void handleGuiShortDesc(ServerPlayer player, String langId, String description) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();

        // Truncate if too long
        if (description.length() > 512) {
            description = description.substring(0, 512);
        }

        // Use provided langId, fallback to player's language
        String lang = langId;
        if (lang == null || lang.isEmpty()) {
            lang = I18n.getPlayerLanguage(player);
        }

        // Set short description
        construction.setShortDescription(lang, description);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "short_desc.saved")));

        // Update translations on client
        sendTranslations(player);
    }

    /**
     * Gestisce l'azione remove_block via GUI.
     * Rimuove tutti i blocchi di un tipo specifico dalla costruzione o dalla room corrente.
     */
    private static void handleGuiRemoveBlock(ServerPlayer player, String blockId) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();
        boolean inRoom = session.isInRoom();
        it.magius.struttura.architect.model.Room room = inRoom ? session.getCurrentRoomObject() : null;

        // Conta e rimuovi i blocchi del tipo specificato
        int removedCount = 0;
        java.util.List<BlockPos> toRemove = new java.util.ArrayList<>();

        if (inRoom && room != null) {
            // Remove from room's block changes
            for (java.util.Map.Entry<BlockPos, BlockState> entry : room.getBlockChanges().entrySet()) {
                String entryBlockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(entry.getValue().getBlock())
                    .toString();

                if (entryBlockId.equals(blockId)) {
                    toRemove.add(entry.getKey());
                }
            }

            // Remove from room data
            for (BlockPos pos : toRemove) {
                room.removeBlockChange(pos);
                removedCount++;
            }

            // Restore base blocks in world using centralized method
            ServerLevel world = (ServerLevel) player.level();
            ConstructionOperations.restoreBaseBlocksAt(world, toRemove, construction);
        } else {
            // Remove from construction base
            for (java.util.Map.Entry<BlockPos, BlockState> entry : construction.getBlocks().entrySet()) {
                String entryBlockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(entry.getValue().getBlock())
                    .toString();

                if (entryBlockId.equals(blockId)) {
                    toRemove.add(entry.getKey());
                }
            }

            // Remove from construction data and place air in world
            ServerLevel world = (ServerLevel) player.level();
            for (BlockPos pos : toRemove) {
                // Clear container contents first
                net.minecraft.world.level.block.entity.BlockEntity be = world.getBlockEntity(pos);
                if (be instanceof net.minecraft.world.Clearable clearable) {
                    clearable.clearContent();
                }

                // Remove from construction data
                construction.removeBlock(pos);

                // Place air in world
                world.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);

                removedCount++;
            }

            // Update shape connections for neighbors
            for (BlockPos pos : toRemove) {
                // Update neighbors of removed blocks
                for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                    BlockPos neighborPos = pos.relative(dir);
                    BlockState neighborState = world.getBlockState(neighborPos);
                    if (!neighborState.isAir()) {
                        neighborState.updateNeighbourShapes(world, neighborPos, net.minecraft.world.level.block.Block.UPDATE_CLIENTS, 0);
                    }
                }
            }
        }

        if (removedCount > 0) {
            // Aggiorna il client
            sendWireframeSync(player);
            sendEditingInfo(player);
            sendBlockList(player);

            String displayName = blockId;
            net.minecraft.resources.Identifier loc = net.minecraft.resources.Identifier.tryParse(blockId);
            if (loc != null) {
                net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(loc);
                if (block != null) {
                    displayName = block.getName().getString();
                }
            }

            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "remove_block.success", removedCount, displayName)));
        } else {
            player.sendSystemMessage(Component.literal("§e[Struttura] §f" +
                    I18n.tr(player, "remove_block.not_found", blockId)));
        }
    }

    /**
     * Handles remove_entity action via GUI.
     * Removes ALL entities of a specific type from the construction or room.
     */
    private static void handleGuiRemoveEntity(ServerPlayer player, String entityType) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();
        boolean inRoom = session.isInRoom();
        it.magius.struttura.architect.model.Room room = inRoom ? session.getCurrentRoomObject() : null;

        // Get the entity list
        java.util.List<it.magius.struttura.architect.model.EntityData> entityList;
        if (inRoom && room != null) {
            entityList = room.getEntities();
        } else {
            entityList = construction.getEntities();
        }

        // Find indices of all entities matching this type (iterate in reverse to safely remove)
        java.util.List<Integer> indicesToRemove = new java.util.ArrayList<>();
        for (int i = 0; i < entityList.size(); i++) {
            if (entityList.get(i).getEntityType().equals(entityType)) {
                indicesToRemove.add(i);
            }
        }

        // Remove all matching entities from construction/room (in reverse order to maintain correct indices)
        int removedCount = 0;
        for (int i = indicesToRemove.size() - 1; i >= 0; i--) {
            int indexToRemove = indicesToRemove.get(i);
            boolean removed;
            if (inRoom && room != null) {
                removed = room.removeEntity(indexToRemove);
            } else {
                removed = construction.removeEntity(indexToRemove);
            }
            if (removed) {
                removedCount++;
            }
        }

        // Clear all tracking since we're removing entities (simpler than tracking each index shift)
        // The tracking will be rebuilt when entities are spawned again if needed

        // Also discard all entities of this type in the world within construction bounds
        ServerLevel world = (ServerLevel) player.level();
        var bounds = construction.getBounds();
        if (bounds.isValid()) {
            net.minecraft.world.phys.AABB aabb = new net.minecraft.world.phys.AABB(
                bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
                bounds.getMaxX() + 1, bounds.getMaxY() + 1, bounds.getMaxZ() + 1
            );

            // Find all entities of this type in the bounds
            java.util.List<Entity> entitiesToDiscard = world.getEntitiesOfClass(
                Entity.class,
                aabb,
                e -> {
                    if (e instanceof net.minecraft.world.entity.player.Player) return false;
                    String eType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(e.getType()).toString();
                    return eType.equals(entityType);
                }
            );

            for (Entity entity : entitiesToDiscard) {
                java.util.UUID uuid = entity.getUUID();
                // Untrack the entity
                session.untrackEntity(uuid);
                it.magius.struttura.architect.entity.EntityFreezeHandler.getInstance().unfreezeEntity(uuid);
                it.magius.struttura.architect.entity.EntitySpawnHandler.getInstance().unignoreEntity(uuid);
                entity.discard();
            }
        }

        if (removedCount > 0) {
            // Update UI
            sendWireframeSync(player);
            sendEditingInfo(player);
            sendBlockList(player);

            // Get display name from entity type
            net.minecraft.resources.Identifier entityLoc = net.minecraft.resources.Identifier.tryParse(entityType);
            String displayName = entityType;
            if (entityLoc != null) {
                var entityTypeObj = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(entityLoc);
                if (entityTypeObj != null) {
                    displayName = entityTypeObj.getDescription().getString();
                }
            }

            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "entity.removed_count", removedCount, displayName)));
        } else {
            player.sendSystemMessage(Component.literal("§e[Struttura] §f" +
                    I18n.tr(player, "entity.error.not_found")));
        }
    }

    /**
     * Gestisce l'azione room_create via GUI.
     * Crea una nuova stanza e entra in editing.
     */
    private static void handleGuiRoomCreate(ServerPlayer player, String roomId, String roomName) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();

        // Verifica che l'ID sia valido
        if (roomId == null || roomId.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "room.error.invalid_id")));
            return;
        }

        // Verifica che non esista già una stanza con lo stesso ID
        if (construction.getRoom(roomId) != null) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "room.error.exists", roomId)));
            return;
        }

        // Crea la stanza
        Room room = new Room(roomId);
        if (roomName != null && !roomName.isEmpty()) {
            room.setName(roomName);
        }
        construction.addRoom(room);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "room.created", roomId)));

        // Entra in editing della stanza
        session.enterRoom(roomId);

        // Aggiorna il client
        sendWireframeSync(player);
        sendEditingInfo(player);
    }

    /**
     * Gestisce l'azione room_edit via GUI.
     * Entra in editing di una stanza esistente.
     */
    private static void handleGuiRoomEdit(ServerPlayer player, String roomId) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();

        // Verifica che la stanza esista
        if (construction.getRoom(roomId) == null) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "room.not_found", roomId)));
            return;
        }

        // Entra in editing della stanza
        session.enterRoom(roomId);

        // Aggiorna il client
        sendWireframeSync(player);
        sendEditingInfo(player);
    }

    /**
     * Gestisce l'azione room_delete via GUI.
     * Elimina una stanza.
     */
    private static void handleGuiRoomDelete(ServerPlayer player, String roomId) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();

        // Verifica che la stanza esista
        if (construction.getRoom(roomId) == null) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "room.not_found", roomId)));
            return;
        }

        // Se siamo in editing della stanza che vogliamo eliminare, usciamo prima
        if (session.isInRoom() && roomId.equals(session.getCurrentRoom())) {
            session.exitRoom();
        }

        // Elimina la stanza
        construction.removeRoom(roomId);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "room.deleted", roomId)));

        // Aggiorna il client
        sendWireframeSync(player);
        sendEditingInfo(player);
    }

    /**
     * Gestisce l'azione room_rename via GUI.
     * Rinomina una stanza con nuovo ID e nome.
     * @param oldId ID attuale della stanza
     * @param extraData formato: "newId|newName"
     */
    private static void handleGuiRoomRename(ServerPlayer player, String oldId, String extraData) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        EditingSession session = EditingSession.getSession(player);

        // Parse extraData: newId|newName
        String[] parts = extraData.split("\\|", 2);
        if (parts.length < 2) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "room.error.invalid_data")));
            return;
        }

        String newId = parts[0];
        String newName = parts[1];

        // Verifica che l'ID sia valido
        if (newId == null || newId.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "room.error.invalid_id")));
            return;
        }

        // Verifica lunghezza nome
        if (newName.length() > 100) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "room.name_too_long")));
            return;
        }

        // Rinomina la stanza (restituisce il nuovo ID o null se fallisce)
        String resultId = session.renameRoomWithId(oldId, newId, newName);
        if (resultId != null) {
            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "room.renamed", oldId, newName) +
                    (resultId.equals(oldId) ? "" : "\nNew ID: " + resultId)));
        } else {
            // Fallimento: probabilmente ID già esistente
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "room.error.exists", newId)));
        }

        // Aggiorna il client
        sendWireframeSync(player);
        sendEditingInfo(player);
    }

    /**
     * Gestisce l'azione room_exit via GUI.
     * Esce dalla modalità editing room e torna all'editing building.
     * @param saveEntities se true mantiene le entità, se false le rimuove dalla room
     */
    private static void handleGuiRoomExit(ServerPlayer player, boolean saveEntities) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        EditingSession session = EditingSession.getSession(player);

        if (!session.isInRoom()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "room.not_in_room")));
            return;
        }

        String roomId = session.getCurrentRoom();

        // Se non salviamo le entità, rimuovile dalla room
        if (!saveEntities) {
            Room room = session.getCurrentRoomObject();
            if (room != null) {
                room.clearEntities();
                Architect.LOGGER.info("Cleared entities from room {} (exit nomob)", roomId);
            }
        }

        // Esci dalla room
        session.exitRoom();

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "room.exited", roomId)));

        // Aggiorna il client
        sendWireframeSync(player);
        sendEditingInfo(player);
    }

    /**
     * Handles set_entrance action via GUI.
     * Sets the entrance anchor at player's current position (normalized).
     */
    private static void handleGuiSetEntrance(ServerPlayer player) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        EditingSession session = EditingSession.getSession(player);

        // Entrance can only be set for base construction, not rooms
        if (session.isInRoom()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "entrance.not_in_room")));
            return;
        }

        Construction construction = session.getConstruction();
        ConstructionBounds bounds = construction.getBounds();

        if (!bounds.isValid()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "entrance.no_bounds")));
            return;
        }

        // Use round(player.getY()) - 1 for anchor Y
        int playerX = player.blockPosition().getX();
        int playerY = (int) Math.round(player.getY()) - 1;
        int playerZ = player.blockPosition().getZ();

        // Check if player is within or directly above construction bounds
        boolean withinXZ = playerX >= bounds.getMinX() && playerX <= bounds.getMaxX() &&
                          playerZ >= bounds.getMinZ() && playerZ <= bounds.getMaxZ();
        boolean withinY = playerY >= bounds.getMinY() && playerY <= bounds.getMaxY() + 1;

        if (!withinXZ || !withinY) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "entrance.outside_bounds")));
            return;
        }

        // Normalize coordinates (relative to bounds min corner)
        // Y is stored as floor(player.getY()) + 1 - normalized
        int normalizedX = playerX - bounds.getMinX();
        int normalizedY = playerY - bounds.getMinY();
        int normalizedZ = playerZ - bounds.getMinZ();

        // Get player's yaw rotation
        float yaw = player.getYRot();

        // Set the entrance anchor with yaw
        construction.getAnchors().setEntrance(new BlockPos(normalizedX, normalizedY, normalizedZ), yaw);

        // Save construction
        ConstructionRegistry.getInstance().register(construction);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "entrance.set", playerX, playerY, playerZ)));

        // Update client
        sendEditingInfo(player);
    }

    /**
     * Handles tp_entrance action via GUI.
     * Teleports player to the entrance anchor position using centralized logic.
     */
    private static void handleGuiTpEntrance(ServerPlayer player) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        EditingSession session = EditingSession.getSession(player);

        // Entrance can only be used for base construction, not rooms
        if (session.isInRoom()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "entrance.not_in_room")));
            return;
        }

        Construction construction = session.getConstruction();

        if (!construction.getAnchors().hasEntrance()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "entrance.not_set")));
            return;
        }

        if (!construction.getBounds().isValid()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "entrance.no_bounds")));
            return;
        }

        BlockPos pos = teleportToConstruction(player, construction);
        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "entrance.tp", pos.getX(), pos.getY(), pos.getZ())));
    }

    /**
     * Handles move action via GUI.
     * Uses centralized operations to move construction to new position.
     */
    private static void handleGuiMove(ServerPlayer player, String id) {
        // Verify the construction exists
        Construction construction = getConstructionIncludingEditing(id);
        if (construction == null) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "move.not_found", id)));
            return;
        }

        // Verify the construction is not being edited
        if (isConstructionBeingEdited(id)) {
            EditingSession existingSession = getSessionForConstruction(id);
            String otherPlayerName = existingSession != null
                ? existingSession.getPlayer().getName().getString()
                : "unknown";
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "move.in_editing", id, otherPlayerName)));
            return;
        }

        if (construction.getBlockCount() == 0) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "move.empty", id)));
            return;
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

        // 4. Validate coherence after move
        it.magius.struttura.architect.validation.CoherenceChecker.validateConstruction(
            level, construction, true);

        // 5. Save the updated construction
        ConstructionRegistry.getInstance().register(construction);

        // 6. Update visibility state
        VISIBLE_CONSTRUCTIONS.add(id);

        Architect.LOGGER.info("Player {} moved construction {} via GUI to new position ({} blocks, {} entities)",
            player.getName().getString(), id, placementResult.blocksPlaced(), placementResult.entitiesSpawned());

        if (placementResult.entitiesSpawned() > 0) {
            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "move.success_with_entities", id, placementResult.blocksPlaced(), placementResult.entitiesSpawned())));
        } else {
            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "move.success", id, placementResult.blocksPlaced())));
        }
    }

    // ===== Helper Methods =====

    private static boolean isConstructionBeingEdited(String constructionId) {
        return getSessionForConstruction(constructionId) != null;
    }

    private static EditingSession getSessionForConstruction(String constructionId) {
        for (EditingSession session : EditingSession.getAllSessions()) {
            if (session.getConstruction().getId().equals(constructionId)) {
                return session;
            }
        }
        return null;
    }

    private static Construction getConstructionIncludingEditing(String id) {
        ConstructionRegistry registry = ConstructionRegistry.getInstance();
        if (registry.exists(id)) {
            return registry.get(id);
        }
        EditingSession session = getSessionForConstruction(id);
        if (session != null) {
            return session.getConstruction();
        }
        return null;
    }

    // ===== Editing Info Packet Methods =====

    /**
     * Invia le informazioni di editing al client.
     */
    public static void sendEditingInfo(ServerPlayer player) {
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            sendEditingInfoEmpty(player);
            return;
        }

        Construction construction = session.getConstruction();
        ConstructionBounds bounds = construction.getBounds();

        String boundsStr = bounds.isValid()
                ? bounds.getSizeX() + "x" + bounds.getSizeY() + "x" + bounds.getSizeZ()
                : "0x0x0";

        // Get title in player's language
        String lang = I18n.getPlayerLanguage(player);
        String title = construction.getTitle(lang);
        if (title == null || title.isEmpty()) {
            // Try English as fallback
            title = construction.getTitle("en");
            if (title == null) title = "";
        }

        // Get short description in player's language
        String shortDesc = construction.getShortDescriptionWithFallback(lang);
        if (shortDesc == null) shortDesc = "";

        // Room info
        boolean inRoom = session.isInRoom();
        String currentRoomId = "";
        String currentRoomName = "";
        int roomBlockChanges = 0;

        // Stats to send - use total stats (base + all rooms) for building editing
        int statsBlockCount = construction.getTotalBlockCount();
        int statsSolidBlockCount = construction.getTotalSolidBlockCount();
        int statsAirCount = construction.getTotalAirBlockCount();
        int statsEntityCount = construction.getTotalEntityCount();
        int statsMobCount = construction.getTotalMobCount();
        String statsBoundsStr = boundsStr;

        if (inRoom) {
            currentRoomId = session.getCurrentRoom();
            var room = construction.getRoom(currentRoomId);
            if (room != null) {
                currentRoomName = room.getName();
                roomBlockChanges = room.getChangedBlockCount();

                // Calculate room-specific stats
                int roomSolidBlocks = 0;
                int roomAirBlocks = 0;
                for (var entry : room.getBlockChanges().entrySet()) {
                    if (entry.getValue().isAir()) {
                        roomAirBlocks++;
                    } else {
                        roomSolidBlocks++;
                    }
                }

                // Override stats with room-specific values
                statsBlockCount = room.getChangedBlockCount();
                statsSolidBlockCount = roomSolidBlocks;
                statsAirCount = roomAirBlocks;
                statsEntityCount = room.getEntityCount();
                // Count mobs in room entities
                statsMobCount = 0;
                for (var entityData : room.getEntities()) {
                    if (entityData != null && Construction.isMobEntity(entityData.getEntityType())) {
                        statsMobCount++;
                    }
                }
                // Rooms don't have their own bounds, so show N/A
                statsBoundsStr = "N/A";
            }
        }

        // Build room list
        java.util.List<EditingInfoPacket.RoomInfo> roomList = new java.util.ArrayList<>();
        for (var room : construction.getRooms().values()) {
            roomList.add(new EditingInfoPacket.RoomInfo(
                room.getId(),
                room.getName(),
                room.getChangedBlockCount(),
                room.getEntityCount()
            ));
        }
        // Sort by name
        roomList.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));

        // Entrance anchor data (only for base construction, not rooms)
        // Send absolute (world) coordinates for GUI display
        boolean hasEntrance = !inRoom && construction.getAnchors().hasEntrance() && bounds.isValid();
        int entranceX = 0, entranceY = 0, entranceZ = 0;
        float entranceYaw = 0f;
        if (hasEntrance) {
            BlockPos entrance = construction.getAnchors().getEntrance();
            // Denormalize: convert from relative (0,0,0 based) to absolute world coordinates
            entranceX = entrance.getX() + bounds.getMinX();
            entranceY = entrance.getY() + bounds.getMinY();
            entranceZ = entrance.getZ() + bounds.getMinZ();
            entranceYaw = construction.getAnchors().getEntranceYaw();
        }

        EditingInfoPacket packet = new EditingInfoPacket(
                true,
                construction.getId(),
                title,
                statsBlockCount,
                statsSolidBlockCount,
                statsAirCount,
                statsEntityCount,
                statsMobCount,
                statsBoundsStr,
                session.getMode().name(),
                shortDesc,
                // Room fields
                inRoom,
                currentRoomId,
                currentRoomName,
                construction.getRoomCount(),
                roomBlockChanges,
                roomList,
                // Anchor fields
                hasEntrance,
                entranceX,
                entranceY,
                entranceZ,
                entranceYaw
        );

        ServerPlayNetworking.send(player, packet);

        // Invia anche la lista blocchi e le traduzioni
        sendBlockList(player);
        sendTranslations(player);
    }

    /**
     * Invia un packet di editing vuoto (non in editing).
     */
    public static void sendEditingInfoEmpty(ServerPlayer player) {
        ServerPlayNetworking.send(player, EditingInfoPacket.empty());
        ServerPlayNetworking.send(player, BlockListPacket.empty());
        ServerPlayNetworking.send(player, TranslationsPacket.empty());
    }

    /**
     * Invia tutte le traduzioni (titles e shortDescriptions) al client.
     */
    public static void sendTranslations(ServerPlayer player) {
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            ServerPlayNetworking.send(player, TranslationsPacket.empty());
            return;
        }

        Construction construction = session.getConstruction();
        TranslationsPacket packet = new TranslationsPacket(
                construction.getTitles(),
                construction.getShortDescriptions()
        );
        ServerPlayNetworking.send(player, packet);
    }

    /**
     * Invia la lista blocchi e entità al client per il dropdown nel pannello editing.
     */
    public static void sendBlockList(ServerPlayer player) {
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            ServerPlayNetworking.send(player, BlockListPacket.empty());
            return;
        }

        Construction construction = session.getConstruction();
        boolean inRoom = session.isInRoom();
        it.magius.struttura.architect.model.Room room = inRoom ? session.getCurrentRoomObject() : null;

        // --- Build block list ---
        // Use room blocks if editing a room, otherwise use construction blocks
        java.util.Map<String, Integer> blockCounts;
        if (inRoom && room != null) {
            // Count blocks in the room's changes
            blockCounts = new java.util.HashMap<>();
            for (BlockState state : room.getBlockChanges().values()) {
                String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                blockCounts.merge(blockId, 1, Integer::sum);
            }
        } else {
            blockCounts = construction.getBlockCounts();
        }

        List<BlockListPacket.BlockInfo> blocks = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> entry : blockCounts.entrySet()) {
            String blockId = entry.getKey();
            int count = entry.getValue();

            // Get display name from registry
            net.minecraft.resources.Identifier loc = net.minecraft.resources.Identifier.tryParse(blockId);
            String displayName = blockId; // fallback
            if (loc != null) {
                net.minecraft.world.level.block.Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(loc);
                if (block != null) {
                    displayName = block.getName().getString();
                }
            }

            blocks.add(new BlockListPacket.BlockInfo(blockId, displayName, count));
        }

        // Sort blocks by count descending
        blocks.sort((a, b) -> Integer.compare(b.count(), a.count()));

        // --- Build entity list (grouped by type) ---
        java.util.List<it.magius.struttura.architect.model.EntityData> entityList;

        if (inRoom && room != null) {
            entityList = room.getEntities();
        } else {
            entityList = construction.getEntities();
        }

        // Count entities by type
        java.util.Map<String, Integer> entityCounts = new java.util.HashMap<>();
        for (it.magius.struttura.architect.model.EntityData data : entityList) {
            String entityType = data.getEntityType();
            entityCounts.merge(entityType, 1, Integer::sum);
        }

        // Build entity info list
        List<BlockListPacket.EntityInfo> entities = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> entry : entityCounts.entrySet()) {
            String entityType = entry.getKey();
            int count = entry.getValue();

            // Get display name from entity type
            net.minecraft.resources.Identifier entityLoc = net.minecraft.resources.Identifier.tryParse(entityType);
            String displayName = entityType; // fallback
            if (entityLoc != null) {
                var entityTypeObj = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(entityLoc);
                if (entityTypeObj != null) {
                    displayName = entityTypeObj.getDescription().getString();
                }
            }

            entities.add(new BlockListPacket.EntityInfo(entityType, displayName, count));
        }

        // Sort entities by count descending
        entities.sort((a, b) -> Integer.compare(b.count(), a.count()));

        ServerPlayNetworking.send(player, new BlockListPacket(blocks, entities));
    }

    /**
     * Invia la lista delle costruzioni al client.
     * Usa i totali (base + room) per blockCount e entityCount.
     */
    public static void sendConstructionList(ServerPlayer player) {
        List<ConstructionListPacket.ConstructionInfo> list = new ArrayList<>();

        // Get player's preferred language for title fallback
        String lang = I18n.getPlayerLanguage(player);

        // Aggiungi tutte le costruzioni dal registry
        for (Construction c : ConstructionRegistry.getInstance().getAll()) {
            boolean isEditing = isConstructionBeingEdited(c.getId());
            list.add(new ConstructionListPacket.ConstructionInfo(
                    c.getId(),
                    c.getTitleWithFallback(lang),
                    c.getTotalBlockCount(),
                    c.getTotalEntityCount(),
                    isEditing
            ));
        }

        // Aggiungi quelle in editing che non sono ancora nel registry
        for (EditingSession session : EditingSession.getAllSessions()) {
            Construction c = session.getConstruction();
            boolean alreadyInList = list.stream().anyMatch(info -> info.id().equals(c.getId()));
            if (!alreadyInList) {
                list.add(new ConstructionListPacket.ConstructionInfo(
                        c.getId(),
                        c.getTitleWithFallback(lang),
                        c.getTotalBlockCount(),
                        c.getTotalEntityCount(),
                        true
                ));
            }
        }

        ServerPlayNetworking.send(player, new ConstructionListPacket(list));
    }

    // ===== Pull con validazione mod =====

    /**
     * Prima fase del pull: scarica solo i metadati e invia i mod richiesti al client.
     * Il client mostrerà una dialog se ci sono mod mancanti.
     */
    private static void handleGuiPullCheck(ServerPlayer player, String id) {
        // Verifica che la costruzione non sia in editing
        if (isConstructionBeingEdited(id)) {
            EditingSession existingSession = getSessionForConstruction(id);
            String otherPlayerName = existingSession != null
                ? existingSession.getPlayer().getName().getString()
                : "unknown";
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "pull.in_editing", id, otherPlayerName)));
            return;
        }

        // Verifica che non sia già in corso un pull per questa costruzione
        if (PULLING_CONSTRUCTIONS.contains(id)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "pull.already_pulling", id)));
            return;
        }

        // Verifica che non ci sia già una richiesta API in corso
        if (ApiClient.isRequestInProgress()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "push.request_in_progress")));
            return;
        }

        // Messaggio di inizio
        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "pull.checking", id)));

        Architect.LOGGER.info("Player {} checking mod requirements for {} via GUI",
            player.getName().getString(), id);

        // Cattura il server per il callback sul main thread
        var server = ((ServerLevel) player.level()).getServer();

        // Esegui il pull dei soli metadati
        boolean started = ApiClient.pullMetadataOnly(id, response -> {
            if (server != null) {
                server.execute(() -> {
                    if (response.success() && response.requiredMods() != null) {
                        // Invia i mod richiesti al client
                        ModRequirementsPacket packet = new ModRequirementsPacket(
                            response.constructionId(),
                            response.requiredMods()
                        );
                        ServerPlayNetworking.send(player, packet);

                        Architect.LOGGER.info("Sent mod requirements for {} to {}: {} mods",
                            id, player.getName().getString(), response.requiredMods().size());
                    } else {
                        player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                            I18n.tr(player, "pull.failed", id, response.statusCode(), response.message())
                        ));
                        Architect.LOGGER.warn("Pull check failed for {}: {} - {}",
                            id, response.statusCode(), response.message());
                    }
                });
            }
        });

        if (!started) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "push.request_in_progress")));
        }
    }

    /**
     * Seconda fase del pull: scarica la costruzione completa dopo conferma dell'utente.
     * Chiamato dal client dopo aver mostrato la dialog dei mod mancanti.
     */
    private static void handleGuiPullConfirm(ServerPlayer player, String id) {
        // Il flusso è identico a handleGuiPull, ma viene chiamato solo dopo conferma
        handleGuiPull(player, id);
    }

    // ===== Entity spawning =====

    /**
     * Spawns entities of a construction into the world.
     *
     * @param construction the construction
     * @param level the ServerLevel to spawn into
     * @param originX X offset for coordinates (0 for original placement)
     * @param originY Y offset for coordinates (0 for original placement)
     * @param originZ Z offset for coordinates (0 for original placement)
     * @return the number of spawned entities
     */
    public static int spawnConstructionEntities(Construction construction, ServerLevel level,
                                                 int originX, int originY, int originZ) {
        if (construction.getEntities().isEmpty()) {
            return 0;
        }

        int spawnedCount = 0;

        for (EntityData data : construction.getEntities()) {

            try {
                // Calcola la posizione nel mondo
                double worldX = originX + data.getRelativePos().x;
                double worldY = originY + data.getRelativePos().y;
                double worldZ = originZ + data.getRelativePos().z;

                // Copia l'NBT e rimuovi/aggiorna i tag di posizione
                CompoundTag nbt = data.getNbt().copy();
                nbt.remove("Pos");      // Rimuovi posizione originale
                nbt.remove("Motion");   // Rimuovi movimento
                nbt.remove("UUID");     // UUID sarà generato nuovo

                // Per item frame: rimuovi le mappe dall'NBT (le mappe non possono essere trasferite)
                // L'item frame verrà spawnato vuoto invece che saltato
                String entityType = data.getEntityType();
                if (entityType.equals("minecraft:item_frame") || entityType.equals("minecraft:glow_item_frame")) {
                    EntityData.removeMapFromItemFrameNbt(nbt);
                }

                // IMPORTANTE: Assicuriamoci che l'NBT contenga il tag "id" per EntityType.loadEntityRecursive
                if (!nbt.contains("id")) {
                    nbt.putString("id", data.getEntityType());
                }

                // Per le entità "hanging" (item frame, painting, etc.) aggiorna block_pos o TileX/Y/Z
                // MC 1.21.11 usa "block_pos" come IntArrayTag [x, y, z], non CompoundTag!
                if (nbt.contains("block_pos")) {
                    net.minecraft.nbt.Tag rawTag = nbt.get("block_pos");
                    if (rawTag instanceof net.minecraft.nbt.IntArrayTag intArrayTag) {
                        int[] coords = intArrayTag.getAsIntArray();
                        if (coords.length >= 3) {
                            int relX = coords[0];
                            int relY = coords[1];
                            int relZ = coords[2];

                            int newX = originX + relX;
                            int newY = originY + relY;
                            int newZ = originZ + relZ;

                            // Crea un nuovo IntArrayTag con le coordinate assolute
                            nbt.putIntArray("block_pos", new int[] { newX, newY, newZ });
                        }
                    }
                }
                // Fallback per vecchi formati (TileX/Y/Z)
                else if (nbt.contains("TileX") && nbt.contains("TileY") && nbt.contains("TileZ")) {
                    int relativeTileX = nbt.getIntOr("TileX", 0);
                    int relativeTileY = nbt.getIntOr("TileY", 0);
                    int relativeTileZ = nbt.getIntOr("TileZ", 0);

                    int newTileX = originX + relativeTileX;
                    int newTileY = originY + relativeTileY;
                    int newTileZ = originZ + relativeTileZ;

                    nbt.putInt("TileX", newTileX);
                    nbt.putInt("TileY", newTileY);
                    nbt.putInt("TileZ", newTileZ);
                }

                // Converti sleeping_pos per villager che dormono (da coordinate relative ad assolute)
                // MC 1.21.11 usa IntArrayTag per sleeping_pos, come block_pos
                if (nbt.contains("sleeping_pos")) {
                    net.minecraft.nbt.Tag sleepingTag = nbt.get("sleeping_pos");
                    if (sleepingTag instanceof net.minecraft.nbt.IntArrayTag sleepingIntArray) {
                        int[] coords = sleepingIntArray.getAsIntArray();
                        if (coords.length >= 3) {
                            int relX = coords[0];
                            int relY = coords[1];
                            int relZ = coords[2];

                            int newX = originX + relX;
                            int newY = originY + relY;
                            int newZ = originZ + relZ;

                            nbt.putIntArray("sleeping_pos", new int[] { newX, newY, newZ });
                        }
                    }
                }

                // Crea l'entità dall'NBT (MC 1.21+ richiede EntitySpawnReason)
                Entity entity = EntityType.loadEntityRecursive(nbt, level, EntitySpawnReason.LOAD, e -> e);

                if (entity != null) {
                    // Imposta posizione e rotazione DOPO la creazione
                    entity.setPos(worldX, worldY, worldZ);
                    entity.setYRot(data.getYaw());
                    entity.setXRot(data.getPitch());
                    // Genera un nuovo UUID per evitare conflitti
                    entity.setUUID(UUID.randomUUID());

                    level.addFreshEntity(entity);
                    spawnedCount++;
                } else {
                    Architect.LOGGER.warn("Failed to create entity of type {}", data.getEntityType());
                }
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to spawn entity of type {}: {}",
                    data.getEntityType(), e.getMessage());
            }
        }

        return spawnedCount;
    }

    // ===== Centralized teleport logic =====

    /**
     * Teleports a player to a construction.
     * If the construction has an entrance anchor, teleports to that position.
     * Otherwise, teleports to the south edge facing the center.
     *
     * @param player The player to teleport
     * @param construction The construction to teleport to
     * @return The teleport position (for logging/messaging)
     */
    public static BlockPos teleportToConstruction(ServerPlayer player, Construction construction) {
        var bounds = construction.getBounds();
        double tpX, tpY, tpZ;
        float yaw, pitch;

        // Check if entrance anchor is set - use it for teleport destination
        if (construction.getAnchors().hasEntrance()) {
            // Denormalize entrance coordinates
            // TP to anchor.Y + 1
            BlockPos entrance = construction.getAnchors().getEntrance();
            tpX = bounds.getMinX() + entrance.getX() + 0.5;
            tpY = bounds.getMinY() + entrance.getY() + 1;
            tpZ = bounds.getMinZ() + entrance.getZ() + 0.5;

            // Use saved yaw rotation, keep current pitch
            yaw = construction.getAnchors().getEntranceYaw();
            pitch = player.getXRot();
        } else {
            // Fall back to default behavior: teleport to south edge facing center
            BlockPos center = bounds.getCenter();
            double centerX = center.getX() + 0.5;
            double centerY = center.getY() + 0.5;
            double centerZ = center.getZ() + 0.5;

            BlockPos min = bounds.getMin();
            tpX = centerX;
            tpY = min.getY();
            tpZ = bounds.getMax().getZ() + 2;

            double dx = centerX - tpX;
            double dz = centerZ - tpZ;
            yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);

            double dy = centerY - (tpY + 1.6);
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            pitch = (float) (-Math.atan2(dy, horizontalDist) * 180.0 / Math.PI);
        }

        player.teleportTo(
                (ServerLevel) player.level(),
                tpX, tpY, tpZ,
                java.util.Set.of(),
                yaw, pitch,
                false
        );

        return new BlockPos((int) tpX, (int) tpY, (int) tpZ);
    }

    // ===== InGame Building Methods =====

    /**
     * Sends the in-game building state to a player.
     * Called when player enters or exits a spawned building.
     */
    public static void sendInGameBuildingState(ServerPlayer player,
            it.magius.struttura.architect.ingame.model.SpawnedBuildingInfo buildingInfo,
            boolean hasLiked) {
        InGameBuildingPacket packet;
        if (buildingInfo != null) {
            // Look up the building in the spawnable list to get localized name/description
            String localizedName = "";
            String localizedDescription = "";
            String author = "";

            it.magius.struttura.architect.ingame.InGameManager manager =
                it.magius.struttura.architect.ingame.InGameManager.getInstance();
            it.magius.struttura.architect.ingame.model.SpawnableList spawnableList = manager.getSpawnableList();

            if (spawnableList != null) {
                it.magius.struttura.architect.ingame.model.SpawnableBuilding building =
                    spawnableList.getBuildingByRdns(buildingInfo.rdns());
                if (building != null) {
                    // Get player's language code
                    String langCode = it.magius.struttura.architect.i18n.PlayerLanguageTracker
                        .getInstance().getPlayerLanguage(player);
                    localizedName = building.getLocalizedName(langCode);
                    localizedDescription = building.getLocalizedDescription(langCode);
                    author = building.getAuthor();
                }
            }

            // Fallback to chunk data if building is not in list (was removed after spawning)
            if (localizedName.isEmpty() && buildingInfo.name() != null && !buildingInfo.name().isEmpty()) {
                localizedName = buildingInfo.name();
            }
            if (author.isEmpty() && buildingInfo.author() != null && !buildingInfo.author().isEmpty()) {
                author = buildingInfo.author();
            }

            packet = InGameBuildingPacket.entered(buildingInfo.rdns(), buildingInfo.pk(), hasLiked,
                localizedName, localizedDescription, author);
        } else {
            packet = InGameBuildingPacket.empty();
        }
        ServerPlayNetworking.send(player, packet);
        Architect.LOGGER.debug("Sent in-game building state to {}: inBuilding={}, rdns={}, name={}, author={}",
            player.getName().getString(), packet.inBuilding(), packet.rdns(), packet.localizedName(), packet.author());
    }

    /**
     * Handles a like request from a client.
     */
    private static void handleInGameLike(InGameLikePacket packet,
            ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();

        Architect.LOGGER.debug("Received like request from {}: rdns={}, pk={}",
            player.getName().getString(), packet.rdns(), packet.pk());

        // Send the like via LikeManager
        it.magius.struttura.architect.ingame.LikeManager.getInstance()
            .likeBuilding(player, packet.rdns(), packet.pk());

        // Send updated state back to client (now liked)
        it.magius.struttura.architect.ingame.tracker.PlayerBuildingState state =
            it.magius.struttura.architect.ingame.tracker.PlayerBuildingState.getInstance();
        it.magius.struttura.architect.ingame.model.SpawnedBuildingInfo currentBuilding =
            state.getCurrentBuilding(player);

        if (currentBuilding != null && currentBuilding.pk() == packet.pk()) {
            // Player is still in the building they liked, send update
            sendInGameBuildingState(player, currentBuilding, true);
        }
    }

    /**
     * Handles an InGame list selection from a client.
     */
    private static void handleInGameSelect(InGameSelectPacket packet,
            ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();

        it.magius.struttura.architect.ingame.InGameManager manager =
            it.magius.struttura.architect.ingame.InGameManager.getInstance();

        switch (packet.action()) {
            case DECLINE -> {
                Architect.LOGGER.info("Player {} declined InGame mode", player.getName().getString());
                manager.decline();
                // Send message that adventure mode is disabled
                player.sendSystemMessage(
                    net.minecraft.network.chat.Component.translatable("struttura.ingame.disabled")
                );
            }
            case SKIP -> {
                Architect.LOGGER.info("Player {} skipped InGame mode for now", player.getName().getString());
                // Don't mark as initialized - will retry next world load
                player.sendSystemMessage(
                    net.minecraft.network.chat.Component.translatable("struttura.ingame.skipped")
                );
            }
            case SELECT -> {
                Architect.LOGGER.info("Player {} selected InGame list: {} (id={})",
                    player.getName().getString(), packet.listName(), packet.listId());

                // Initialize with selected list
                manager.initialize(
                    packet.listId(),
                    packet.listName(),
                    it.magius.struttura.architect.ingame.InGameState.AuthType.PUBLIC
                );

                // Fetch the spawnable list data
                var server = ((ServerLevel) player.level()).getServer();
                it.magius.struttura.architect.api.ApiClient.fetchSpawnableList(packet.listId(), response -> {
                    if (response != null && response.success() && response.spawnableList() != null) {
                        server.execute(() -> {
                            manager.setSpawnableList(response.spawnableList());
                            player.sendSystemMessage(
                                net.minecraft.network.chat.Component.translatable(
                                    "struttura.ingame.activated",
                                    packet.listName(),
                                    response.spawnableList().getBuildingCount()
                                )
                            );
                        });
                    }
                });
            }
        }
    }
}
