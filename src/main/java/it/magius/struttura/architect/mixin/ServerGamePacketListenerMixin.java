package it.magius.struttura.architect.mixin;

import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.item.ConstructionHammerItem;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.model.Room;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.session.EditingSession;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mixin per intercettare le interazioni con le entità.
 * Gestisce:
 * - Click destro con hammer: aggiunge/rimuove entità dalla costruzione/room
 * - Click sinistro su entità non inclusa: uccide senza drop
 * - Click sinistro su entità inclusa: impedisce l'uccisione
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    // Costanti per tipo di interazione
    @Unique
    private static final int INTERACTION_NONE = 0;
    @Unique
    private static final int INTERACTION_RIGHT_CLICK = 1;
    @Unique
    private static final int INTERACTION_ATTACK = 2;

    // Cooldown per evitare doppi packet - chiave: "playerUUID:entityUUID:type" -> timestamp
    @Unique
    private static final Map<String, Long> interactionCooldowns = new HashMap<>();
    @Unique
    private static final long COOLDOWN_MS = 200; // 200ms di cooldown

    /**
     * Intercetta tutte le interazioni con le entità.
     */
    @Inject(
        method = "handleInteract",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onHandleInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) player.level();
        Entity targetEntity = packet.getTarget(level);

        if (targetEntity == null) {
            return;
        }

        // Non gestire player o proiettili
        if (targetEntity instanceof Player || targetEntity instanceof Projectile) {
            return;
        }

        EditingSession session = EditingSession.getSession(player);
        if (session == null) {
            return;
        }

        ItemStack heldItem = player.getMainHandItem();
        boolean holdingHammer = heldItem.getItem() instanceof ConstructionHammerItem;

        if (!holdingHammer) {
            return;
        }

        // Determina il tipo di interazione usando AtomicInteger
        // Usa AtomicBoolean per fermarsi alla prima chiamata del dispatch
        AtomicInteger interactionType = new AtomicInteger(INTERACTION_NONE);
        AtomicBoolean alreadySet = new AtomicBoolean(false);

        packet.dispatch(new ServerboundInteractPacket.Handler() {
            @Override
            public void onInteraction(InteractionHand hand) {
                // Click destro senza posizione specifica - solo se non già settato
                if (alreadySet.compareAndSet(false, true)) {
                    interactionType.set(INTERACTION_RIGHT_CLICK);
                }
            }

            @Override
            public void onInteraction(InteractionHand hand, net.minecraft.world.phys.Vec3 pos) {
                // Click destro con posizione specifica - solo se non già settato
                if (alreadySet.compareAndSet(false, true)) {
                    interactionType.set(INTERACTION_RIGHT_CLICK);
                }
            }

            @Override
            public void onAttack() {
                // Click sinistro (attacco) - solo se non già settato
                if (alreadySet.compareAndSet(false, true)) {
                    interactionType.set(INTERACTION_ATTACK);
                }
            }
        });

        // Esegui l'azione basata sul tipo di interazione
        int type = interactionType.get();
        if (type == INTERACTION_NONE) {
            return;
        }

        // Controlla cooldown per evitare doppi packet
        String cooldownKey = player.getUUID() + ":" + targetEntity.getUUID() + ":" + type;
        long now = System.currentTimeMillis();
        Long lastInteraction = interactionCooldowns.get(cooldownKey);

        if (lastInteraction != null && (now - lastInteraction) < COOLDOWN_MS) {
            // Ignora questo packet, è un duplicato
            ci.cancel();
            return;
        }

        // Aggiorna il timestamp
        interactionCooldowns.put(cooldownKey, now);

        // Pulisci vecchie entries (ogni tanto)
        if (interactionCooldowns.size() > 100) {
            interactionCooldowns.entrySet().removeIf(e -> (now - e.getValue()) > 5000);
        }

        if (type == INTERACTION_RIGHT_CLICK) {
            handleHammerRightClick(targetEntity, session);
            ci.cancel();
        } else if (type == INTERACTION_ATTACK) {
            handleHammerLeftClick(targetEntity, session);
            ci.cancel();
        }
    }

    /**
     * Handles right-click with hammer on an entity.
     * Adds or removes the entity from the construction/room.
     */
    @Unique
    private void handleHammerRightClick(Entity entity, EditingSession session) {
        Construction construction = session.getConstruction();
        boolean inRoom = session.isInRoom();
        Room room = inRoom ? session.getCurrentRoomObject() : null;

        UUID entityId = entity.getUUID();
        ServerLevel level = (ServerLevel) player.level();

        // Check if entity is already tracked (added to room/construction)
        boolean isTracked = session.isEntityTracked(entityId);

        if (isTracked) {
            // Remove the entity
            int listIndex = session.getEntityIndex(entityId);
            if (listIndex >= 0) {
                if (inRoom && room != null) {
                    room.removeEntity(listIndex);
                } else {
                    construction.removeEntity(listIndex);
                }
                // Remove from tracking and update indices for remaining entities
                session.untrackEntity(entityId);
                session.updateTrackingAfterRemoval(listIndex);
            }

            String targetName = formatTargetName(session);
            int entityCount = inRoom && room != null ? room.getEntityCount() : construction.getEntityCount();
            player.sendSystemMessage(Component.literal(targetName + ": §c" +
                    I18n.tr(player, "entity.removed") + " §7(" + entityCount + ")"));
        } else {
            // Add the entity
            EntityData data = EntityData.fromEntity(entity, construction.getBounds(), level.registryAccess());

            int newIndex;
            if (inRoom && room != null) {
                newIndex = room.addEntity(data);
            } else {
                newIndex = construction.addEntity(data);
            }
            // Track the entity with its list index
            session.trackEntity(entityId, newIndex);

            // Expand bounds if entity is outside
            net.minecraft.core.BlockPos entityBlockPos = entity.blockPosition();
            construction.getBounds().expandToInclude(entityBlockPos);

            String targetName = formatTargetName(session);
            int entityCount = inRoom && room != null ? room.getEntityCount() : construction.getEntityCount();
            player.sendSystemMessage(Component.literal(targetName + ": §a" +
                    I18n.tr(player, "entity.added") + " §7(" + entityCount + ")"));
        }

        // Update UI
        NetworkHandler.sendWireframeSync(player);
        NetworkHandler.sendEditingInfo(player);
    }

    /**
     * Handles left-click with hammer on an entity.
     * If entity is not included, kills it without drops.
     * If included, prevents killing.
     */
    @Unique
    private void handleHammerLeftClick(Entity entity, EditingSession session) {
        UUID entityId = entity.getUUID();

        // Check if entity is tracked (included in room/construction)
        boolean isTracked = session.isEntityTracked(entityId);

        if (isTracked) {
            // Entity is included: prevent killing
            player.sendSystemMessage(Component.literal("§e[Struttura] §f" +
                    I18n.tr(player, "entity.protected")));
        } else {
            // Entity not included: kill without drops
            // Use discard() to remove without effects
            entity.discard();

            player.sendSystemMessage(Component.literal("§c[Struttura] §f" +
                    I18n.tr(player, "entity.killed")));
        }
    }

    /**
     * Formatta il nome del target corrente (edificio o edificio/stanza) con colori.
     */
    @Unique
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
