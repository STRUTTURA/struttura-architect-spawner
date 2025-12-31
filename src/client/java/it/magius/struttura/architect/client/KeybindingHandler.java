package it.magius.struttura.architect.client;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.gui.PanelManager;
import it.magius.struttura.architect.network.GuiActionPacket;
import it.magius.struttura.architect.network.SelectionKeyPacket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Gestisce la pressione dei tasti per i comandi di selezione.
 * Invia i packet al server quando i tasti vengono premuti.
 */
@Environment(EnvType.CLIENT)
public class KeybindingHandler {

    /**
     * Chiamato ogni tick del client per controllare i keybindings.
     */
    public static void onClientTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }

        // Non processare i tasti se una GUI vanilla è aperta
        if (client.screen != null) {
            return;
        }

        // Controlla il keybinding per il toggle GUI (sempre attivo)
        while (ModKeybindings.TOGGLE_GUI.consumeClick()) {
            PanelManager pm = PanelManager.getInstance();
            pm.toggleMainPanel();

            // Se stiamo aprendo il pannello, richiedi la lista costruzioni
            if (pm.isMainPanelOpen()) {
                ClientPlayNetworking.send(new GuiActionPacket("request_list", "", ""));
            }
            Architect.LOGGER.debug("Toggle GUI: {}", pm.isMainPanelOpen() ? "open" : "closed");
        }

        // Controlla se siamo in editing (wireframe attivo)
        if (!WireframeRenderer.isConstructionActive()) {
            return;
        }

        // Controlla i keybindings di selezione
        while (ModKeybindings.SELECT_POS1.consumeClick()) {
            sendSelectionAction(SelectionKeyPacket.Action.POS1);
        }

        while (ModKeybindings.SELECT_POS2.consumeClick()) {
            sendSelectionAction(SelectionKeyPacket.Action.POS2);
        }

        while (ModKeybindings.SELECT_CLEAR.consumeClick()) {
            sendSelectionAction(SelectionKeyPacket.Action.CLEAR);
        }

        while (ModKeybindings.SELECT_APPLY.consumeClick()) {
            sendSelectionAction(SelectionKeyPacket.Action.APPLY);
        }

        while (ModKeybindings.SELECT_APPLYALL.consumeClick()) {
            sendSelectionAction(SelectionKeyPacket.Action.APPLYALL);
        }
    }

    private static void sendSelectionAction(SelectionKeyPacket.Action action) {
        SelectionKeyPacket packet = new SelectionKeyPacket(action);
        ClientPlayNetworking.send(packet);
        Architect.LOGGER.debug("Sent selection key action: {}", action);
    }
}
