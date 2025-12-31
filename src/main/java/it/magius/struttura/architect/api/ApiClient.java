package it.magius.struttura.architect.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.config.ArchitectConfig;
import it.magius.struttura.architect.model.Construction;
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
     */
    private static byte[] serializeBlocksToNbt(Construction construction) throws IOException {
        CompoundTag root = new CompoundTag();

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

            // Aggiungi il blocco
            CompoundTag blockTag = new CompoundTag();
            blockTag.putInt("x", pos.getX());
            blockTag.putInt("y", pos.getY());
            blockTag.putInt("z", pos.getZ());
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
}
