package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ConstructionBounds;
import it.magius.struttura.architect.model.EditMode;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.model.ModInfo;
import it.magius.struttura.architect.registry.ConstructionRegistry;
import it.magius.struttura.architect.registry.ModItems;
import it.magius.struttura.architect.selection.SelectionManager;
import it.magius.struttura.architect.session.EditingSession;
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

        // Registra il receiver per i dati dello screenshot
        ServerPlayNetworking.registerGlobalReceiver(ScreenshotDataPacket.TYPE, NetworkHandler::handleScreenshotData);
        // Registra il receiver per le azioni di selezione via keybinding
        ServerPlayNetworking.registerGlobalReceiver(SelectionKeyPacket.TYPE, NetworkHandler::handleSelectionKey);
        // Registra il receiver per le azioni GUI
        ServerPlayNetworking.registerGlobalReceiver(GuiActionPacket.TYPE, NetworkHandler::handleGuiAction);

        Architect.LOGGER.info("Registered network packets");
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
     * Include anche i blocchi di anteprima (quelli nella selezione che verranno aggiunti).
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

        // Calcola i blocchi di anteprima (nella selezione ma non nella costruzione)
        List<BlockPos> previewBlocks = calculatePreviewBlocks(player, construction);

        BlockPositionsSyncPacket packet = new BlockPositionsSyncPacket(solidBlocks, airBlocks, previewBlocks);
        ServerPlayNetworking.send(player, packet);

        Architect.LOGGER.debug("Sent block positions to {}: {} solid, {} air, {} preview",
            player.getName().getString(), solidBlocks.size(), airBlocks.size(), previewBlocks.size());
    }

    /**
     * Calcola i blocchi che verranno modificati con select apply.
     * In mode ADD: blocchi nell'area che NON sono già nella costruzione e NON sono aria (verranno aggiunti).
     * In mode REMOVE: blocchi nell'area che SONO nella costruzione (verranno rimossi).
     */
    private static List<BlockPos> calculatePreviewBlocks(ServerPlayer player, Construction construction) {
        SelectionManager.Selection selection = SelectionManager.getInstance().getSelection(player);

        // Se non c'è selezione completa, nessun blocco di anteprima
        if (selection == null || !selection.isComplete()) {
            return List.of();
        }

        // Ottieni la modalità dalla sessione
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            return List.of();
        }
        EditMode mode = session.getMode();

        BlockPos min = selection.getMin();
        BlockPos max = selection.getMax();
        ServerLevel level = (ServerLevel) player.level();

        List<BlockPos> previewBlocks = new ArrayList<>();

        // Itera su tutti i blocchi nell'area selezionata
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);

                    if (mode == EditMode.ADD) {
                        // Mode ADD: mostra blocchi che verranno aggiunti
                        // Salta se già nella costruzione
                        if (construction.containsBlock(pos)) {
                            continue;
                        }
                        // Salta i blocchi aria
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) {
                            continue;
                        }
                        previewBlocks.add(pos);
                    } else {
                        // Mode REMOVE: mostra blocchi che verranno rimossi
                        // Mostra solo se è nella costruzione
                        if (construction.containsBlock(pos)) {
                            previewBlocks.add(pos);
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
        BlockPos targetPos = player.blockPosition().below();
        SelectionManager.getInstance().setPos1(player, targetPos);
        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "selection.pos1_set", targetPos.getX(), targetPos.getY(), targetPos.getZ())));
        sendWireframeSync(player);
    }

    private static void handlePos2(ServerPlayer player) {
        BlockPos targetPos = player.blockPosition().below();
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

    private static void handleApply(ServerPlayer player, EditingSession session, boolean includeAir) {
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

        if (mode == EditMode.ADD) {
            // Mode ADD: aggiungi i blocchi alla costruzione
            int addedCount = 0;
            int skippedAir = 0;

            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = level.getBlockState(pos);

                        // Salta i blocchi aria se non includeAir
                        if (!includeAir && state.isAir()) {
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
            sendWireframeSync(player);
            sendBlockList(player);

            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "select.apply.add_success", addedCount, skippedAir, construction.getBlockCount())));
        } else {
            // Mode REMOVE: rimuovi i blocchi dalla costruzione
            int removedCount = 0;
            int skippedNotInConstruction = 0;

            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int y = min.getY(); y <= max.getY(); y++) {
                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);

                        // Rimuovi solo se il blocco è nella costruzione
                        if (construction.containsBlock(pos)) {
                            construction.removeBlock(pos);
                            removedCount++;
                        } else {
                            skippedNotInConstruction++;
                        }
                    }
                }
            }

            // Pulisci la selezione dopo la rimozione
            SelectionManager.getInstance().clearSelection(player);
            sendWireframeSync(player);
            sendBlockList(player);

            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "select.apply.remove_success", removedCount, skippedNotInConstruction, construction.getBlockCount())));
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
            case "short_desc" -> handleGuiShortDesc(player, targetId, extraData);  // targetId = langId
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

        ServerLevel level = (ServerLevel) player.level();

        // Piazza i blocchi della costruzione nel mondo
        // Usa UPDATE_CLIENTS | UPDATE_SKIP_ON_PLACE per preservare l'orientamento delle rotaie
        int placementFlags = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE;
        int placedCount = 0;
        for (Map.Entry<BlockPos, BlockState> entry : construction.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            level.setBlock(pos, state, placementFlags);
            placedCount++;
        }

        // Spawna le entità (coordinate relative ai bounds minimi)
        var bounds = construction.getBounds();
        int entityCount = spawnConstructionEntities(construction, level,
            bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());

        VISIBLE_CONSTRUCTIONS.add(id);

        if (entityCount > 0) {
            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "show.success_with_entities", id, placedCount, entityCount)));
        } else {
            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "show.success", id, placedCount)));
        }
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

        // Usa la stessa logica di destroy per rimuovere completamente i blocchi
        int removedCount = hideConstructionFromWorld(level, construction);

        VISIBLE_CONSTRUCTIONS.remove(id);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "hide.success", id, removedCount)));
    }

    private static void handleGuiTp(ServerPlayer player, String id) {
        Construction construction = getConstructionIncludingEditing(id);
        if (construction == null || !construction.getBounds().isValid()) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "tp.not_found", id)));
            return;
        }

        BlockPos center = construction.getBounds().getCenter();
        double centerX = center.getX() + 0.5;
        double centerY = center.getY() + 0.5;
        double centerZ = center.getZ() + 0.5;

        BlockPos min = construction.getBounds().getMin();
        double tpX = centerX;
        double tpY = min.getY();
        double tpZ = construction.getBounds().getMax().getZ() + 2;

        double dx = centerX - tpX;
        double dz = centerZ - tpZ;
        float yaw = (float) (Math.atan2(-dx, dz) * 180.0 / Math.PI);

        double dy = centerY - (tpY + 1.6);
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) (-Math.atan2(dy, horizontalDist) * 180.0 / Math.PI);

        player.teleportTo(
                (ServerLevel) player.level(),
                tpX, tpY, tpZ,
                java.util.Set.of(),
                yaw, pitch,
                false
        );

        BlockPos pos = new BlockPos((int) tpX, (int) tpY, (int) tpZ);
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

        // 1. Rimuovi entità, svuota container e rimuovi blocchi
        int blocksRemoved = clearConstructionFromWorld(level, construction);
        if (blocksRemoved > 0) {
            Architect.LOGGER.info("GUI Destroy: removed {} blocks for construction {}", blocksRemoved, id);
        }

        // 2. Pulisci i dati di visibilità
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

        // Messaggio di invio
        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "push.sending", id, construction.getBlockCount())));

        Architect.LOGGER.info("Player {} pushing construction {} ({} blocks) via GUI",
            player.getName().getString(), id, construction.getBlockCount());

        // Cattura il server per il callback sul main thread
        var server = ((ServerLevel) player.level()).getServer();

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

                        // Registra la costruzione nel registry
                        ConstructionRegistry.getInstance().register(construction);

                        // Piazza la costruzione di fronte al giocatore
                        int placedCount = spawnConstructionInFrontOfPlayer(player, construction);

                        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                            I18n.tr(player, "pull.success", id, placedCount)
                        ));

                        // Aggiorna la lista costruzioni
                        sendConstructionList(player);

                        Architect.LOGGER.info("Pull successful for {}: {} blocks placed",
                            id, placedCount);
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

        // Distanza di spawn basata sulla dimensione della costruzione
        int distance = Math.max(sizeX, sizeZ) / 2 + 3;

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

    // ===== GUI Spawn/Move handlers =====

    /**
     * Gestisce l'azione spawn via GUI.
     * Usa la funzione centralizzata spawnConstructionInFrontOfPlayer senza modificare la costruzione.
     */
    private static void handleGuiSpawn(ServerPlayer player, String id) {
        // Verifica che la costruzione esista (incluse quelle in editing)
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

        // Usa la funzione centralizzata con updateConstruction=false per non modificare la costruzione
        int placedCount = spawnConstructionInFrontOfPlayer(player, construction, false);

        Architect.LOGGER.info("Player {} spawned construction {} via GUI ({} blocks)",
            player.getName().getString(), id, placedCount);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "spawn.success", id, placedCount)));
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
     * Rimuove tutti i blocchi di un tipo specifico dalla costruzione.
     */
    private static void handleGuiRemoveBlock(ServerPlayer player, String blockId) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        EditingSession session = EditingSession.getSession(player);
        Construction construction = session.getConstruction();

        // Conta e rimuovi i blocchi del tipo specificato
        int removedCount = 0;
        java.util.List<BlockPos> toRemove = new java.util.ArrayList<>();

        for (java.util.Map.Entry<BlockPos, BlockState> entry : construction.getBlocks().entrySet()) {
            String entryBlockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(entry.getValue().getBlock())
                .toString();

            if (entryBlockId.equals(blockId)) {
                toRemove.add(entry.getKey());
            }
        }

        for (BlockPos pos : toRemove) {
            construction.removeBlock(pos);
            removedCount++;
        }

        if (removedCount > 0) {
            // Aggiorna il client
            sendWireframeSync(player);
            sendEditingInfo(player);

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
     * Gestisce l'azione move via GUI.
     * Ordine corretto:
     * 1. Calcola nuova posizione
     * 2. SPAWN nella nuova posizione
     * 3. DESTROY della vecchia posizione
     * 4. Aggiorna la costruzione nel registry
     */
    private static void handleGuiMove(ServerPlayer player, String id) {
        // Verifica che la costruzione esista
        Construction construction = getConstructionIncludingEditing(id);
        if (construction == null) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "move.not_found", id)));
            return;
        }

        // Verifica che la costruzione non sia in editing
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
            offsetX = playerPos.getX() - bounds.getMinX() - (sizeX / 2);
            offsetZ = playerPos.getZ() + 2 - bounds.getMinZ();
        } else if (yaw >= 45 && yaw < 135) {
            offsetX = playerPos.getX() - 2 - bounds.getMaxX();
            offsetZ = playerPos.getZ() - bounds.getMinZ() - (sizeZ / 2);
        } else if (yaw >= 135 && yaw < 225) {
            offsetX = playerPos.getX() - bounds.getMinX() - (sizeX / 2);
            offsetZ = playerPos.getZ() - 2 - bounds.getMaxZ();
        } else {
            offsetX = playerPos.getX() + 2 - bounds.getMinX();
            offsetZ = playerPos.getZ() - bounds.getMinZ() - (sizeZ / 2);
        }

        int offsetY = playerPos.getY() - bounds.getMinY();

        // 4. Piazza e aggiorna la costruzione
        java.util.Map<BlockPos, BlockState> newBlocks = new java.util.HashMap<>();
        java.util.Map<BlockPos, CompoundTag> newBlockEntityNbt = new java.util.HashMap<>();
        int placedCount = 0;
        int blockEntityCount = 0;

        // Ordina i blocchi per Y crescente per piazzare prima i blocchi di supporto
        // (es. trapdoor prima dei carpet che ci stanno sopra)
        java.util.List<Map.Entry<BlockPos, BlockState>> sortedBlocks =
            new java.util.ArrayList<>(construction.getBlocks().entrySet());
        sortedBlocks.sort((a, b) -> Integer.compare(a.getKey().getY(), b.getKey().getY()));

        int placementFlags = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE;
        for (Map.Entry<BlockPos, BlockState> entry : sortedBlocks) {
            BlockPos originalPos = entry.getKey();
            BlockState state = entry.getValue();

            BlockPos newPos = originalPos.offset(offsetX, offsetY, offsetZ);

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

                        // Salva l'NBT aggiornato per la nuova posizione
                        newBlockEntityNbt.put(newPos, nbtCopy);
                    }
                }
            }

            newBlocks.put(newPos, state);
        }

        if (blockEntityCount > 0) {
            Architect.LOGGER.info("Move: Applied NBT to {} block entities", blockEntityCount);
        }

        // Spawna le entità con l'offset appropriato
        int entityCount = spawnConstructionEntities(construction, level,
            offsetX + bounds.getMinX(), offsetY + bounds.getMinY(), offsetZ + bounds.getMinZ());

        // 4. DESTROY: Rimuovi i blocchi dalla vecchia posizione usando i bounds salvati
        clearAreaFromWorld(level, oldMinX, oldMinY, oldMinZ, oldMaxX, oldMaxY, oldMaxZ);

        // 5. Aggiorna la costruzione con le nuove posizioni
        construction.getBlocks().clear();
        construction.getBlockEntityNbtMap().clear();
        construction.getBounds().reset();
        for (Map.Entry<BlockPos, BlockState> entry : newBlocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            CompoundTag nbt = newBlockEntityNbt.get(pos);
            if (nbt != null) {
                construction.addBlock(pos, state, nbt);
            } else {
                construction.addBlock(pos, state);
            }
        }

        // 6. Salva la costruzione aggiornata
        ConstructionRegistry.getInstance().register(construction);

        // 7. Aggiorna lo stato di visibilità
        VISIBLE_CONSTRUCTIONS.add(id);

        Architect.LOGGER.info("Player {} moved construction {} via GUI to new position ({} blocks, {} entities)",
            player.getName().getString(), id, placedCount, entityCount);

        if (entityCount > 0) {
            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "move.success_with_entities", id, placedCount, entityCount)));
        } else {
            player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                    I18n.tr(player, "move.success", id, placedCount)));
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

        int airCount = construction.getBlockCount() - construction.getSolidBlockCount();

        EditingInfoPacket packet = new EditingInfoPacket(
                true,
                construction.getId(),
                title,
                construction.getBlockCount(),
                construction.getSolidBlockCount(),
                airCount,
                construction.getEntityCount(),
                construction.getMobCount(),
                boundsStr,
                session.getMode().name(),
                shortDesc
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
     * Invia la lista blocchi al client per il dropdown nel pannello editing.
     */
    public static void sendBlockList(ServerPlayer player) {
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            ServerPlayNetworking.send(player, BlockListPacket.empty());
            return;
        }

        Construction construction = session.getConstruction();
        java.util.Map<String, Integer> blockCounts = construction.getBlockCounts();

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

        // Sort by count descending
        blocks.sort((a, b) -> Integer.compare(b.count(), a.count()));

        ServerPlayNetworking.send(player, new BlockListPacket(blocks));
    }

    /**
     * Invia la lista delle costruzioni al client.
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
                    c.getBlockCount(),
                    c.getEntityCount(),
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
                        c.getBlockCount(),
                        c.getEntityCount(),
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
     * Spawna le entità di una costruzione nel mondo.
     *
     * @param construction la costruzione
     * @param level il ServerLevel dove spawnare
     * @param originX offset X per le coordinate (0 per piazzamento originale)
     * @param originY offset Y per le coordinate (0 per piazzamento originale)
     * @param originZ offset Z per le coordinate (0 per piazzamento originale)
     * @return il numero di entità spawnate
     */
    public static int spawnConstructionEntities(Construction construction, ServerLevel level,
                                                 int originX, int originY, int originZ) {
        if (construction.getEntities().isEmpty()) {
            return 0;
        }

        int spawnedCount = 0;

        for (Map.Entry<UUID, EntityData> entry : construction.getEntities().entrySet()) {
            EntityData data = entry.getValue();

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

    /**
     * Nasconde una costruzione dal mondo rimuovendo tutti i blocchi nell'area bounds.
     * Rimuove solo XP orbs e item drops, NON le entità della costruzione.
     * La costruzione rimane in memoria per future operazioni SHOW.
     *
     * @param level Il ServerLevel
     * @param construction La costruzione da nascondere
     * @return Il numero di blocchi rimossi
     */
    public static int hideConstructionFromWorld(ServerLevel level, Construction construction) {
        return hideConstructionFromWorld(level, construction, false);
    }

    /**
     * Nasconde una costruzione dal mondo rimuovendo tutti i blocchi nell'area bounds.
     * La costruzione rimane in memoria per future operazioni SHOW.
     *
     * @param level Il ServerLevel
     * @param construction La costruzione da nascondere
     * @param removeAllEntities Se true, rimuove TUTTE le entità (usato per MOVE). Se false, rimuove solo XP e items.
     * @return Il numero di blocchi rimossi
     */
    public static int hideConstructionFromWorld(ServerLevel level, Construction construction, boolean removeAllEntities) {
        if (construction == null || construction.getBlockCount() == 0) {
            return 0;
        }

        var bounds = construction.getBounds();
        if (!bounds.isValid()) {
            return 0;
        }

        // 1. Svuota tutti i container prima di rimuovere i blocchi
        for (int x = bounds.getMinX(); x <= bounds.getMaxX(); x++) {
            for (int y = bounds.getMinY(); y <= bounds.getMaxY(); y++) {
                for (int z = bounds.getMinZ(); z <= bounds.getMaxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity instanceof net.minecraft.world.Clearable clearable) {
                        clearable.clearContent();
                    }
                }
            }
        }

        // 2. Rimuovi TUTTI i blocchi nell'area dei bounds (incluse sorgenti acqua/lava)
        int removeFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        int blocksRemoved = 0;
        for (int x = bounds.getMinX(); x <= bounds.getMaxX(); x++) {
            for (int y = bounds.getMinY(); y <= bounds.getMaxY(); y++) {
                for (int z = bounds.getMinZ(); z <= bounds.getMaxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState currentState = level.getBlockState(pos);
                    if (!currentState.is(Blocks.AIR)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), removeFlags);
                        blocksRemoved++;
                    }
                }
            }
        }

        // 3. Rimuovi le entità nell'area
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(
            bounds.getMinX() - 1, bounds.getMinY() - 1, bounds.getMinZ() - 1,
            bounds.getMaxX() + 2, bounds.getMaxY() + 2, bounds.getMaxZ() + 2
        );
        java.util.List<Entity> entitiesToRemove;
        if (removeAllEntities) {
            // Per MOVE: rimuovi tutte le entità (esclusi i giocatori)
            entitiesToRemove = level.getEntitiesOfClass(Entity.class, area,
                e -> !(e instanceof net.minecraft.world.entity.player.Player));
        } else {
            // Per HIDE: rimuovi solo XP orbs e item drops
            entitiesToRemove = level.getEntitiesOfClass(Entity.class, area,
                e -> e instanceof net.minecraft.world.entity.ExperienceOrb ||
                     e instanceof net.minecraft.world.entity.item.ItemEntity);
        }
        for (Entity entity : entitiesToRemove) {
            entity.discard();
        }

        Architect.LOGGER.info("Hide: removed {} blocks, {} entities from bounds area", blocksRemoved, entitiesToRemove.size());

        return blocksRemoved;
    }

    /**
     * Rimuove tutti i blocchi e le entità in un'area specificata dai bounds.
     * Usato per MOVE quando i nuovi blocchi sono già stati piazzati nella nuova posizione.
     *
     * @param level Il ServerLevel
     * @param minX, minY, minZ, maxX, maxY, maxZ I bounds dell'area da pulire
     * @return Il numero di blocchi rimossi
     */
    public static int clearAreaFromWorld(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        // 1. Svuota tutti i container prima di rimuovere i blocchi
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (blockEntity instanceof net.minecraft.world.Clearable clearable) {
                        clearable.clearContent();
                    }
                }
            }
        }

        // 2. Rimuovi TUTTI i blocchi nell'area (incluse sorgenti acqua/lava)
        int removeFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        int blocksRemoved = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState currentState = level.getBlockState(pos);
                    if (!currentState.is(Blocks.AIR)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), removeFlags);
                        blocksRemoved++;
                    }
                }
            }
        }

        // 3. Rimuovi tutte le entità nell'area (esclusi i giocatori)
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(
            minX - 1, minY - 1, minZ - 1,
            maxX + 2, maxY + 2, maxZ + 2
        );
        java.util.List<Entity> entitiesToRemove = level.getEntitiesOfClass(Entity.class, area,
            e -> !(e instanceof net.minecraft.world.entity.player.Player));
        for (Entity entity : entitiesToRemove) {
            entity.discard();
        }

        Architect.LOGGER.info("ClearArea: removed {} blocks, {} entities", blocksRemoved, entitiesToRemove.size());

        return blocksRemoved;
    }

    /**
     * Rimuove completamente una costruzione dal mondo senza causare drop di oggetti.
     * Ordine di rimozione:
     * 1. Svuota contenuto dei container (casse, hopper, furnace, etc.)
     * 2. Rimuovi i blocchi della costruzione (senza drop)
     * 3. Rimuovi tutte le entità nell'area (inclusi XP orbs e item drops generati)
     *
     * @param level Il ServerLevel
     * @param construction La costruzione da rimuovere
     * @return Il numero di blocchi rimossi
     */
    public static int clearConstructionFromWorld(ServerLevel level, Construction construction) {
        if (construction == null || construction.getBlockCount() == 0) {
            return 0;
        }

        var bounds = construction.getBounds();
        if (!bounds.isValid()) {
            return 0;
        }

        // 1. Svuota tutti i container prima di rimuovere i blocchi
        int containersCleared = 0;
        for (BlockPos pos : construction.getBlocks().keySet()) {
            net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof net.minecraft.world.Clearable clearable) {
                clearable.clearContent();
                containersCleared++;
            }
        }
        if (containersCleared > 0) {
            Architect.LOGGER.debug("Destroy: Cleared {} containers", containersCleared);
        }

        // 2. Rimuovi TUTTI i blocchi nell'area dei bounds (incluse sorgenti acqua/lava)
        // Usa UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE per:
        // - UPDATE_CLIENTS (2): invia l'update ai client
        // - UPDATE_KNOWN_SHAPE (64): salta la chiamata a updateShape che può causare drop
        // NON usare UPDATE_NEIGHBORS (1) che triggera onRemove con drop
        int removeFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        int blocksRemoved = 0;
        for (int x = bounds.getMinX(); x <= bounds.getMaxX(); x++) {
            for (int y = bounds.getMinY(); y <= bounds.getMaxY(); y++) {
                for (int z = bounds.getMinZ(); z <= bounds.getMaxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState currentState = level.getBlockState(pos);
                    // Rimuovi qualsiasi blocco che non sia già aria (inclusi fluidi)
                    if (!currentState.is(Blocks.AIR)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), removeFlags);
                        blocksRemoved++;
                    }
                }
            }
        }

        // 3. Rimuovi tutte le entità nell'area DOPO aver rimosso i blocchi
        // Questo cattura anche XP orbs e item drops che potrebbero essere stati generati
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(
            bounds.getMinX() - 1, bounds.getMinY() - 1, bounds.getMinZ() - 1,
            bounds.getMaxX() + 2, bounds.getMaxY() + 2, bounds.getMaxZ() + 2
        );
        java.util.List<Entity> entitiesToRemove = level.getEntitiesOfClass(Entity.class, area,
            e -> !(e instanceof net.minecraft.world.entity.player.Player));
        for (Entity entity : entitiesToRemove) {
            entity.discard();
        }
        Architect.LOGGER.debug("Destroy: Removed {} entities", entitiesToRemove.size());

        Architect.LOGGER.info("Destroy: removed {} blocks, {} entities, {} containers cleared",
            blocksRemoved, entitiesToRemove.size(), containersCleared);

        return blocksRemoved;
    }
}
