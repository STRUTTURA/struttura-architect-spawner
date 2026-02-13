package it.magius.struttura.architect.ingame;

import com.google.gson.*;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.ingame.model.EnsureBoundsMode;
import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import it.magius.struttura.architect.ingame.model.SpawnableList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles persistence of SpawnableList to the world directory.
 * The list is saved once when selected and loaded on world start.
 * It is only re-downloaded when the user explicitly selects a new list.
 */
public class SpawnableListStorage {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private static final String SPAWNABLE_LIST_FILE = "spawnable_list.json";
    private static final String STRUTTURA_DIR = "struttura";

    private final Path worldPath;

    public SpawnableListStorage(Path worldPath) {
        this.worldPath = worldPath;
    }

    /**
     * Loads the SpawnableList from disk.
     * @return the loaded list, or null if not found or invalid
     */
    public SpawnableList load() {
        Path filePath = getFilePath();

        if (!Files.exists(filePath)) {
            Architect.LOGGER.debug("No spawnable_list.json found");
            return null;
        }

        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            SpawnableList list = deserializeList(json);
            Architect.LOGGER.info("Loaded spawnable list from disk with {} buildings",
                list != null ? list.getBuildingCount() : 0);
            return list;
        } catch (Exception e) {
            Architect.LOGGER.error("Failed to load spawnable_list.json", e);
            return null;
        }
    }

    /**
     * Saves the SpawnableList to disk.
     * @param list the list to save
     */
    public void save(SpawnableList list) {
        if (list == null) {
            Architect.LOGGER.warn("Cannot save null spawnable list");
            return;
        }

        Path filePath = getFilePath();

        try {
            Files.createDirectories(filePath.getParent());

            JsonObject json = serializeList(list);

            try (Writer writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }

            Architect.LOGGER.info("Saved spawnable list to disk with {} buildings", list.getBuildingCount());

        } catch (IOException e) {
            Architect.LOGGER.error("Failed to save spawnable_list.json", e);
        }
    }

    /**
     * Deletes the persisted SpawnableList file.
     * Called on reset.
     */
    public void delete() {
        Path filePath = getFilePath();

        try {
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                Architect.LOGGER.info("Deleted spawnable_list.json");
            }
        } catch (IOException e) {
            Architect.LOGGER.error("Failed to delete spawnable_list.json", e);
        }
    }

    /**
     * Checks if a persisted list exists.
     */
    public boolean exists() {
        return Files.exists(getFilePath());
    }

    private Path getFilePath() {
        return worldPath.resolve(STRUTTURA_DIR).resolve(SPAWNABLE_LIST_FILE);
    }

    // ===== Serialization =====

    private JsonObject serializeList(SpawnableList list) {
        JsonObject json = new JsonObject();
        if (list.getListHash() != null) {
            json.addProperty("listHash", list.getListHash());
        }
        json.addProperty("spawningPercentage", list.getSpawningPercentage());
        json.addProperty("downloadTime", list.getDownloadTime());

        JsonArray buildingsArray = new JsonArray();
        for (SpawnableBuilding building : list.getBuildings()) {
            buildingsArray.add(serializeBuilding(building));
        }
        json.add("buildings", buildingsArray);

        return json;
    }

    private JsonObject serializeBuilding(SpawnableBuilding building) {
        JsonObject json = new JsonObject();
        json.addProperty("rdns", building.getRdns());
        json.addProperty("pk", building.getPk());
        json.addProperty("ownerUserId", building.getOwnerUserId());
        if (building.getHash() != null) {
            json.addProperty("hash", building.getHash());
        }
        if (building.getAuthor() != null && !building.getAuthor().isEmpty()) {
            json.addProperty("author", building.getAuthor());
        }
        json.addProperty("xWorld", building.getXWorld());

        // Entrance position with yaw
        JsonObject entranceJson = new JsonObject();
        entranceJson.addProperty("x", building.getEntrance().getX());
        entranceJson.addProperty("y", building.getEntrance().getY());
        entranceJson.addProperty("z", building.getEntrance().getZ());
        entranceJson.addProperty("yaw", building.getEntranceYaw());
        json.add("entrance", entranceJson);

        // Bounds - save only size {x,y,z} since bounds are normalized (min always 0,0,0)
        JsonObject boundsJson = new JsonObject();
        AABB b = building.getBounds();
        boundsJson.addProperty("x", (int) (b.maxX - b.minX + 1));
        boundsJson.addProperty("y", (int) (b.maxY - b.minY + 1));
        boundsJson.addProperty("z", (int) (b.maxZ - b.minZ + 1));
        json.add("bounds", boundsJson);

        // Rules
        JsonArray rulesArray = new JsonArray();
        for (SpawnRule rule : building.getRules()) {
            rulesArray.add(serializeRule(rule));
        }
        json.add("rules", rulesArray);

        // Names (localized)
        JsonObject namesJson = new JsonObject();
        for (Map.Entry<String, String> entry : building.getNames().entrySet()) {
            namesJson.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("names", namesJson);

        // Descriptions (localized)
        JsonObject descriptionsJson = new JsonObject();
        for (Map.Entry<String, String> entry : building.getDescriptions().entrySet()) {
            descriptionsJson.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("descriptions", descriptionsJson);

        return json;
    }

    private JsonObject serializeRule(SpawnRule rule) {
        JsonObject json = new JsonObject();
        json.addProperty("percentage", rule.getPercentage());
        json.addProperty("type", rule.getType().getApiValue());
        json.addProperty("y1", rule.getY1());
        json.addProperty("y2", rule.getY2());
        json.addProperty("margin", rule.getMargin());
        // Only write ensureBounds if not NONE (save space)
        if (rule.getEnsureBoundsMode() != EnsureBoundsMode.NONE) {
            json.addProperty("ensureBounds", rule.getEnsureBoundsMode().toApi());
        }

        JsonArray biomesArray = new JsonArray();
        for (String biome : rule.getBiomes()) {
            biomesArray.add(biome);
        }
        json.add("biomes", biomesArray);

        return json;
    }

    // ===== Deserialization =====

    private SpawnableList deserializeList(JsonObject json) {
        String listHash = json.has("listHash") && !json.get("listHash").isJsonNull()
            ? json.get("listHash").getAsString() : null;
        double spawningPercentage = json.has("spawningPercentage")
            ? json.get("spawningPercentage").getAsDouble()
            : 0.5;
        long downloadTime = json.has("downloadTime")
            ? json.get("downloadTime").getAsLong()
            : System.currentTimeMillis();

        List<SpawnableBuilding> buildings = new ArrayList<>();
        if (json.has("buildings") && json.get("buildings").isJsonArray()) {
            JsonArray buildingsArray = json.getAsJsonArray("buildings");
            for (JsonElement elem : buildingsArray) {
                SpawnableBuilding building = deserializeBuilding(elem.getAsJsonObject());
                if (building != null) {
                    buildings.add(building);
                }
            }
        }

        return new SpawnableList(listHash, spawningPercentage, buildings, downloadTime);
    }

    private SpawnableBuilding deserializeBuilding(JsonObject json) {
        try {
            String rdns = json.get("rdns").getAsString();
            long pk = json.get("pk").getAsLong();
            long ownerUserId = json.has("ownerUserId") && !json.get("ownerUserId").isJsonNull()
                ? json.get("ownerUserId").getAsLong() : 0;
            String hash = json.has("hash") && !json.get("hash").isJsonNull()
                ? json.get("hash").getAsString() : null;
            String author = json.has("author") && !json.get("author").isJsonNull()
                ? json.get("author").getAsString() : null;
            int xWorld = json.has("xWorld") ? json.get("xWorld").getAsInt() : 0;

            // Entrance with yaw
            BlockPos entrance = BlockPos.ZERO;
            float entranceYaw = 0f;
            if (json.has("entrance") && json.get("entrance").isJsonObject()) {
                JsonObject entranceJson = json.getAsJsonObject("entrance");
                entrance = new BlockPos(
                    entranceJson.get("x").getAsInt(),
                    entranceJson.get("y").getAsInt(),
                    entranceJson.get("z").getAsInt()
                );
                entranceYaw = entranceJson.has("yaw") ? entranceJson.get("yaw").getAsFloat() : 0f;
            }

            // Bounds - load size {x,y,z} and convert to AABB(0,0,0, x-1,y-1,z-1)
            AABB bounds = new AABB(0, 0, 0, 1, 1, 1);
            if (json.has("bounds") && json.get("bounds").isJsonObject()) {
                JsonObject boundsJson = json.getAsJsonObject("bounds");
                int sizeX = boundsJson.get("x").getAsInt();
                int sizeY = boundsJson.get("y").getAsInt();
                int sizeZ = boundsJson.get("z").getAsInt();
                bounds = new AABB(0, 0, 0, sizeX - 1, sizeY - 1, sizeZ - 1);
            }

            // Rules
            List<SpawnRule> rules = new ArrayList<>();
            if (json.has("rules") && json.get("rules").isJsonArray()) {
                JsonArray rulesArray = json.getAsJsonArray("rules");
                for (JsonElement elem : rulesArray) {
                    SpawnRule rule = deserializeRule(elem.getAsJsonObject());
                    if (rule != null) {
                        rules.add(rule);
                    }
                }
            }

            // Names (localized)
            Map<String, String> names = new HashMap<>();
            if (json.has("names") && json.get("names").isJsonObject()) {
                JsonObject namesJson = json.getAsJsonObject("names");
                for (Map.Entry<String, JsonElement> entry : namesJson.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        names.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }

            // Descriptions (localized)
            Map<String, String> descriptions = new HashMap<>();
            if (json.has("descriptions") && json.get("descriptions").isJsonObject()) {
                JsonObject descriptionsJson = json.getAsJsonObject("descriptions");
                for (Map.Entry<String, JsonElement> entry : descriptionsJson.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        descriptions.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }

            return new SpawnableBuilding(rdns, pk, ownerUserId, hash, author, entrance, entranceYaw, xWorld, rules, bounds, names, descriptions);

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to deserialize building", e);
            return null;
        }
    }

    private SpawnRule deserializeRule(JsonObject json) {
        try {
            double percentage = json.has("percentage") ? json.get("percentage").getAsDouble() : 1.0;
            PositionType type = json.has("type")
                ? PositionType.fromApiValue(json.get("type").getAsString())
                : PositionType.ON_GROUND;
            int y1 = json.has("y1") ? json.get("y1").getAsInt() : 60;
            int y2 = json.has("y2") ? json.get("y2").getAsInt() : 100;
            int margin = json.has("margin") ? json.get("margin").getAsInt() : 5;
            // Backward compat: absent → NONE, boolean true → ALL, string → fromApi()
            EnsureBoundsMode ensureBoundsMode = EnsureBoundsMode.NONE;
            if (json.has("ensureBounds")) {
                JsonElement ebElem = json.get("ensureBounds");
                if (ebElem.isJsonPrimitive() && ebElem.getAsJsonPrimitive().isBoolean()) {
                    ensureBoundsMode = ebElem.getAsBoolean() ? EnsureBoundsMode.ALL : EnsureBoundsMode.NONE;
                } else if (ebElem.isJsonPrimitive() && ebElem.getAsJsonPrimitive().isString()) {
                    ensureBoundsMode = EnsureBoundsMode.fromApi(ebElem.getAsString());
                }
            }

            List<String> biomes = new ArrayList<>();
            if (json.has("biomes") && json.get("biomes").isJsonArray()) {
                JsonArray biomesArray = json.getAsJsonArray("biomes");
                for (JsonElement elem : biomesArray) {
                    biomes.add(elem.getAsString());
                }
            }

            return new SpawnRule(biomes, percentage, type, y1, y2, margin, ensureBoundsMode);

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to deserialize spawn rule", e);
            return null;
        }
    }
}
