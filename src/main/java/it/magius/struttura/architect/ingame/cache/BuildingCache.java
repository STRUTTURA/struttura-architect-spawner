package it.magius.struttura.architect.ingame.cache;

import it.magius.struttura.architect.config.ArchitectConfig;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ConstructionSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LRU cache for downloaded building constructions (NBT data).
 * Implements configurable max size with least-recently-used eviction.
 *
 * Thread-safe for concurrent access during spawning operations.
 */
public class BuildingCache {

    private static BuildingCache instance;

    // Main cache storage with access tracking
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    // Hash tracking for cache invalidation
    private final Map<String, String> hashByRdns = new ConcurrentHashMap<>();

    // Snapshot storage for InGame spawner (full block/entity data for placement)
    // TODO: Refactor to use ConstructionSnapshot as the primary cache entry instead of Construction
    private final Map<String, ConstructionSnapshot> snapshotByRdns = new ConcurrentHashMap<>();

    // Track buildings currently being downloaded to prevent duplicate download requests
    private final Set<String> downloading = ConcurrentHashMap.newKeySet();

    private BuildingCache() {}

    public static BuildingCache getInstance() {
        if (instance == null) {
            instance = new BuildingCache();
        }
        return instance;
    }

    /**
     * Gets a cached construction by RDNS.
     * Updates access time for LRU tracking.
     * @param rdns the building's reverse DNS identifier
     * @return the cached construction, or null if not cached
     */
    public Construction get(String rdns) {
        CacheEntry entry = cache.get(rdns);
        if (entry != null) {
            entry.updateAccessTime();
            return entry.construction;
        }
        return null;
    }

    /**
     * Caches a construction with its hash.
     * If cache exceeds max size, evicts least recently used entries.
     * @param rdns the building's reverse DNS identifier
     * @param construction the construction to cache
     * @param hash the content hash for cache invalidation
     */
    public void put(String rdns, Construction construction, String hash) {
        int maxSize = ArchitectConfig.getInstance().getMaxCachedNbt();

        // Check if we need to evict before adding
        if (!cache.containsKey(rdns) && cache.size() >= maxSize) {
            evictLeastRecentlyUsed();
        }

        cache.put(rdns, new CacheEntry(construction));
        if (hash != null) {
            hashByRdns.put(rdns, hash);
        }
    }

    /**
     * Caches a construction (without hash tracking).
     * @param rdns the building's reverse DNS identifier
     * @param construction the construction to cache
     */
    public void put(String rdns, Construction construction) {
        put(rdns, construction, null);
    }

    /**
     * Checks if a building is cached.
     * @param rdns the building's reverse DNS identifier
     * @return true if cached
     */
    public boolean contains(String rdns) {
        return cache.containsKey(rdns);
    }

    /**
     * Checks if a building is currently being downloaded.
     * @param rdns the building's reverse DNS identifier
     * @return true if download is in progress
     */
    public boolean isDownloading(String rdns) {
        return downloading.contains(rdns);
    }

    /**
     * Marks a building as currently being downloaded.
     * Returns true if the mark was successful (not already downloading).
     * @param rdns the building's reverse DNS identifier
     * @return true if marked, false if already downloading
     */
    public boolean markDownloading(String rdns) {
        return downloading.add(rdns);
    }

    /**
     * Clears the downloading flag for a building.
     * Should be called when download completes (success or failure).
     * @param rdns the building's reverse DNS identifier
     */
    public void clearDownloading(String rdns) {
        downloading.remove(rdns);
    }

    /**
     * Gets the cached hash for a building.
     * @param rdns the building's reverse DNS identifier
     * @return the hash, or null if not tracked
     */
    public String getHash(String rdns) {
        return hashByRdns.get(rdns);
    }

    /**
     * Stores a snapshot for a building (full block/entity data for spawning).
     * TODO: Refactor to use ConstructionSnapshot as the primary cache type.
     * @param rdns the building's reverse DNS identifier
     * @param snapshot the snapshot containing block states, NBT, and entity data
     */
    public void putSnapshot(String rdns, ConstructionSnapshot snapshot) {
        snapshotByRdns.put(rdns, snapshot);
    }

    /**
     * Gets the cached snapshot for a building.
     * TODO: Refactor to use ConstructionSnapshot as the primary cache type.
     * @param rdns the building's reverse DNS identifier
     * @return the cached snapshot, or null if not available
     */
    public ConstructionSnapshot getSnapshot(String rdns) {
        return snapshotByRdns.get(rdns);
    }

    /**
     * Removes a building from the cache.
     * @param rdns the building's reverse DNS identifier
     */
    public void remove(String rdns) {
        cache.remove(rdns);
        hashByRdns.remove(rdns);
        snapshotByRdns.remove(rdns);
    }

    /**
     * Removes buildings whose hash doesn't match the expected hash.
     * Used for cache invalidation when list is refreshed.
     * @param rdns the building's RDNS
     * @param expectedHash the expected content hash
     */
    public void invalidateIfHashMismatch(String rdns, String expectedHash) {
        String cachedHash = hashByRdns.get(rdns);
        if (cachedHash != null && !cachedHash.equals(expectedHash)) {
            remove(rdns);
        }
    }

    /**
     * Clears the entire cache.
     */
    public void clear() {
        cache.clear();
        hashByRdns.clear();
        snapshotByRdns.clear();
        downloading.clear();
    }

    /**
     * Gets the number of cached buildings.
     */
    public int size() {
        return cache.size();
    }

    /**
     * Gets all cached entries for disk persistence.
     * @return map of RDNS to construction
     */
    public Map<String, Construction> getAllEntries() {
        Map<String, Construction> result = new LinkedHashMap<>();
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            result.put(entry.getKey(), entry.getValue().construction);
        }
        return result;
    }

    /**
     * Gets all tracked hashes for disk persistence.
     * @return map of RDNS to hash
     */
    public Map<String, String> getAllHashes() {
        return new LinkedHashMap<>(hashByRdns);
    }

    /**
     * Evicts the least recently used entry from the cache.
     */
    private void evictLeastRecentlyUsed() {
        String lruKey = null;
        long oldestTime = Long.MAX_VALUE;

        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (entry.getValue().lastAccessTime < oldestTime) {
                oldestTime = entry.getValue().lastAccessTime;
                lruKey = entry.getKey();
            }
        }

        if (lruKey != null) {
            remove(lruKey);
        }
    }

    /**
     * Cache entry with access time tracking for LRU eviction.
     */
    private static class CacheEntry {
        final Construction construction;
        volatile long lastAccessTime;

        CacheEntry(Construction construction) {
            this.construction = construction;
            this.lastAccessTime = System.currentTimeMillis();
        }

        void updateAccessTime() {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }
}
