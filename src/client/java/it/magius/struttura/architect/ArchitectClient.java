package it.magius.struttura.architect;

import it.magius.struttura.architect.client.ClientCommands;
import it.magius.struttura.architect.client.KeybindingHandler;
import it.magius.struttura.architect.client.ModKeybindings;
import it.magius.struttura.architect.client.ScreenshotCapture;
import it.magius.struttura.architect.client.WireframeRenderer;
import it.magius.struttura.architect.client.gui.PanelManager;
import it.magius.struttura.architect.client.gui.StrutturaHud;
import it.magius.struttura.architect.client.gui.panel.MainPanel;
import it.magius.struttura.architect.client.gui.InGameSetupScreen;
import it.magius.struttura.architect.client.ingame.InGameClientState;
import it.magius.struttura.architect.network.BlockListPacket;
import it.magius.struttura.architect.network.BlockPositionsSyncPacket;
import it.magius.struttura.architect.network.ConstructionListPacket;
import it.magius.struttura.architect.network.EditingInfoPacket;
import it.magius.struttura.architect.network.InGameBuildingPacket;
import it.magius.struttura.architect.network.InGameListsPacket;
import it.magius.struttura.architect.network.ModRequirementsPacket;
import it.magius.struttura.architect.network.OpenOptionsPacket;
import it.magius.struttura.architect.network.ScreenshotRequestPacket;
import it.magius.struttura.architect.network.TranslationsPacket;
import it.magius.struttura.architect.network.WireframeSyncPacket;
import it.magius.struttura.architect.client.gui.StrutturaSettingsScreen;
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

        // Registra i comandi client-side
        ClientCommands.register();

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
                WireframeRenderer.setRoomBlockPositions(packet.roomBlocks());
                Architect.LOGGER.debug("Received block positions: {} solid, {} air, {} preview, {} room",
                    packet.solidBlocks().size(), packet.airBlocks().size(), packet.previewBlocks().size(), packet.roomBlocks().size());
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
                    // Convert roomList from packet to PanelManager format
                    java.util.List<PanelManager.RoomInfo> roomList = new java.util.ArrayList<>();
                    for (EditingInfoPacket.RoomInfo ri : packet.roomList()) {
                        roomList.add(new PanelManager.RoomInfo(ri.id(), ri.name(), ri.blockCount(), ri.entityCount()));
                    }
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
                            packet.shortDesc(),
                            packet.inRoom(),
                            packet.currentRoomId(),
                            packet.currentRoomName(),
                            packet.roomCount(),
                            packet.roomBlockChanges(),
                            roomList
                    );
                    // Update entrance anchor data
                    if (packet.hasEntrance()) {
                        pm.setEntrance(packet.entranceX(), packet.entranceY(), packet.entranceZ(), packet.entranceYaw());
                    } else {
                        pm.clearEntrance();
                    }
                    // Aggiorna WireframeRenderer con lo stato della stanza
                    WireframeRenderer.setInRoomEditing(packet.inRoom());
                } else {
                    pm.clearEditingInfo();
                    WireframeRenderer.setInRoomEditing(false);
                }
                Architect.LOGGER.debug("Received editing info: editing={}, id={}, inRoom={}, roomId={}",
                        packet.isEditing(), packet.constructionId(), packet.inRoom(), packet.currentRoomId());
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

        // Registra il receiver per la lista blocchi e entità (per dropdown in editing panel)
        ClientPlayNetworking.registerGlobalReceiver(BlockListPacket.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                // Update block list
                List<PanelManager.BlockInfo> blocks = new ArrayList<>();
                for (BlockListPacket.BlockInfo info : packet.blocks()) {
                    blocks.add(new PanelManager.BlockInfo(
                            info.blockId(),
                            info.displayName(),
                            info.count()
                    ));
                }
                PanelManager.getInstance().updateBlockList(blocks);

                // Update entity list (grouped by type with count)
                List<PanelManager.EntityInfo> entities = new ArrayList<>();
                for (BlockListPacket.EntityInfo info : packet.entities()) {
                    entities.add(new PanelManager.EntityInfo(
                            info.entityType(),
                            info.displayName(),
                            info.count()
                    ));
                }
                PanelManager.getInstance().updateEntityList(entities);

                Architect.LOGGER.debug("Received block list: {} types, {} entities", blocks.size(), entities.size());
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

        // Registra il receiver per lo stato building InGame
        ClientPlayNetworking.registerGlobalReceiver(InGameBuildingPacket.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                InGameClientState state = InGameClientState.getInstance();
                if (packet.inBuilding()) {
                    state.enterBuilding(packet.rdns(), packet.pk(), packet.hasLiked());
                    Architect.LOGGER.debug("Entered building: {} (pk={})", packet.rdns(), packet.pk());
                } else {
                    state.exitBuilding();
                    Architect.LOGGER.debug("Exited building");
                }
            });
        });

        // Registra il receiver per la lista InGame (mostra setup screen)
        ClientPlayNetworking.registerGlobalReceiver(InGameListsPacket.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                Architect.LOGGER.info("Received InGame lists packet with {} lists", packet.lists().size());
                // Show the setup screen
                context.client().setScreen(new InGameSetupScreen(packet.lists(), packet.isNewWorld()));
            });
        });

        // Registra il receiver per aprire la schermata opzioni (/struttura options)
        ClientPlayNetworking.registerGlobalReceiver(OpenOptionsPacket.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                Architect.LOGGER.debug("Opening settings screen via packet");
                context.client().setScreen(new StrutturaSettingsScreen(null));
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
            InGameClientState.getInstance().reset();
            Architect.LOGGER.debug("Disconnected, wireframe and GUI data reset");
        });

        Architect.LOGGER.info("STRUTTURA: Architect client initialized");
    }
}
