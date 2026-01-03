package it.magius.struttura.architect;

import it.magius.struttura.architect.client.KeybindingHandler;
import it.magius.struttura.architect.client.ModKeybindings;
import it.magius.struttura.architect.client.ScreenshotCapture;
import it.magius.struttura.architect.client.WireframeRenderer;
import it.magius.struttura.architect.client.gui.PanelManager;
import it.magius.struttura.architect.client.gui.StrutturaHud;
import it.magius.struttura.architect.client.gui.panel.MainPanel;
import it.magius.struttura.architect.network.BlockListPacket;
import it.magius.struttura.architect.network.BlockPositionsSyncPacket;
import it.magius.struttura.architect.network.ConstructionListPacket;
import it.magius.struttura.architect.network.EditingInfoPacket;
import it.magius.struttura.architect.network.ModRequirementsPacket;
import it.magius.struttura.architect.network.ScreenshotRequestPacket;
import it.magius.struttura.architect.network.TranslationsPacket;
import it.magius.struttura.architect.network.WireframeSyncPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class ArchitectClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Architect.LOGGER.info("STRUTTURA: Architect client initializing...");

        // Registra i keybindings
        ModKeybindings.register();

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
                WireframeRenderer.setBlockPositions(packet.solidBlocks(), packet.airBlocks(), packet.previewBlocks());
                Architect.LOGGER.debug("Received block positions: {} solid, {} air, {} preview",
                    packet.solidBlocks().size(), packet.airBlocks().size(), packet.previewBlocks().size());
            });
        });

        // Registra il receiver per le richieste di screenshot
        ClientPlayNetworking.registerGlobalReceiver(ScreenshotRequestPacket.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                Architect.LOGGER.debug("Received screenshot request for {}", packet.constructionId());
                ScreenshotCapture.requestCapture(packet.constructionId(), packet.title());
            });
        });

        // Registra il receiver per le informazioni di editing
        ClientPlayNetworking.registerGlobalReceiver(EditingInfoPacket.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                PanelManager pm = PanelManager.getInstance();
                if (packet.isEditing()) {
                    pm.updateEditingInfo(
                            packet.constructionId(),
                            packet.title(),
                            packet.blockCount(),
                            packet.solidBlockCount(),
                            packet.airBlockCount(),
                            packet.entityCount(),
                            packet.mobCount(),
                            packet.bounds(),
                            packet.mode(),
                            packet.shortDesc()
                    );
                } else {
                    pm.clearEditingInfo();
                }
                Architect.LOGGER.debug("Received editing info: editing={}, id={}",
                        packet.isEditing(), packet.constructionId());
            });
        });

        // Registra il receiver per la lista costruzioni
        ClientPlayNetworking.registerGlobalReceiver(ConstructionListPacket.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                List<MainPanel.ConstructionInfo> list = new ArrayList<>();
                for (ConstructionListPacket.ConstructionInfo info : packet.constructions()) {
                    list.add(new MainPanel.ConstructionInfo(
                            info.id(),
                            info.title(),
                            info.blockCount(),
                            info.entityCount(),
                            info.isBeingEdited()
                    ));
                }
                PanelManager.getInstance().getMainPanel().updateConstructionList(list);
                Architect.LOGGER.debug("Received construction list: {} items", list.size());
            });
        });

        // Registra il receiver per i mod richiesti (prima del pull)
        ClientPlayNetworking.registerGlobalReceiver(ModRequirementsPacket.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                PanelManager.getInstance().getMainPanel().handleModRequirements(
                    packet.constructionId(),
                    packet.requiredMods()
                );
                Architect.LOGGER.debug("Received mod requirements for {}: {} mods",
                    packet.constructionId(), packet.requiredMods().size());
            });
        });

        // Registra il receiver per la lista blocchi (per dropdown in editing panel)
        ClientPlayNetworking.registerGlobalReceiver(BlockListPacket.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                List<PanelManager.BlockInfo> blocks = new ArrayList<>();
                for (BlockListPacket.BlockInfo info : packet.blocks()) {
                    blocks.add(new PanelManager.BlockInfo(
                            info.blockId(),
                            info.displayName(),
                            info.count()
                    ));
                }
                PanelManager.getInstance().updateBlockList(blocks);
                Architect.LOGGER.debug("Received block list: {} types", blocks.size());
            });
        });

        // Registra il receiver per le traduzioni (titles e shortDescriptions)
        ClientPlayNetworking.registerGlobalReceiver(TranslationsPacket.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                PanelManager.getInstance().updateTranslations(packet.titles(), packet.shortDescriptions());
                Architect.LOGGER.debug("Received translations: {} titles, {} short descs",
                        packet.titles().size(), packet.shortDescriptions().size());
            });
        });

        // Registra l'evento per la cattura screenshot (deve essere su render tick)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ScreenshotCapture.onRenderTick();
        });

        // Registra l'handler per i keybindings di selezione
        ClientTickEvents.END_CLIENT_TICK.register(KeybindingHandler::onClientTick);

        // Inizializza il renderer wireframe (registra WorldRenderEvents)
        WireframeRenderer.init();

        // Inizializza l'HUD (registra HudRenderCallback)
        StrutturaHud.init();

        // Pulisci i dati quando ci disconnettiamo
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            WireframeRenderer.reset();
            PanelManager.getInstance().reset();
            Architect.LOGGER.debug("Disconnected, wireframe and GUI data reset");
        });

        Architect.LOGGER.info("STRUTTURA: Architect client initialized");
    }
}
