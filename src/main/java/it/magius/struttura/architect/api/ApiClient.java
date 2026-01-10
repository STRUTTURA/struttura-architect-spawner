package it.magius.struttura.architect.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.config.ArchitectConfig;
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
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
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
    public record ApiResponse(int statusCode, String message, boolean success) {}

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
     * Esegue il push di una costruzione al server in modo asincrono.
     *
     * @param construction la costruzione da inviare
     * @param onComplete callback chiamato al completamento (sul main thread)
     * @return true se la richiesta è stata avviata, false se c'è già una richiesta in corso
     */
    public static boolean pushConstruction(Construction construction, Consumer<ApiResponse> onComplete) {
        return pushConstruction(construction, false, onComplete);
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
        if (!REQUEST_IN_PROGRESS.compareAndSet(false, true)) {
            return false;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return executePush(construction, purge);
            } catch (Exception e) {
                Architect.LOGGER.error("Push request failed", e);
                return new ApiResponse(0, "Error: " + e.getMessage(), false);
            } finally {
                REQUEST_IN_PROGRESS.set(false);
            }
        }).thenAccept(onComplete);

        return true;
    }

    private static ApiResponse executePush(Construction construction, boolean purge) throws Exception {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();
        String url = endpoint + "/building/add/" + construction.getId();
        if (purge) {
            url += "?purge=yes";
        }

        Architect.LOGGER.info("Pushing construction {} to {}", construction.getId(), url);

        // Prepara il payload JSON con blocks in base64
        JsonObject payload = buildPayload(construction);
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
            boolean success = statusCode >= 200 && statusCode < 300;

            return new ApiResponse(statusCode, message, success);

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Costruisce il payload JSON per il push.
     */
    private static JsonObject buildPayload(Construction construction) throws IOException {
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
        json.addProperty("blocksCount", construction.getTotalBlockCount());
        json.addProperty("entitiesCount", construction.getTotalEntityCount());
        json.addProperty("mobsCount", construction.getTotalMobCount());
        json.addProperty("commandBlocksCount", construction.getTotalCommandBlockCount());

        // Bounds (normalizzati: min=0, max=size-1)
        JsonObject bounds = new JsonObject();
        var b = construction.getBounds();
        if (b.isValid()) {
            // I bounds nel metadata devono essere relativi (0,0,0 based)
            // perché i blocchi sono salvati con coordinate normalizzate
            bounds.addProperty("minX", 0);
            bounds.addProperty("minY", 0);
            bounds.addProperty("minZ", 0);
            bounds.addProperty("maxX", b.getSizeX() - 1);
            bounds.addProperty("maxY", b.getSizeY() - 1);
            bounds.addProperty("maxZ", b.getSizeZ() - 1);
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

        // Versione del mod Struttura
        json.addProperty("modVersion", Architect.MOD_VERSION);

        // Blocchi in formato NBT compresso e codificato base64
        byte[] nbtBytes = serializeBlocksToNbt(construction);
        String blocksBase64 = Base64.getEncoder().encodeToString(nbtBytes);
        json.addProperty("blocks", blocksBase64);

        Architect.LOGGER.debug("NBT size: {} bytes, Base64 size: {} bytes",
            nbtBytes.length, blocksBase64.length());

        // Entità in formato NBT compresso e codificato base64 (se presenti)
        if (!construction.getEntities().isEmpty()) {
            byte[] entitiesNbtBytes = serializeEntitiesToNbt(construction);
            String entitiesBase64 = Base64.getEncoder().encodeToString(entitiesNbtBytes);
            json.addProperty("entities", entitiesBase64);

            Architect.LOGGER.debug("Entities NBT size: {} bytes, Base64 size: {} bytes",
                entitiesNbtBytes.length, entitiesBase64.length());
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
        if (!REQUEST_IN_PROGRESS.compareAndSet(false, true)) {
            return false;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return executeUploadScreenshot(constructionId, imageData, filename, title, purge);
            } catch (Exception e) {
                Architect.LOGGER.error("Screenshot upload failed", e);
                return new ApiResponse(0, "Error: " + e.getMessage(), false);
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

            return new ApiResponse(statusCode, message, success);

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Esegue il pull di una costruzione dal server in modo asincrono.
     *
     * @param constructionId l'ID della costruzione da scaricare
     * @param onComplete callback chiamato al completamento
     * @return true se la richiesta è stata avviata, false se c'è già una richiesta in corso
     */
    public static boolean pullConstruction(String constructionId, Consumer<PullResponse> onComplete) {
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

        // 2. Scarica i blocchi (NBT binario compresso, non base64)
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
            blocksConn.setRequestProperty("Accept", "application/octet-stream");
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

        // 3. Scarica le entità (NBT binario compresso, opzionale - può non esistere)
        String entitiesUrl = endpoint + "/building/" + constructionId + "/entities";
        Architect.LOGGER.info("Pulling entities for {} from {}", constructionId, entitiesUrl);

        byte[] entitiesData = null;

        HttpURLConnection entitiesConn = (HttpURLConnection) URI.create(entitiesUrl).toURL().openConnection();
        try {
            entitiesConn.setRequestMethod("GET");
            entitiesConn.setInstanceFollowRedirects(true);
            entitiesConn.setConnectTimeout(config.getRequestTimeout() * 1000);
            entitiesConn.setReadTimeout(config.getRequestTimeout() * 1000);
            entitiesConn.setRequestProperty("Accept", "application/octet-stream");
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

            // Parse i bounds dal metadata (necessari per denormalizzare le coordinate)
            if (metadata.has("bounds") && metadata.get("bounds").isJsonObject()) {
                JsonObject boundsObj = metadata.getAsJsonObject("bounds");
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

            // Deserializza i blocchi da NBT compresso (dati binari diretti, non base64)
            if (blocksData != null && blocksData.length > 0) {
                deserializeBlocksFromNbt(construction, blocksData);
            }

            // Deserializza le entità da NBT compresso (opzionale)
            if (entitiesData != null && entitiesData.length > 0) {
                deserializeEntitiesFromNbt(construction, entitiesData);
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

            return new MetadataResponse(metadataStatus, "Success", true, constructionId, requiredMods);

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to parse metadata response", e);
            return new MetadataResponse(metadataStatus, "Failed to parse metadata", false, constructionId, null);
        }
    }
}
