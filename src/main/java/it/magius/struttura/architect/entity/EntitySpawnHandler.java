package it.magius.struttura.architect.entity;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.model.Room;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.session.EditingSession;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Gestisce l'aggiunta automatica di entità spawnate dal giocatore
 * all'interno dei bounds di una costruzione in editing.
 *
 * IMPORTANTE: Aggiunge automaticamente SOLO entità appena spawnate (nuove).
 * Le entità già esistenti nel mondo non vengono aggiunte automaticamente.
 */
public class EntitySpawnHandler {

    private static EntitySpawnHandler INSTANCE;

    // Set di entità già viste - per evitare di riaggiungere entità già esistenti
    // quando ENTITY_LOAD viene chiamato per altri motivi (es. cambio chunk)
    private final Set<UUID> knownEntities = new HashSet<>();

    // Set di entità da ignorare temporaneamente (spawnate dal sistema di editing)
    // Queste entità non devono essere aggiunte automaticamente alla construction/room
    private final Set<UUID> ignoredEntities = new HashSet<>();

    private EntitySpawnHandler() {
    }

    /**
     * Registra un'entità da ignorare (non deve essere aggiunta automaticamente).
     * Usato quando il sistema spawna entità durante l'editing (enterRoom, show, etc.)
     */
    public void ignoreEntity(UUID entityId) {
        ignoredEntities.add(entityId);
        knownEntities.add(entityId); // Segnala anche come conosciuta
    }

    /**
     * Rimuove un'entità dalla lista di quelle ignorate.
     */
    public void unignoreEntity(UUID entityId) {
        ignoredEntities.remove(entityId);
    }

    public static EntitySpawnHandler getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new EntitySpawnHandler();
        }
        return INSTANCE;
    }

    /**
     * Registra l'evento di spawn entità.
     * Chiamato durante l'inizializzazione del mod.
     */
    public void register() {
        ServerEntityEvents.ENTITY_LOAD.register(this::onEntityLoad);
        Architect.LOGGER.info("EntitySpawnHandler registered");
    }

    /**
     * Chiamato quando un'entità viene caricata/spawnata nel mondo.
     */
    private void onEntityLoad(Entity entity, ServerLevel level) {
        // Ignora player e proiettili
        if (entity instanceof Player || entity instanceof Projectile) {
            return;
        }

        // Accept all entity types (mobs, item frames, armor stands, paintings, etc.)
        // The previous LivingEntity filter was too restrictive

        UUID entityId = entity.getUUID();

        // Se l'entità è nella lista di quelle da ignorare (spawnate dal sistema), non fare nulla
        if (ignoredEntities.contains(entityId)) {
            return;
        }

        // Se l'entità è già conosciuta, non fare nulla
        // Questo evita di riaggiungere entità quando ENTITY_LOAD viene chiamato
        // per motivi diversi dallo spawn (es. cambio chunk, setPos del freeze handler)
        if (knownEntities.contains(entityId)) {
            return;
        }

        // Segna l'entità come conosciuta
        knownEntities.add(entityId);

        // Cerca una sessione di editing il cui player è nelle vicinanze
        // e i cui bounds contengono l'entità appena spawnata
        for (EditingSession session : EditingSession.getAllSessions()) {
            ServerPlayer editingPlayer = session.getPlayer();
            if (editingPlayer == null) {
                continue;
            }

            // Verifica che il player sia nello stesso livello
            if (editingPlayer.level() != level) {
                continue;
            }

            Construction construction = session.getConstruction();
            var bounds = construction.getBounds();

            if (!bounds.isValid()) {
                continue;
            }

            // Verifica se l'entità è nei bounds della costruzione
            int entityX = entity.blockPosition().getX();
            int entityY = entity.blockPosition().getY();
            int entityZ = entity.blockPosition().getZ();

            if (entityX >= bounds.getMinX() && entityX <= bounds.getMaxX() &&
                entityY >= bounds.getMinY() && entityY <= bounds.getMaxY() &&
                entityZ >= bounds.getMinZ() && entityZ <= bounds.getMaxZ()) {

                // L'entità è nei bounds - aggiungila automaticamente
                addEntityToSession(entity, session, editingPlayer, level);
                return; // Solo una costruzione può contenere l'entità
            }
        }
    }

    /**
     * Adds an entity to the construction or room in editing.
     * Uses the same logic as right-click with hammer.
     */
    private void addEntityToSession(Entity entity, EditingSession session, ServerPlayer player, ServerLevel level) {
        Construction construction = session.getConstruction();
        boolean inRoom = session.isInRoom();
        Room room = inRoom ? session.getCurrentRoomObject() : null;

        UUID entityId = entity.getUUID();

        // Check if entity is already tracked (shouldn't be, but verify)
        if (session.isEntityTracked(entityId)) {
            // Already tracked, do nothing
            return;
        }

        // Add the entity
        EntityData data = EntityData.fromEntity(entity, construction.getBounds(), level.registryAccess());

        int newIndex;
        if (inRoom && room != null) {
            newIndex = room.addEntity(data);
        } else {
            newIndex = construction.addEntity(data);
        }
        // Track the entity with its list index (session tracking for UI)
        session.trackEntity(entityId, newIndex);
        // Track the entity UUID in construction (for refreshEntitiesFromWorld before push)
        construction.trackSpawnedEntity(entityId);

        // Expand bounds if entity is outside (shouldn't happen, but for safety)
        construction.getBounds().expandToInclude(entity.blockPosition());

        // Notify the player
        String targetName = formatTargetName(session);
        int entityCount = inRoom && room != null ? room.getEntityCount() : construction.getEntityCount();
        player.sendSystemMessage(Component.literal(targetName + ": §a" +
                I18n.tr(player, "entity.added") + " §7(" + entityCount + ")"));

        // Update UI
        NetworkHandler.sendWireframeSync(player);
        NetworkHandler.sendEditingInfo(player);

        Architect.LOGGER.debug("Auto-added spawned entity {} to {} (room: {})",
            entity.getType().getDescriptionId(), construction.getId(), inRoom ? room.getId() : "base");
    }

    /**
     * Formatta il nome del target corrente (edificio o edificio/stanza) con colori.
     */
    private String formatTargetName(EditingSession session) {
        String constructionId = session.getConstruction().getId();
        String shortName = constructionId.contains(".")
            ? constructionId.substring(constructionId.lastIndexOf('.') + 1)
            : constructionId;

        if (session.isInRoom()) {
            Room room = session.getCurrentRoomObject();
            String roomName = room != null ? room.getName() : session.getCurrentRoom();
            return "§d" + shortName + "§7/§e" + roomName;
        } else {
            return "§d" + shortName;
        }
    }
}
