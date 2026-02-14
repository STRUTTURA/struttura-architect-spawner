package it.magius.struttura.architect.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.config.ArchitectConfig;
import it.magius.struttura.architect.ingame.model.EnsureBoundsMode;
import it.magius.struttura.architect.ingame.model.InGameListInfo;
import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import it.magius.struttura.architect.ingame.model.SpawnableList;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.EntityData;
import it.magius.struttura.architect.model.ModInfo;
import it.magius.struttura.architect.model.Room;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Client API per comunicare con il backend STRUTTURA.
 * Gestisce richieste asincrone con supporto per upload di grandi quantità di dati.
 */
public class ApiClient {

    private static final Gson GSON = new GsonBuilder().create();
    private static final AtomicBoolean REQUEST_IN_PROGRESS = new AtomicBoolean(false);

    /**
     * Risultato di una richiesta API.
     */
    public record ApiResponse(int statusCode, String message, boolean success, int version) {}

    /**
     * Risultato di una richiesta pull con i dati della costruzione.
     */
    public record PullResponse(int statusCode, String message, boolean success, Construction construction) {}

    /**
     * Verifica se una richiesta è attualmente in corso.
     */
    public static boolean isRequestInProgress() {
        return REQUEST_IN_PROGRESS.get();
    }

    /**
     * Checks if cloud access is currently denied by the server.
     * When denied, all API calls except fetchModSettings and update downloads are blocked.
     */
    public static boolean isCloudDenied() {
        return ArchitectConfig.getInstance().isCloudDenied();
    }

    /**
     * Esegue il push di una costruzione al server in modo asincrono.
     *
     * @param construction la costruzione da inviare
     * @param onComplete callback chiamato al completamento (sul main thread)
     * @return true se la richiesta è stata avviata, false se c'è già una richiesta in corso
     */
    public static boolean pushConstruction(Construction construction, Consumer<ApiResponse> onComplete) {
        return pushConstruction(construction, false, false, onComplete);
    }

    /**
     * Esegue il push di una costruzione al server in modo asincrono.
     *
     * @param construction la costruzione da inviare
     * @param purge se true, elimina tutti i dati esistenti della costruzione prima di aggiungere i nuovi
     * @param onComplete callback chiamato al completamento (sul main thread)
     * @return true se la richiesta è stata avviata, false se c'è già una richiesta in corso
     */
    public static boolean pushConstruction(Construction construction, boolean purge, Consumer<ApiResponse> onComplete) {
        return pushConstruction(construction, purge, false, onComplete);
    }

    /**
     * Esegue il push di una costruzione al server in modo asincrono.
     *
     * @param construction la costruzione da inviare
     * @param purge se true, elimina tutti i dati esistenti della costruzione prima di aggiungere i nuovi
     * @param jsonFormat se true, serializza blocchi/entita in formato JSON invece che NBT
     * @param onComplete callback chiamato al completamento (sul main thread)
     * @return true se la richiesta è stata avviata, false se c'è già una richiesta in corso
     */
    public static boolean pushConstruction(Construction construction, boolean purge, boolean jsonFormat, Consumer<ApiResponse> onComplete) {
        if (isCloudDenied()) {
            onComplete.accept(new ApiResponse(403, "Cloud access denied: mod version too old", false, 0));
            return true;
        }
        if (!REQUEST_IN_PROGRESS.compareAndSet(false, true)) {
            return false;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return executePush(construction, purge, jsonFormat);
            } catch (Exception e) {
                Architect.LOGGER.error("Push request failed", e);
                return new ApiResponse(0, "Error: " + e.getMessage(), false, 0);
            } finally {
                REQUEST_IN_PROGRESS.set(false);
            }
        }).thenAccept(onComplete);

        return true;
    }

    private static ApiResponse executePush(Construction construction, boolean purge, boolean jsonFormat) throws Exception {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();
        String url = endpoint + "/building/add/" + construction.getId();
        if (purge) {
            url += "?purge=yes";
        }

        Architect.LOGGER.info("Pushing construction {} to {}", construction.getId(), url);

        // Build JSON payload (blocks/entities as base64 NBT or JSON based on format flag)
        JsonObject payload = buildPayload(construction, jsonFormat);
        byte[] jsonBytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);

        Architect.LOGGER.info("Payload size: {} bytes ({} KB)", jsonBytes.length, jsonBytes.length / 1024);

        // Configura la connessione
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(config.getRequestTimeout() * 1000);
            conn.setReadTimeout(config.getRequestTimeout() * 1000);

            // Headers
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", config.getAuth());
            conn.setRequestProperty("X-Api-Key", config.getApikey());
            conn.setRequestProperty("Content-Length", String.valueOf(jsonBytes.length));

            // Streaming output per efficienza con grandi payload
            conn.setFixedLengthStreamingMode(jsonBytes.length);

            // Invia il body
            try (OutputStream os = new BufferedOutputStream(conn.getOutputStream(), 65536)) {
                os.write(jsonBytes);
                os.flush();
            }

            // Leggi la risposta
            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn);

            Architect.LOGGER.info("Push response: {} - {}", statusCode, responseBody);

            // Parse risposta JSON se possibile
            String message = parseResponseMessage(responseBody, statusCode);
            int version = parseResponseVersion(responseBody);
            boolean success = statusCode >= 200 && statusCode < 300;

            return new ApiResponse(statusCode, message, success, version);

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Costruisce il payload JSON per il push.
     * @param jsonFormat if true, blocks/entities are serialized as JSON with type hints instead of compressed NBT base64
     */
    private static JsonObject buildPayload(Construction construction, boolean jsonFormat) throws IOException {
        JsonObject json = new JsonObject();

        // Titoli multilingua: { "en": "Medieval Tower", "it": "Torre Medievale" }
        JsonObject titles = new JsonObject();
        for (var entry : construction.getTitles().entrySet()) {
            titles.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("titles", titles);

        // Descrizioni brevi multilingua: { "en": "A defensive tower", "it": "Una torre difensiva" }
        JsonObject shortDescriptions = new JsonObject();
        for (var entry : construction.getShortDescriptions().entrySet()) {
            shortDescriptions.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("shortDescriptions", shortDescriptions);

        // Descrizioni complete multilingua
        JsonObject descriptions = new JsonObject();
        for (var entry : construction.getDescriptions().entrySet()) {
            descriptions.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("descriptions", descriptions);

        // Conteggi totali (base + tutte le stanze)
        int blocksCount = construction.getTotalBlockCount();
        int entitiesCount = construction.getTotalEntityCount();
        int mobsCount = construction.getTotalMobCount();
        int commandBlocksCount = construction.getTotalCommandBlockCount();
        int roomsCount = construction.getRooms().size();

        Architect.LOGGER.info("Push counts for {}: blocks={}, entities={}, mobs={}, commandBlocks={}, rooms={}",
            construction.getId(), blocksCount, entitiesCount, mobsCount, commandBlocksCount, roomsCount);

        json.addProperty("blocksCount", blocksCount);
        json.addProperty("entitiesCount", entitiesCount);
        json.addProperty("mobsCount", mobsCount);
        json.addProperty("commandBlocksCount", commandBlocksCount);
        json.addProperty("roomsCount", roomsCount);

        // Bounds (dimensions)
        JsonObject bounds = new JsonObject();
        var b = construction.getBounds();
        if (b.isValid()) {
            // bounds.x/y/z are the dimensions (width, height, depth)
            bounds.addProperty("x", b.getSizeX());
            bounds.addProperty("y", b.getSizeY());
            bounds.addProperty("z", b.getSizeZ());
        }
        json.add("bounds", bounds);

        // Calcola e aggiungi i mod richiesti
        construction.computeRequiredMods();
        JsonObject modsObject = new JsonObject();
        for (ModInfo mod : construction.getRequiredMods().values()) {
            JsonObject modJson = new JsonObject();
            modJson.addProperty("displayName", mod.getDisplayName());
            modJson.addProperty("blocksCount", mod.getBlockCount());
            modJson.addProperty("entitiesCount", mod.getEntitiesCount());
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

        // Anchors (entrance anchor with normalized coordinates)
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

        // Versione del mod Struttura
        json.addProperty("modVersion", Architect.MOD_VERSION);

        // Check if any entities exist (base + rooms)
        boolean hasEntities = !construction.getEntities().isEmpty();
        if (!hasEntities) {
            for (Room room : construction.getRooms().values()) {
                if (!room.getEntities().isEmpty()) {
                    hasEntities = true;
                    break;
                }
            }
        }

        if (jsonFormat) {
            // JSON format: blocks/entities as JSON objects (REST API expects objects, not strings)
            json.addProperty("contentType", "application/json");

            JsonObject blocksJson = serializeBlocksToJson(construction);
            json.add("blocks", blocksJson);

            Architect.LOGGER.debug("Blocks JSON size: {} bytes", GSON.toJson(blocksJson).length());

            if (hasEntities) {
                JsonObject entitiesJson = serializeEntitiesToJson(construction);
                json.add("entities", entitiesJson);

                Architect.LOGGER.debug("Entities JSON size: {} bytes", GSON.toJson(entitiesJson).length());
            }
        } else {
            // NBT format: blocks/entities as base64-encoded compressed NBT (default)
            byte[] nbtBytes = serializeBlocksToNbt(construction);
            String blocksBase64 = Base64.getEncoder().encodeToString(nbtBytes);
            json.addProperty("blocks", blocksBase64);

            Architect.LOGGER.debug("NBT size: {} bytes, Base64 size: {} bytes",
                nbtBytes.length, blocksBase64.length());

            if (hasEntities) {
                byte[] entitiesNbtBytes = serializeEntitiesToNbt(construction);
                String entitiesBase64 = Base64.getEncoder().encodeToString(entitiesNbtBytes);
                json.addProperty("entities", entitiesBase64);

                Architect.LOGGER.debug("Entities NBT size: {} bytes, Base64 size: {} bytes",
                    entitiesNbtBytes.length, entitiesBase64.length());
            }
        }

        return json;
    }

    /**
     * Serializza i blocchi della costruzione in formato NBT compresso.
     * Le coordinate vengono normalizzate (relative a 0,0,0) sottraendo i bounds minimi.
     */
    private static byte[] serializeBlocksToNbt(Construction construction) throws IOException {
        CompoundTag root = new CompoundTag();

        // Ottieni i bounds per normalizzare le coordinate
        var bounds = construction.getBounds();
        int offsetX = bounds.isValid() ? bounds.getMinX() : 0;
        int offsetY = bounds.isValid() ? bounds.getMinY() : 0;
        int offsetZ = bounds.isValid() ? bounds.getMinZ() : 0;

        Architect.LOGGER.debug("Serializing blocks with offset ({},{},{})", offsetX, offsetY, offsetZ);

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

            // Aggiungi il blocco con coordinate RELATIVE (normalizzate a 0,0,0)
            CompoundTag blockTag = new CompoundTag();
            blockTag.putInt("x", pos.getX() - offsetX);
            blockTag.putInt("y", pos.getY() - offsetY);
            blockTag.putInt("z", pos.getZ() - offsetZ);
            blockTag.putInt("p", paletteIndex);

            // Se il blocco ha un NBT associato (block entity), includilo
            CompoundTag blockEntityNbt = construction.getBlockEntityNbt(pos);
            if (blockEntityNbt != null && !blockEntityNbt.isEmpty()) {
                blockTag.put("nbt", blockEntityNbt);
            }

            blocksList.add(blockTag);
        }

        root.put("palette", paletteList);
        root.put("blocks", blocksList);
        root.putInt("version", 1);

        // Salva i delta delle stanze
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
                    // Coordinate relative (normalizzate)
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

        // Comprimi in memoria
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, baos);
        return baos.toByteArray();
    }

    /**
     * Serializza le entità della costruzione in formato NBT compresso.
     * Le coordinate vengono normalizzate (relative a 0,0,0) sottraendo i bounds minimi.
     * Anche i tag TileX/Y/Z delle entità hanging vengono normalizzati.
     */
    private static byte[] serializeEntitiesToNbt(Construction construction) throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("version", 1);

        var bounds = construction.getBounds();
        int minX = bounds.isValid() ? bounds.getMinX() : 0;
        int minY = bounds.isValid() ? bounds.getMinY() : 0;
        int minZ = bounds.isValid() ? bounds.getMinZ() : 0;

        ListTag entitiesList = new ListTag();

        for (EntityData data : construction.getEntities()) {
            CompoundTag entityTag = new CompoundTag();
            entityTag.putString("type", data.getEntityType());
            entityTag.putDouble("x", data.getRelativePos().x);
            entityTag.putDouble("y", data.getRelativePos().y);
            entityTag.putDouble("z", data.getRelativePos().z);
            entityTag.putFloat("yaw", data.getYaw());
            entityTag.putFloat("pitch", data.getPitch());

            // Copia l'NBT e normalizza coordinate per entità hanging
            // Le coordinate sono già normalizzate in EntityData.fromEntity(), ma ri-normalizziamo
            // per sicurezza nel caso vengano aggiunte entità in modo diverso
            CompoundTag nbt = data.getNbt().copy();

            // MC 1.21+ usa "block_pos" (CompoundTag con X, Y, Z)
            if (nbt.contains("block_pos")) {
                nbt.getCompound("block_pos").ifPresent(blockPos -> {
                    int x = blockPos.getIntOr("X", 0);
                    int y = blockPos.getIntOr("Y", 0);
                    int z = blockPos.getIntOr("Z", 0);

                    // Normalizza sottraendo i bounds minimi
                    blockPos.putInt("X", x - minX);
                    blockPos.putInt("Y", y - minY);
                    blockPos.putInt("Z", z - minZ);
                });
            }
            // Fallback per vecchi formati (TileX/Y/Z)
            else if (nbt.contains("TileX") && nbt.contains("TileY") && nbt.contains("TileZ")) {
                int tileX = nbt.getIntOr("TileX", 0);
                int tileY = nbt.getIntOr("TileY", 0);
                int tileZ = nbt.getIntOr("TileZ", 0);

                // Normalizza sottraendo i bounds minimi
                nbt.putInt("TileX", tileX - minX);
                nbt.putInt("TileY", tileY - minY);
                nbt.putInt("TileZ", tileZ - minZ);
            }
            entityTag.put("nbt", nbt);

            entitiesList.add(entityTag);
        }

        root.put("entities", entitiesList);

        // Entità delle stanze
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

                    // Copia l'NBT e normalizza coordinate per entità hanging
                    CompoundTag nbt = data.getNbt().copy();

                    // MC 1.21+ usa "block_pos" (CompoundTag con X, Y, Z)
                    if (nbt.contains("block_pos")) {
                        nbt.getCompound("block_pos").ifPresent(blockPos -> {
                            int x = blockPos.getIntOr("X", 0);
                            int y = blockPos.getIntOr("Y", 0);
                            int z = blockPos.getIntOr("Z", 0);

                            // Normalizza sottraendo i bounds minimi
                            blockPos.putInt("X", x - minX);
                            blockPos.putInt("Y", y - minY);
                            blockPos.putInt("Z", z - minZ);
                        });
                    }
                    // Fallback per vecchi formati (TileX/Y/Z)
                    else if (nbt.contains("TileX") && nbt.contains("TileY") && nbt.contains("TileZ")) {
                        int tileX = nbt.getIntOr("TileX", 0);
                        int tileY = nbt.getIntOr("TileY", 0);
                        int tileZ = nbt.getIntOr("TileZ", 0);

                        // Normalizza sottraendo i bounds minimi
                        nbt.putInt("TileX", tileX - minX);
                        nbt.putInt("TileY", tileY - minY);
                        nbt.putInt("TileZ", tileZ - minZ);
                    }
                    entityTag.put("nbt", nbt);

                    roomEntitiesList.add(entityTag);
                }

                roomTag.put("entities", roomEntitiesList);
                roomsTag.put(room.getId(), roomTag);
            }
        }
        if (!roomsTag.isEmpty()) {
            root.put("rooms", roomsTag);
        }

        // Comprimi in memoria
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, baos);
        return baos.toByteArray();
    }

    /**
     * Serializes blocks to JSON format with NBT type hints.
     * Structure mirrors serializeBlocksToNbt() but produces a JsonObject.
     * Top-level fields (x, y, z, p) are plain JSON numbers.
     * Block entity NBT uses NbtJsonConverter type-hint suffixes.
     */
    private static JsonObject serializeBlocksToJson(Construction construction) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);

        var bounds = construction.getBounds();
        int offsetX = bounds.isValid() ? bounds.getMinX() : 0;
        int offsetY = bounds.isValid() ? bounds.getMinY() : 0;
        int offsetZ = bounds.isValid() ? bounds.getMinZ() : 0;

        // Palette: blockState string -> index
        Map<String, Integer> palette = new LinkedHashMap<>();
        com.google.gson.JsonArray paletteArray = new com.google.gson.JsonArray();

        // Blocks
        com.google.gson.JsonArray blocksArray = new com.google.gson.JsonArray();

        for (Map.Entry<BlockPos, BlockState> entry : construction.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();

            String stateString = serializeBlockState(state);

            int paletteIndex = palette.computeIfAbsent(stateString, s -> {
                JsonObject paletteEntry = new JsonObject();
                paletteEntry.addProperty("state", s);
                paletteArray.add(paletteEntry);
                return palette.size();
            });

            JsonObject blockObj = new JsonObject();
            blockObj.addProperty("x", pos.getX() - offsetX);
            blockObj.addProperty("y", pos.getY() - offsetY);
            blockObj.addProperty("z", pos.getZ() - offsetZ);
            blockObj.addProperty("p", paletteIndex);

            CompoundTag blockEntityNbt = construction.getBlockEntityNbt(pos);
            if (blockEntityNbt != null && !blockEntityNbt.isEmpty()) {
                blockObj.add("nbt", NbtJsonConverter.compoundTagToJson(blockEntityNbt));
            }

            blocksArray.add(blockObj);
        }

        root.add("palette", paletteArray);
        root.add("blocks", blocksArray);

        // Room deltas
        JsonObject roomsObj = new JsonObject();
        for (Room room : construction.getRooms().values()) {
            if (room.getChangedBlockCount() > 0) {
                JsonObject roomObj = new JsonObject();
                com.google.gson.JsonArray roomBlocksArray = new com.google.gson.JsonArray();

                for (Map.Entry<BlockPos, BlockState> entry : room.getBlockChanges().entrySet()) {
                    BlockPos pos = entry.getKey();
                    BlockState state = entry.getValue();

                    String stateString = serializeBlockState(state);

                    int paletteIndex = palette.computeIfAbsent(stateString, s -> {
                        JsonObject paletteEntry = new JsonObject();
                        paletteEntry.addProperty("state", s);
                        paletteArray.add(paletteEntry);
                        return palette.size();
                    });

                    JsonObject blockObj = new JsonObject();
                    blockObj.addProperty("x", pos.getX() - offsetX);
                    blockObj.addProperty("y", pos.getY() - offsetY);
                    blockObj.addProperty("z", pos.getZ() - offsetZ);
                    blockObj.addProperty("p", paletteIndex);

                    CompoundTag blockEntityNbt = room.getBlockEntityNbt(pos);
                    if (blockEntityNbt != null && !blockEntityNbt.isEmpty()) {
                        blockObj.add("nbt", NbtJsonConverter.compoundTagToJson(blockEntityNbt));
                    }

                    roomBlocksArray.add(blockObj);
                }

                roomObj.add("blocks", roomBlocksArray);
                roomsObj.add(room.getId(), roomObj);
            }
        }
        if (roomsObj.size() > 0) {
            root.add("rooms", roomsObj);
        }

        return root;
    }

    /**
     * Serializes entities to JSON format with NBT type hints.
     * Structure mirrors serializeEntitiesToNbt() but produces a JsonObject.
     * Top-level fields (type, x, y, z, yaw, pitch) are plain JSON values.
     * Entity NBT uses NbtJsonConverter type-hint suffixes.
     */
    private static JsonObject serializeEntitiesToJson(Construction construction) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);

        var bounds = construction.getBounds();
        int minX = bounds.isValid() ? bounds.getMinX() : 0;
        int minY = bounds.isValid() ? bounds.getMinY() : 0;
        int minZ = bounds.isValid() ? bounds.getMinZ() : 0;

        com.google.gson.JsonArray entitiesArray = new com.google.gson.JsonArray();

        for (EntityData data : construction.getEntities()) {
            JsonObject entityObj = new JsonObject();
            entityObj.addProperty("type", data.getEntityType());
            entityObj.addProperty("x", data.getRelativePos().x);
            entityObj.addProperty("y", data.getRelativePos().y);
            entityObj.addProperty("z", data.getRelativePos().z);
            entityObj.addProperty("yaw", data.getYaw());
            entityObj.addProperty("pitch", data.getPitch());

            // Copy NBT and normalize coordinates for hanging entities
            CompoundTag nbt = data.getNbt().copy();
            normalizeEntityNbtCoordinates(nbt, minX, minY, minZ);
            entityObj.add("nbt", NbtJsonConverter.compoundTagToJson(nbt));

            entitiesArray.add(entityObj);
        }

        root.add("entities", entitiesArray);

        // Room entities
        JsonObject roomsObj = new JsonObject();
        for (Room room : construction.getRooms().values()) {
            if (!room.getEntities().isEmpty()) {
                JsonObject roomObj = new JsonObject();
                com.google.gson.JsonArray roomEntitiesArray = new com.google.gson.JsonArray();

                for (EntityData data : room.getEntities()) {
                    JsonObject entityObj = new JsonObject();
                    entityObj.addProperty("type", data.getEntityType());
                    entityObj.addProperty("x", data.getRelativePos().x);
                    entityObj.addProperty("y", data.getRelativePos().y);
                    entityObj.addProperty("z", data.getRelativePos().z);
                    entityObj.addProperty("yaw", data.getYaw());
                    entityObj.addProperty("pitch", data.getPitch());

                    CompoundTag nbt = data.getNbt().copy();
                    normalizeEntityNbtCoordinates(nbt, minX, minY, minZ);
                    entityObj.add("nbt", NbtJsonConverter.compoundTagToJson(nbt));

                    roomEntitiesArray.add(entityObj);
                }

                roomObj.add("entities", roomEntitiesArray);
                roomsObj.add(room.getId(), roomObj);
            }
        }
        if (roomsObj.size() > 0) {
            root.add("rooms", roomsObj);
        }

        return root;
    }

    /**
     * Normalizes entity NBT coordinates for hanging entities.
     * Shared by both NBT and JSON serialization of entities.
     */
    private static void normalizeEntityNbtCoordinates(CompoundTag nbt, int minX, int minY, int minZ) {
        // MC 1.21+ uses "block_pos" (CompoundTag with X, Y, Z)
        if (nbt.contains("block_pos")) {
            nbt.getCompound("block_pos").ifPresent(blockPos -> {
                int x = blockPos.getIntOr("X", 0);
                int y = blockPos.getIntOr("Y", 0);
                int z = blockPos.getIntOr("Z", 0);

                blockPos.putInt("X", x - minX);
                blockPos.putInt("Y", y - minY);
                blockPos.putInt("Z", z - minZ);
            });
        }
        // Fallback for old formats (TileX/Y/Z)
        else if (nbt.contains("TileX") && nbt.contains("TileY") && nbt.contains("TileZ")) {
            int tileX = nbt.getIntOr("TileX", 0);
            int tileY = nbt.getIntOr("TileY", 0);
            int tileZ = nbt.getIntOr("TileZ", 0);

            nbt.putInt("TileX", tileX - minX);
            nbt.putInt("TileY", tileY - minY);
            nbt.putInt("TileZ", tileZ - minZ);
        }
    }

    /**
     * Serializza un BlockState in stringa.
     */
    private static String serializeBlockState(BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        StringBuilder sb = new StringBuilder(blockId);

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
     * Legge la risposta dalla connessione.
     */
    private static String readResponse(HttpURLConnection conn) {
        try {
            InputStream is = conn.getResponseCode() >= 400
                ? conn.getErrorStream()
                : conn.getInputStream();

            if (is == null) {
                return "";
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (IOException e) {
            return "Failed to read response: " + e.getMessage();
        }
    }

    /**
     * Estrae il messaggio dalla risposta JSON o usa un default.
     * Priorità: message > error > default
     */
    private static String parseResponseMessage(String responseBody, int statusCode) {
        if (responseBody == null || responseBody.isEmpty()) {
            return getDefaultMessage(statusCode);
        }

        try {
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            if (json.has("message") && !json.get("message").isJsonNull()) {
                return json.get("message").getAsString();
            }
            if (json.has("error") && !json.get("error").isJsonNull()) {
                return json.get("error").getAsString();
            }
        } catch (Exception e) {
            // Non è JSON valido, usa il body direttamente se breve
            if (responseBody.length() < 200) {
                return responseBody;
            }
        }

        return getDefaultMessage(statusCode);
    }

    /**
     * Extracts the version number from the response JSON body.
     * Returns 0 if parsing fails or version is not present.
     */
    private static int parseResponseVersion(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return 0;
        }
        try {
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            if (json.has("version") && !json.get("version").isJsonNull()) {
                return json.get("version").getAsInt();
            }
        } catch (Exception e) {
            // Not valid JSON or no version field
        }
        return 0;
    }

    private static String getDefaultMessage(int statusCode) {
        return switch (statusCode) {
            case 200, 201 -> "Success";
            case 400 -> "Bad request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not found";
            case 409 -> "Conflict";
            case 429 -> "Rate limited";
            case 500 -> "Server error";
            case 0 -> "Connection failed";
            default -> "HTTP " + statusCode;
        };
    }

    /**
     * Esegue l'upload di uno screenshot al server in modo asincrono.
     *
     * @param constructionId l'ID della costruzione (formato RDNS)
     * @param imageData i dati JPEG dell'immagine
     * @param filename il nome del file (es: "screenshot_001.jpg")
     * @param title il titolo dell'immagine
     * @param onComplete callback chiamato al completamento
     * @return true se la richiesta è stata avviata, false se c'è già una richiesta in corso
     */
    public static boolean uploadScreenshot(String constructionId, byte[] imageData, String filename,
                                           String title, Consumer<ApiResponse> onComplete) {
        return uploadScreenshot(constructionId, imageData, filename, title, false, onComplete);
    }

    /**
     * Esegue l'upload di uno screenshot al server in modo asincrono.
     *
     * @param constructionId l'ID della costruzione (formato RDNS)
     * @param imageData i dati JPEG dell'immagine
     * @param filename il nome del file (es: "screenshot_001.jpg")
     * @param title il titolo dell'immagine
     * @param purge se true, elimina tutti gli screenshot esistenti prima di aggiungere il nuovo
     * @param onComplete callback chiamato al completamento
     * @return true se la richiesta è stata avviata, false se c'è già una richiesta in corso
     */
    public static boolean uploadScreenshot(String constructionId, byte[] imageData, String filename,
                                           String title, boolean purge, Consumer<ApiResponse> onComplete) {
        if (isCloudDenied()) {
            onComplete.accept(new ApiResponse(403, "Cloud access denied: mod version too old", false, 0));
            return true;
        }
        if (!REQUEST_IN_PROGRESS.compareAndSet(false, true)) {
            return false;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return executeUploadScreenshot(constructionId, imageData, filename, title, purge);
            } catch (Exception e) {
                Architect.LOGGER.error("Screenshot upload failed", e);
                return new ApiResponse(0, "Error: " + e.getMessage(), false, 0);
            } finally {
                REQUEST_IN_PROGRESS.set(false);
            }
        }).thenAccept(onComplete);

        return true;
    }

    /**
     * Esegue l'upload dello screenshot.
     */
    private static ApiResponse executeUploadScreenshot(String constructionId, byte[] imageData,
                                                       String filename, String title, boolean purge) throws Exception {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();
        String url = endpoint + "/building/" + constructionId + "/images";
        if (purge) {
            url += "?purge=yes";
        }

        Architect.LOGGER.info("Uploading screenshot for {} to {}", constructionId, url);

        // Prepara il payload JSON
        JsonObject payload = new JsonObject();
        payload.addProperty("filename", filename);
        payload.addProperty("title", title != null && !title.isEmpty() ? title : "Screenshot");
        payload.addProperty("data", Base64.getEncoder().encodeToString(imageData));

        byte[] jsonBytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);

        Architect.LOGGER.info("Screenshot payload size: {} bytes ({} KB)", jsonBytes.length, jsonBytes.length / 1024);

        // Configura la connessione
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(config.getRequestTimeout() * 1000);
            conn.setReadTimeout(config.getRequestTimeout() * 1000);

            // Headers
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", config.getAuth());
            conn.setRequestProperty("X-Api-Key", config.getApikey());
            conn.setRequestProperty("Content-Length", String.valueOf(jsonBytes.length));

            // Streaming output per efficienza con grandi payload
            conn.setFixedLengthStreamingMode(jsonBytes.length);

            // Invia il body
            try (OutputStream os = new BufferedOutputStream(conn.getOutputStream(), 65536)) {
                os.write(jsonBytes);
                os.flush();
            }

            // Leggi la risposta
            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn);

            Architect.LOGGER.info("Screenshot upload response: {} - {}", statusCode, responseBody);

            // Parse risposta JSON se possibile
            String message = parseResponseMessage(responseBody, statusCode);
            boolean success = statusCode >= 200 && statusCode < 300;

            return new ApiResponse(statusCode, message, success, 0);

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Esegue il pull di una costruzione dal server in modo asincrono.
     * Questo metodo usa un lock globale per prevenire richieste concorrenti.
     * Per download batch (es. InGame pre-download), usare {@link #downloadConstruction} invece.
     *
     * @param constructionId l'ID della costruzione da scaricare
     * @param onComplete callback chiamato al completamento
     * @return true se la richiesta è stata avviata, false se c'è già una richiesta in corso
     */
    public static boolean pullConstruction(String constructionId, Consumer<PullResponse> onComplete) {
        if (isCloudDenied()) {
            onComplete.accept(new PullResponse(403, "Cloud access denied: mod version too old", false, null));
            return true;
        }
        if (!REQUEST_IN_PROGRESS.compareAndSet(false, true)) {
            return false;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return executePull(constructionId);
            } catch (Exception e) {
                Architect.LOGGER.error("Pull request failed", e);
                return new PullResponse(0, "Error: " + e.getMessage(), false, null);
            } finally {
                REQUEST_IN_PROGRESS.set(false);
            }
        }).thenAccept(onComplete);

        return true;
    }

    /**
     * Downloads a construction for InGame spawning (blocks + entities only, no metadata).
     * Uses the public /building/:id/ingame endpoint that doesn't require owner auth.
     * This method does NOT use global locks, so it can be used for batch downloads.
     *
     * @param constructionId the building RDNS to download
     * @param onComplete callback called on completion
     */
    public static void downloadConstruction(String constructionId, Consumer<PullResponse> onComplete) {
        if (isCloudDenied()) {
            onComplete.accept(new PullResponse(403, "Cloud access denied: mod version too old", false, null));
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return executeInGameDownload(constructionId);
            } catch (Exception e) {
                Architect.LOGGER.error("InGame download request failed for {}", constructionId, e);
                return new PullResponse(0, "Error: " + e.getMessage(), false, null);
            }
        }).thenAccept(onComplete);
    }

    /**
     * Downloads blocks and entities for InGame spawning using the public endpoint.
     * 1. Calls /building/:id/ingame to get signed CDN URLs
     * 2. Downloads blocks from blocksUrl
     * 3. Downloads entities from entitiesUrl (if present)
     * No metadata is downloaded - it comes from the list export.
     */
    private static PullResponse executeInGameDownload(String constructionId) throws Exception {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();

        // Step 1: Get CDN URLs from the InGame endpoint
        String url = endpoint + "/building/" + constructionId + "/ingame";
        Architect.LOGGER.info("Fetching InGame URLs for {} from {}", constructionId, url);

        String blocksUrl;
        String entitiesUrl = null;

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.getRequestTimeout() * 1000);
            conn.setReadTimeout(config.getRequestTimeout() * 1000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", config.getAuth());
            conn.setRequestProperty("X-Api-Key", config.getApikey());

            int status = conn.getResponseCode();

            if (status < 200 || status >= 300) {
                String errorBody = readResponse(conn);
                String message = parseResponseMessage(errorBody, status);
                return new PullResponse(status, message, false, null);
            }

            // Parse JSON response with CDN URLs
            String responseBody = readResponse(conn);
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            blocksUrl = json.get("blocksUrl").getAsString();
            if (json.has("entitiesUrl") && !json.get("entitiesUrl").isJsonNull()) {
                entitiesUrl = json.get("entitiesUrl").getAsString();
            }

            Architect.LOGGER.info("Got CDN URLs - blocks: {}, entities: {}",
                blocksUrl != null ? "yes" : "no", entitiesUrl != null ? "yes" : "no");

        } finally {
            conn.disconnect();
        }

        // Step 2: Download blocks from CDN
        byte[] blocksData;
        HttpURLConnection blocksConn = (HttpURLConnection) URI.create(blocksUrl).toURL().openConnection();
        try {
            blocksConn.setRequestMethod("GET");
            blocksConn.setInstanceFollowRedirects(true);
            blocksConn.setConnectTimeout(config.getRequestTimeout() * 1000);
            blocksConn.setReadTimeout(config.getRequestTimeout() * 1000);
            blocksConn.setRequestProperty("Accept", "application/octet-stream, application/json");

            int blocksStatus = blocksConn.getResponseCode();

            if (blocksStatus < 200 || blocksStatus >= 300) {
                String errorBody = readResponse(blocksConn);
                String message = parseResponseMessage(errorBody, blocksStatus);
                return new PullResponse(blocksStatus, message, false, null);
            }

            blocksData = readBinaryResponse(blocksConn);
            Architect.LOGGER.info("Downloaded blocks: {} bytes", blocksData.length);

        } finally {
            blocksConn.disconnect();
        }

        // Step 3: Download entities from CDN (if present)
        byte[] entitiesData = null;
        if (entitiesUrl != null) {
            HttpURLConnection entitiesConn = (HttpURLConnection) URI.create(entitiesUrl).toURL().openConnection();
            try {
                entitiesConn.setRequestMethod("GET");
                entitiesConn.setInstanceFollowRedirects(true);
                entitiesConn.setConnectTimeout(config.getRequestTimeout() * 1000);
                entitiesConn.setReadTimeout(config.getRequestTimeout() * 1000);
                entitiesConn.setRequestProperty("Accept", "application/octet-stream, application/json");

                int entitiesStatus = entitiesConn.getResponseCode();

                if (entitiesStatus >= 200 && entitiesStatus < 300) {
                    entitiesData = readBinaryResponse(entitiesConn);
                    Architect.LOGGER.info("Downloaded entities: {} bytes", entitiesData.length);
                } else if (entitiesStatus == 404) {
                    Architect.LOGGER.debug("No entities for {}", constructionId);
                } else {
                    Architect.LOGGER.warn("Failed to download entities: {}", entitiesStatus);
                }

            } finally {
                entitiesConn.disconnect();
            }
        }

        // Step 4: Parse the NBT data (reuse existing parsing logic)
        Construction construction = parseConstructionFromNbt(constructionId, blocksData, entitiesData);
        if (construction == null) {
            return new PullResponse(200, "Failed to parse NBT data", false, null);
        }

        return new PullResponse(200, "Success", true, construction);
    }

    /**
     * Parses blocks from JSON format (with type-hinted NBT).
     * Mirrors the NBT block deserialization but reads from JSON.
     */
    private static void deserializeBlocksFromJson(Construction construction, String jsonString) {
        JsonObject root = GSON.fromJson(jsonString, JsonObject.class);

        // Parse palette
        com.google.gson.JsonArray paletteArray = root.getAsJsonArray("palette");
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteArray.size(); i++) {
            String stateString = paletteArray.get(i).getAsJsonObject().get("state").getAsString();
            BlockState state = parseBlockState(stateString);
            palette.add(state);
        }

        // Parse blocks
        com.google.gson.JsonArray blocksArray = root.getAsJsonArray("blocks");
        for (int i = 0; i < blocksArray.size(); i++) {
            JsonObject blockObj = blocksArray.get(i).getAsJsonObject();
            int x = blockObj.get("x").getAsInt();
            int y = blockObj.get("y").getAsInt();
            int z = blockObj.get("z").getAsInt();
            int paletteIndex = blockObj.get("p").getAsInt();

            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = palette.get(paletteIndex);

            if (blockObj.has("nbt")) {
                CompoundTag nbt = NbtJsonConverter.jsonToCompoundTag(blockObj.getAsJsonObject("nbt"));
                construction.addBlockRaw(pos, state, nbt);
            } else {
                construction.addBlockRaw(pos, state);
            }
        }

        // Parse rooms if present
        if (root.has("rooms")) {
            JsonObject roomsObj = root.getAsJsonObject("rooms");
            for (String roomId : roomsObj.keySet()) {
                Room room = construction.getRoom(roomId);
                if (room == null) {
                    room = new Room(roomId, roomId, java.time.Instant.now());
                    construction.addRoom(room);
                }

                JsonObject roomObj = roomsObj.getAsJsonObject(roomId);
                if (roomObj == null || !roomObj.has("blocks")) continue;

                com.google.gson.JsonArray roomBlocksArray = roomObj.getAsJsonArray("blocks");
                for (int i = 0; i < roomBlocksArray.size(); i++) {
                    JsonObject blockObj = roomBlocksArray.get(i).getAsJsonObject();
                    int x = blockObj.get("x").getAsInt();
                    int y = blockObj.get("y").getAsInt();
                    int z = blockObj.get("z").getAsInt();
                    int paletteIndex = blockObj.get("p").getAsInt();

                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = palette.get(paletteIndex);

                    if (blockObj.has("nbt")) {
                        CompoundTag nbt = NbtJsonConverter.jsonToCompoundTag(blockObj.getAsJsonObject("nbt"));
                        room.setBlockChange(pos, state, nbt);
                    } else {
                        room.setBlockChange(pos, state);
                    }
                }
            }
        }
    }

    /**
     * Parses entities from JSON format (with type-hinted NBT).
     * Mirrors the NBT entity deserialization but reads from JSON.
     */
    private static void deserializeEntitiesFromJson(Construction construction, String jsonString) {
        JsonObject root = GSON.fromJson(jsonString, JsonObject.class);

        // Base entities
        com.google.gson.JsonArray entitiesArray = root.getAsJsonArray("entities");
        for (int i = 0; i < entitiesArray.size(); i++) {
            JsonObject entityObj = entitiesArray.get(i).getAsJsonObject();
            EntityData data = parseEntityDataFromJson(entityObj);
            construction.addEntity(data);
        }

        // Room entities
        if (root.has("rooms")) {
            JsonObject roomsObj = root.getAsJsonObject("rooms");
            for (String roomId : roomsObj.keySet()) {
                Room room = construction.getRoom(roomId);
                if (room == null) {
                    room = new Room(roomId, roomId, java.time.Instant.now());
                    construction.addRoom(room);
                }

                JsonObject roomObj = roomsObj.getAsJsonObject(roomId);
                if (roomObj == null || !roomObj.has("entities")) continue;

                com.google.gson.JsonArray roomEntitiesArray = roomObj.getAsJsonArray("entities");
                for (int i = 0; i < roomEntitiesArray.size(); i++) {
                    JsonObject entityObj = roomEntitiesArray.get(i).getAsJsonObject();
                    EntityData data = parseEntityDataFromJson(entityObj);
                    room.addEntity(data);
                }
            }
        }
    }

    /**
     * Parses a single EntityData from a JSON object.
     */
    private static EntityData parseEntityDataFromJson(JsonObject entityObj) {
        String type = entityObj.get("type").getAsString();
        double x = entityObj.get("x").getAsDouble();
        double y = entityObj.get("y").getAsDouble();
        double z = entityObj.get("z").getAsDouble();
        float yaw = entityObj.get("yaw").getAsFloat();
        float pitch = entityObj.get("pitch").getAsFloat();
        CompoundTag nbt = entityObj.has("nbt")
            ? NbtJsonConverter.jsonToCompoundTag(entityObj.getAsJsonObject("nbt"))
            : new CompoundTag();

        Vec3 relativePos = new Vec3(x, y, z);
        return new EntityData(type, relativePos, yaw, pitch, nbt);
    }

    /**
     * Detects whether binary data is JSON (starts with '{') or compressed NBT (GZIP magic bytes 0x1F 0x8B).
     */
    private static boolean isJsonFormat(byte[] data) {
        return data.length > 0 && data[0] == '{';
    }

    /**
     * Parses blocks and entities data into a Construction object.
     * Auto-detects format: JSON (starts with '{') or compressed NBT (GZIP).
     * Used by InGame download (no metadata needed).
     */
    private static Construction parseConstructionFromNbt(String constructionId, byte[] blocksData, byte[] entitiesData) {
        try {
            // Create minimal construction (metadata comes from list export)
            Construction construction = new Construction(constructionId, new java.util.UUID(0, 0), "ingame");

            // Auto-detect blocks format and parse
            if (isJsonFormat(blocksData)) {
                String jsonString = new String(blocksData, StandardCharsets.UTF_8);
                deserializeBlocksFromJson(construction, jsonString);
                Architect.LOGGER.info("Parsed InGame blocks from JSON for {}", constructionId);
            } else {
                // Parse blocks NBT (existing logic)
                ByteArrayInputStream blocksStream = new ByteArrayInputStream(blocksData);
                CompoundTag blocksRoot = NbtIo.readCompressed(blocksStream, net.minecraft.nbt.NbtAccounter.unlimitedHeap());

                ListTag paletteList = blocksRoot.getList("palette").orElse(new ListTag());
                List<BlockState> palette = new ArrayList<>();
                for (int i = 0; i < paletteList.size(); i++) {
                    CompoundTag paletteEntry = paletteList.getCompound(i).orElseThrow();
                    String stateString = paletteEntry.getString("state").orElse("");
                    BlockState state = parseBlockState(stateString);
                    palette.add(state);
                }

                ListTag blocksList = blocksRoot.getList("blocks").orElse(new ListTag());
                for (int i = 0; i < blocksList.size(); i++) {
                    CompoundTag blockTag = blocksList.getCompound(i).orElseThrow();
                    int x = blockTag.getInt("x").orElse(0);
                    int y = blockTag.getInt("y").orElse(0);
                    int z = blockTag.getInt("z").orElse(0);
                    int paletteIndex = blockTag.getInt("p").orElse(0);

                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = palette.get(paletteIndex);

                    CompoundTag blockEntityNbt = blockTag.getCompound("nbt").orElse(null);
                    if (blockEntityNbt != null && !blockEntityNbt.isEmpty()) {
                        construction.addBlockRaw(pos, state, blockEntityNbt);
                    } else {
                        construction.addBlockRaw(pos, state);
                    }
                }

                CompoundTag roomsTag = blocksRoot.getCompound("rooms").orElse(null);
                if (roomsTag != null) {
                    for (String roomId : roomsTag.keySet()) {
                        Room room = construction.getRoom(roomId);
                        if (room == null) {
                            room = new Room(roomId, roomId, java.time.Instant.now());
                            construction.addRoom(room);
                        }

                        CompoundTag roomTag = roomsTag.getCompound(roomId).orElse(null);
                        if (roomTag == null) continue;

                        ListTag roomBlocksList = roomTag.getList("blocks").orElse(new ListTag());
                        for (int i = 0; i < roomBlocksList.size(); i++) {
                            CompoundTag blockTag = roomBlocksList.getCompound(i).orElseThrow();
                            int x = blockTag.getInt("x").orElse(0);
                            int y = blockTag.getInt("y").orElse(0);
                            int z = blockTag.getInt("z").orElse(0);
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

            // Auto-detect entities format and parse
            if (entitiesData != null && entitiesData.length > 0) {
                if (isJsonFormat(entitiesData)) {
                    String jsonString = new String(entitiesData, StandardCharsets.UTF_8);
                    deserializeEntitiesFromJson(construction, jsonString);
                    Architect.LOGGER.info("Parsed InGame entities from JSON for {}", constructionId);
                } else {
                    // Parse entities NBT (existing logic)
                    ByteArrayInputStream entitiesStream = new ByteArrayInputStream(entitiesData);
                    CompoundTag entitiesRoot = NbtIo.readCompressed(entitiesStream, net.minecraft.nbt.NbtAccounter.unlimitedHeap());

                    ListTag entitiesList = entitiesRoot.getList("entities").orElse(new ListTag());
                    for (int i = 0; i < entitiesList.size(); i++) {
                        CompoundTag entityTag = entitiesList.getCompound(i).orElseThrow();

                        String type = entityTag.getString("type").orElse("");
                        double ex = entityTag.getDouble("x").orElse(0.0);
                        double ey = entityTag.getDouble("y").orElse(0.0);
                        double ez = entityTag.getDouble("z").orElse(0.0);
                        float yaw = entityTag.getFloat("yaw").orElse(0.0f);
                        float pitch = entityTag.getFloat("pitch").orElse(0.0f);
                        CompoundTag nbt = entityTag.getCompound("nbt").orElse(new CompoundTag());

                        Vec3 relativePos = new Vec3(ex, ey, ez);
                        EntityData data = new EntityData(type, relativePos, yaw, pitch, nbt);
                        construction.addEntity(data);
                    }

                    CompoundTag roomsEntitiesTag = entitiesRoot.getCompound("rooms").orElse(null);
                    if (roomsEntitiesTag != null) {
                        for (String roomId : roomsEntitiesTag.keySet()) {
                            Room room = construction.getRoom(roomId);
                            if (room == null) {
                                room = new Room(roomId, roomId, java.time.Instant.now());
                                construction.addRoom(room);
                            }

                            CompoundTag roomTag = roomsEntitiesTag.getCompound(roomId).orElse(null);
                            if (roomTag == null) continue;

                            ListTag roomEntitiesList = roomTag.getList("entities").orElse(new ListTag());
                            for (int i = 0; i < roomEntitiesList.size(); i++) {
                                CompoundTag entityTag = roomEntitiesList.getCompound(i).orElseThrow();

                                String type = entityTag.getString("type").orElse("");
                                double ex = entityTag.getDouble("x").orElse(0.0);
                                double ey = entityTag.getDouble("y").orElse(0.0);
                                double ez = entityTag.getDouble("z").orElse(0.0);
                                float yaw = entityTag.getFloat("yaw").orElse(0.0f);
                                float pitch = entityTag.getFloat("pitch").orElse(0.0f);
                                CompoundTag nbt = entityTag.getCompound("nbt").orElse(new CompoundTag());

                                Vec3 relativePos = new Vec3(ex, ey, ez);
                                EntityData data = new EntityData(type, relativePos, yaw, pitch, nbt);
                                room.addEntity(data);
                            }
                        }
                    }
                }
            }

            // Note: Bounds are NOT set here - they come from SpawnableBuilding metadata
            // and will be applied in InGameBuildingSpawner.doSpawn before architectSpawn

            Architect.LOGGER.info("Parsed InGame construction: {} blocks, {} entities",
                construction.getBlockCount(), construction.getEntityCount());

            return construction;

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to parse InGame data for {}", constructionId, e);
            return null;
        }
    }

    /**
     * Esegue il download di una costruzione.
     * Usa due chiamate separate:
     * - GET /building/:id/metadata per i metadati (redirect a CDN, ritorna JSON)
     * - GET /building/:id/blocks per i blocchi (redirect a CDN, ritorna NBT binario compresso)
     */
    private static PullResponse executePull(String constructionId) throws Exception {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();

        // 1. Scarica i metadati (JSON)
        String metadataUrl = endpoint + "/building/" + constructionId + "/metadata";
        Architect.LOGGER.info("Pulling metadata for {} from {}", constructionId, metadataUrl);

        String metadataBody;
        int metadataStatus;

        HttpURLConnection metadataConn = (HttpURLConnection) URI.create(metadataUrl).toURL().openConnection();
        try {
            metadataConn.setRequestMethod("GET");
            metadataConn.setInstanceFollowRedirects(true); // Segui redirect al CDN
            metadataConn.setConnectTimeout(config.getRequestTimeout() * 1000);
            metadataConn.setReadTimeout(config.getRequestTimeout() * 1000);
            metadataConn.setRequestProperty("Accept", "application/json");
            metadataConn.setRequestProperty("Authorization", config.getAuth());
            metadataConn.setRequestProperty("X-Api-Key", config.getApikey());

            metadataStatus = metadataConn.getResponseCode();
            metadataBody = readResponse(metadataConn);

            Architect.LOGGER.info("Metadata response: {} - {} bytes", metadataStatus, metadataBody.length());

            if (metadataStatus < 200 || metadataStatus >= 300) {
                String message = parseResponseMessage(metadataBody, metadataStatus);
                return new PullResponse(metadataStatus, message, false, null);
            }
        } finally {
            metadataConn.disconnect();
        }

        // 2. Download blocks (compressed NBT or JSON, auto-detect from content)
        String blocksUrl = endpoint + "/building/" + constructionId + "/blocks";
        Architect.LOGGER.info("Pulling blocks for {} from {}", constructionId, blocksUrl);

        byte[] blocksData;
        int blocksStatus;

        HttpURLConnection blocksConn = (HttpURLConnection) URI.create(blocksUrl).toURL().openConnection();
        try {
            blocksConn.setRequestMethod("GET");
            blocksConn.setInstanceFollowRedirects(true); // Segui redirect al CDN
            blocksConn.setConnectTimeout(config.getRequestTimeout() * 1000);
            blocksConn.setReadTimeout(config.getRequestTimeout() * 1000);
            blocksConn.setRequestProperty("Accept", "application/octet-stream, application/json");
            blocksConn.setRequestProperty("Authorization", config.getAuth());
            blocksConn.setRequestProperty("X-Api-Key", config.getApikey());

            blocksStatus = blocksConn.getResponseCode();

            if (blocksStatus < 200 || blocksStatus >= 300) {
                String errorBody = readResponse(blocksConn);
                String message = parseResponseMessage(errorBody, blocksStatus);
                return new PullResponse(blocksStatus, message, false, null);
            }

            // Leggi i dati binari
            blocksData = readBinaryResponse(blocksConn);
            Architect.LOGGER.info("Blocks response: {} - {} bytes", blocksStatus, blocksData.length);

        } finally {
            blocksConn.disconnect();
        }

        // 3. Download entities (compressed NBT or JSON, optional - may not exist)
        String entitiesUrl = endpoint + "/building/" + constructionId + "/entities";
        Architect.LOGGER.info("Pulling entities for {} from {}", constructionId, entitiesUrl);

        byte[] entitiesData = null;

        HttpURLConnection entitiesConn = (HttpURLConnection) URI.create(entitiesUrl).toURL().openConnection();
        try {
            entitiesConn.setRequestMethod("GET");
            entitiesConn.setInstanceFollowRedirects(true);
            entitiesConn.setConnectTimeout(config.getRequestTimeout() * 1000);
            entitiesConn.setReadTimeout(config.getRequestTimeout() * 1000);
            entitiesConn.setRequestProperty("Accept", "application/octet-stream, application/json");
            entitiesConn.setRequestProperty("Authorization", config.getAuth());
            entitiesConn.setRequestProperty("X-Api-Key", config.getApikey());

            int entitiesStatus = entitiesConn.getResponseCode();

            if (entitiesStatus >= 200 && entitiesStatus < 300) {
                // Entità presenti
                entitiesData = readBinaryResponse(entitiesConn);
                Architect.LOGGER.info("Entities response: {} - {} bytes", entitiesStatus, entitiesData.length);
            } else if (entitiesStatus == 404) {
                // Nessuna entità (costruzione vecchia o senza entità)
                Architect.LOGGER.debug("No entities for construction {}", constructionId);
            } else {
                // Altri errori - logga ma non fallire
                Architect.LOGGER.warn("Failed to fetch entities: {}", entitiesStatus);
            }
        } finally {
            entitiesConn.disconnect();
        }

        // 4. Parse e combina i dati
        Construction construction = parseConstructionFromResponses(constructionId, metadataBody, blocksData, entitiesData);
        if (construction == null) {
            return new PullResponse(metadataStatus, "Failed to parse construction data", false, null);
        }

        return new PullResponse(metadataStatus, "Success", true, construction);
    }

    /**
     * Legge la risposta binaria dalla connessione.
     */
    private static byte[] readBinaryResponse(HttpURLConnection conn) throws IOException {
        try (InputStream is = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }

    /**
     * Parsa le risposte (metadati JSON, blocchi e entità NBT binari) e crea un oggetto Construction.
     *
     * @param constructionId l'ID della costruzione
     * @param metadataBody la risposta JSON di /building/:id/metadata
     * @param blocksData i dati NBT binari compressi di /building/:id/blocks
     * @param entitiesData i dati NBT binari compressi di /building/:id/entities (può essere null)
     */
    private static Construction parseConstructionFromResponses(String constructionId, String metadataBody,
                                                                byte[] blocksData, byte[] entitiesData) {
        try {
            JsonObject metadata = GSON.fromJson(metadataBody, JsonObject.class);

            // Parse autore dai metadati
            String authorName = "remote";
            if (metadata.has("author") && metadata.get("author").isJsonObject()) {
                JsonObject author = metadata.getAsJsonObject("author");
                if (author.has("nickname")) {
                    authorName = author.get("nickname").getAsString();
                }
            }

            // Crea la costruzione con l'ID (usa UUID placeholder per costruzioni remote)
            Construction construction = new Construction(constructionId, new java.util.UUID(0, 0), authorName);

            // Parse titoli dai metadati
            if (metadata.has("titles") && metadata.get("titles").isJsonObject()) {
                JsonObject titles = metadata.getAsJsonObject("titles");
                for (String lang : titles.keySet()) {
                    construction.setTitle(lang, titles.get(lang).getAsString());
                }
            }

            // Parse descrizioni brevi dai metadati
            if (metadata.has("short_descriptions") && metadata.get("short_descriptions").isJsonObject()) {
                JsonObject shortDescs = metadata.getAsJsonObject("short_descriptions");
                for (String lang : shortDescs.keySet()) {
                    construction.setShortDescription(lang, shortDescs.get(lang).getAsString());
                }
            }

            // Parse descrizioni complete dai metadati
            if (metadata.has("descriptions") && metadata.get("descriptions").isJsonObject()) {
                JsonObject descs = metadata.getAsJsonObject("descriptions");
                for (String lang : descs.keySet()) {
                    construction.setDescription(lang, descs.get(lang).getAsString());
                }
            }

            // Parse mod richiesti dai metadati
            Map<String, ModInfo> requiredMods = new HashMap<>();
            if (metadata.has("mods") && metadata.get("mods").isJsonObject()) {
                JsonObject modsObject = metadata.getAsJsonObject("mods");
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

            // Parse bounds from metadata (needed to denormalize coordinates)
            // bounds.x/y/z are the dimensions, min is always 0,0,0
            if (metadata.has("bounds") && metadata.get("bounds").isJsonObject()) {
                JsonObject boundsObj = metadata.getAsJsonObject("bounds");
                if (boundsObj.has("x") && boundsObj.has("y") && boundsObj.has("z")) {
                    int sizeX = boundsObj.get("x").getAsInt();
                    int sizeY = boundsObj.get("y").getAsInt();
                    int sizeZ = boundsObj.get("z").getAsInt();
                    // Set bounds: min is 0,0,0, max is size-1
                    construction.getBounds().set(0, 0, 0, sizeX - 1, sizeY - 1, sizeZ - 1);
                }
            }

            // Parse rooms dai metadati (crea le Room vuote, i blocchi/entità verranno caricati dopo)
            if (metadata.has("rooms") && metadata.get("rooms").isJsonArray()) {
                com.google.gson.JsonArray roomsArray = metadata.getAsJsonArray("rooms");
                for (JsonElement element : roomsArray) {
                    JsonObject roomJson = element.getAsJsonObject();

                    String roomId = roomJson.get("id").getAsString();
                    String roomName = roomJson.has("name") ? roomJson.get("name").getAsString() : roomId;
                    java.time.Instant roomCreatedAt = roomJson.has("createdAt")
                        ? java.time.Instant.parse(roomJson.get("createdAt").getAsString())
                        : java.time.Instant.now();

                    Room room = new Room(roomId, roomName, roomCreatedAt);
                    construction.addRoom(room);
                }
            }

            // Parse anchors from metadata
            if (metadata.has("anchors") && metadata.get("anchors").isJsonObject()) {
                JsonObject anchorsObj = metadata.getAsJsonObject("anchors");
                if (anchorsObj.has("entrance") && anchorsObj.get("entrance").isJsonObject()) {
                    JsonObject entranceObj = anchorsObj.getAsJsonObject("entrance");
                    int x = entranceObj.get("x").getAsInt();
                    int y = entranceObj.get("y").getAsInt();
                    int z = entranceObj.get("z").getAsInt();
                    float yaw = entranceObj.has("yaw") ? entranceObj.get("yaw").getAsFloat() : 0f;
                    construction.getAnchors().setEntrance(new BlockPos(x, y, z), yaw);
                    Architect.LOGGER.debug("Parsed entrance anchor from pull: [{},{},{}] yaw={}", x, y, z, yaw);
                }
            }

            // Deserialize blocks (auto-detect format: JSON or compressed NBT)
            if (blocksData != null && blocksData.length > 0) {
                if (isJsonFormat(blocksData)) {
                    String jsonString = new String(blocksData, StandardCharsets.UTF_8);
                    deserializeBlocksFromJson(construction, jsonString);
                    Architect.LOGGER.info("Parsed blocks from JSON for pull of {}", constructionId);
                } else {
                    deserializeBlocksFromNbt(construction, blocksData);
                }
            }

            // Deserialize entities (auto-detect format: JSON or compressed NBT)
            if (entitiesData != null && entitiesData.length > 0) {
                if (isJsonFormat(entitiesData)) {
                    String jsonString = new String(entitiesData, StandardCharsets.UTF_8);
                    deserializeEntitiesFromJson(construction, jsonString);
                    Architect.LOGGER.info("Parsed entities from JSON for pull of {}", constructionId);
                } else {
                    deserializeEntitiesFromNbt(construction, entitiesData);
                }
            }

            return construction;

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to parse construction response", e);
            return null;
        }
    }

    /**
     * Deserializza i blocchi da NBT compresso e li aggiunge alla costruzione.
     * I blocchi nel file hanno coordinate relative (normalizzate a 0,0,0).
     * Vengono denormalizzati usando i bounds della costruzione.
     */
    private static void deserializeBlocksFromNbt(Construction construction, byte[] nbtBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(nbtBytes);
        CompoundTag root = NbtIo.readCompressed(bais, net.minecraft.nbt.NbtAccounter.unlimitedHeap());

        // Ottieni i bounds per denormalizzare le coordinate (caricati dal metadata)
        var bounds = construction.getBounds();
        int offsetX = bounds.isValid() ? bounds.getMinX() : 0;
        int offsetY = bounds.isValid() ? bounds.getMinY() : 0;
        int offsetZ = bounds.isValid() ? bounds.getMinZ() : 0;

        // Leggi la palette - MC 1.21 API con Optional
        ListTag paletteList = root.getList("palette").orElse(new ListTag());
        List<BlockState> palette = new ArrayList<>();

        for (int i = 0; i < paletteList.size(); i++) {
            CompoundTag paletteEntry = paletteList.getCompound(i).orElseThrow();
            String stateString = paletteEntry.getString("state").orElse("");
            BlockState state = parseBlockState(stateString);
            palette.add(state);
        }

        // Leggi i blocchi (denormalizzando le coordinate)
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
     * Deserializza le entità da NBT compresso e le aggiunge alla costruzione.
     */
    private static void deserializeEntitiesFromNbt(Construction construction, byte[] nbtBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(nbtBytes);
        CompoundTag root = NbtIo.readCompressed(bais, net.minecraft.nbt.NbtAccounter.unlimitedHeap());

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

        // Carica le entità delle stanze
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

    /**
     * Parsa una stringa di BlockState (es: "minecraft:oak_stairs[facing=north,half=bottom]")
     */
    private static BlockState parseBlockState(String stateString) {
        try {
            // Separa l'ID del blocco dalle proprietà
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
            net.minecraft.world.level.block.Block block = net.minecraft.world.level.block.Blocks.AIR;

            for (net.minecraft.world.level.block.Block b : BuiltInRegistries.BLOCK) {
                String registeredId = BuiltInRegistries.BLOCK.getKey(b).toString();
                if (registeredId.equals(blockIdStr)) {
                    block = b;
                    break;
                }
            }

            BlockState state = block.defaultBlockState();

            // Applica le proprietà se presenti
            if (propertiesStr != null && !propertiesStr.isEmpty()) {
                state = applyProperties(state, propertiesStr);
            }

            return state;

        } catch (Exception e) {
            Architect.LOGGER.warn("Failed to parse block state: {}", stateString, e);
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
    }

    /**
     * Applica le proprietà a un BlockState.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperties(BlockState state, String propertiesStr) {
        String[] pairs = propertiesStr.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=");
            if (keyValue.length != 2) continue;

            String propName = keyValue[0].trim();
            String propValue = keyValue[1].trim();

            // Trova la proprietà nel blocco
            for (net.minecraft.world.level.block.state.properties.Property<?> prop :
                    state.getProperties()) {
                if (prop.getName().equals(propName)) {
                    Optional<?> value = prop.getValue(propValue);
                    if (value.isPresent()) {
                        state = state.setValue((net.minecraft.world.level.block.state.properties.Property) prop,
                                              (Comparable) value.get());
                    }
                    break;
                }
            }
        }
        return state;
    }

    /**
     * Risultato di una richiesta di metadata only.
     */
    public record MetadataResponse(int statusCode, String message, boolean success,
                                    String constructionId, Map<String, ModInfo> requiredMods) {}

    /**
     * Esegue il pull dei soli metadati (senza blocchi) per validare i mod richiesti.
     *
     * @param constructionId l'ID della costruzione
     * @param onComplete callback chiamato al completamento
     * @return true se la richiesta è stata avviata, false se c'è già una richiesta in corso
     */
    public static boolean pullMetadataOnly(String constructionId, Consumer<MetadataResponse> onComplete) {
        if (isCloudDenied()) {
            onComplete.accept(new MetadataResponse(403, "Cloud access denied: mod version too old", false, constructionId, null));
            return true;
        }
        if (!REQUEST_IN_PROGRESS.compareAndSet(false, true)) {
            return false;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return executePullMetadataOnly(constructionId);
            } catch (Exception e) {
                Architect.LOGGER.error("Pull metadata request failed", e);
                return new MetadataResponse(0, "Error: " + e.getMessage(), false, constructionId, null);
            } finally {
                REQUEST_IN_PROGRESS.set(false);
            }
        }).thenAccept(onComplete);

        return true;
    }

    /**
     * Esegue il download dei soli metadati di una costruzione.
     */
    private static MetadataResponse executePullMetadataOnly(String constructionId) throws Exception {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();

        // Scarica solo i metadati (JSON)
        String metadataUrl = endpoint + "/building/" + constructionId + "/metadata";
        Architect.LOGGER.info("Pulling metadata only for {} from {}", constructionId, metadataUrl);

        String metadataBody;
        int metadataStatus;

        HttpURLConnection metadataConn = (HttpURLConnection) URI.create(metadataUrl).toURL().openConnection();
        try {
            metadataConn.setRequestMethod("GET");
            metadataConn.setInstanceFollowRedirects(true);
            metadataConn.setConnectTimeout(config.getRequestTimeout() * 1000);
            metadataConn.setReadTimeout(config.getRequestTimeout() * 1000);
            metadataConn.setRequestProperty("Accept", "application/json");
            metadataConn.setRequestProperty("Authorization", config.getAuth());
            metadataConn.setRequestProperty("X-Api-Key", config.getApikey());

            metadataStatus = metadataConn.getResponseCode();
            metadataBody = readResponse(metadataConn);

            Architect.LOGGER.info("Metadata response: {} - {} bytes", metadataStatus, metadataBody.length());

            if (metadataStatus < 200 || metadataStatus >= 300) {
                String message = parseResponseMessage(metadataBody, metadataStatus);
                return new MetadataResponse(metadataStatus, message, false, constructionId, null);
            }
        } finally {
            metadataConn.disconnect();
        }

        // Parse i mod richiesti dai metadati
        try {
            JsonObject metadata = GSON.fromJson(metadataBody, JsonObject.class);
            Map<String, ModInfo> requiredMods = new HashMap<>();

            if (metadata.has("mods") && metadata.get("mods").isJsonObject()) {
                JsonObject modsObject = metadata.getAsJsonObject("mods");
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
                    if (modJson.has("version") && !modJson.get("version").isJsonNull()) {
                        info.setVersion(modJson.get("version").getAsString());
                    }
                    if (modJson.has("downloadUrl") && !modJson.get("downloadUrl").isJsonNull()) {
                        info.setDownloadUrl(modJson.get("downloadUrl").getAsString());
                    }
                    requiredMods.put(modId, info);
                }
            }

            return new MetadataResponse(metadataStatus, "Success", true, constructionId, requiredMods);

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to parse metadata response", e);
            return new MetadataResponse(metadataStatus, "Failed to parse metadata", false, constructionId, null);
        }
    }

    /**
     * Fetches mod settings from the server asynchronously.
     * Updates the config with modOptionsDisclaimer and saves it.
     * If the call fails, sets default disclaimer messages.
     */
    public static void fetchModSettings() {
        CompletableFuture.runAsync(() -> {
            try {
                executeFetchModSettings();
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to fetch mod settings", e);
                setDefaultDisclaimer();
            }
        });
    }

    private static void executeFetchModSettings() {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();
        String url = endpoint + "/mod/settings"
            + "?modVersion=" + URLEncoder.encode(Architect.MOD_VERSION, StandardCharsets.UTF_8)
            + "&minecraftVersion=" + URLEncoder.encode(Architect.MINECRAFT_VERSION, StandardCharsets.UTF_8)
            + "&loaderVersion=" + URLEncoder.encode(Architect.LOADER_VERSION, StandardCharsets.UTF_8);

        Architect.LOGGER.info("Fetching mod settings from {}", url);

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000); // 10 seconds for startup
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Authorization", config.getAuth());
                conn.setRequestProperty("X-Api-Key", config.getApikey());

                int statusCode = conn.getResponseCode();
                String responseBody = readResponse(conn);

                Architect.LOGGER.info("Mod settings response: {} - {} bytes", statusCode, responseBody.length());

                if (statusCode >= 200 && statusCode < 300) {
                    // Parse the response
                    JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
                    boolean updated = false;

                    // Update www if present
                    if (json.has("www") && json.get("www").isJsonPrimitive()) {
                        String www = json.get("www").getAsString();
                        if (www != null && !www.isEmpty()) {
                            config.setWww(www);
                            Architect.LOGGER.info("Mod settings: www updated to {}", www);
                            updated = true;
                        }
                    }

                    // Update disclaimer if present (empty object = use mod fallback)
                    if (json.has("modOptionsDisclaimer") && json.get("modOptionsDisclaimer").isJsonObject()) {
                        JsonObject disclaimerJson = json.getAsJsonObject("modOptionsDisclaimer");
                        Map<String, String> disclaimer = new HashMap<>();

                        for (var entry : disclaimerJson.entrySet()) {
                            if (entry.getValue().isJsonPrimitive()) {
                                disclaimer.put(entry.getKey(), entry.getValue().getAsString());
                            }
                        }

                        config.setModOptionsDisclaimer(disclaimer);
                        Architect.LOGGER.info("Mod settings: {} disclaimer languages loaded", disclaimer.size());
                        updated = true;
                    }

                    // Update welcome message if present (empty object = use mod fallback)
                    if (json.has("welcomeMessage") && json.get("welcomeMessage").isJsonObject()) {
                        JsonObject welcomeJson = json.getAsJsonObject("welcomeMessage");
                        Map<String, String> welcome = new HashMap<>();

                        for (var entry : welcomeJson.entrySet()) {
                            if (entry.getValue().isJsonPrimitive()) {
                                welcome.put(entry.getKey(), entry.getValue().getAsString());
                            }
                        }

                        config.setWelcomeMessage(welcome);
                        Architect.LOGGER.info("Mod settings: {} welcome message languages loaded", welcome.size());
                        updated = true;
                    }

                    // Parse cloud deny flag
                    if (json.has("denyCloud") && json.get("denyCloud").isJsonPrimitive()) {
                        boolean denyCloud = json.get("denyCloud").getAsBoolean();
                        config.setCloudDenied(denyCloud);
                        if (denyCloud) {
                            Architect.LOGGER.warn("Mod settings: cloud access denied by server (mod version too old)");
                        }
                    } else {
                        config.setCloudDenied(false);
                    }

                    // Parse latest version info
                    if (json.has("latestVersion") && json.get("latestVersion").isJsonPrimitive()) {
                        config.setLatestVersion(json.get("latestVersion").getAsString());
                        Architect.LOGGER.info("Mod settings: newer version available: {}", config.getLatestVersion());
                    }
                    if (json.has("downloadUrl") && json.get("downloadUrl").isJsonPrimitive()) {
                        config.setDownloadUrl(json.get("downloadUrl").getAsString());
                    }

                    // Show update/deny screens on the render thread
                    handleVersionScreens(config);

                    if (updated) {
                        config.save();
                        return;
                    }
                }

                // If we got here, something went wrong - use defaults
                Architect.LOGGER.warn("Invalid mod settings response, using defaults");
                setDefaultDisclaimer();

            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Architect.LOGGER.error("Failed to fetch mod settings: {}", e.getMessage());
            setDefaultDisclaimer();
        }
    }

    // Callback invoked after mod settings are fetched (set by client-side code)
    private static volatile Runnable modSettingsCallback = null;

    /**
     * Sets a callback to be invoked after mod settings are fetched.
     * Used by client-side code to show update/deny screens on the render thread.
     */
    public static void setModSettingsCallback(Runnable callback) {
        modSettingsCallback = callback;
    }

    /**
     * Notifies the registered callback that mod settings have been processed.
     */
    private static void handleVersionScreens(ArchitectConfig config) {
        Runnable callback = modSettingsCallback;
        if (callback != null) {
            callback.run();
        }
    }

    private static void setDefaultDisclaimer() {
        // If API call fails, do NOT modify the current config value.
        // This preserves any previously fetched disclaimer from the saved config.
        Architect.LOGGER.info("Keeping existing disclaimer from config (API call failed)");
    }

    // ===== InGame Spawner API Methods =====

    /**
     * Response for InGame lists fetch.
     */
    public record InGameListsResponse(int statusCode, String message, boolean success,
                                       List<InGameListInfo> lists, boolean authenticated, long userId) {}

    /**
     * Response for spawnable list export fetch.
     * userId is the current authenticated user ID (0 if anonymous) - used by mod to check building ownership.
     */
    public record SpawnableListResponse(int statusCode, String message, boolean success,
                                         SpawnableList spawnableList, long userId) {}

    /**
     * Fetches available InGame lists from the server asynchronously.
     * Uses API key if available, otherwise returns public lists only.
     *
     * @param onComplete callback called on completion
     */
    public static void fetchInGameLists(Consumer<InGameListsResponse> onComplete) {
        if (isCloudDenied()) {
            onComplete.accept(new InGameListsResponse(403, "Cloud access denied: mod version too old", false, null, false, 0));
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return executeFetchInGameLists();
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to fetch InGame lists", e);
                return new InGameListsResponse(0, "Error: " + e.getMessage(), false, null, false, 0);
            }
        }).thenAccept(onComplete);
    }

    private static InGameListsResponse executeFetchInGameLists() throws Exception {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();
        String url = endpoint + "/lists/ingame";

        Architect.LOGGER.info("Fetching InGame lists from {}", url);

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.getRequestTimeout() * 1000);
            conn.setReadTimeout(config.getRequestTimeout() * 1000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", config.getAuth());
            conn.setRequestProperty("X-Api-Key", config.getApikey());

            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn);

            Architect.LOGGER.info("InGame lists response: {} - {} bytes", statusCode, responseBody.length());

            if (statusCode < 200 || statusCode >= 300) {
                String message = parseResponseMessage(responseBody, statusCode);
                return new InGameListsResponse(statusCode, message, false, null, false, 0);
            }

            // Parse response
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            List<InGameListInfo> lists = new ArrayList<>();

            if (json.has("lists") && json.get("lists").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("lists")) {
                    JsonObject listObj = element.getAsJsonObject();
                    // Handle both numeric and string IDs (virtual lists have alphanumeric IDs like "most-popular")
                    String id = listObj.get("id").isJsonPrimitive()
                        ? listObj.get("id").getAsString() : String.valueOf(listObj.get("id").getAsLong());

                    // Parse localized names JSON object
                    Map<String, String> names = new HashMap<>();
                    if (listObj.has("names") && listObj.get("names").isJsonObject()) {
                        JsonObject namesObj = listObj.getAsJsonObject("names");
                        for (Map.Entry<String, JsonElement> entry : namesObj.entrySet()) {
                            if (!entry.getValue().isJsonNull()) {
                                names.put(entry.getKey(), entry.getValue().getAsString());
                            }
                        }
                    }

                    // Parse localized descriptions JSON object
                    Map<String, String> descriptions = new HashMap<>();
                    if (listObj.has("descriptions") && listObj.get("descriptions").isJsonObject()) {
                        JsonObject descObj = listObj.getAsJsonObject("descriptions");
                        for (Map.Entry<String, JsonElement> entry : descObj.entrySet()) {
                            if (!entry.getValue().isJsonNull()) {
                                descriptions.put(entry.getKey(), entry.getValue().getAsString());
                            }
                        }
                    }

                    int buildingCount = listObj.has("buildingCount") ? listObj.get("buildingCount").getAsInt() : 0;
                    boolean isPublic = listObj.has("isPublic") && listObj.get("isPublic").getAsBoolean();
                    boolean isVirtual = listObj.has("virtual") && listObj.get("virtual").getAsBoolean();
                    boolean isOwn = listObj.has("isOwn") && listObj.get("isOwn").getAsBoolean();
                    String icon = listObj.has("icon") && !listObj.get("icon").isJsonNull()
                        ? listObj.get("icon").getAsString() : "minecraft:book";  // Default icon
                    String contentHash = listObj.has("contentHash") && !listObj.get("contentHash").isJsonNull()
                        ? listObj.get("contentHash").getAsString() : null;

                    // Parse mods map
                    Map<String, ModInfo> mods = null;
                    if (listObj.has("mods") && listObj.get("mods").isJsonObject()) {
                        JsonObject modsObj = listObj.getAsJsonObject("mods");
                        if (!modsObj.isEmpty()) {
                            mods = new HashMap<>();
                            for (Map.Entry<String, JsonElement> entry : modsObj.entrySet()) {
                                String modId = entry.getKey();
                                if (entry.getValue().isJsonObject()) {
                                    JsonObject modObj = entry.getValue().getAsJsonObject();
                                    String displayName = modObj.has("displayName") && !modObj.get("displayName").isJsonNull()
                                        ? modObj.get("displayName").getAsString() : modId;
                                    int blocksCount = modObj.has("blocksCount") ? modObj.get("blocksCount").getAsInt() : 0;
                                    int entitiesCount = modObj.has("entitiesCount") ? modObj.get("entitiesCount").getAsInt() : 0;
                                    String version = modObj.has("version") && !modObj.get("version").isJsonNull()
                                        ? modObj.get("version").getAsString() : null;
                                    String downloadUrl = modObj.has("downloadUrl") && !modObj.get("downloadUrl").isJsonNull()
                                        ? modObj.get("downloadUrl").getAsString() : null;
                                    mods.put(modId, new ModInfo(modId, displayName, blocksCount, entitiesCount, downloadUrl, version));
                                }
                            }
                        }
                    }

                    lists.add(new InGameListInfo(id, names, descriptions, buildingCount, isPublic, isVirtual, isOwn, icon, contentHash, mods));
                }
            }

            boolean authenticated = json.has("authenticated") && json.get("authenticated").getAsBoolean();
            long userId = json.has("userId") && !json.get("userId").isJsonNull()
                ? json.get("userId").getAsLong() : 0;

            return new InGameListsResponse(statusCode, "Success", true, lists, authenticated, userId);

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Fetches the full spawnable list with buildings from the server asynchronously.
     *
     * @param listId the list ID to fetch (can be numeric or alphanumeric for virtual lists)
     * @param worldSeed the Minecraft world seed (used for download event tracking)
     * @param onComplete callback called on completion
     */
    public static void fetchSpawnableList(String listId, String worldSeed, Consumer<SpawnableListResponse> onComplete) {
        fetchSpawnableList(listId, null, worldSeed, onComplete);
    }

    /**
     * Fetches the spawnable list with optional hash validation.
     * If currentHash is provided and matches the server's hash, returns 204 (no changes).
     *
     * @param listId the list ID to fetch (can be numeric or alphanumeric for virtual lists)
     * @param currentHash the current list hash for cache validation (null to always fetch)
     * @param worldSeed the Minecraft world seed (used for download event tracking)
     * @param onComplete callback called on completion
     */
    public static void fetchSpawnableList(String listId, String currentHash, String worldSeed, Consumer<SpawnableListResponse> onComplete) {
        if (isCloudDenied()) {
            onComplete.accept(new SpawnableListResponse(403, "Cloud access denied: mod version too old", false, null, 0));
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return executeFetchSpawnableList(listId, currentHash, worldSeed);
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to fetch spawnable list {}", listId, e);
                return new SpawnableListResponse(0, "Error: " + e.getMessage(), false, null, 0);
            }
        }).thenAccept(onComplete);
    }

    private static SpawnableListResponse executeFetchSpawnableList(String listId, String currentHash, String worldSeed) throws Exception {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();

        // Build URL with query parameters
        StringBuilder urlBuilder = new StringBuilder(endpoint + "/lists/" + listId + "/export");
        boolean hasParams = false;

        if (currentHash != null && !currentHash.isEmpty()) {
            urlBuilder.append("?hash=").append(currentHash);
            hasParams = true;
        }

        if (worldSeed != null && !worldSeed.isEmpty()) {
            urlBuilder.append(hasParams ? "&" : "?").append("worldSeed=").append(worldSeed);
        }

        String url = urlBuilder.toString();

        Architect.LOGGER.info("Fetching spawnable list {} from {}", listId, url);

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.getRequestTimeout() * 1000);
            conn.setReadTimeout(config.getRequestTimeout() * 1000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", config.getAuth());
            conn.setRequestProperty("X-Api-Key", config.getApikey());

            int statusCode = conn.getResponseCode();

            // Handle 204 No Content - list hasn't changed
            if (statusCode == 204) {
                Architect.LOGGER.info("Spawnable list {} unchanged (hash match)", listId);
                return new SpawnableListResponse(statusCode, "Not Modified", true, null, 0);
            }

            String responseBody = readResponse(conn);

            Architect.LOGGER.info("Spawnable list response: {} - {} bytes", statusCode, responseBody.length());

            if (statusCode < 200 || statusCode >= 300) {
                String message = parseResponseMessage(responseBody, statusCode);
                return new SpawnableListResponse(statusCode, message, false, null, 0);
            }

            // Parse response
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            SpawnableList spawnableList = parseSpawnableList(json);

            // Extract userId for ownership check
            long userId = json.has("userId") && !json.get("userId").isJsonNull()
                ? json.get("userId").getAsLong() : 0;

            return new SpawnableListResponse(statusCode, "Success", true, spawnableList, userId);

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Parses a SpawnableList from the API export response.
     */
    private static SpawnableList parseSpawnableList(JsonObject json) {
        String listHash = json.has("listHash") && !json.get("listHash").isJsonNull()
            ? json.get("listHash").getAsString() : null;
        double spawningPercentage = json.has("spawningPercentage")
            ? json.get("spawningPercentage").getAsDouble() : 0.025;

        List<SpawnableBuilding> buildings = new ArrayList<>();

        if (json.has("buildings") && json.get("buildings").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("buildings")) {
                JsonObject bldgObj = element.getAsJsonObject();

                String rdns = bldgObj.get("rdns").getAsString();
                long pk = bldgObj.get("pk").getAsLong();
                long ownerUserId = bldgObj.has("ownerUserId") && !bldgObj.get("ownerUserId").isJsonNull()
                    ? bldgObj.get("ownerUserId").getAsLong() : 0;
                boolean isPrivate = bldgObj.has("isPrivate") && !bldgObj.get("isPrivate").isJsonNull()
                    && bldgObj.get("isPrivate").getAsBoolean();
                String hash = bldgObj.has("hash") && !bldgObj.get("hash").isJsonNull()
                    ? bldgObj.get("hash").getAsString() : null;
                String author = bldgObj.has("author") && !bldgObj.get("author").isJsonNull()
                    ? bldgObj.get("author").getAsString() : null;

                // Parse entrance anchor
                BlockPos entrance = BlockPos.ZERO;
                float entranceYaw = 0f;
                if (bldgObj.has("anchor") && bldgObj.get("anchor").isJsonObject()) {
                    JsonObject anchorObj = bldgObj.getAsJsonObject("anchor");
                    if (anchorObj.has("entrance") && anchorObj.get("entrance").isJsonObject()) {
                        JsonObject entranceObj = anchorObj.getAsJsonObject("entrance");
                        int x = entranceObj.has("x") ? entranceObj.get("x").getAsInt() : 0;
                        int y = entranceObj.has("y") ? entranceObj.get("y").getAsInt() : 0;
                        int z = entranceObj.has("z") ? entranceObj.get("z").getAsInt() : 0;
                        entranceYaw = entranceObj.has("yaw") ? entranceObj.get("yaw").getAsFloat() : 0f;
                        entrance = new BlockPos(x, y, z);
                    }
                }

                // Parse limits and rules
                int xWorld = 0;
                List<SpawnRule> rules = new ArrayList<>();

                if (bldgObj.has("limits") && bldgObj.get("limits").isJsonObject()) {
                    JsonObject limitsObj = bldgObj.getAsJsonObject("limits");
                    xWorld = limitsObj.has("xWorld") ? limitsObj.get("xWorld").getAsInt() : 0;

                    if (limitsObj.has("rules") && limitsObj.get("rules").isJsonArray()) {
                        for (JsonElement ruleElement : limitsObj.getAsJsonArray("rules")) {
                            JsonObject ruleObj = ruleElement.getAsJsonObject();

                            // Parse biomes list
                            List<String> biomes = new ArrayList<>();
                            if (ruleObj.has("biomes") && ruleObj.get("biomes").isJsonArray()) {
                                for (JsonElement biomeElement : ruleObj.getAsJsonArray("biomes")) {
                                    biomes.add(biomeElement.getAsString());
                                }
                            }

                            double percentage = ruleObj.has("percentage")
                                ? ruleObj.get("percentage").getAsDouble() : 1.0;

                            // Parse position
                            PositionType posType = PositionType.ON_GROUND;
                            int y1 = 50, y2 = 100, margin = 5;

                            if (ruleObj.has("position") && ruleObj.get("position").isJsonObject()) {
                                JsonObject posObj = ruleObj.getAsJsonObject("position");
                                String typeStr = posObj.has("type") ? posObj.get("type").getAsString() : "onGround";
                                posType = PositionType.fromApiValue(typeStr);
                                y1 = posObj.has("y1") ? posObj.get("y1").getAsInt() : 50;
                                y2 = posObj.has("y2") ? posObj.get("y2").getAsInt() : 100;
                                margin = posObj.has("margin") ? posObj.get("margin").getAsInt() : 5;
                            }

                            // Parse ensureBounds (backward compat: boolean or string)
                            EnsureBoundsMode ensureBoundsMode = EnsureBoundsMode.NONE;
                            if (ruleObj.has("ensureBounds")) {
                                JsonElement ebElem = ruleObj.get("ensureBounds");
                                if (ebElem.isJsonPrimitive() && ebElem.getAsJsonPrimitive().isBoolean()) {
                                    ensureBoundsMode = ebElem.getAsBoolean() ? EnsureBoundsMode.ALL : EnsureBoundsMode.NONE;
                                } else if (ebElem.isJsonPrimitive() && ebElem.getAsJsonPrimitive().isString()) {
                                    ensureBoundsMode = EnsureBoundsMode.fromApi(ebElem.getAsString());
                                }
                            }

                            rules.add(new SpawnRule(biomes, percentage, posType, y1, y2, margin, ensureBoundsMode));
                        }
                    }
                }

                // Parse bounds - API sends size {x,y,z}, convert to AABB(0,0,0, x-1,y-1,z-1)
                AABB bounds = new AABB(0, 0, 0, 1, 1, 1);
                if (bldgObj.has("bounds") && bldgObj.get("bounds").isJsonObject()) {
                    JsonObject boundsObj = bldgObj.getAsJsonObject("bounds");
                    int sizeX = boundsObj.has("x") ? boundsObj.get("x").getAsInt() : 1;
                    int sizeY = boundsObj.has("y") ? boundsObj.get("y").getAsInt() : 1;
                    int sizeZ = boundsObj.has("z") ? boundsObj.get("z").getAsInt() : 1;
                    bounds = new AABB(0, 0, 0, sizeX - 1, sizeY - 1, sizeZ - 1);
                }

                // Parse names (localized)
                java.util.Map<String, String> names = new java.util.HashMap<>();
                if (bldgObj.has("names") && bldgObj.get("names").isJsonObject()) {
                    JsonObject namesObj = bldgObj.getAsJsonObject("names");
                    for (java.util.Map.Entry<String, JsonElement> entry : namesObj.entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) {
                            names.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                }

                // Parse descriptions (localized)
                java.util.Map<String, String> descriptions = new java.util.HashMap<>();
                if (bldgObj.has("descriptions") && bldgObj.get("descriptions").isJsonObject()) {
                    JsonObject descsObj = bldgObj.getAsJsonObject("descriptions");
                    for (java.util.Map.Entry<String, JsonElement> entry : descsObj.entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) {
                            descriptions.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                }

                buildings.add(new SpawnableBuilding(rdns, pk, ownerUserId, isPrivate, hash, author, entrance, entranceYaw, xWorld, rules, bounds, names, descriptions));
            }
        }

        Architect.LOGGER.info("Parsed spawnable list: {} buildings, {}% spawn rate, hash={}",
            buildings.size(), spawningPercentage * 100, listHash);

        return new SpawnableList(listHash, spawningPercentage, buildings);
    }

    // ===== API Key Validation =====

    /**
     * Response for API key validation.
     */
    public record ValidateApiKeyResponse(int statusCode, String message, boolean success, long userId) {}

    /**
     * Validates an API key and returns the user ID.
     * Used when the user changes API key in config to update cached userId.
     *
     * @param onComplete callback called on completion
     */
    public static void validateApiKey(Consumer<ValidateApiKeyResponse> onComplete) {
        if (isCloudDenied()) {
            onComplete.accept(new ValidateApiKeyResponse(403, "Cloud access denied: mod version too old", false, 0));
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return executeValidateApiKey();
            } catch (Exception e) {
                Architect.LOGGER.error("Failed to validate API key", e);
                return new ValidateApiKeyResponse(0, "Error: " + e.getMessage(), false, 0);
            }
        }).thenAccept(onComplete);
    }

    private static ValidateApiKeyResponse executeValidateApiKey() throws Exception {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();
        String url = endpoint + "/api-keys/validate";

        String apiKey = config.getApikey();
        if (apiKey == null || apiKey.isEmpty()) {
            return new ValidateApiKeyResponse(401, "No API key configured", false, 0);
        }

        Architect.LOGGER.debug("Validating API key at {}", url);

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.getRequestTimeout() * 1000);
            conn.setReadTimeout(config.getRequestTimeout() * 1000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", config.getAuth());
            conn.setRequestProperty("X-Api-Key", apiKey);

            int statusCode = conn.getResponseCode();
            String responseBody = readResponse(conn);

            Architect.LOGGER.debug("Validate API key response: {} - {}", statusCode, responseBody);

            if (statusCode < 200 || statusCode >= 300) {
                String message = parseResponseMessage(responseBody, statusCode);
                return new ValidateApiKeyResponse(statusCode, message, false, 0);
            }

            // Parse response to get userId
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            long userId = json.has("userId") && !json.get("userId").isJsonNull()
                ? json.get("userId").getAsLong() : 0;

            return new ValidateApiKeyResponse(statusCode, "Success", true, userId);

        } finally {
            conn.disconnect();
        }
    }
}
