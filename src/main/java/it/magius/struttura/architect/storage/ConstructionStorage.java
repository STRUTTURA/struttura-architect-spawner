package it.magius.struttura.architect.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.Construction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

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

    public ConstructionStorage(Path gameDirectory) {
        this.baseDirectory = gameDirectory.resolve("struttura").resolve("structures");
        ensureDirectoryExists(baseDirectory);
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

            // Salva metadata
            saveMetadata(construction, constructionDir);

            // Salva blocchi
            saveBlocks(construction, constructionDir);

            Architect.LOGGER.info("Saved construction: {} ({} blocks)",
                construction.getId(), construction.getBlockCount());
            return true;

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to save construction: {}", construction.getId(), e);
            return false;
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

            Architect.LOGGER.info("Loaded construction: {} ({} blocks)",
                construction.getId(), construction.getBlockCount());
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
            // Scansiona namespace/category/name
            Files.walk(baseDirectory, 3)
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

        // Titoli multilingua
        JsonObject titles = new JsonObject();
        for (var entry : construction.getTitles().entrySet()) {
            titles.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("titles", titles);

        // Descrizioni brevi multilingua
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

            // Carica titoli multilingua
            Map<String, String> titles = new HashMap<>();
            if (json.has("titles") && json.get("titles").isJsonObject()) {
                JsonObject titlesObj = json.getAsJsonObject("titles");
                for (var entry : titlesObj.entrySet()) {
                    if (!entry.getValue().isJsonNull()) {
                        titles.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } else if (json.has("title") && !json.get("title").isJsonNull()) {
                // Retrocompatibilità: vecchio formato con singolo title -> usa "en"
                titles.put("en", json.get("title").getAsString());
            }

            // Carica descrizioni brevi multilingua
            Map<String, String> shortDescriptions = new HashMap<>();
            if (json.has("short_descriptions") && json.get("short_descriptions").isJsonObject()) {
                JsonObject shortDescObj = json.getAsJsonObject("short_descriptions");
                for (var entry : shortDescObj.entrySet()) {
                    if (!entry.getValue().isJsonNull()) {
                        shortDescriptions.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } else if (json.has("description") && !json.get("description").isJsonNull()) {
                // Retrocompatibilità: vecchio formato con singola description -> metti in short_descriptions
                String desc = json.get("description").getAsString();
                if (!desc.isEmpty()) {
                    shortDescriptions.put("en", desc);
                }
            }

            // Carica descrizioni complete multilingua
            Map<String, String> descriptions = new HashMap<>();
            if (json.has("descriptions") && json.get("descriptions").isJsonObject()) {
                JsonObject descriptionsObj = json.getAsJsonObject("descriptions");
                for (var entry : descriptionsObj.entrySet()) {
                    if (!entry.getValue().isJsonNull()) {
                        descriptions.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }

            return new Construction(id, authorId, authorName, createdAt, titles, shortDescriptions, descriptions);
        }
    }

    // ===== Blocks serialization (NBT) =====

    private void saveBlocks(Construction construction, Path directory) throws IOException {
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
            blockTag.putInt("p", paletteIndex); // palette index
            blocksList.add(blockTag);
        }

        root.put("palette", paletteList);
        root.put("blocks", blocksList);
        root.putInt("version", 1); // NBT format version

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

        // Carica blocchi
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
}
