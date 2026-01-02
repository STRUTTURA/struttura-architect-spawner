package it.magius.struttura.architect.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.config.ArchitectConfig;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ModInfo;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

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
        if (!REQUEST_IN_PROGRESS.compareAndSet(false, true)) {
            return false;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return executePush(construction);
            } catch (Exception e) {
                Architect.LOGGER.error("Push request failed", e);
                return new ApiResponse(0, "Error: " + e.getMessage(), false);
            } finally {
                REQUEST_IN_PROGRESS.set(false);
            }
        }).thenAccept(onComplete);

        return true;
    }

    private static ApiResponse executePush(Construction construction) throws Exception {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();
        String url = endpoint + "/building/add/" + construction.getId();

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
        json.add("short_descriptions", shortDescriptions);

        // Descrizioni complete multilingua
        JsonObject descriptions = new JsonObject();
        for (var entry : construction.getDescriptions().entrySet()) {
            descriptions.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("descriptions", descriptions);

        json.addProperty("blockCount", construction.getBlockCount());
        json.addProperty("solidBlockCount", construction.getSolidBlockCount());

        // Bounds
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

        // Calcola e aggiungi i mod richiesti
        construction.computeRequiredMods();
        JsonObject modsObject = new JsonObject();
        for (ModInfo mod : construction.getRequiredMods().values()) {
            JsonObject modJson = new JsonObject();
            modJson.addProperty("displayName", mod.getDisplayName());
            modJson.addProperty("blockCount", mod.getBlockCount());
            modJson.addProperty("entityCount", mod.getEntityCount());
            if (mod.getVersion() != null) {
                modJson.addProperty("version", mod.getVersion());
            }
            if (mod.getDownloadUrl() != null) {
                modJson.addProperty("downloadUrl", mod.getDownloadUrl());
            }
            modsObject.add(mod.getModId(), modJson);
        }
        json.add("mods", modsObject);

        // Versione del mod Struttura
        json.addProperty("strutturaVersion", Architect.MOD_VERSION);

        // Blocchi in formato NBT compresso e codificato base64
        byte[] nbtBytes = serializeBlocksToNbt(construction);
        String blocksBase64 = Base64.getEncoder().encodeToString(nbtBytes);
        json.addProperty("blocks", blocksBase64);

        Architect.LOGGER.debug("NBT size: {} bytes, Base64 size: {} bytes",
            nbtBytes.length, blocksBase64.length());

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
            blocksList.add(blockTag);
        }

        root.put("palette", paletteList);
        root.put("blocks", blocksList);
        root.putInt("version", 1);

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
        if (!REQUEST_IN_PROGRESS.compareAndSet(false, true)) {
            return false;
        }

        CompletableFuture.supplyAsync(() -> {
            try {
                return executeUploadScreenshot(constructionId, imageData, filename, title);
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
                                                       String filename, String title) throws Exception {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();
        String url = endpoint + "/building/" + constructionId + "/images";

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

        // 3. Parse e combina i dati
        Construction construction = parseConstructionFromResponses(constructionId, metadataBody, blocksData);
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
     * Parsa le risposte (metadati JSON e blocchi NBT binari) e crea un oggetto Construction.
     *
     * @param constructionId l'ID della costruzione
     * @param metadataBody la risposta JSON di /building/:id/metadata
     * @param blocksData i dati NBT binari compressi di /building/:id/blocks
     */
    private static Construction parseConstructionFromResponses(String constructionId, String metadataBody, byte[] blocksData) {
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
                    if (modJson.has("blockCount")) {
                        info.setBlockCount(modJson.get("blockCount").getAsInt());
                    }
                    if (modJson.has("entityCount")) {
                        info.setEntityCount(modJson.get("entityCount").getAsInt());
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

            // Deserializza i blocchi da NBT compresso (dati binari diretti, non base64)
            if (blocksData != null && blocksData.length > 0) {
                deserializeBlocksFromNbt(construction, blocksData);
            }

            Architect.LOGGER.info("Parsed construction {} with {} blocks",
                constructionId, construction.getBlockCount());

            return construction;

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to parse construction response", e);
            return null;
        }
    }

    /**
     * Deserializza i blocchi da NBT compresso e li aggiunge alla costruzione.
     * I blocchi hanno coordinate relative (normalizzate a 0,0,0).
     */
    private static void deserializeBlocksFromNbt(Construction construction, byte[] nbtBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(nbtBytes);
        CompoundTag root = NbtIo.readCompressed(bais, net.minecraft.nbt.NbtAccounter.unlimitedHeap());

        // Leggi la palette - MC 1.21 API con Optional
        ListTag paletteList = root.getList("palette").orElse(new ListTag());
        List<BlockState> palette = new ArrayList<>();

        for (int i = 0; i < paletteList.size(); i++) {
            CompoundTag paletteEntry = paletteList.getCompound(i).orElseThrow();
            String stateString = paletteEntry.getString("state").orElse("");
            BlockState state = parseBlockState(stateString);
            palette.add(state);
        }

        // Leggi i blocchi (coordinate relative)
        ListTag blocksList = root.getList("blocks").orElse(new ListTag());
        for (int i = 0; i < blocksList.size(); i++) {
            CompoundTag blockTag = blocksList.getCompound(i).orElseThrow();
            int x = blockTag.getInt("x").orElse(0);
            int y = blockTag.getInt("y").orElse(0);
            int z = blockTag.getInt("z").orElse(0);
            int paletteIndex = blockTag.getInt("p").orElse(0);

            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = palette.get(paletteIndex);
            construction.addBlock(pos, state);
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
                    if (modJson.has("blockCount")) {
                        info.setBlockCount(modJson.get("blockCount").getAsInt());
                    }
                    if (modJson.has("entityCount")) {
                        info.setEntityCount(modJson.get("entityCount").getAsInt());
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
