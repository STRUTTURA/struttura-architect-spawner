package it.magius.struttura.architect;

import it.magius.struttura.architect.client.ScreenshotCapture;
import it.magius.struttura.architect.client.WireframeRenderer;
import it.magius.struttura.architect.network.BlockPositionsSyncPacket;
import it.magius.struttura.architect.network.ScreenshotRequestPacket;
import it.magius.struttura.architect.network.WireframeSyncPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

@Environment(EnvType.CLIENT)
public class ArchitectClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Architect.LOGGER.info("STRUTTURA: Architect client initializing...");

        // Il payload type è già registrato dal server in NetworkHandler.registerServer()
        // Il client deve solo registrare il receiver

        // Registra il receiver per i packet di sincronizzazione wireframe
        ClientPlayNetworking.registerGlobalReceiver(WireframeSyncPacket.TYPE, (packet, context) -> {
            // Esegui sul thread principale del client
            context.client().execute(() -> {
                WireframeRenderer.setConstructionData(packet.getConstructionWireframe());
                WireframeRenderer.setSelectionData(packet.getSelectionWireframe());
                Architect.LOGGER.debug("Received wireframe sync: construction={}, selection={}",
                    packet.constructionActive(), packet.selectionActive());
            });
        });

        // Registra il receiver per le posizioni dei blocchi
        ClientPlayNetworking.registerGlobalReceiver(BlockPositionsSyncPacket.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                WireframeRenderer.setBlockPositions(packet.solidBlocks(), packet.airBlocks());
                Architect.LOGGER.debug("Received block positions: {} solid, {} air",
                    packet.solidBlocks().size(), packet.airBlocks().size());
            });
        });

        // Registra il receiver per le richieste di screenshot
        ClientPlayNetworking.registerGlobalReceiver(ScreenshotRequestPacket.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                Architect.LOGGER.debug("Received screenshot request for {}", packet.constructionId());
                ScreenshotCapture.requestCapture(packet.constructionId(), packet.title());
            });
        });

        // Registra l'evento per la cattura screenshot (deve essere su render tick)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ScreenshotCapture.onRenderTick();
        });

        // Inizializza il renderer wireframe (registra WorldRenderEvents)
        WireframeRenderer.init();

        // Pulisci i dati quando ci disconnettiamo
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            WireframeRenderer.reset();
            Architect.LOGGER.debug("Disconnected, wireframe data reset");
        });

        Architect.LOGGER.info("STRUTTURA: Architect client initialized");
    }
}
