package it.magius.struttura.architect.mixin;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.item.ConstructionHammerItem;
import it.magius.struttura.architect.item.MeasuringTapeItem;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.model.Room;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.session.EditingSession;
import it.magius.struttura.architect.ChatMessages;
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
 * Mixin to intercept entity interactions.
 * Handles:
 * - Right-click with hammer: adds/removes entities from construction/room
 * - Left-click on non-included entity: kills without drops
 * - Left-click on included entity: prevents killing
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    // Interaction type constants
    @Unique
    private static final int INTERACTION_NONE = 0;
    @Unique
    private static final int INTERACTION_RIGHT_CLICK = 1;
    @Unique
    private static final int INTERACTION_ATTACK = 2;

    // Cooldown to prevent duplicate packets - key: "playerUUID:entityUUID:type" -> timestamp
    @Unique
    private static final Map<String, Long> interactionCooldowns = new HashMap<>();
    @Unique
    private static final long COOLDOWN_MS = 200;

    /**
     * Intercepts all entity interactions.
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

        // Don't handle player or projectile interactions
        if (targetEntity instanceof Player || targetEntity instanceof Projectile) {
            return;
        }

        ItemStack heldItem = player.getMainHandItem();
        boolean holdingHammer = heldItem.getItem() instanceof ConstructionHammerItem;
        boolean holdingTape = heldItem.getItem() instanceof MeasuringTapeItem;

        EditingSession session = EditingSession.getSession(player);

        // If not holding hammer or tape, we only care about protecting tracked entities from attacks
        if (!holdingHammer && !holdingTape) {
            // Only protect if player is in editing mode
            if (session == null) {
                return;
            }
            // Check if this is an attack on a tracked entity
            if (session.isEntityTracked(targetEntity.getUUID())) {
                // Determine if this is an attack interaction
                AtomicBoolean isAttack = new AtomicBoolean(false);
                packet.dispatch(new ServerboundInteractPacket.Handler() {
                    @Override
                    public void onInteraction(InteractionHand hand) {}
                    @Override
                    public void onInteraction(InteractionHand hand, net.minecraft.world.phys.Vec3 pos) {}
                    @Override
                    public void onAttack() {
                        isAttack.set(true);
                    }
                });
                if (isAttack.get()) {
                    // Block the attack - entity is protected
                    ChatMessages.send(player, ChatMessages.Level.ERROR, "entity.protected");
                    ci.cancel();
                }
            }
            return;
        }

        // For hammer, we need an editing session
        if (holdingHammer && session == null) {
            return;
        }

        // For tape, show error if not in editing mode
        if (holdingTape && session == null) {
            ChatMessages.send(player, ChatMessages.Level.ERROR, "tape.error.not_editing");
            ci.cancel();
            return;
        }

        // For tape, show error if editing a room (tape only works on base construction)
        if (holdingTape && session.isInRoom()) {
            ChatMessages.send(player, ChatMessages.Level.ERROR, "tape.error.not_in_room");
            ci.cancel();
            return;
        }

        // Determine interaction type
        AtomicInteger interactionType = new AtomicInteger(INTERACTION_NONE);
        AtomicBoolean alreadySet = new AtomicBoolean(false);

        packet.dispatch(new ServerboundInteractPacket.Handler() {
            @Override
            public void onInteraction(InteractionHand hand) {
                if (alreadySet.compareAndSet(false, true)) {
                    interactionType.set(INTERACTION_RIGHT_CLICK);
                }
            }

            @Override
            public void onInteraction(InteractionHand hand, net.minecraft.world.phys.Vec3 pos) {
                if (alreadySet.compareAndSet(false, true)) {
                    interactionType.set(INTERACTION_RIGHT_CLICK);
                }
            }

            @Override
            public void onAttack() {
                if (alreadySet.compareAndSet(false, true)) {
                    interactionType.set(INTERACTION_ATTACK);
                }
            }
        });

        // Execute action based on interaction type
        int type = interactionType.get();
        if (type == INTERACTION_NONE) {
            return;
        }

        // Check cooldown to prevent duplicate packets
        String cooldownKey = player.getUUID() + ":" + targetEntity.getUUID() + ":" + type;
        long now = System.currentTimeMillis();
        Long lastInteraction = interactionCooldowns.get(cooldownKey);

        if (lastInteraction != null && (now - lastInteraction) < COOLDOWN_MS) {
            // Ignore this packet, it's a duplicate
            ci.cancel();
            return;
        }

        // Update timestamp
        interactionCooldowns.put(cooldownKey, now);

        // Clean old entries periodically
        if (interactionCooldowns.size() > 100) {
            interactionCooldowns.entrySet().removeIf(e -> (now - e.getValue()) > 5000);
        }

        if (holdingHammer) {
            if (type == INTERACTION_RIGHT_CLICK) {
                handleHammerRightClick(targetEntity, session);
                ci.cancel();
            } else if (type == INTERACTION_ATTACK) {
                handleHammerLeftClick(targetEntity, session);
                ci.cancel();
            }
        } else if (holdingTape) {
            if (type == INTERACTION_RIGHT_CLICK) {
                handleTapeRightClick(targetEntity, session);
                ci.cancel();
            } else if (type == INTERACTION_ATTACK) {
                handleTapeLeftClick(targetEntity, session);
                ci.cancel();
            }
        }
    }

    /**
     * Handles right-click with hammer on an entity.
     * Adds or removes the entity from the construction/room by UUID.
     */
    @Unique
    private void handleHammerRightClick(Entity entity, EditingSession session) {
        Construction construction = session.getConstruction();
        boolean inRoom = session.isInRoom();
        Room room = inRoom ? session.getCurrentRoomObject() : null;

        UUID entityId = entity.getUUID();
        ServerLevel level = (ServerLevel) player.level();

        // Check if entity is already tracked
        boolean isTracked = session.isEntityTracked(entityId);

        if (isTracked) {
            // Remove the entity from tracking
            if (inRoom && room != null) {
                room.removeEntity(entityId);
            } else {
                construction.removeEntity(entityId, level);
            }

            String targetName = ChatMessages.formatTargetName(player, session);
            int entityCount = inRoom && room != null ? room.getEntityCount() : construction.getEntityCount();
            ChatMessages.sendTarget(player, targetName, ChatMessages.Level.ERROR,
                    I18n.tr(player, "entity.removed") + " §7(" + entityCount + ")");
        } else {
            // Expand bounds BEFORE adding so entity is within bounds
            EntityData.expandBoundsForEntity(entity, construction.getBounds());

            // Add entity UUID to construction or room
            if (inRoom && room != null) {
                room.addEntity(entityId);
            } else {
                construction.addEntity(entityId, level);
            }

            String targetName = ChatMessages.formatTargetName(player, session);
            int entityCount = inRoom && room != null ? room.getEntityCount() : construction.getEntityCount();
            ChatMessages.sendTarget(player, targetName, ChatMessages.Level.INFO,
                    I18n.tr(player, "entity.added") + " §7(" + entityCount + ")");
        }

        // Update UI
        NetworkHandler.sendWireframeSync(player);
        NetworkHandler.sendEditingInfo(player);
    }

    /**
     * Handles right-click with tape on an entity.
     * Shows error if entity is not part of the construction,
     * or info message if it is (no action yet).
     */
    @Unique
    private void handleTapeRightClick(Entity entity, EditingSession session) {
        UUID entityId = entity.getUUID();

        boolean isTracked = session.isEntityTracked(entityId);

        if (!isTracked) {
            ChatMessages.send(player, ChatMessages.Level.ERROR, "tape.error.not_in_construction");
            return;
        }

        // Entity is in construction but tape has no action on entities yet
        ChatMessages.send(player, ChatMessages.Level.INFO, "tape.entity.no_action");
    }

    /**
     * Handles left-click with tape on an entity.
     * Shows error if entity is not part of the construction,
     * or info message if it is (no action yet).
     */
    @Unique
    private void handleTapeLeftClick(Entity entity, EditingSession session) {
        UUID entityId = entity.getUUID();

        boolean isTracked = session.isEntityTracked(entityId);

        if (!isTracked) {
            ChatMessages.send(player, ChatMessages.Level.ERROR, "tape.error.not_in_construction");
            return;
        }

        // Entity is in construction but tape has no action on entities yet
        ChatMessages.send(player, ChatMessages.Level.INFO, "tape.entity.no_action");
    }

    /**
     * Handles left-click with hammer on an entity.
     * If entity is not included, kills it without drops.
     * If included, prevents killing.
     */
    @Unique
    private void handleHammerLeftClick(Entity entity, EditingSession session) {
        UUID entityId = entity.getUUID();

        boolean isTracked = session.isEntityTracked(entityId);

        if (isTracked) {
            // Entity is included: prevent killing
            ChatMessages.send(player, ChatMessages.Level.ERROR, "entity.protected");
        } else {
            // Entity not included: kill without drops
            entity.discard();

            ChatMessages.send(player, ChatMessages.Level.INFO, "entity.killed");
        }
    }

}
