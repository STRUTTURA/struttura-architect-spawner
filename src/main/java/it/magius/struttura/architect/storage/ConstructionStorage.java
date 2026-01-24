package it.magius.struttura.architect.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.i18n.LanguageUtils;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.model.ModInfo;
import it.magius.struttura.architect.model.Room;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Gestisce il salvataggio e il caricamento delle costruzioni su filesystem.
 *
 * Struttura directory:
 * .minecraft/struttura/structures/
 *   └── namespace/
 *       └── category/
 *           └── name/
 *               ├── metadata.json  (info costruzione)
 *               └── blocks.nbt     (dati blocchi)
 */
public class ConstructionStorage {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private final Path baseDirectory;

    /**
     * Creates a ConstructionStorage for Architect mode (editing).
     * Path: {gameDirectory}/struttura/architect/buildings/
     */
    public ConstructionStorage(Path gameDirectory) {
        this.baseDirectory = gameDirectory.resolve("struttura").resolve("architect").resolve("buildings");
        ensureDirectoryExists(baseDirectory);
    }

    /**
     * Creates a ConstructionStorage with a custom base directory.
     * Used by NbtCacheStorage for spawner cache.
     */
    public ConstructionStorage(Path baseDirectory, boolean directPath) {
        this.baseDirectory = baseDirectory.resolve("buildings");
        ensureDirectoryExists(this.baseDirectory);
    }

    /**
     * Salva una costruzione su disco.
     *
     * @param construction la costruzione da salvare
     * @return true se il salvataggio ha avuto successo
     */
    public boolean save(Construction construction) {
        try {
            Path constructionDir = getConstructionDirectory(construction.getId());
            ensureDirectoryExists(constructionDir);

            // Calcola i mod richiesti dai blocchi prima di salvare
            construction.computeRequiredMods();

            // Salva metadata
            saveMetadata(construction, constructionDir);

            // Salva blocchi
            saveBlocks(construction, constructionDir);

            // Salva entità
            saveEntities(construction, constructionDir);

            Architect.LOGGER.info("Saved construction: {} ({} blocks, {} entities, {} rooms)",
                construction.getId(), construction.getBlockCount(), construction.getEntityCount(), construction.getRoomCount());
            return true;

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to save construction: {}", construction.getId(), e);
            return false;
        }
    }

    /**
     * Saves only NBT files (blocks.nbt and entities.nbt), no metadata.json.
     * Used by InGame spawner cache - metadata comes from the list export.
     *
     * @param construction the construction to save
     * @return true if save was successful
     */
    public boolean saveNbtOnly(Construction construction) {
        try {
            Path constructionDir = getConstructionDirectory(construction.getId());
            ensureDirectoryExists(constructionDir);

            // Save blocks only
            saveBlocks(construction, constructionDir);

            // Save entities only
            saveEntities(construction, constructionDir);

            Architect.LOGGER.debug("Saved NBT cache: {} ({} blocks, {} entities)",
                construction.getId(), construction.getBlockCount(), construction.getEntityCount());
            return true;

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to save NBT cache: {}", construction.getId(), e);
            return false;
        }
    }

    /**
     * Loads only NBT files (blocks.nbt and entities.nbt) into an existing construction.
     * Used by InGame spawner cache - metadata comes from the list export.
     *
     * @param id the construction ID (RDNS)
     * @return the construction with loaded blocks/entities, or null if not found
     */
    public Construction loadNbtOnly(String id) {
        try {
            Path constructionDir = getConstructionDirectory(id);

            if (!Files.exists(constructionDir)) {
                return null;
            }

            Path blocksFile = constructionDir.resolve("blocks.nbt");
            if (!Files.exists(blocksFile)) {
                return null;
            }

            // Create minimal construction (metadata not needed for InGame)
            Construction construction = new Construction(id, new UUID(0, 0), "ingame");

            // Load blocks
            loadBlocks(construction, constructionDir);

            // Load entities
            loadEntities(construction, constructionDir);

            // Note: Bounds are NOT set here - they come from SpawnableBuilding metadata
            // and will be applied in InGameBuildingSpawner.doSpawn before architectSpawn

            Architect.LOGGER.debug("Loaded NBT cache: {} ({} blocks, {} entities)",
                construction.getId(), construction.getBlockCount(), construction.getEntityCount());
            return construction;

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to load NBT cache: {}", id, e);
            return null;
        }
    }

    /**
     * Carica una costruzione da disco.
     *
     * @param id l'ID della costruzione
     * @return la costruzione caricata, o null se non esiste
     */
    public Construction load(String id) {
        try {
            Path constructionDir = getConstructionDirectory(id);

            if (!Files.exists(constructionDir)) {
                return null;
            }

            // Carica metadata
            Construction construction = loadMetadata(constructionDir);
            if (construction == null) {
                return null;
            }

            // Carica blocchi
            loadBlocks(construction, constructionDir);

            // Carica entità
            loadEntities(construction, constructionDir);

            Architect.LOGGER.info("Loaded construction: {} ({} blocks, {} entities, {} rooms)",
                construction.getId(), construction.getBlockCount(), construction.getEntityCount(), construction.getRoomCount());
            return construction;

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to load construction: {}", id, e);
            return null;
        }
    }

    /**
     * Elenca tutti gli ID delle costruzioni salvate.
     *
     * @return lista degli ID
     */
    public List<String> listAll() {
        List<String> ids = new ArrayList<>();

        if (!Files.exists(baseDirectory)) {
            return ids;
        }

        try {
            // Scansiona fino a 10 livelli per supportare ID con molti segmenti
            // (es: it.magius.category.subcategory.name -> 5 livelli)
            Files.walk(baseDirectory, 10)
                .filter(path -> Files.isDirectory(path))
                .filter(path -> Files.exists(path.resolve("metadata.json")))
                .forEach(path -> {
                    String id = pathToId(path);
                    if (id != null) {
                        ids.add(id);
                    }
                });
        } catch (IOException e) {
            Architect.LOGGER.error("Failed to list constructions", e);
        }

        return ids;
    }

    /**
     * Verifica se una costruzione esiste su disco.
     *
     * @param id l'ID della costruzione
     * @return true se esiste
     */
    public boolean exists(String id) {
        Path constructionDir = getConstructionDirectory(id);
        return Files.exists(constructionDir.resolve("metadata.json"));
    }

    /**
     * Elimina una costruzione da disco.
     *
     * @param id l'ID della costruzione
     * @return true se l'eliminazione ha avuto successo
     */
    public boolean delete(String id) {
        try {
            Path constructionDir = getConstructionDirectory(id);

            if (!Files.exists(constructionDir)) {
                return false;
            }

            // Elimina tutti i file nella directory
            Files.walk(constructionDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        Architect.LOGGER.warn("Failed to delete: {}", path);
                    }
                });

            // Prova a eliminare le directory vuote parent
            cleanEmptyParentDirectories(constructionDir.getParent());

            Architect.LOGGER.info("Deleted construction: {}", id);
            return true;

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to delete construction: {}", id, e);
            return false;
        }
    }

    // ===== Private methods =====

    private Path getConstructionDirectory(String id) {
        // ID format: namespace.category.name -> namespace/category/name
        String[] parts = id.split("\\.");
        Path path = baseDirectory;
        for (String part : parts) {
            path = path.resolve(part);
        }
        return path;
    }

    private String pathToId(Path path) {
        Path relative = baseDirectory.relativize(path);
        if (relative.getNameCount() < 3) {
            return null;
        }

        StringBuilder id = new StringBuilder();
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) id.append(".");
            id.append(relative.getName(i).toString());
        }
        return id.toString();
    }

    private void ensureDirectoryExists(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            Architect.LOGGER.error("Failed to create directory: {}", directory, e);
        }
    }

    private void cleanEmptyParentDirectories(Path directory) {
        try {
            while (directory != null && !directory.equals(baseDirectory)) {
                if (Files.isDirectory(directory) && isDirectoryEmpty(directory)) {
                    Files.delete(directory);
                    directory = directory.getParent();
                } else {
                    break;
                }
            }
        } catch (IOException e) {
            // Ignora errori nella pulizia
        }
    }

    private boolean isDirectoryEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findFirst().isEmpty();
        }
    }

    // ===== Metadata serialization =====

    private void saveMetadata(Construction construction, Path directory) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("id", construction.getId());
        json.addProperty("authorId", construction.getAuthorId().toString());
        json.addProperty("authorName", construction.getAuthorName());
        json.addProperty("createdAt", construction.getCreatedAt().toString());

        // Multilingual titles - migrate to BCP 47 format
        JsonObject titles = new JsonObject();
        Map<String, String> migratedTitles = LanguageUtils.migrateKeys(construction.getTitles());
        for (var entry : migratedTitles.entrySet()) {
            titles.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("titles", titles);

        // Multilingual short descriptions - migrate to BCP 47 format
        JsonObject shortDescriptions = new JsonObject();
        Map<String, String> migratedShortDescs = LanguageUtils.migrateKeys(construction.getShortDescriptions());
        for (var entry : migratedShortDescs.entrySet()) {
            shortDescriptions.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("shortDescriptions", shortDescriptions);

        // Multilingual full descriptions - migrate to BCP 47 format
        JsonObject descriptions = new JsonObject();
        Map<String, String> migratedDescs = LanguageUtils.migrateKeys(construction.getDescriptions());
        for (var entry : migratedDescs.entrySet()) {
            descriptions.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("descriptions", descriptions);

        // Conteggi totali (base + tutte le stanze)
        json.addProperty("blocksCount", construction.getTotalBlockCount());
        json.addProperty("entitiesCount", construction.getTotalEntityCount());

        // Bounds info
        JsonObject bounds = new JsonObject();
        var b = construction.getBounds();
        if (b.isValid()) {
            bounds.addProperty("minX", b.getMinX());
            bounds.addProperty("minY", b.getMinY());
            bounds.addProperty("minZ", b.getMinZ());
            bounds.addProperty("maxX", b.getMaxX());
            bounds.addProperty("maxY", b.getMaxY());
            bounds.addProperty("maxZ", b.getMaxZ());
        }
        json.add("bounds", bounds);

        // Mod richiesti
        JsonObject modsObject = new JsonObject();
        for (ModInfo mod : construction.getRequiredMods().values()) {
            JsonObject modJson = new JsonObject();
            modJson.addProperty("displayName", mod.getDisplayName());
            modJson.addProperty("blocksCount", mod.getBlockCount());
            modJson.addProperty("entitiesCount", mod.getEntitiesCount());
            modJson.addProperty("mobsCount", mod.getMobsCount());
            modJson.addProperty("commandBlocksCount", mod.getCommandBlocksCount());
            if (mod.getVersion() != null) {
                modJson.addProperty("version", mod.getVersion());
            }
            if (mod.getDownloadUrl() != null) {
                modJson.addProperty("downloadUrl", mod.getDownloadUrl());
            }
            modsObject.add(mod.getModId(), modJson);
        }
        json.add("mods", modsObject);

        // Stanze (room metadata) - array format
        com.google.gson.JsonArray roomsArray = new com.google.gson.JsonArray();
        for (Room room : construction.getRooms().values()) {
            JsonObject roomJson = new JsonObject();
            roomJson.addProperty("id", room.getId());
            roomJson.addProperty("name", room.getName());
            roomJson.addProperty("createdAt", room.getCreatedAt().toString());
            roomJson.addProperty("blockChanges", room.getChangedBlockCount());
            roomJson.addProperty("entitiesCount", room.getEntityCount());
            roomsArray.add(roomJson);
        }
        json.add("rooms", roomsArray);

        // Anchors (only save if any anchor is set)
        if (construction.getAnchors().hasAnyAnchor()) {
            JsonObject anchorsObj = new JsonObject();
            if (construction.getAnchors().hasEntrance()) {
                JsonObject entranceObj = new JsonObject();
                BlockPos entrance = construction.getAnchors().getEntrance();
                entranceObj.addProperty("x", entrance.getX());
                entranceObj.addProperty("y", entrance.getY());
                entranceObj.addProperty("z", entrance.getZ());
                entranceObj.addProperty("yaw", construction.getAnchors().getEntranceYaw());
                anchorsObj.add("entrance", entranceObj);
            }
            json.add("anchors", anchorsObj);
        }

        // Spawned entity UUIDs (runtime tracking for entity removal)
        Set<UUID> spawnedUuids = construction.getSpawnedEntityUuids();
        if (!spawnedUuids.isEmpty()) {
            com.google.gson.JsonArray uuidsArray = new com.google.gson.JsonArray();
            for (UUID uuid : spawnedUuids) {
                uuidsArray.add(uuid.toString());
            }
            json.add("spawnedEntityUuids", uuidsArray);
        }

        // Versione del mod Struttura
        json.addProperty("modVersion", Architect.MOD_VERSION);

        Path metadataFile = directory.resolve("metadata.json");
        try (Writer writer = Files.newBufferedWriter(metadataFile, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
    }

    private Construction loadMetadata(Path directory) throws IOException {
        Path metadataFile = directory.resolve("metadata.json");

        if (!Files.exists(metadataFile)) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(metadataFile, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

            String id = json.get("id").getAsString();
            UUID authorId = UUID.fromString(json.get("authorId").getAsString());
            String authorName = json.get("authorName").getAsString();
            Instant createdAt = Instant.parse(json.get("createdAt").getAsString());

            // Load multilingual titles and migrate to BCP 47 format
            Map<String, String> rawTitles = new HashMap<>();
            if (json.has("titles") && json.get("titles").isJsonObject()) {
                JsonObject titlesObj = json.getAsJsonObject("titles");
                for (var entry : titlesObj.entrySet()) {
                    if (!entry.getValue().isJsonNull()) {
                        rawTitles.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            Map<String, String> titles = LanguageUtils.migrateKeys(rawTitles);

            // Load multilingual short descriptions and migrate to BCP 47 format
            Map<String, String> rawShortDescs = new HashMap<>();
            if (json.has("shortDescriptions") && json.get("shortDescriptions").isJsonObject()) {
                JsonObject shortDescObj = json.getAsJsonObject("shortDescriptions");
                for (var entry : shortDescObj.entrySet()) {
                    if (!entry.getValue().isJsonNull()) {
                        rawShortDescs.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            Map<String, String> shortDescriptions = LanguageUtils.migrateKeys(rawShortDescs);

            // Load multilingual full descriptions and migrate to BCP 47 format
            Map<String, String> rawDescs = new HashMap<>();
            if (json.has("descriptions") && json.get("descriptions").isJsonObject()) {
                JsonObject descriptionsObj = json.getAsJsonObject("descriptions");
                for (var entry : descriptionsObj.entrySet()) {
                    if (!entry.getValue().isJsonNull()) {
                        rawDescs.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            Map<String, String> descriptions = LanguageUtils.migrateKeys(rawDescs);

            Construction construction = new Construction(id, authorId, authorName, createdAt, titles, shortDescriptions, descriptions);

            // Carica mod richiesti
            Map<String, ModInfo> requiredMods = new HashMap<>();
            if (json.has("mods") && json.get("mods").isJsonObject()) {
                JsonObject modsObject = json.getAsJsonObject("mods");
                for (Map.Entry<String, JsonElement> entry : modsObject.entrySet()) {
                    String modId = entry.getKey();
                    JsonObject modJson = entry.getValue().getAsJsonObject();

                    ModInfo info = new ModInfo(modId);
                    if (modJson.has("displayName")) {
                        info.setDisplayName(modJson.get("displayName").getAsString());
                    }
                    if (modJson.has("blocksCount")) {
                        info.setBlockCount(modJson.get("blocksCount").getAsInt());
                    }
                    if (modJson.has("entitiesCount")) {
                        info.setEntitiesCount(modJson.get("entitiesCount").getAsInt());
                    }
                    if (modJson.has("mobsCount")) {
                        info.setMobsCount(modJson.get("mobsCount").getAsInt());
                    }
                    if (modJson.has("commandBlocksCount")) {
                        info.setCommandBlocksCount(modJson.get("commandBlocksCount").getAsInt());
                    }
                    if (modJson.has("version") && !modJson.get("version").isJsonNull()) {
                        info.setVersion(modJson.get("version").getAsString());
                    }
                    if (modJson.has("downloadUrl") && !modJson.get("downloadUrl").isJsonNull()) {
                        info.setDownloadUrl(modJson.get("downloadUrl").getAsString());
                    }
                    requiredMods.put(modId, info);
                }
            }
            construction.setRequiredMods(requiredMods);

            // Carica i bounds dal metadata (necessari per denormalizzare le coordinate)
            if (json.has("bounds") && json.get("bounds").isJsonObject()) {
                JsonObject boundsObj = json.getAsJsonObject("bounds");
                if (boundsObj.has("minX") && boundsObj.has("maxX")) {
                    int minX = boundsObj.get("minX").getAsInt();
                    int minY = boundsObj.get("minY").getAsInt();
                    int minZ = boundsObj.get("minZ").getAsInt();
                    int maxX = boundsObj.get("maxX").getAsInt();
                    int maxY = boundsObj.get("maxY").getAsInt();
                    int maxZ = boundsObj.get("maxZ").getAsInt();
                    construction.getBounds().set(minX, minY, minZ, maxX, maxY, maxZ);
                }
            }

            // Carica metadata delle stanze (i blocchi/entità vengono caricati dopo)
            if (json.has("rooms") && json.get("rooms").isJsonArray()) {
                com.google.gson.JsonArray roomsArray = json.getAsJsonArray("rooms");
                for (JsonElement element : roomsArray) {
                    JsonObject roomJson = element.getAsJsonObject();

                    String roomId = roomJson.get("id").getAsString();
                    String roomName = roomJson.has("name") ? roomJson.get("name").getAsString() : roomId;
                    Instant roomCreatedAt = roomJson.has("createdAt")
                        ? Instant.parse(roomJson.get("createdAt").getAsString())
                        : Instant.now();

                    Room room = new Room(roomId, roomName, roomCreatedAt);
                    construction.addRoom(room);
                }
            }

            // Load anchors
            if (json.has("anchors") && json.get("anchors").isJsonObject()) {
                JsonObject anchorsObj = json.getAsJsonObject("anchors");
                if (anchorsObj.has("entrance") && anchorsObj.get("entrance").isJsonObject()) {
                    JsonObject entranceObj = anchorsObj.getAsJsonObject("entrance");
                    int x = entranceObj.get("x").getAsInt();
                    int y = entranceObj.get("y").getAsInt();
                    int z = entranceObj.get("z").getAsInt();
                    float yaw = entranceObj.has("yaw") ? entranceObj.get("yaw").getAsFloat() : 0f;
                    construction.getAnchors().setEntrance(new BlockPos(x, y, z), yaw);
                }
            }

            // Load spawned entity UUIDs
            if (json.has("spawnedEntityUuids") && json.get("spawnedEntityUuids").isJsonArray()) {
                com.google.gson.JsonArray uuidsArray = json.getAsJsonArray("spawnedEntityUuids");
                for (JsonElement element : uuidsArray) {
                    try {
                        UUID uuid = UUID.fromString(element.getAsString());
                        construction.trackSpawnedEntity(uuid);
                    } catch (IllegalArgumentException e) {
                        Architect.LOGGER.warn("Invalid UUID in spawnedEntityUuids: {}", element.getAsString());
                    }
                }
            }

            return construction;
        }
    }

    // ===== Blocks serialization (NBT) =====

    private void saveBlocks(Construction construction, Path directory) throws IOException {
        CompoundTag root = new CompoundTag();

        // Ottieni i bounds per normalizzare le coordinate
        var bounds = construction.getBounds();
        int offsetX = bounds.isValid() ? bounds.getMinX() : 0;
        int offsetY = bounds.isValid() ? bounds.getMinY() : 0;
        int offsetZ = bounds.isValid() ? bounds.getMinZ() : 0;

        // Palette: mappa blockState -> index
        Map<String, Integer> palette = new LinkedHashMap<>();
        ListTag paletteList = new ListTag();

        // Blocchi
        ListTag blocksList = new ListTag();

        for (Map.Entry<BlockPos, BlockState> entry : construction.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();

            // Serializza lo stato del blocco
            String stateString = serializeBlockState(state);

            // Aggiungi alla palette se non esiste
            int paletteIndex = palette.computeIfAbsent(stateString, s -> {
                CompoundTag paletteEntry = new CompoundTag();
                paletteEntry.putString("state", s);
                paletteList.add(paletteEntry);
                return palette.size();
            });

            // Aggiungi il blocco con coordinate NORMALIZZATE (relative a 0,0,0)
            CompoundTag blockTag = new CompoundTag();
            blockTag.putInt("x", pos.getX() - offsetX);
            blockTag.putInt("y", pos.getY() - offsetY);
            blockTag.putInt("z", pos.getZ() - offsetZ);
            blockTag.putInt("p", paletteIndex); // palette index

            // Se il blocco ha un NBT associato (block entity), includilo
            CompoundTag blockEntityNbt = construction.getBlockEntityNbt(pos);
            if (blockEntityNbt != null && !blockEntityNbt.isEmpty()) {
                blockTag.put("nbt", blockEntityNbt);
            }

            blocksList.add(blockTag);
        }

        root.put("palette", paletteList);
        root.put("blocks", blocksList);
        root.putInt("version", 1); // NBT format version

        // Salva i delta delle stanze (sempre con coordinate normalizzate)
        CompoundTag roomsTag = new CompoundTag();
        for (Room room : construction.getRooms().values()) {
            if (room.getChangedBlockCount() > 0) {
                CompoundTag roomTag = new CompoundTag();
                ListTag roomBlocksList = new ListTag();

                for (Map.Entry<BlockPos, BlockState> entry : room.getBlockChanges().entrySet()) {
                    BlockPos pos = entry.getKey();
                    BlockState state = entry.getValue();

                    String stateString = serializeBlockState(state);

                    // Aggiungi alla palette condivisa se non esiste
                    int paletteIndex = palette.computeIfAbsent(stateString, s -> {
                        CompoundTag paletteEntry = new CompoundTag();
                        paletteEntry.putString("state", s);
                        paletteList.add(paletteEntry);
                        return palette.size();
                    });

                    CompoundTag blockTag = new CompoundTag();
                    // Coordinate normalizzate (relative a 0,0,0)
                    blockTag.putInt("x", pos.getX() - offsetX);
                    blockTag.putInt("y", pos.getY() - offsetY);
                    blockTag.putInt("z", pos.getZ() - offsetZ);
                    blockTag.putInt("p", paletteIndex);

                    // NBT del block entity se presente
                    CompoundTag blockEntityNbt = room.getBlockEntityNbt(pos);
                    if (blockEntityNbt != null && !blockEntityNbt.isEmpty()) {
                        blockTag.put("nbt", blockEntityNbt);
                    }

                    roomBlocksList.add(blockTag);
                }

                roomTag.put("blocks", roomBlocksList);
                roomsTag.put(room.getId(), roomTag);
            }
        }
        if (!roomsTag.isEmpty()) {
            root.put("rooms", roomsTag);
        }

        Path blocksFile = directory.resolve("blocks.nbt");
        try (OutputStream os = Files.newOutputStream(blocksFile)) {
            NbtIo.writeCompressed(root, os);
        }
    }

    private void loadBlocks(Construction construction, Path directory) throws IOException {
        Path blocksFile = directory.resolve("blocks.nbt");

        if (!Files.exists(blocksFile)) {
            Architect.LOGGER.warn("No blocks file for construction: {}", construction.getId());
            return;
        }

        // Ottieni i bounds per denormalizzare le coordinate (caricati da loadMetadata)
        var bounds = construction.getBounds();
        int offsetX = bounds.isValid() ? bounds.getMinX() : 0;
        int offsetY = bounds.isValid() ? bounds.getMinY() : 0;
        int offsetZ = bounds.isValid() ? bounds.getMinZ() : 0;

        CompoundTag root;
        try (InputStream is = Files.newInputStream(blocksFile)) {
            root = NbtIo.readCompressed(is, NbtAccounter.unlimitedHeap());
        }

        // Carica palette - MC 1.21 API
        ListTag paletteList = root.getList("palette").orElse(new ListTag());
        List<BlockState> palette = new ArrayList<>();

        for (int i = 0; i < paletteList.size(); i++) {
            CompoundTag paletteEntry = paletteList.getCompound(i).orElseThrow();
            String stateString = paletteEntry.getString("state").orElse("");
            BlockState state = deserializeBlockState(stateString);
            palette.add(state);
        }

        // Carica blocchi (denormalizzando le coordinate)
        ListTag blocksList = root.getList("blocks").orElse(new ListTag());

        for (int i = 0; i < blocksList.size(); i++) {
            CompoundTag blockTag = blocksList.getCompound(i).orElseThrow();
            int x = blockTag.getInt("x").orElse(0) + offsetX;
            int y = blockTag.getInt("y").orElse(0) + offsetY;
            int z = blockTag.getInt("z").orElse(0) + offsetZ;
            int paletteIndex = blockTag.getInt("p").orElse(0);

            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = palette.get(paletteIndex);

            // Se presente, leggi l'NBT del block entity
            // Usa addBlockRaw per non alterare i bounds già caricati dal metadata
            CompoundTag blockEntityNbt = blockTag.getCompound("nbt").orElse(null);
            if (blockEntityNbt != null && !blockEntityNbt.isEmpty()) {
                construction.addBlockRaw(pos, state, blockEntityNbt);
            } else {
                construction.addBlockRaw(pos, state);
            }
        }

        // Carica i delta delle stanze (denormalizzando le coordinate)
        CompoundTag roomsTag = root.getCompound("rooms").orElse(null);
        if (roomsTag != null) {
            for (String roomId : roomsTag.keySet()) {
                Room room = construction.getRoom(roomId);
                if (room == null) {
                    Architect.LOGGER.warn("Room {} not found in metadata, skipping blocks", roomId);
                    continue;
                }

                CompoundTag roomTag = roomsTag.getCompound(roomId).orElse(null);
                if (roomTag == null) continue;

                ListTag roomBlocksList = roomTag.getList("blocks").orElse(new ListTag());

                for (int i = 0; i < roomBlocksList.size(); i++) {
                    CompoundTag blockTag = roomBlocksList.getCompound(i).orElseThrow();
                    int x = blockTag.getInt("x").orElse(0) + offsetX;
                    int y = blockTag.getInt("y").orElse(0) + offsetY;
                    int z = blockTag.getInt("z").orElse(0) + offsetZ;
                    int paletteIndex = blockTag.getInt("p").orElse(0);

                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = palette.get(paletteIndex);

                    CompoundTag blockEntityNbt = blockTag.getCompound("nbt").orElse(null);
                    if (blockEntityNbt != null && !blockEntityNbt.isEmpty()) {
                        room.setBlockChange(pos, state, blockEntityNbt);
                    } else {
                        room.setBlockChange(pos, state);
                    }
                }
            }
        }
    }

    /**
     * Serializza un BlockState in stringa.
     * Formato: "minecraft:stone" o "minecraft:oak_stairs[facing=north,half=bottom]"
     */
    private String serializeBlockState(BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        StringBuilder sb = new StringBuilder(blockId);

        // Aggiungi proprietà se presenti
        var properties = state.getValues();
        if (!properties.isEmpty()) {
            sb.append("[");
            boolean first = true;
            for (var entry : properties.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(entry.getKey().getName());
                sb.append("=");
                sb.append(entry.getValue().toString().toLowerCase());
            }
            sb.append("]");
        }

        return sb.toString();
    }

    /**
     * Deserializza un BlockState da stringa.
     */
    private BlockState deserializeBlockState(String stateString) {
        try {
            // Parse block ID e properties
            String blockIdStr;
            String propertiesStr = null;

            int bracketIndex = stateString.indexOf('[');
            if (bracketIndex != -1) {
                blockIdStr = stateString.substring(0, bracketIndex);
                propertiesStr = stateString.substring(bracketIndex + 1, stateString.length() - 1);
            } else {
                blockIdStr = stateString;
            }

            // Ottieni il blocco cercando nel registro
            Block block = net.minecraft.world.level.block.Blocks.AIR;

            // Itera sul registro per trovare il blocco con l'ID corrispondente
            for (Block b : BuiltInRegistries.BLOCK) {
                String registeredId = BuiltInRegistries.BLOCK.getKey(b).toString();
                if (registeredId.equals(blockIdStr)) {
                    block = b;
                    break;
                }
            }

            BlockState state = block.defaultBlockState();

            // Applica le proprietà
            if (propertiesStr != null && !propertiesStr.isEmpty()) {
                for (String propPair : propertiesStr.split(",")) {
                    String[] kv = propPair.split("=");
                    if (kv.length == 2) {
                        state = applyProperty(state, kv[0].trim(), kv[1].trim());
                    }
                }
            }

            return state;

        } catch (Exception e) {
            Architect.LOGGER.warn("Failed to deserialize block state: {}", stateString, e);
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BlockState applyProperty(BlockState state, String propertyName, String value) {
        for (var property : state.getProperties()) {
            if (property.getName().equals(propertyName)) {
                Optional<?> parsedValue = property.getValue(value);
                if (parsedValue.isPresent()) {
                    return state.setValue((net.minecraft.world.level.block.state.properties.Property) property,
                        (Comparable) parsedValue.get());
                }
            }
        }
        return state;
    }

    // ===== Entities serialization (NBT) =====

    /**
     * Saves construction entities to entities.nbt.
     * Structure:
     * root {
     *     version: 2
     *     entities: [
     *         { type: "minecraft:armor_stand", x: 1.5, y: 0.0, z: 2.3, yaw: 90.0, pitch: 0.0, nbt: {...} },
     *         ...
     *     ]
     * }
     * Note: UUID is NOT stored - it's only a runtime identifier.
     */
    private void saveEntities(Construction construction, Path directory) throws IOException {
        // Count all entities (base + rooms)
        boolean hasAnyEntities = !construction.getEntities().isEmpty();
        if (!hasAnyEntities) {
            for (Room room : construction.getRooms().values()) {
                if (!room.getEntities().isEmpty()) {
                    hasAnyEntities = true;
                    break;
                }
            }
        }

        // If no entities, delete existing file if present
        if (!hasAnyEntities) {
            Path entitiesFile = directory.resolve("entities.nbt");
            if (Files.exists(entitiesFile)) {
                Files.delete(entitiesFile);
            }
            return;
        }

        CompoundTag root = new CompoundTag();
        root.putInt("version", 2); // Version 2: no UUID in saved data

        // Base entities
        ListTag entitiesList = new ListTag();

        for (EntityData data : construction.getEntities()) {
            CompoundTag entityTag = new CompoundTag();
            entityTag.putString("type", data.getEntityType());
            entityTag.putDouble("x", data.getRelativePos().x);
            entityTag.putDouble("y", data.getRelativePos().y);
            entityTag.putDouble("z", data.getRelativePos().z);
            entityTag.putFloat("yaw", data.getYaw());
            entityTag.putFloat("pitch", data.getPitch());
            entityTag.put("nbt", data.getNbt().copy());

            entitiesList.add(entityTag);
        }

        root.put("entities", entitiesList);

        // Room entities
        CompoundTag roomsTag = new CompoundTag();
        for (Room room : construction.getRooms().values()) {
            if (!room.getEntities().isEmpty()) {
                CompoundTag roomTag = new CompoundTag();
                ListTag roomEntitiesList = new ListTag();

                for (EntityData data : room.getEntities()) {
                    CompoundTag entityTag = new CompoundTag();
                    entityTag.putString("type", data.getEntityType());
                    entityTag.putDouble("x", data.getRelativePos().x);
                    entityTag.putDouble("y", data.getRelativePos().y);
                    entityTag.putDouble("z", data.getRelativePos().z);
                    entityTag.putFloat("yaw", data.getYaw());
                    entityTag.putFloat("pitch", data.getPitch());
                    entityTag.put("nbt", data.getNbt().copy());

                    roomEntitiesList.add(entityTag);
                }

                roomTag.put("entities", roomEntitiesList);
                roomsTag.put(room.getId(), roomTag);
            }
        }
        if (!roomsTag.isEmpty()) {
            root.put("rooms", roomsTag);
        }

        Path entitiesFile = directory.resolve("entities.nbt");
        try (OutputStream os = Files.newOutputStream(entitiesFile)) {
            NbtIo.writeCompressed(root, os);
        }
    }

    /**
     * Loads entities from entities.nbt.
     * Backwards compatible: if the file doesn't exist, does nothing.
     * Supports both version 1 (with UUID) and version 2 (without UUID).
     */
    private void loadEntities(Construction construction, Path directory) throws IOException {
        Path entitiesFile = directory.resolve("entities.nbt");

        if (!Files.exists(entitiesFile)) {
            // Backwards compatibility: old constructions without entities
            return;
        }

        CompoundTag root;
        try (InputStream is = Files.newInputStream(entitiesFile)) {
            root = NbtIo.readCompressed(is, NbtAccounter.unlimitedHeap());
        }

        // Base entities
        ListTag entitiesList = root.getList("entities").orElse(new ListTag());

        for (int i = 0; i < entitiesList.size(); i++) {
            CompoundTag entityTag = entitiesList.getCompound(i).orElseThrow();

            String type = entityTag.getString("type").orElse("");
            double x = entityTag.getDouble("x").orElse(0.0);
            double y = entityTag.getDouble("y").orElse(0.0);
            double z = entityTag.getDouble("z").orElse(0.0);
            float yaw = entityTag.getFloat("yaw").orElse(0.0f);
            float pitch = entityTag.getFloat("pitch").orElse(0.0f);
            CompoundTag nbt = entityTag.getCompound("nbt").orElse(new CompoundTag());

            Vec3 relativePos = new Vec3(x, y, z);
            EntityData data = new EntityData(type, relativePos, yaw, pitch, nbt);

            // No UUID needed - just add to list
            construction.addEntity(data);
        }

        // Room entities
        CompoundTag roomsTag = root.getCompound("rooms").orElse(null);
        if (roomsTag != null) {
            for (String roomId : roomsTag.keySet()) {
                Room room = construction.getRoom(roomId);
                if (room == null) {
                    Architect.LOGGER.warn("Room {} not found in metadata, skipping entities", roomId);
                    continue;
                }

                CompoundTag roomTag = roomsTag.getCompound(roomId).orElse(null);
                if (roomTag == null) continue;

                ListTag roomEntitiesList = roomTag.getList("entities").orElse(new ListTag());

                for (int i = 0; i < roomEntitiesList.size(); i++) {
                    CompoundTag entityTag = roomEntitiesList.getCompound(i).orElseThrow();

                    String type = entityTag.getString("type").orElse("");
                    double x = entityTag.getDouble("x").orElse(0.0);
                    double y = entityTag.getDouble("y").orElse(0.0);
                    double z = entityTag.getDouble("z").orElse(0.0);
                    float yaw = entityTag.getFloat("yaw").orElse(0.0f);
                    float pitch = entityTag.getFloat("pitch").orElse(0.0f);
                    CompoundTag nbt = entityTag.getCompound("nbt").orElse(new CompoundTag());

                    Vec3 relativePos = new Vec3(x, y, z);
                    EntityData data = new EntityData(type, relativePos, yaw, pitch, nbt);

                    // No UUID needed - just add to list
                    room.addEntity(data);
                }
            }
        }
    }

}
