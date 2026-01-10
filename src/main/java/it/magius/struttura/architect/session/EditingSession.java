package it.magius.struttura.architect.session;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EditMode;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.model.Room;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.placement.ConstructionOperations;
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

import it.magius.struttura.architect.entity.EntitySpawnHandler;

import java.util.ArrayList;
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

    // Maps active entity UUID (in world) -> index in the room/construction entity list
    // Used to track spawned entities and find the correct data when removing
    private final Map<UUID, Integer> activeEntityToIndex = new HashMap<>();

    // Hidden base entities during room editing (temporarily removed when entering a room)
    // Stores the EntityData so we can respawn them when exiting the room
    private final List<EntityData> hiddenBaseEntities = new ArrayList<>();

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
     * Automatically tracks existing entities in the construction bounds.
     */
    public static EditingSession startSession(ServerPlayer player, Construction construction) {
        EditingSession session = new EditingSession(player, construction);
        ACTIVE_SESSIONS.put(player.getUUID(), session);
        // Track entities already in the world (e.g., after a pull)
        session.trackExistingEntitiesInWorld();
        return session;
    }

    /**
     * Termina la sessione per un giocatore.
     * Cattura gli NBT dei block entities prima di terminare.
     * Se si era in una room, ripristina i blocchi base prima di uscire.
     * NOTA: Le entità NON vengono catturate automaticamente.
     * Devono essere aggiunte manualmente con il martello (click destro su entità).
     * NOTA: Le entità rimangono freezate nei bounds anche dopo l'uscita dall'editing.
     */
    public static EditingSession endSession(ServerPlayer player) {
        EditingSession session = ACTIVE_SESSIONS.remove(player.getUUID());
        if (session != null) {
            // Se si era in una room, ripristina i blocchi base
            if (session.currentRoom != null) {
                session.exitRoom();
            }
            // Cattura NBT dei block entities (casse, furnace, etc.)
            session.captureBlockEntityNbt();
            // NOTA: Non catturiamo più le entità automaticamente
            // Le entità devono essere aggiunte manualmente con il martello
            // NOTA: Non sblocchiamo le entità - rimangono freezate nei bounds
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
     * Se in una stanza, salva nella room (NON differenziale); altrimenti nella costruzione base.
     */
    public void onBlockPlaced(BlockPos pos, BlockState state) {
        if (mode == EditMode.ADD) {
            if (isInRoom()) {
                // Stiamo editando una stanza: salva nella room
                // NON differenziale: salva tutti i blocchi, anche se uguali a quelli base
                Room room = construction.getRoom(currentRoom);
                if (room != null) {
                    room.setBlockChange(pos, state);
                    // Espandi i bounds della costruzione se il blocco è fuori dai bounds attuali
                    construction.getBounds().expandToInclude(pos);
                }
            } else {
                // Editing costruzione base
                construction.addBlock(pos, state);
            }
            // Aggiorna il wireframe (i bounds potrebbero essere cambiati)
            NetworkHandler.sendWireframeSync(player);
            // Aggiorna info editing per la GUI
            NetworkHandler.sendEditingInfo(player);
            // Aggiorna le posizioni dei blocchi
            NetworkHandler.sendBlockPositions(player);
        }
        // In mode REMOVE il piazzamento non fa nulla di speciale
    }

    /**
     * Gestisce la rottura di un blocco.
     * Se in una stanza, salva aria nella room (NON differenziale); altrimenti la costruzione base.
     */
    public void onBlockBroken(BlockPos pos, BlockState previousState) {
        if (mode == EditMode.ADD) {
            if (isInRoom()) {
                // Stiamo editando una stanza: aria nella room
                // NON differenziale: salva aria anche se la base era già aria
                Room room = construction.getRoom(currentRoom);
                if (room != null) {
                    BlockState airState = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                    room.setBlockChange(pos, airState);
                    // Espandi i bounds della costruzione se il blocco è fuori dai bounds attuali
                    construction.getBounds().expandToInclude(pos);
                }
            } else {
                // Editing costruzione base: rompere un blocco aggiunge aria
                construction.addBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            // Aggiorna il wireframe (i bounds potrebbero essere cambiati)
            NetworkHandler.sendWireframeSync(player);
            // Aggiorna info editing per la GUI
            NetworkHandler.sendEditingInfo(player);
            // Aggiorna le posizioni dei blocchi
            NetworkHandler.sendBlockPositions(player);
        } else {
            if (isInRoom()) {
                // In REMOVE in una stanza: rimuovi dalla room
                // NOTA: non ricalcoliamo i bounds perché la room potrebbe avere altri blocchi
                // e i bounds della costruzione devono comunque includere tutti i blocchi base + room
                Room room = construction.getRoom(currentRoom);
                if (room != null) {
                    room.removeBlockChange(pos);
                }
            } else {
                // In REMOVE nella base: rimuovi dalla costruzione
                construction.removeBlock(pos);
            }
            // Aggiorna il wireframe (i bounds sono stati ricalcolati)
            NetworkHandler.sendWireframeSync(player);
            // Aggiorna info editing per la GUI
            NetworkHandler.sendEditingInfo(player);
            // Aggiorna le posizioni dei blocchi
            NetworkHandler.sendBlockPositions(player);
        }
    }

    /**
     * Captures all entities in the construction bounds area.
     * Excludes Player and Projectile (as defined in EntityData.shouldSaveEntity).
     * Called automatically during save/exit.
     */
    public void captureEntities() {
        var bounds = construction.getBounds();

        // Cannot capture entities without valid bounds
        if (!bounds.isValid()) {
            Architect.LOGGER.debug("Cannot capture entities: bounds not valid for {}", construction.getId());
            return;
        }

        // Get ServerLevel from player (MC 1.21: player.level() instead of player.serverLevel())
        ServerLevel world = (ServerLevel) player.level();

        // Create AABB from bounds (add 1 to include the full block)
        AABB area = new AABB(
            bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
            bounds.getMaxX() + 1, bounds.getMaxY() + 1, bounds.getMaxZ() + 1
        );

        // Capture all entities in the area that pass the filter
        List<Entity> worldEntities = world.getEntities(
            (Entity) null,
            area,
            EntityData::shouldSaveEntity
        );

        // Clear previous entities
        construction.clearEntities();

        // Add each captured entity
        // Pass registryAccess to properly serialize ItemStacks (armor stand equipment, etc.)
        for (Entity entity : worldEntities) {
            EntityData data = EntityData.fromEntity(entity, bounds, world.registryAccess());
            construction.addEntity(data);
        }

        Architect.LOGGER.info("Captured {} entities for construction {}",
            worldEntities.size(), construction.getId());
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

    // ===== Room management =====

    /**
     * Entra in una stanza esistente per editarla.
     * Applica i blocchi della room nel mondo e gestisce le entità:
     * - Nasconde i mob della costruzione base (senza trigger)
     * - Nasconde le entità statiche base che si sovrappongono con quelle della room
     * - Spawna tutte le entità della room (inclusi mob)
     * @param roomId l'ID della stanza
     * @return true se l'entrata ha avuto successo
     */
    public boolean enterRoom(String roomId) {
        Room room = construction.getRoom(roomId);
        if (room == null) {
            Architect.LOGGER.warn("Cannot enter room '{}': room does not exist in construction {}",
                roomId, construction.getId());
            return false;
        }

        ServerLevel world = (ServerLevel) player.level();

        // Se eravamo già in una room, cattura dati e ripristina prima
        if (currentRoom != null) {
            Room previousRoom = construction.getRoom(currentRoom);
            if (previousRoom != null) {
                // Cattura gli NBT prima di ripristinare
                captureRoomBlockEntityNbt(world, previousRoom);
                // NOTA: Non catturiamo più le entità automaticamente
                // Le entità devono essere aggiunte manualmente con il martello
                // Rimuovi le entità della room precedente
                removeSpawnedRoomEntities(world);
                restoreBaseBlocks(world, previousRoom);
                // Ripristina le entità base nascoste
                respawnHiddenBaseEntities(world);
            }
        }

        // Nascondi le entità della costruzione base
        hideBaseEntities(world);

        // Applica i blocchi della nuova room
        applyRoomBlocks(world, room);

        // Spawna le entità della nuova room
        spawnRoomEntities(world, room);

        this.currentRoom = roomId;
        Architect.LOGGER.info("Player {} entered room '{}' ({}) in construction {}",
            player.getName().getString(), room.getName(), roomId, construction.getId());

        // Aggiorna UI
        NetworkHandler.sendWireframeSync(player);
        NetworkHandler.sendEditingInfo(player);
        NetworkHandler.sendBlockPositions(player);

        return true;
    }

    /**
     * Esce dalla stanza corrente e torna all'editing base.
     * Cattura gli NBT dei block entity, poi rimuove entità room e ripristina blocchi e entità base.
     * NOTA: Le entità NON vengono catturate automaticamente.
     * Devono essere aggiunte manualmente con il martello (click destro su entità).
     */
    public void exitRoom() {
        if (currentRoom == null) {
            return;
        }

        ServerLevel world = (ServerLevel) player.level();
        Room room = construction.getRoom(currentRoom);

        if (room != null) {
            // Cattura gli NBT dei block entity della room
            captureRoomBlockEntityNbt(world, room);

            // NOTA: Non catturiamo più le entità automaticamente
            // Le entità devono essere aggiunte manualmente con il martello

            // Rimuovi le entità spawnate dalla room
            removeSpawnedRoomEntities(world);

            // Ripristina i blocchi base dove la room aveva modifiche
            restoreBaseBlocks(world, room);

            // Ripristina le entità base che erano state nascoste
            respawnHiddenBaseEntities(world);
        }

        String previousRoom = currentRoom;
        this.currentRoom = null;

        Architect.LOGGER.info("Player {} exited room '{}' in construction {}",
            player.getName().getString(), previousRoom, construction.getId());

        // Aggiorna UI
        NetworkHandler.sendWireframeSync(player);
        NetworkHandler.sendEditingInfo(player);
        NetworkHandler.sendBlockPositions(player);
    }

    /**
     * Cattura gli NBT dei block entity di una room.
     * Salva il contenuto di casse, furnace, hoppers, etc. nel delta della room.
     */
    private void captureRoomBlockEntityNbt(ServerLevel world, Room room) {
        int capturedCount = 0;

        for (BlockPos pos : room.getBlockChanges().keySet()) {
            BlockState state = room.getBlockChange(pos);
            // Cattura solo se il blocco delta non è aria
            if (state != null && !state.isAir()) {
                BlockEntity blockEntity = world.getBlockEntity(pos);
                if (blockEntity != null) {
                    CompoundTag nbt = blockEntity.saveWithoutMetadata(world.registryAccess());

                    // Rimuovi tag che non dovrebbero essere copiati
                    nbt.remove("x");
                    nbt.remove("y");
                    nbt.remove("z");
                    nbt.remove("id");

                    // Salva solo se l'NBT contiene dati utili
                    if (!nbt.isEmpty()) {
                        room.setBlockEntityNbt(pos, nbt);
                        capturedCount++;
                    }
                }
            }
        }

        Architect.LOGGER.debug("Captured {} block entity NBTs for room '{}'", capturedCount, room.getId());
    }

    /**
     * Captures ALL entities in the construction bounds area and saves them to the room.
     * This includes all entities present (not just those spawned from the room).
     * Not differential: saves everything.
     */
    private void captureRoomEntitiesFullBounds(ServerLevel world, Room room) {
        var bounds = construction.getBounds();

        // Cannot capture entities without valid bounds
        if (!bounds.isValid()) {
            Architect.LOGGER.debug("Cannot capture room entities: bounds not valid for {}", construction.getId());
            return;
        }

        // Create AABB from bounds (add 1 to include the full block)
        AABB area = new AABB(
            bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
            bounds.getMaxX() + 1, bounds.getMaxY() + 1, bounds.getMaxZ() + 1
        );

        // Capture all entities in the area that pass the filter
        List<Entity> worldEntities = world.getEntities(
            (Entity) null,
            area,
            EntityData::shouldSaveEntity
        );

        // Clear previous room entities
        room.clearEntities();

        // Add each captured entity
        for (Entity entity : worldEntities) {
            EntityData data = EntityData.fromEntity(entity, bounds, world.registryAccess());
            room.addEntity(data);
        }

        Architect.LOGGER.info("Captured {} entities for room '{}' (full bounds capture)",
            worldEntities.size(), room.getId());
    }

    /**
     * Hides base construction entities when entering a room.
     * All mobs are hidden. Static entities are hidden only if they overlap
     * with block positions in the room.
     */
    private void hideBaseEntities(ServerLevel world) {
        var bounds = construction.getBounds();

        if (!bounds.isValid()) {
            return;
        }

        // Create AABB from construction bounds
        AABB area = new AABB(
            bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
            bounds.getMaxX() + 1, bounds.getMaxY() + 1, bounds.getMaxZ() + 1
        );

        // Get all entities in the construction area
        List<Entity> worldEntities = world.getEntities(
            (Entity) null,
            area,
            EntityData::shouldSaveEntity
        );

        hiddenBaseEntities.clear();
        int hiddenCount = 0;

        for (Entity entity : worldEntities) {
            // Save entity data before hiding
            EntityData data = EntityData.fromEntity(entity, bounds, world.registryAccess());
            hiddenBaseEntities.add(data);

            // Remove entity without triggers (discard)
            entity.discard();
            hiddenCount++;
        }

        Architect.LOGGER.debug("Hidden {} base entities for room editing", hiddenCount);
    }

    /**
     * Respawns base construction entities that were hidden when exiting the room.
     */
    private void respawnHiddenBaseEntities(ServerLevel world) {
        if (hiddenBaseEntities.isEmpty()) {
            return;
        }

        var bounds = construction.getBounds();
        if (!bounds.isValid()) {
            hiddenBaseEntities.clear();
            return;
        }

        int originX = bounds.getMinX();
        int originY = bounds.getMinY();
        int originZ = bounds.getMinZ();

        int respawnedCount = 0;

        for (EntityData data : hiddenBaseEntities) {
            try {
                // Calculate world position
                double worldX = originX + data.getRelativePos().x;
                double worldY = originY + data.getRelativePos().y;
                double worldZ = originZ + data.getRelativePos().z;

                // Copy NBT and remove/update position tags
                CompoundTag nbt = data.getNbt().copy();
                nbt.remove("Pos");
                nbt.remove("Motion");
                nbt.remove("UUID");

                // Ensure NBT contains the "id" tag
                if (!nbt.contains("id")) {
                    nbt.putString("id", data.getEntityType());
                }

                // Update block_pos for hanging entities
                if (nbt.contains("block_pos")) {
                    net.minecraft.nbt.Tag rawTag = nbt.get("block_pos");
                    if (rawTag instanceof net.minecraft.nbt.IntArrayTag intArrayTag) {
                        int[] coords = intArrayTag.getAsIntArray();
                        if (coords.length >= 3) {
                            int newX = originX + coords[0];
                            int newY = originY + coords[1];
                            int newZ = originZ + coords[2];
                            nbt.putIntArray("block_pos", new int[] { newX, newY, newZ });
                        }
                    }
                }

                // Create entity from NBT
                Entity entity = net.minecraft.world.entity.EntityType.loadEntityRecursive(
                    nbt, world, net.minecraft.world.entity.EntitySpawnReason.LOAD, e -> e);

                if (entity != null) {
                    entity.setPos(worldX, worldY, worldZ);
                    entity.setYRot(data.getYaw());
                    entity.setXRot(data.getPitch());
                    UUID newUuid = UUID.randomUUID();
                    entity.setUUID(newUuid);

                    // Register entity to ignore BEFORE adding to world
                    // to prevent EntitySpawnHandler from auto-adding it
                    EntitySpawnHandler.getInstance().ignoreEntity(newUuid);

                    world.addFreshEntity(entity);
                    respawnedCount++;
                }
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to respawn base entity of type {}: {}",
                    data.getEntityType(), e.getMessage());
            }
        }

        hiddenBaseEntities.clear();
        Architect.LOGGER.debug("Respawned {} hidden base entities", respawnedCount);
    }

    /**
     * Spawns room entities into the world.
     * Uses the same spawn logic as base construction.
     */
    private void spawnRoomEntities(ServerLevel world, Room room) {
        List<EntityData> roomEntities = room.getEntities();
        if (roomEntities.isEmpty()) {
            return;
        }

        // Calculate origin from construction bounds
        var bounds = construction.getBounds();
        if (!bounds.isValid()) {
            return;
        }

        int originX = bounds.getMinX();
        int originY = bounds.getMinY();
        int originZ = bounds.getMinZ();

        int spawnedCount = 0;

        for (int index = 0; index < roomEntities.size(); index++) {
            EntityData data = roomEntities.get(index);

            try {
                // Calculate world position
                double worldX = originX + data.getRelativePos().x;
                double worldY = originY + data.getRelativePos().y;
                double worldZ = originZ + data.getRelativePos().z;

                // Copy NBT and remove/update position tags
                CompoundTag nbt = data.getNbt().copy();
                nbt.remove("Pos");
                nbt.remove("Motion");
                nbt.remove("UUID");

                // Ensure NBT contains the "id" tag
                if (!nbt.contains("id")) {
                    nbt.putString("id", data.getEntityType());
                }

                // Update block_pos for hanging entities
                if (nbt.contains("block_pos")) {
                    net.minecraft.nbt.Tag rawTag = nbt.get("block_pos");
                    if (rawTag instanceof net.minecraft.nbt.IntArrayTag intArrayTag) {
                        int[] coords = intArrayTag.getAsIntArray();
                        if (coords.length >= 3) {
                            int newX = originX + coords[0];
                            int newY = originY + coords[1];
                            int newZ = originZ + coords[2];
                            nbt.putIntArray("block_pos", new int[] { newX, newY, newZ });
                        }
                    }
                }

                // Create entity from NBT
                Entity entity = net.minecraft.world.entity.EntityType.loadEntityRecursive(
                    nbt, world, net.minecraft.world.entity.EntitySpawnReason.LOAD, e -> e);

                if (entity != null) {
                    entity.setPos(worldX, worldY, worldZ);
                    entity.setYRot(data.getYaw());
                    entity.setXRot(data.getPitch());
                    UUID newUuid = UUID.randomUUID();
                    entity.setUUID(newUuid);

                    // Register entity to ignore BEFORE adding to world
                    // to prevent EntitySpawnHandler from auto-adding it
                    EntitySpawnHandler.getInstance().ignoreEntity(newUuid);

                    world.addFreshEntity(entity);
                    // Map new UUID to list index for later removal
                    activeEntityToIndex.put(newUuid, index);
                    spawnedCount++;
                }
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to spawn room entity of type {}: {}",
                    data.getEntityType(), e.getMessage());
            }
        }

        Architect.LOGGER.debug("Spawned {} entities for room '{}'", spawnedCount, room.getId());
    }

    /**
     * Removes entities spawned for the current room.
     */
    private void removeSpawnedRoomEntities(ServerLevel world) {
        Architect.LOGGER.info("removeSpawnedRoomEntities: {} entities to remove", activeEntityToIndex.size());
        int removedCount = 0;

        for (UUID activeUuid : activeEntityToIndex.keySet()) {
            Entity entity = world.getEntity(activeUuid);
            Architect.LOGGER.info("  Entity UUID {}: found={}", activeUuid, entity != null);
            if (entity != null) {
                // Unfreeze entity before removing
                it.magius.struttura.architect.entity.EntityFreezeHandler.getInstance().unfreezeEntity(activeUuid);
                // Remove from ignored entities list
                EntitySpawnHandler.getInstance().unignoreEntity(activeUuid);
                // Remove the entity
                entity.discard();
                removedCount++;
            }
        }

        activeEntityToIndex.clear();
        Architect.LOGGER.info("Removed {} room entities", removedCount);
    }

    /**
     * Applies room delta blocks in the world.
     * Delegates to centralized ConstructionOperations.placeRoomBlocks.
     */
    private void applyRoomBlocks(ServerLevel world, Room room) {
        int appliedCount = ConstructionOperations.placeRoomBlocks(world, room, construction);
        Architect.LOGGER.debug("Applied {} room blocks for room '{}' in construction {}",
            appliedCount, room.getId(), construction.getId());
    }

    /**
     * Restores base construction blocks where the room had modifications.
     * Delegates to centralized ConstructionOperations.restoreBaseBlocks.
     */
    private void restoreBaseBlocks(ServerLevel world, Room room) {
        int restoredCount = ConstructionOperations.restoreBaseBlocks(world, room, construction);
        Architect.LOGGER.debug("Restored {} base blocks after exiting room '{}' in construction {}",
            restoredCount, room.getId(), construction.getId());
    }

    /**
     * Crea una nuova stanza e ci entra.
     * @param name il nome della stanza
     * @return la stanza creata, o null se non e' stato possibile crearla
     */
    public Room createRoom(String name) {
        // Verifica limiti
        if (construction.getRoomCount() >= 50) {
            Architect.LOGGER.warn("Cannot create room: max rooms (50) reached for construction {}",
                construction.getId());
            return null;
        }

        // Crea la stanza
        Room room = new Room(name);

        // Verifica ID univoco
        if (construction.hasRoom(room.getId())) {
            // Aggiungi suffisso numerico
            int suffix = 2;
            String baseId = room.getId();
            while (construction.hasRoom(baseId + "_" + suffix)) {
                suffix++;
            }
            room = new Room(baseId + "_" + suffix, name, room.getCreatedAt());
        }

        construction.addRoom(room);

        Architect.LOGGER.info("Player {} created room '{}' ({}) in construction {}",
            player.getName().getString(), name, room.getId(), construction.getId());

        // Entra nella stanza appena creata
        enterRoom(room.getId());

        return room;
    }

    /**
     * Elimina una stanza.
     * Se si sta editando quella stanza, esce prima.
     * @param roomId l'ID della stanza da eliminare
     * @return true se la stanza e' stata eliminata
     */
    public boolean deleteRoom(String roomId) {
        // Se stiamo editando questa stanza, esci prima
        if (roomId.equals(currentRoom)) {
            exitRoom();
        }

        boolean removed = construction.removeRoom(roomId);

        if (removed) {
            Architect.LOGGER.info("Player {} deleted room '{}' from construction {}",
                player.getName().getString(), roomId, construction.getId());

            // Aggiorna UI
            NetworkHandler.sendEditingInfo(player);
        }

        return removed;
    }

    /**
     * Rinomina una stanza. Questo cambia sia il nome che l'ID (derivato dal nome).
     * @param roomId ID attuale della stanza
     * @param newName nuovo nome
     * @return il nuovo ID della stanza, o null se fallisce
     */
    public String renameRoom(String roomId, String newName) {
        // Genera il nuovo ID dal nome e delega al metodo completo
        String newId = Room.generateId(newName);
        return renameRoomWithId(roomId, newId, newName);
    }

    /**
     * Rinomina una stanza specificando sia il nuovo ID che il nuovo nome.
     * Usato dalla GUI quando si vuole impostare un ID personalizzato.
     *
     * @param roomId ID attuale della stanza
     * @param newId nuovo ID (può essere diverso dal nome)
     * @param newName nuovo nome
     * @return il nuovo ID della stanza, o null se fallisce (ID già esistente)
     */
    public String renameRoomWithId(String roomId, String newId, String newName) {
        Room oldRoom = construction.getRoom(roomId);
        if (oldRoom == null) {
            return null;
        }

        // Se l'ID non cambia, aggiorna solo il nome
        if (newId.equals(roomId)) {
            oldRoom.setName(newName);
            Architect.LOGGER.info("Player {} renamed room '{}' (same ID) in construction {}",
                player.getName().getString(), roomId, construction.getId());
            NetworkHandler.sendEditingInfo(player);
            return newId;
        }

        // Verifica che il nuovo ID non esista già
        if (construction.hasRoom(newId)) {
            Architect.LOGGER.warn("Cannot rename room '{}' to '{}': ID already exists",
                roomId, newId);
            return null;
        }

        // Crea una nuova stanza con ID e nome specificati, copiando i dati
        Room newRoom = new Room(newId, newName, oldRoom.getCreatedAt());
        // Copia tutti i blocchi
        for (var entry : oldRoom.getBlockChanges().entrySet()) {
            newRoom.setBlockChange(entry.getKey(), entry.getValue());
        }
        // Copia tutti i block entity NBT
        for (var entry : oldRoom.getBlockEntityNbtMap().entrySet()) {
            newRoom.setBlockEntityNbt(entry.getKey(), entry.getValue().copy());
        }
        // Copy all entities
        for (EntityData data : oldRoom.getEntities()) {
            newRoom.addEntity(data);
        }

        // Rimuovi la vecchia room e aggiungi la nuova
        construction.removeRoom(roomId);
        construction.addRoom(newRoom);

        // Se l'utente era in questa room, aggiorna il riferimento
        if (roomId.equals(currentRoom)) {
            currentRoom = newId;
        }

        Architect.LOGGER.info("Player {} renamed room '{}' to '{}' (new ID: '{}') in construction {}",
            player.getName().getString(), roomId, newName, newId, construction.getId());

        // Aggiorna UI
        NetworkHandler.sendEditingInfo(player);

        return newId;
    }

    /**
     * Ottiene la stanza corrente in editing, o null se non in una stanza.
     */
    public Room getCurrentRoomObject() {
        if (currentRoom == null) {
            return null;
        }
        return construction.getRoom(currentRoom);
    }

    /**
     * Ottiene il conteggio blocchi effettivo per la visualizzazione.
     * Se in una stanza, somma i blocchi base + le modifiche delta.
     */
    public int getEffectiveBlockCount() {
        if (!isInRoom()) {
            return construction.getBlockCount();
        }

        Room room = getCurrentRoomObject();
        if (room == null) {
            return construction.getBlockCount();
        }

        // Base blocks + delta changes (approssimativo)
        return construction.getBlockCount() + room.getChangedBlockCount();
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

    /**
     * Tracks an entity added to the current room/construction.
     * @param activeUuid The UUID of the entity currently in the world
     * @param listIndex The index of this entity in the room/construction entity list
     */
    public void trackEntity(UUID activeUuid, int listIndex) {
        Architect.LOGGER.debug("trackEntity: activeUuid={}, listIndex={}, isInRoom={}, currentRoom={}",
            activeUuid, listIndex, isInRoom(), currentRoom);
        activeEntityToIndex.put(activeUuid, listIndex);
    }

    /**
     * Removes an entity from tracking.
     * @param activeUuid The UUID of the entity in the world
     * @return The list index of the entity, or -1 if not tracked
     */
    public int untrackEntity(UUID activeUuid) {
        Integer index = activeEntityToIndex.remove(activeUuid);
        return index != null ? index : -1;
    }

    /**
     * Checks if an entity is tracked.
     * @param activeUuid The UUID of the entity in the world
     * @return true if the entity is tracked
     */
    public boolean isEntityTracked(UUID activeUuid) {
        return activeEntityToIndex.containsKey(activeUuid);
    }

    /**
     * Gets the list index for a tracked entity.
     * @param activeUuid The UUID of the entity in the world
     * @return The list index, or -1 if not tracked
     */
    public int getEntityIndex(UUID activeUuid) {
        Integer index = activeEntityToIndex.get(activeUuid);
        return index != null ? index : -1;
    }

    /**
     * Updates tracking indices after an entity is removed from the list.
     * When an entity at index N is removed, all entities with index > N need to be decremented.
     * @param removedIndex The index of the removed entity
     */
    public void updateTrackingAfterRemoval(int removedIndex) {
        Map<UUID, Integer> updated = new HashMap<>();
        for (Map.Entry<UUID, Integer> entry : activeEntityToIndex.entrySet()) {
            int idx = entry.getValue();
            if (idx > removedIndex) {
                updated.put(entry.getKey(), idx - 1);
            } else {
                updated.put(entry.getKey(), idx);
            }
        }
        activeEntityToIndex.clear();
        activeEntityToIndex.putAll(updated);
    }

    /**
     * Scans the world for existing entities in the construction bounds and tracks them.
     * This is called when starting an edit session to protect entities that were
     * spawned before the session started (e.g., after a pull).
     * Matches world entities to EntityData in the construction by type and approximate position.
     */
    public void trackExistingEntitiesInWorld() {
        var bounds = construction.getBounds();
        if (!bounds.isValid()) {
            return;
        }

        ServerLevel world = (ServerLevel) player.level();

        // Create AABB from bounds
        AABB area = new AABB(
            bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
            bounds.getMaxX() + 1, bounds.getMaxY() + 1, bounds.getMaxZ() + 1
        );

        // Get all entities in the area
        List<Entity> worldEntities = world.getEntities(
            (Entity) null,
            area,
            EntityData::shouldSaveEntity
        );

        List<EntityData> constructionEntities = construction.getEntities();
        if (constructionEntities.isEmpty()) {
            return;
        }

        int originX = bounds.getMinX();
        int originY = bounds.getMinY();
        int originZ = bounds.getMinZ();

        int trackedCount = 0;

        // For each entity in the world, try to find a matching EntityData
        for (Entity worldEntity : worldEntities) {
            if (isEntityTracked(worldEntity.getUUID())) {
                continue; // Already tracked
            }

            String entityType = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(worldEntity.getType()).toString();
            double worldX = worldEntity.getX();
            double worldY = worldEntity.getY();
            double worldZ = worldEntity.getZ();

            // Find matching EntityData by type and approximate position
            for (int i = 0; i < constructionEntities.size(); i++) {
                EntityData data = constructionEntities.get(i);

                // Check type match
                if (!data.getEntityType().equals(entityType)) {
                    continue;
                }

                // Calculate expected world position from EntityData
                double expectedX = originX + data.getRelativePos().x;
                double expectedY = originY + data.getRelativePos().y;
                double expectedZ = originZ + data.getRelativePos().z;

                // Check position match (within 1 block tolerance)
                double dx = Math.abs(worldX - expectedX);
                double dy = Math.abs(worldY - expectedY);
                double dz = Math.abs(worldZ - expectedZ);

                if (dx < 1.0 && dy < 1.0 && dz < 1.0) {
                    // Check if this index is already used by another entity
                    boolean indexAlreadyUsed = activeEntityToIndex.containsValue(i);
                    if (!indexAlreadyUsed) {
                        trackEntity(worldEntity.getUUID(), i);
                        trackedCount++;
                        break; // Move to next world entity
                    }
                }
            }
        }

        if (trackedCount > 0) {
            Architect.LOGGER.info("Tracked {} existing entities in world for construction {}",
                trackedCount, construction.getId());
        }
    }
}
