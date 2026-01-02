package it.magius.struttura.architect.session;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EditMode;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.network.NetworkHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
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

    // Costruzione in editing (può essere sostituita durante rename)
    private Construction construction;

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
     * Cattura automaticamente le entità e gli NBT dei block entities prima di terminare.
     */
    public static EditingSession endSession(ServerPlayer player) {
        EditingSession session = ACTIVE_SESSIONS.remove(player.getUUID());
        if (session != null) {
            // Cattura NBT dei block entities (casse, furnace, etc.)
            session.captureBlockEntityNbt();
            // Cattura entità automaticamente prima di terminare
            session.captureEntities();
        }
        return session;
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
            // Aggiorna info editing per la GUI
            NetworkHandler.sendEditingInfo(player);
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
            // Aggiorna info editing per la GUI
            NetworkHandler.sendEditingInfo(player);
        } else {
            // In REMOVE, rompere un blocco lo rimuove dalla costruzione
            construction.removeBlock(pos);
            // Aggiorna il wireframe (i bounds sono stati ricalcolati)
            NetworkHandler.sendWireframeSync(player);
            // Aggiorna info editing per la GUI
            NetworkHandler.sendEditingInfo(player);
        }
    }

    /**
     * Cattura tutte le entità nell'area dei bounds della costruzione.
     * Esclude Player e Projectile (come definito in EntityData.shouldSaveEntity).
     * Chiamato automaticamente durante il save/exit.
     */
    public void captureEntities() {
        var bounds = construction.getBounds();

        // Se non ci sono bounds validi, non possiamo catturare entità
        if (!bounds.isValid()) {
            Architect.LOGGER.debug("Cannot capture entities: bounds not valid for {}", construction.getId());
            return;
        }

        // Ottieni il ServerLevel dal player (MC 1.21: player.level() invece di player.serverLevel())
        ServerLevel world = (ServerLevel) player.level();

        // Crea AABB dai bounds (aggiungi 1 per includere il blocco completo)
        AABB area = new AABB(
            bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
            bounds.getMaxX() + 1, bounds.getMaxY() + 1, bounds.getMaxZ() + 1
        );

        // Cattura tutte le entità nell'area che passano il filtro
        List<Entity> entities = world.getEntities(
            (Entity) null,
            area,
            EntityData::shouldSaveEntity
        );

        // Pulisci le entità precedenti
        construction.clearEntities();

        // Aggiungi ogni entità catturata
        // Passa il registryAccess per serializzare correttamente gli ItemStack (armor stand equipment, etc.)
        for (Entity entity : entities) {
            EntityData data = EntityData.fromEntity(entity, bounds, world.registryAccess());
            construction.addEntity(entity.getUUID(), data);
        }

        Architect.LOGGER.info("Captured {} entities for construction {}",
            entities.size(), construction.getId());
    }

    /**
     * Cattura gli NBT di tutti i block entities nella costruzione.
     * Salva il contenuto di casse, furnace, hoppers, etc.
     * Chiamato automaticamente durante il save/exit.
     */
    public void captureBlockEntityNbt() {
        // Ottieni il ServerLevel dal player
        ServerLevel world = (ServerLevel) player.level();

        int capturedCount = 0;

        // Itera su tutti i blocchi della costruzione
        for (BlockPos pos : construction.getBlocks().keySet()) {
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (blockEntity != null) {
                // Salva l'NBT del block entity
                CompoundTag nbt = blockEntity.saveWithoutMetadata(world.registryAccess());

                // Rimuovi tag che non dovrebbero essere copiati
                nbt.remove("x");
                nbt.remove("y");
                nbt.remove("z");
                nbt.remove("id");  // Il tipo del block entity sarà dedotto dal blocco

                // Salva solo se l'NBT contiene dati utili
                if (!nbt.isEmpty()) {
                    construction.setBlockEntityNbt(pos, nbt);
                    capturedCount++;
                }
            }
        }

        Architect.LOGGER.info("Captured {} block entity NBTs for construction {}",
            capturedCount, construction.getId());
    }

    // Getters e Setters
    public ServerPlayer getPlayer() { return player; }
    public Construction getConstruction() { return construction; }
    public void setConstruction(Construction construction) { this.construction = construction; }

    public EditMode getMode() { return mode; }
    public void setMode(EditMode mode) { this.mode = mode; }

    public String getCurrentRoom() { return currentRoom; }
    public void setCurrentRoom(String currentRoom) { this.currentRoom = currentRoom; }

    public boolean isInRoom() { return currentRoom != null; }
}
