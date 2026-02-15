package it.magius.struttura.architect.registry;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ConstructionBounds;
import it.magius.struttura.architect.storage.ConstructionStorage;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.*;

/**
 * Registro globale delle costruzioni nel mondo.
 * Tiene traccia di tutte le costruzioni create/caricate.
 * Integrato con ConstructionStorage per la persistenza.
 */
public class ConstructionRegistry {

    // Singleton instance
    private static final ConstructionRegistry INSTANCE = new ConstructionRegistry();

    // Mappa ID -> Costruzione
    private final Map<String, Construction> constructions = new HashMap<>();

    // Mappa ID -> Posizione centro (per teleport)
    private final Map<String, BlockPos> constructionPositions = new HashMap<>();

    // Storage per persistenza
    private ConstructionStorage storage;

    // Flag per indicare se lo storage è stato inizializzato
    private boolean storageInitialized = false;

    private ConstructionRegistry() {}

    public static ConstructionRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Inizializza lo storage con la directory del gioco.
     * Deve essere chiamato all'avvio del server/mondo.
     *
     * @param gameDirectory la directory del gioco (.minecraft)
     */
    public void initStorage(Path gameDirectory) {
        this.storage = new ConstructionStorage(gameDirectory);
        this.storageInitialized = true;
        Architect.LOGGER.info("ConstructionRegistry storage initialized at: {}/struttura/structures",
            gameDirectory);
    }

    /**
     * Carica tutte le costruzioni salvate da disco.
     */
    public void loadAll() {
        if (!storageInitialized) {
            Architect.LOGGER.warn("Cannot load constructions: storage not initialized");
            return;
        }

        List<String> ids = storage.listAll();
        int loaded = 0;

        for (String id : ids) {
            Construction construction = storage.load(id);
            if (construction != null) {
                constructions.put(id, construction);

                // Aggiorna posizione
                ConstructionBounds bounds = construction.getBounds();
                if (bounds.isInitialized()) {
                    constructionPositions.put(id, bounds.getCenter());
                }

                loaded++;
            }
        }

        Architect.LOGGER.info("Loaded {} constructions from disk", loaded);
    }

    /**
     * Salva tutte le costruzioni su disco.
     */
    public void saveAll() {
        if (!storageInitialized) {
            Architect.LOGGER.warn("Cannot save constructions: storage not initialized");
            return;
        }

        int saved = 0;
        for (Construction construction : constructions.values()) {
            if (storage.save(construction)) {
                saved++;
            }
        }

        Architect.LOGGER.info("Saved {} constructions to disk", saved);
    }

    /**
     * Registra una costruzione nel registro e la salva su disco.
     */
    public void register(Construction construction) {
        constructions.put(construction.getId(), construction);

        // Salva la posizione centro
        ConstructionBounds bounds = construction.getBounds();
        if (bounds.isInitialized()) {
            constructionPositions.put(construction.getId(), bounds.getCenter());
        }

        // Salva su disco
        if (storageInitialized && storage != null) {
            storage.save(construction);
        }
    }

    /**
     * Rimuove una costruzione dal registro e da disco.
     */
    public void unregister(String id) {
        constructions.remove(id);
        constructionPositions.remove(id);

        // Elimina da disco
        if (storageInitialized && storage != null) {
            storage.delete(id);
        }
    }

    /**
     * Ottiene una costruzione per ID.
     * Se non è in memoria, prova a caricarla da disco.
     */
    public Construction get(String id) {
        Construction construction = constructions.get(id);

        // Se non in memoria, prova a caricare da disco
        if (construction == null && storageInitialized && storage != null) {
            construction = storage.load(id);
            if (construction != null) {
                constructions.put(id, construction);

                ConstructionBounds bounds = construction.getBounds();
                if (bounds.isInitialized()) {
                    constructionPositions.put(id, bounds.getCenter());
                }
            }
        }

        return construction;
    }

    /**
     * Verifica se una costruzione esiste (in memoria o su disco).
     */
    public boolean exists(String id) {
        if (constructions.containsKey(id)) {
            return true;
        }

        // Controlla anche su disco
        if (storageInitialized && storage != null) {
            return storage.exists(id);
        }

        return false;
    }

    /**
     * Ottiene la posizione di una costruzione.
     */
    public BlockPos getPosition(String id) {
        return constructionPositions.get(id);
    }

    /**
     * Aggiorna la posizione di una costruzione.
     */
    public void updatePosition(String id, BlockPos pos) {
        constructionPositions.put(id, pos);
    }

    /**
     * Ottiene tutte le costruzioni registrate.
     */
    public Collection<Construction> getAll() {
        return Collections.unmodifiableCollection(constructions.values());
    }

    /**
     * Ottiene tutti gli ID delle costruzioni (memoria + disco).
     */
    public Set<String> getAllIds() {
        Set<String> allIds = new HashSet<>(constructions.keySet());

        // Aggiungi anche quelli su disco non ancora caricati
        if (storageInitialized && storage != null) {
            allIds.addAll(storage.listAll());
        }

        return Collections.unmodifiableSet(allIds);
    }

    /**
     * Ottiene il numero di costruzioni registrate in memoria.
     */
    public int getCount() {
        return constructions.size();
    }

    /**
     * Verifica se lo storage è inizializzato.
     */
    public boolean isStorageInitialized() {
        return storageInitialized;
    }

    /**
     * Gets the underlying storage instance.
     * Returns null if storage is not initialized.
     */
    public ConstructionStorage getStorage() {
        return storage;
    }

    /**
     * Pulisce il registro (usato quando si cambia mondo).
     */
    public void clear() {
        constructions.clear();
        constructionPositions.clear();
    }

    /**
     * Pulisce completamente il registro e resetta lo storage.
     */
    public void shutdown() {
        saveAll();
        clear();
        storage = null;
        storageInitialized = false;
    }
}
