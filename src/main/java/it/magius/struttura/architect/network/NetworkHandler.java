package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ConstructionBounds;
import it.magius.struttura.architect.selection.SelectionManager;
import it.magius.struttura.architect.session.EditingSession;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gestisce la registrazione e l'invio dei packet di rete.
 */
public class NetworkHandler {

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

        // Registra il receiver per i dati dello screenshot
        ServerPlayNetworking.registerGlobalReceiver(ScreenshotDataPacket.TYPE, NetworkHandler::handleScreenshotData);

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

        BlockPositionsSyncPacket packet = new BlockPositionsSyncPacket(solidBlocks, airBlocks);
        ServerPlayNetworking.send(player, packet);

        Architect.LOGGER.debug("Sent block positions to {}: {} solid, {} air",
            player.getName().getString(), solidBlocks.size(), airBlocks.size());
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
}
