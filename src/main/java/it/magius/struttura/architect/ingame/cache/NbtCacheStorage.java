package it.magius.struttura.architect.ingame.cache;

import com.google.gson.*;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ConstructionSnapshot;
import it.magius.struttura.architect.storage.ConstructionStorage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Handles persistence of NBT cache to disk.
 * Saves cached constructions on world unload and loads them on world load.
 * Only loads NBT files that match the current list's hashes (invalidates stale cache).
 *
 * Uses ConstructionStorage for actual NBT serialization.
 */
public class NbtCacheStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CACHE_DIR = "struttura/spawner";
    private static final String INDEX_FILE = "cache_index.json";

    private final Path worldPath;
    private final ConstructionStorage constructionStorage;

    public NbtCacheStorage(Path worldPath) {
        this.worldPath = worldPath;
        // Use the cache directory as the base for ConstructionStorage (direct path mode)
        this.constructionStorage = new ConstructionStorage(getCacheDir(), true);
    }

    /**
     * Saves all cached constructions to disk.
     * Called on world unload.
     */
    public void save() {
        BuildingCache cache = BuildingCache.getInstance();
        Map<String, Construction> entries = cache.getAllEntries();
        Map<String, String> hashes = cache.getAllHashes();

        if (entries.isEmpty()) {
            return;
        }

        Path cacheDir = getCacheDir();
        try {
            Files.createDirectories(cacheDir);

            // Save index file with hashes
            JsonObject index = new JsonObject();
            for (Map.Entry<String, String> entry : hashes.entrySet()) {
                index.addProperty(entry.getKey(), entry.getValue());
            }

            Path indexPath = cacheDir.resolve(INDEX_FILE);
            try (Writer writer = Files.newBufferedWriter(indexPath, StandardCharsets.UTF_8)) {
                GSON.toJson(index, writer);
            }

            // Save each construction using ConstructionStorage (NBT only, no metadata)
            // TODO: InGame cache needs refactoring to store ConstructionSnapshot instead of Construction
            //       For now, the save path is disabled since Construction is reference-only
            //       and no longer contains block state data.
            int saved = 0;
            for (Map.Entry<String, Construction> entry : entries.entrySet()) {
                String rdns = entry.getKey();
                Construction construction = entry.getValue();
                ConstructionSnapshot snapshot = cache.getSnapshot(rdns);

                if (snapshot == null) {
                    Architect.LOGGER.warn("No snapshot available for NBT cache save: {}", rdns);
                    continue;
                }

                try {
                    if (constructionStorage.saveNbtOnly(construction, snapshot)) {
                        saved++;
                    }
                } catch (Exception e) {
                    Architect.LOGGER.warn("Failed to save NBT cache for {}: {}", rdns, e.getMessage());
                }
            }

        } catch (IOException e) {
            Architect.LOGGER.error("Failed to save NBT cache", e);
        }
    }

    /**
     * Loads cached constructions from disk that match the expected hashes.
     * Only loads NBT files that have the same hash as the current list (invalidates stale cache).
     * @param expectedHashes map of RDNS to expected content hash from current list
     */
    public void load(Map<String, String> expectedHashes) {
        Path cacheDir = getCacheDir();
        Path indexPath = cacheDir.resolve(INDEX_FILE);

        if (!Files.exists(indexPath)) {
            return;
        }

        try {
            // Load index
            Map<String, String> cachedHashes = new HashMap<>();
            try (Reader reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
                JsonObject index = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : index.entrySet()) {
                    cachedHashes.put(entry.getKey(), entry.getValue().getAsString());
                }
            }

            // Load each construction that has a matching hash
            BuildingCache cache = BuildingCache.getInstance();
            int loaded = 0;
            int skipped = 0;

            for (Map.Entry<String, String> entry : cachedHashes.entrySet()) {
                String rdns = entry.getKey();
                String cachedHash = entry.getValue();

                // Check if this building is in the current list with the same hash
                String expectedHash = expectedHashes.get(rdns);
                if (expectedHash == null || !expectedHash.equals(cachedHash)) {
                    // Hash mismatch or building no longer in list - don't load
                    skipped++;
                    continue;
                }

                // Load NBT data as a snapshot (full block state data for spawning)
                // TODO: InGame cache needs refactoring to store ConstructionSnapshot instead of Construction
                try {
                    ConstructionSnapshot snapshot = constructionStorage.loadNbtOnly(rdns, null);
                    if (snapshot != null) {
                        // Create a minimal Construction for backward compatibility
                        Construction construction = new Construction(rdns, new java.util.UUID(0, 0), "ingame");
                        cache.put(rdns, construction, cachedHash);
                        cache.putSnapshot(rdns, snapshot);
                        loaded++;
                    }
                } catch (Exception e) {
                    Architect.LOGGER.warn("Failed to load NBT cache for {}: {}", rdns, e.getMessage());
                }
            }

            // Clean up stale files
            cleanupStaleFiles(expectedHashes.keySet());

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to load NBT cache", e);
        }
    }

    /**
     * Deletes the entire cache directory.
     * Called on reset.
     */
    public void deleteAll() {
        Path cacheDir = getCacheDir();
        if (!Files.exists(cacheDir)) {
            return;
        }

        try {
            // Delete all files and directories recursively
            Files.walk(cacheDir)
                .sorted((a, b) -> b.compareTo(a)) // Reverse order to delete children first
                .forEach(file -> {
                    try {
                        Files.delete(file);
                    } catch (IOException e) {
                        Architect.LOGGER.warn("Failed to delete cache file {}: {}", file, e.getMessage());
                    }
                });
        } catch (IOException e) {
            Architect.LOGGER.error("Failed to delete NBT cache directory", e);
        }
    }

    private Path getCacheDir() {
        return worldPath.resolve(CACHE_DIR);
    }

    /**
     * Deletes cache entries for buildings that are no longer in the current list.
     */
    private void cleanupStaleFiles(Set<String> validRdns) {
        // List all saved constructions and delete those not in validRdns
        List<String> savedIds = constructionStorage.listAll();

        for (String id : savedIds) {
            if (!validRdns.contains(id)) {
                constructionStorage.delete(id);
            }
        }

        // Also remove entries from the index that are no longer valid
        Path indexPath = getCacheDir().resolve(INDEX_FILE);
        if (Files.exists(indexPath)) {
            try {
                JsonObject index;
                try (Reader reader = Files.newBufferedReader(indexPath, StandardCharsets.UTF_8)) {
                    index = JsonParser.parseReader(reader).getAsJsonObject();
                }

                // Remove stale entries
                Set<String> keysToRemove = new HashSet<>();
                for (String key : index.keySet()) {
                    if (!validRdns.contains(key)) {
                        keysToRemove.add(key);
                    }
                }
                for (String key : keysToRemove) {
                    index.remove(key);
                }

                // Write updated index
                try (Writer writer = Files.newBufferedWriter(indexPath, StandardCharsets.UTF_8)) {
                    GSON.toJson(index, writer);
                }
            } catch (IOException e) {
                Architect.LOGGER.warn("Failed to update cache index: {}", e.getMessage());
            }
        }
    }
}
