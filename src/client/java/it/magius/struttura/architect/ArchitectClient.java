package it.magius.struttura.architect;

import it.magius.struttura.architect.client.WireframeRenderer;
import it.magius.struttura.architect.network.WireframeSyncPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
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

        // TODO: Registrare rendering quando l'API sarà implementata
        // Le WorldRenderEvents sono state reimplementate in Fabric API 0.140+
        // nel nuovo package: net.fabricmc.fabric.api.client.rendering.v1.world
        // L'evento BEFORE_DEBUG_RENDER o AFTER_ENTITIES sono adatti per wireframe

        // Pulisci i dati quando ci disconnettiamo
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            WireframeRenderer.reset();
            Architect.LOGGER.debug("Disconnected, wireframe data reset");
        });

        Architect.LOGGER.info("STRUTTURA: Architect client initialized");
    }
}
