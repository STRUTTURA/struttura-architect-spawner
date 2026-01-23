package it.magius.struttura.architect.ingame.cache;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache for downloaded building constructions.
 * Avoids re-downloading the same building multiple times during a session.
 */
public class BuildingCache {

    private static BuildingCache instance;

    private final Map<String, Construction> cache = new ConcurrentHashMap<>();

    private BuildingCache() {}

    public static BuildingCache getInstance() {
        if (instance == null) {
            instance = new BuildingCache();
        }
        return instance;
    }

    /**
     * Gets a cached construction by RDNS.
     * @param rdns the building's reverse DNS identifier
     * @return the cached construction, or null if not cached
     */
    public Construction get(String rdns) {
        return cache.get(rdns);
    }

    /**
     * Caches a construction.
     * @param rdns the building's reverse DNS identifier
     * @param construction the construction to cache
     */
    public void put(String rdns, Construction construction) {
        cache.put(rdns, construction);
        Architect.LOGGER.debug("Cached building: {} ({} blocks)",
            rdns, construction.getBlockCount());
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
     * Removes a building from the cache.
     * @param rdns the building's reverse DNS identifier
     */
    public void remove(String rdns) {
        cache.remove(rdns);
    }

    /**
     * Clears the entire cache.
     */
    public void clear() {
        int count = cache.size();
        cache.clear();
        Architect.LOGGER.info("Cleared building cache ({} entries)", count);
    }

    /**
     * Gets the number of cached buildings.
     */
    public int size() {
        return cache.size();
    }
}
