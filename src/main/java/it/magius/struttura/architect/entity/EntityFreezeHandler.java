package it.magius.struttura.architect.entity;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ConstructionBounds;
import it.magius.struttura.architect.registry.ConstructionRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gestisce il freeze delle entità nei bounds delle costruzioni.
 * Le entità nei bounds di QUALSIASI costruzione non possono muoversi.
 * Questo vale sempre, non solo durante l'editing.
 */
public class EntityFreezeHandler {

    private static EntityFreezeHandler INSTANCE;

    // Mappa delle posizioni salvate per le entità freezate
    // UUID entità -> posizione salvata
    private final Map<UUID, Vec3> frozenPositions = new HashMap<>();

    private EntityFreezeHandler() {
    }

    public static EntityFreezeHandler getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new EntityFreezeHandler();
        }
        return INSTANCE;
    }

    /**
     * Registra il tick event per bloccare le entità.
     * Chiamato durante l'inizializzazione del mod.
     */
    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        Architect.LOGGER.info("EntityFreezeHandler registered");
    }

    /**
     * Chiamato ogni tick del server.
     * Blocca le entità nei bounds di TUTTE le costruzioni registrate.
     */
    private void onServerTick(MinecraftServer server) {
        var registry = ConstructionRegistry.getInstance();
        var allIds = registry.getAllIds();

        // Se non ci sono costruzioni, non fare nulla
        if (allIds.isEmpty()) {
            frozenPositions.clear();
            return;
        }

        // Ottieni il livello overworld (dove sono le costruzioni)
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }

        // Set per tenere traccia delle entità che devono rimanere freezate
        Set<UUID> entitiesInBounds = new HashSet<>();

        // Per ogni costruzione, blocca le entità nei bounds
        for (String id : allIds) {
            Construction construction = registry.get(id);
            if (construction == null) {
                continue;
            }

            var bounds = construction.getBounds();
            if (!bounds.isValid()) {
                continue;
            }

            freezeEntitiesInBounds(level, bounds, entitiesInBounds);
        }

        // Rimuovi le posizioni salvate per entità che non sono più in nessun bounds
        // e riabilita la loro AI
        Set<UUID> toRemove = new HashSet<>();
        for (UUID entityId : frozenPositions.keySet()) {
            if (!entitiesInBounds.contains(entityId)) {
                toRemove.add(entityId);
                // Trova l'entità e riabilita l'AI
                Entity entity = level.getEntity(entityId);
                if (entity instanceof Mob mob) {
                    mob.setNoAi(false);
                }
            }
        }
        for (UUID entityId : toRemove) {
            frozenPositions.remove(entityId);
        }
    }

    /**
     * Blocca tutte le entità viventi nei bounds specificati.
     * Aggiunge gli UUID al set per tracking.
     */
    private void freezeEntitiesInBounds(ServerLevel level, ConstructionBounds bounds, Set<UUID> entitiesInBounds) {
        // Crea AABB dai bounds
        AABB area = new AABB(
            bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
            bounds.getMaxX() + 1, bounds.getMaxY() + 1, bounds.getMaxZ() + 1
        );

        // Ottieni tutte le entità viventi nell'area (escludi player e proiettili)
        List<Entity> entities = level.getEntities(
            (Entity) null,
            area,
            entity -> entity instanceof LivingEntity &&
                      !(entity instanceof Player) &&
                      !(entity instanceof Projectile)
        );

        for (Entity entity : entities) {
            UUID entityId = entity.getUUID();
            entitiesInBounds.add(entityId);

            // Salva la posizione iniziale se non già salvata
            if (!frozenPositions.containsKey(entityId)) {
                frozenPositions.put(entityId, entity.position());
            }

            Vec3 savedPos = frozenPositions.get(entityId);

            // Riporta l'entità alla posizione salvata
            entity.setPos(savedPos.x, savedPos.y, savedPos.z);

            // Azzera velocità
            entity.setDeltaMovement(Vec3.ZERO);

            // Se è un mob, disabilita l'AI
            if (entity instanceof Mob mob) {
                mob.setNoAi(true);
            }
        }
    }

    /**
     * Sblocca un'entità specifica (rimuove dal tracking).
     * Chiamato quando un'entità viene uccisa/rimossa.
     */
    public void unfreezeEntity(UUID entityId) {
        frozenPositions.remove(entityId);
    }

    /**
     * Aggiorna la posizione salvata di un'entità.
     * Chiamato quando un'entità viene aggiunta alla costruzione nella sua nuova posizione.
     */
    public void updateFrozenPosition(UUID entityId, Vec3 newPos) {
        frozenPositions.put(entityId, newPos);
    }
}
