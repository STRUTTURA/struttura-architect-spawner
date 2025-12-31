package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ConstructionBounds;
import it.magius.struttura.architect.model.EditMode;
import it.magius.struttura.architect.registry.ConstructionRegistry;
import it.magius.struttura.architect.registry.ModItems;
import it.magius.struttura.architect.selection.SelectionManager;
import it.magius.struttura.architect.session.EditingSession;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gestisce la registrazione e l'invio dei packet di rete.
 */
public class NetworkHandler {

    // Mappa per memorizzare i blocchi originali del mondo quando si usa "show"
    private static final Map<String, Map<BlockPos, BlockState>> ORIGINAL_WORLD_BLOCKS = new HashMap<>();
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
            case "exit" -> handleGuiExit(player);
            case "give" -> handleGuiGive(player);
            case "shot" -> handleGuiShot(player, targetId, extraData);
            case "title" -> handleGuiTitle(player, extraData);
            case "rename" -> handleGuiRename(player, targetId);
            case "request_list" -> sendConstructionList(player);
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

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "show.success", id, placedCount)));
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

        // Ripristina i blocchi originali
        Map<BlockPos, BlockState> originalBlocks = ORIGINAL_WORLD_BLOCKS.get(id);
        int restoredCount = 0;

        for (Map.Entry<BlockPos, BlockState> entry : construction.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            if (originalBlocks != null && originalBlocks.containsKey(pos)) {
                level.setBlock(pos, originalBlocks.get(pos), 3);
            } else {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            restoredCount++;
        }

        ORIGINAL_WORLD_BLOCKS.remove(id);
        VISIBLE_CONSTRUCTIONS.remove(id);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "hide.success", id, restoredCount)));
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
        // Verifica che non sia già in editing
        if (EditingSession.hasSession(player)) {
            EditingSession session = EditingSession.getSession(player);
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "edit.already_editing", session.getConstruction().getId())));
            return;
        }

        // Valida formato ID
        if (!Construction.isValidId(id)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "edit.invalid_id", id)));
            return;
        }

        // Verifica che un altro giocatore non stia già modificando questa costruzione
        if (isConstructionBeingEdited(id)) {
            EditingSession existingSession = getSessionForConstruction(id);
            if (existingSession != null) {
                String otherPlayerName = existingSession.getPlayer().getName().getString();
                player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                        I18n.tr(player, "edit.already_in_use", id, otherPlayerName)));
                return;
            }
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

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "edit.success", id)));
    }

    private static void handleGuiExit(ServerPlayer player) {
        if (!EditingSession.hasSession(player)) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        EditingSession session = EditingSession.endSession(player);
        Construction construction = session.getConstruction();

        // Registra la costruzione nel registro (solo se ha blocchi)
        if (construction.getBlockCount() > 0) {
            ConstructionRegistry.getInstance().register(construction);
        }

        // Pulisci la selezione
        SelectionManager.getInstance().clearSelection(player);

        // Invia sync al client
        sendEmptyWireframe(player);
        sendEditingInfoEmpty(player);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "exit.success", construction.getId(), construction.getBlockCount())));
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

    private static void handleGuiTitle(ServerPlayer player, String title) {
        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "command.not_editing")));
            return;
        }

        // Set title for player's language (default to "en")
        String lang = I18n.getPlayerLanguage(player);
        if (lang == null || lang.isEmpty()) {
            lang = "en";
        }

        session.getConstruction().setTitle(lang, title);

        player.sendSystemMessage(Component.literal("§a[Struttura] §f" +
                I18n.tr(player, "title.success", lang, title)));

        // Update editing info on client
        sendEditingInfo(player);
    }

    private static void handleGuiRename(ServerPlayer player, String newId) {
        // Rename is complex - for now just inform user to use commands
        player.sendSystemMessage(Component.literal("§e[Struttura] §f" +
                "Rename is not supported via GUI yet. Use /struttura exit and /struttura edit <new_id>"));
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

        int airCount = construction.getBlockCount() - construction.getSolidBlockCount();

        EditingInfoPacket packet = new EditingInfoPacket(
                true,
                construction.getId(),
                title,
                construction.getBlockCount(),
                construction.getSolidBlockCount(),
                airCount,
                boundsStr,
                session.getMode().name()
        );

        ServerPlayNetworking.send(player, packet);
    }

    /**
     * Invia un packet di editing vuoto (non in editing).
     */
    public static void sendEditingInfoEmpty(ServerPlayer player) {
        ServerPlayNetworking.send(player, EditingInfoPacket.empty());
    }

    /**
     * Invia la lista delle costruzioni al client.
     */
    public static void sendConstructionList(ServerPlayer player) {
        List<ConstructionListPacket.ConstructionInfo> list = new ArrayList<>();

        // Aggiungi tutte le costruzioni dal registry
        for (Construction c : ConstructionRegistry.getInstance().getAll()) {
            boolean isEditing = isConstructionBeingEdited(c.getId());
            list.add(new ConstructionListPacket.ConstructionInfo(
                    c.getId(),
                    c.getAuthorName(),
                    c.getBlockCount(),
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
                        c.getAuthorName(),
                        c.getBlockCount(),
                        true
                ));
            }
        }

        ServerPlayNetworking.send(player, new ConstructionListPacket(list));
    }
}
