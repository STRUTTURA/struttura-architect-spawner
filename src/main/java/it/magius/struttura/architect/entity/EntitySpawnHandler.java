package it.magius.struttura.architect.entity;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.ChatMessages;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EditMode;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.model.Room;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.session.EditingSession;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Handles auto-adding entities spawned by the player
 * inside the bounds of a construction being edited.
 *
 * IMPORTANT: Only auto-adds newly spawned entities.
 * Existing entities in the world are not auto-added.
 */
public class EntitySpawnHandler {

    private static EntitySpawnHandler INSTANCE;

    // Set of already-seen entities - to avoid re-adding existing entities
    // when ENTITY_LOAD is called for other reasons (e.g., chunk change)
    private final Set<UUID> knownEntities = new HashSet<>();

    // Set of entities to temporarily ignore (spawned by the editing system)
    // These entities should not be auto-added to the construction/room
    private final Set<UUID> ignoredEntities = new HashSet<>();

    private EntitySpawnHandler() {
    }

    /**
     * Registers an entity to ignore (should not be auto-added).
     * Used when the system spawns entities during editing (enterRoom, show, etc.)
     */
    public void ignoreEntity(UUID entityId) {
        ignoredEntities.add(entityId);
        knownEntities.add(entityId);
    }

    /**
     * Removes an entity from the ignore list.
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
     * Registers the entity spawn event.
     * Called during mod initialization.
     */
    public void register() {
        ServerEntityEvents.ENTITY_LOAD.register(this::onEntityLoad);
        Architect.LOGGER.info("EntitySpawnHandler registered");
    }

    /**
     * Called when an entity is loaded/spawned in the world.
     */
    private void onEntityLoad(Entity entity, ServerLevel level) {
        // Ignore player, projectiles and dropped items
        if (entity instanceof Player || entity instanceof Projectile || entity instanceof ItemEntity) {
            return;
        }

        // Ignore MARKER entities (used internally by Minecraft for templates)
        if (entity.getType().toString().contains("marker")) {
            return;
        }

        UUID entityId = entity.getUUID();

        // If the entity is in the ignore list (spawned by editing system), do nothing
        if (ignoredEntities.contains(entityId)) {
            return;
        }

        // If the entity is already known, do nothing
        if (knownEntities.contains(entityId)) {
            return;
        }

        // Mark the entity as known
        knownEntities.add(entityId);

        // Find an editing session whose player is nearby and in ADD mode
        for (EditingSession session : EditingSession.getAllSessions()) {
            ServerPlayer editingPlayer = session.getPlayer();
            if (editingPlayer == null) {
                continue;
            }

            // Only auto-add in ADD mode
            if (session.getMode() != EditMode.ADD) {
                continue;
            }

            // Verify player is in the same level
            if (editingPlayer.level() != level) {
                continue;
            }

            // Verify the entity is near the editing player (within interaction range)
            double distance = editingPlayer.distanceToSqr(entity);
            if (distance > 100.0) { // ~10 blocks
                continue;
            }

            // Auto-add the entity to the session
            addEntityToSession(entity, session, editingPlayer, level);
            return;
        }
    }

    /**
     * Adds an entity to the construction or room in editing.
     * Simply adds the UUID to tracked entities - no EntityData creation needed.
     */
    private void addEntityToSession(Entity entity, EditingSession session, ServerPlayer player, ServerLevel level) {
        Construction construction = session.getConstruction();
        boolean inRoom = session.isInRoom();
        Room room = inRoom ? session.getCurrentRoomObject() : null;

        UUID entityId = entity.getUUID();

        // Check if entity is already tracked
        if (session.isEntityTracked(entityId)) {
            return;
        }

        // Expand bounds to include the entity
        EntityData.expandBoundsForEntity(entity, construction.getBounds());

        // Add entity UUID to construction or room
        if (inRoom && room != null) {
            room.addEntity(entityId);
        } else {
            construction.addEntity(entityId, level);
        }

        // Freeze the entity immediately to prevent it from moving/falling
        entity.setDeltaMovement(Vec3.ZERO);
        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
        }
        EntityFreezeHandler.getInstance().updateFrozenPosition(entityId, entity.position());

        // Notify the player
        String targetName = ChatMessages.formatTargetName(player, session);
        int entityCount = inRoom && room != null ? room.getEntityCount() : construction.getEntityCount();
        ChatMessages.sendTarget(player, targetName, ChatMessages.Level.INFO,
                I18n.tr(player, "entity.added") + " §7(" + entityCount + ")");

        // Update UI
        NetworkHandler.sendWireframeSync(player);
        NetworkHandler.sendEditingInfo(player);

        Architect.LOGGER.debug("Auto-added spawned entity {} to {} (room: {})",
            entity.getType().getDescriptionId(), construction.getId(), inRoom ? room.getId() : "base");
    }

}
