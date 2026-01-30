package it.magius.struttura.architect.ingame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.api.ApiClient;
import it.magius.struttura.architect.config.ArchitectConfig;
import net.minecraft.server.level.ServerPlayer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Manages building likes for the current world.
 * Tracks which buildings have been liked to prevent duplicate likes.
 * Persists likes to: <world>/struttura/likes.json
 */
public class LikeManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String LIKES_FILE = "likes.json";
    private static final String STRUTTURA_DIR = "struttura";

    private static LikeManager instance;

    private Path worldPath;
    private final Set<Long> likedBuildings = new HashSet<>();  // Building PKs that have been liked
    private String worldSeed;

    private LikeManager() {}

    public static LikeManager getInstance() {
        if (instance == null) {
            instance = new LikeManager();
        }
        return instance;
    }

    /**
     * Initializes the like manager for a world.
     * @param worldPath the world directory path
     * @param worldSeed the world seed (sent with like requests)
     */
    public void init(Path worldPath, String worldSeed) {
        this.worldPath = worldPath;
        this.worldSeed = worldSeed;
        load();
    }

    /**
     * Clears the manager state (called on world unload).
     */
    public void clear() {
        save();
        likedBuildings.clear();
        worldPath = null;
        worldSeed = null;
    }

    /**
     * Checks if a building has been liked in this world.
     * @param pk the building's primary key
     * @return true if already liked
     */
    public boolean hasLiked(long pk) {
        return likedBuildings.contains(pk);
    }

    /**
     * Result of a like operation to be passed to callback.
     */
    public record LikeOperationResult(boolean success, boolean isOwner) {}

    /**
     * Sends a like for a building.
     * @param player the player who is liking (for logging)
     * @param rdns the building's RDNS identifier
     * @param pk the building's primary key
     * @param onComplete callback called when the operation completes (can be null)
     */
    public void likeBuilding(ServerPlayer player, String rdns, long pk,
                             java.util.function.Consumer<LikeOperationResult> onComplete) {
        if (hasLiked(pk)) {
            Architect.LOGGER.debug("Building {} (pk={}) already liked by {} in this world",
                rdns, pk, player.getName().getString());
            if (onComplete != null) {
                onComplete.accept(new LikeOperationResult(true, false));  // Already liked = success
            }
            return;
        }

        // Send like to API asynchronously
        CompletableFuture.runAsync(() -> {
            boolean success = false;
            boolean isOwner = false;

            try {
                LikeResult result = sendLikeToApi(rdns, pk);

                // Update cached user ID if received from API (even on error responses)
                if (result.userId() > 0) {
                    InGameManager manager = InGameManager.getInstance();
                    if (manager != null && manager.isReady()) {
                        long currentUserId = manager.getCurrentUserId();
                        if (currentUserId != result.userId()) {
                            manager.getStorage().getState().setCurrentUserId(result.userId());
                            manager.getStorage().save();  // Persist to disk
                            Architect.LOGGER.info("Updated cached userId to {} from like response", result.userId());
                        }
                    }
                }

                // Mark as liked locally if the API call was successful
                if (result.success()) {
                    likedBuildings.add(pk);
                    save();
                    success = true;
                    Architect.LOGGER.info("Building {} liked by {}", rdns, player.getName().getString());
                } else if (result.statusCode() == 403) {
                    // 403 = own building or private building
                    // Also save locally on 403 to prevent repeated attempts on same building
                    likedBuildings.add(pk);
                    save();
                    isOwner = true;
                    Architect.LOGGER.debug("Cannot like building {} (status 403, saved locally to prevent retries)", rdns);
                } else {
                    Architect.LOGGER.warn("Like API returned error {} for building {}", result.statusCode(), rdns);
                }

            } catch (Exception e) {
                Architect.LOGGER.error("Failed to send like for building {}: {}",
                    rdns, e.getMessage());
            }

            // Call callback with result
            if (onComplete != null) {
                final boolean finalSuccess = success;
                final boolean finalIsOwner = isOwner;
                onComplete.accept(new LikeOperationResult(finalSuccess, finalIsOwner));
            }
        });
    }

    /**
     * Sends a like for a building (without callback).
     * @param player the player who is liking (for logging)
     * @param rdns the building's RDNS identifier
     * @param pk the building's primary key
     */
    public void likeBuilding(ServerPlayer player, String rdns, long pk) {
        likeBuilding(player, rdns, pk, null);
    }

    /**
     * Result from the like API call.
     */
    private record LikeResult(long userId, boolean success, int statusCode) {}

    /**
     * Sends the like request to the API.
     * @return the result containing userId, success status, and HTTP status code
     */
    private LikeResult sendLikeToApi(String rdns, long pk) throws Exception {
        ArchitectConfig config = ArchitectConfig.getInstance();
        String endpoint = config.getEndpoint();
        String url = endpoint + "/gameplay/building/like";

        // Build JSON payload
        String payload = String.format(
            "{\"buildingRdns\":\"%s\",\"worldSeed\":\"%s\"}",
            rdns, worldSeed != null ? worldSeed : "unknown"
        );

        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");

            // Include API key if available (optional)
            String apiKey = config.getApikey();
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("X-Api-Key", apiKey);
            }

            // Send payload
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }

            int statusCode = conn.getResponseCode();
            Architect.LOGGER.debug("Like API response: {}", statusCode);

            // Parse response to get userId (works for both success and error responses)
            long userId = 0;
            InputStream responseStream = (statusCode >= 200 && statusCode < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

            if (responseStream != null) {
                try (InputStreamReader reader = new InputStreamReader(responseStream, StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    if (json.has("userId") && !json.get("userId").isJsonNull()) {
                        userId = json.get("userId").getAsLong();
                    }
                } catch (Exception e) {
                    Architect.LOGGER.debug("Failed to parse like response: {}", e.getMessage());
                }
            }

            // For error responses, still return userId but mark as not successful
            if (statusCode < 200 || statusCode >= 300) {
                Architect.LOGGER.debug("Like API returned error status {} (userId={})", statusCode, userId);
                return new LikeResult(userId, false, statusCode);
            }

            return new LikeResult(userId, true, statusCode);

        } finally {
            conn.disconnect();
        }
    }

    /**
     * Loads liked buildings from disk.
     */
    private void load() {
        if (worldPath == null) {
            return;
        }

        Path filePath = worldPath.resolve(STRUTTURA_DIR).resolve(LIKES_FILE);
        if (!Files.exists(filePath)) {
            Architect.LOGGER.debug("No likes.json found, starting fresh");
            return;
        }

        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            likedBuildings.clear();

            for (var element : array) {
                likedBuildings.add(element.getAsLong());
            }

            Architect.LOGGER.info("Loaded {} liked buildings", likedBuildings.size());

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to load likes.json", e);
        }
    }

    /**
     * Saves liked buildings to disk.
     */
    private void save() {
        if (worldPath == null) {
            return;
        }

        Path filePath = worldPath.resolve(STRUTTURA_DIR).resolve(LIKES_FILE);

        try {
            Files.createDirectories(filePath.getParent());

            JsonArray array = new JsonArray();
            for (Long pk : likedBuildings) {
                array.add(pk);
            }

            try (Writer writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
                GSON.toJson(array, writer);
            }

            Architect.LOGGER.debug("Saved {} liked buildings to disk", likedBuildings.size());

        } catch (IOException e) {
            Architect.LOGGER.error("Failed to save likes.json", e);
        }
    }

    /**
     * Gets the count of liked buildings in this world.
     */
    public int getLikeCount() {
        return likedBuildings.size();
    }
}
