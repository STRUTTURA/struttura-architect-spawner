package it.magius.struttura.architect.session;

import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EditMode;
import it.magius.struttura.architect.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Rappresenta una sessione di editing attiva per un giocatore.
 * Tiene traccia della costruzione corrente, della modalita' e dei blocchi modificati.
 */
public class EditingSession {

    // Sessioni attive per giocatore
    private static final Map<UUID, EditingSession> ACTIVE_SESSIONS = new HashMap<>();

    // Giocatore proprietario della sessione
    private final ServerPlayer player;

    // Costruzione in editing
    private final Construction construction;

    // Modalita' corrente (ADD o REMOVE)
    private EditMode mode = EditMode.ADD;

    // Stanza corrente (null = costruzione base)
    private String currentRoom = null;

    public EditingSession(ServerPlayer player, Construction construction) {
        this.player = player;
        this.construction = construction;
    }

    /**
     * Ottiene la sessione attiva per un giocatore.
     */
    public static EditingSession getSession(ServerPlayer player) {
        return ACTIVE_SESSIONS.get(player.getUUID());
    }

    /**
     * Ottiene la sessione attiva per UUID.
     */
    public static EditingSession getSession(UUID playerId) {
        return ACTIVE_SESSIONS.get(playerId);
    }

    /**
     * Verifica se un giocatore ha una sessione attiva.
     */
    public static boolean hasSession(ServerPlayer player) {
        return ACTIVE_SESSIONS.containsKey(player.getUUID());
    }

    /**
     * Inizia una nuova sessione per un giocatore.
     */
    public static EditingSession startSession(ServerPlayer player, Construction construction) {
        EditingSession session = new EditingSession(player, construction);
        ACTIVE_SESSIONS.put(player.getUUID(), session);
        return session;
    }

    /**
     * Termina la sessione per un giocatore.
     */
    public static EditingSession endSession(ServerPlayer player) {
        return ACTIVE_SESSIONS.remove(player.getUUID());
    }

    /**
     * Ottiene tutte le sessioni attive.
     */
    public static java.util.Collection<EditingSession> getAllSessions() {
        return ACTIVE_SESSIONS.values();
    }

    /**
     * Gestisce il piazzamento di un blocco.
     */
    public void onBlockPlaced(BlockPos pos, BlockState state) {
        if (mode == EditMode.ADD) {
            construction.addBlock(pos, state);
            // Aggiorna il wireframe (i bounds potrebbero essere cambiati)
            NetworkHandler.sendWireframeSync(player);
        }
        // In mode REMOVE il piazzamento non fa nulla di speciale
    }

    /**
     * Gestisce la rottura di un blocco.
     */
    public void onBlockBroken(BlockPos pos, BlockState previousState) {
        if (mode == EditMode.ADD) {
            // In ADD, rompere un blocco aggiunge aria alla costruzione
            construction.addBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            // Aggiorna il wireframe (i bounds potrebbero essere cambiati)
            NetworkHandler.sendWireframeSync(player);
        } else {
            // In REMOVE, rompere un blocco lo rimuove dalla costruzione
            construction.removeBlock(pos);
            // Aggiorna il wireframe (i bounds sono stati ricalcolati)
            NetworkHandler.sendWireframeSync(player);
        }
    }

    // Getters e Setters
    public ServerPlayer getPlayer() { return player; }
    public Construction getConstruction() { return construction; }

    public EditMode getMode() { return mode; }
    public void setMode(EditMode mode) { this.mode = mode; }

    public String getCurrentRoom() { return currentRoom; }
    public void setCurrentRoom(String currentRoom) { this.currentRoom = currentRoom; }

    public boolean isInRoom() { return currentRoom != null; }
}
