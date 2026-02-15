package it.magius.struttura.architect.vanilla;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.i18n.LanguageUtils;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.ConstructionSnapshot;
import it.magius.struttura.architect.model.EntityData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.stream.Stream;

/**
 * Loads vanilla Minecraft structures and converts them to Struttura constructions.
 * Dynamically discovers available structure templates at runtime - no hardcoded list.
 * This ensures compatibility with future Minecraft versions that add new structures.
 */
public class VanillaStructureLoader {

    /**
     * Result of loading a vanilla structure.
     * Includes the snapshot with block states, NBT and entity data from the template,
     * since the construction only tracks positions (reference-only storage).
     */
    public record LoadResult(boolean success, String message, Construction construction, ConstructionSnapshot snapshot) {}

    /**
     * Information about a discovered vanilla structure template.
     */
    public record VanillaStructureInfo(
        Identifier templateId,
        String constructionId,
        Map<String, String> titles,
        Map<String, String> shortDescriptions
    ) {}

    // Cache of discovered structures (populated at runtime)
    private static List<VanillaStructureInfo> discoveredStructures = null;

    // Known structure path prefixes that are typically monolithic/simple
    // These are hints for filtering, not an exhaustive list
    private static final Set<String> MONOLITHIC_PREFIXES = Set.of(
        "desert_pyramid",
        "jungle_pyramid",
        "igloo",
        "swamp_hut",
        "shipwreck",
        "underwater_ruin",
        "ruined_portal",
        "pillager_outpost",
        "nether_fossils",
        "end_city",
        "trial_chambers",
        "ancient_city",
        "bastion",
        "fossil"
    );

    // Paths to exclude (jigsaw connectors, empties, placeholders, etc.)
    private static final Set<String> EXCLUDED_PATTERNS = Set.of(
        "empty",
        "connector",
        "jigsaw",
        "blocks/air",       // Air placeholder blocks used by jigsaw
        "mobs/",            // Mob spawn markers
        "/air",             // Generic air placeholders
        "feature/",         // Feature markers
        "spawner",          // Spawner markers
        "placeholder"       // Generic placeholders
    );

    /**
     * Discovers all available vanilla structure templates from the game.
     * This method scans the StructureTemplateManager for available templates.
     *
     * @param level The server level
     * @return List of discovered structure infos
     */
    public static List<VanillaStructureInfo> discoverStructures(ServerLevel level) {
        if (discoveredStructures != null) {
            return discoveredStructures;
        }

        List<VanillaStructureInfo> structures = new ArrayList<>();
        StructureTemplateManager manager = level.getStructureManager();

        // Get all structure template IDs from the manager
        // In MC 1.21+, we can list templates from the data pack system
        Stream<Identifier> templateIds = manager.listTemplates();

        templateIds
            .filter(id -> id.getNamespace().equals("minecraft"))
            .filter(id -> !shouldExclude(id.getPath()))
            .forEach(id -> {
                VanillaStructureInfo info = createStructureInfo(id);
                structures.add(info);
            });

        // Sort by path for consistent ordering
        structures.sort(Comparator.comparing(info -> info.templateId().getPath()));

        discoveredStructures = structures;
        Architect.LOGGER.info("Discovered {} vanilla structure templates", structures.size());

        return structures;
    }

    /**
     * Clears the cached structure list (useful for reload).
     */
    public static void clearCache() {
        discoveredStructures = null;
    }

    /**
     * Checks if a template path should be excluded.
     */
    private static boolean shouldExclude(String path) {
        String lowerPath = path.toLowerCase();
        for (String pattern : EXCLUDED_PATTERNS) {
            if (lowerPath.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates structure info from a template ID.
     * Only generates en-US title/description from the path name.
     */
    private static VanillaStructureInfo createStructureInfo(Identifier templateId) {
        String path = templateId.getPath();

        // Generate construction ID: net.minecraft.<path with dots instead of slashes>
        String constructionId = "net.minecraft." + path.replace("/", ".").replace("-", "_");

        // Only en-US with auto-generated title from path
        Map<String, String> titles = Map.of(LanguageUtils.LANG_EN, generateTitle(path));
        Map<String, String> descriptions = Map.of(LanguageUtils.LANG_EN, "A vanilla Minecraft structure.");

        return new VanillaStructureInfo(
            templateId,
            constructionId,
            titles,
            descriptions
        );
    }

    /**
     * Generates a human-readable title from a template path.
     * Examples:
     *   "desert_pyramid" -> "Desert Pyramid"
     *   "shipwreck/with_mast" -> "Shipwreck: With Mast"
     *   "underwater_ruin/big_brick_1" -> "Underwater Ruin: Big Brick 1"
     */
    private static String generateTitle(String path) {
        String[] parts = path.split("/");
        StringBuilder title = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                title.append(": ");
            }
            title.append(humanize(parts[i]));
        }

        return title.toString();
    }

    /**
     * Converts snake_case to Title Case.
     */
    private static String humanize(String str) {
        String[] words = str.split("_");
        StringBuilder result = new StringBuilder();

        for (String word : words) {
            if (!word.isEmpty()) {
                if (result.length() > 0) {
                    result.append(" ");
                }
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1));
                }
            }
        }

        return result.toString();
    }

    /**
     * Loads a vanilla structure template and converts it to a Struttura Construction.
     *
     * @param level The server level (needed for StructureTemplateManager)
     * @param info The structure info with template ID and translations
     * @param skipLootChests If true, skip loot table data from chests (keep chest block, remove loot reference)
     * @return LoadResult with success status and the construction if successful
     */
    public static LoadResult loadStructure(ServerLevel level, VanillaStructureInfo info, boolean skipLootChests) {
        try {
            StructureTemplateManager manager = level.getStructureManager();

            // Load the template
            Optional<StructureTemplate> templateOpt = manager.get(info.templateId());
            if (templateOpt.isEmpty()) {
                return new LoadResult(false, "Template not found: " + info.templateId(), null, null);
            }

            StructureTemplate template = templateOpt.get();

            // Create the construction
            Construction construction = new Construction(
                info.constructionId(),
                new UUID(0, 0), // Minecraft vanilla UUID
                "Minecraft"
            );

            // Set translations
            for (var entry : info.titles().entrySet()) {
                construction.setTitle(entry.getKey(), entry.getValue());
            }
            for (var entry : info.shortDescriptions().entrySet()) {
                construction.setShortDescription(entry.getKey(), entry.getValue());
            }

            // Get template size
            var size = template.getSize();
            Architect.LOGGER.debug("Loading template {} with size {}x{}x{}",
                info.templateId(), size.getX(), size.getY(), size.getZ());

            // Collect block data for the snapshot (block states, NBT)
            // Construction only tracks positions (reference-only storage)
            Map<BlockPos, BlockState> snapshotBlocks = new HashMap<>();
            Map<BlockPos, CompoundTag> snapshotBlockEntityNbt = new HashMap<>();
            int blocksAdded = 0;
            int chestsSkipped = 0;

            var palettes = template.palettes;
            if (palettes.isEmpty()) {
                return new LoadResult(false, "Template has no block palettes: " + info.templateId(), null, null);
            }

            // Use the first palette (index 0)
            var palette = palettes.get(0).blocks();

            for (StructureTemplate.StructureBlockInfo blockInfo : palette) {
                BlockPos pos = blockInfo.pos();
                BlockState state = blockInfo.state();
                CompoundTag nbt = blockInfo.nbt();

                // Skip structure blocks and jigsaw blocks (they're template markers)
                if (state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.JIGSAW)) {
                    continue;
                }

                // Check for loot chests
                if (skipLootChests && nbt != null) {
                    // MC 1.21+ uses "LootTable" or "loot_table" in the nbt
                    if (nbt.contains("LootTable") || nbt.contains("loot_table")) {
                        chestsSkipped++;
                        // Keep the block but remove loot table reference
                        CompoundTag cleanNbt = nbt.copy();
                        cleanNbt.remove("LootTable");
                        cleanNbt.remove("loot_table");
                        cleanNbt.remove("LootTableSeed");
                        cleanNbt.remove("loot_table_seed");

                        // Track position in construction, store state in snapshot
                        construction.addBlockRaw(pos);
                        snapshotBlocks.put(pos, state);
                        if (!cleanNbt.isEmpty()) {
                            snapshotBlockEntityNbt.put(pos, cleanNbt);
                        }
                        blocksAdded++;
                        continue;
                    }
                }

                // Track position in construction, store state/NBT in snapshot
                construction.addBlockRaw(pos);
                snapshotBlocks.put(pos, state);
                if (nbt != null && !nbt.isEmpty()) {
                    snapshotBlockEntityNbt.put(pos, nbt);
                }
                blocksAdded++;
            }

            // Recalculate bounds from all tracked positions
            construction.recalculateBounds();

            // Process entities from template into snapshot data
            List<EntityData> snapshotEntities = new ArrayList<>();
            int entitiesAdded = 0;
            for (StructureTemplate.StructureEntityInfo entityInfo : template.entityInfoList) {
                String entityType = getEntityTypeFromNbt(entityInfo.nbt);
                if (entityType == null || entityType.isEmpty()) {
                    continue;
                }

                Vec3 relativePos = entityInfo.pos;
                CompoundTag entityNbt = entityInfo.nbt.copy();

                // Remove UUID from entity NBT (will be regenerated on spawn)
                entityNbt.remove("UUID");

                EntityData data = new EntityData(
                    entityType,
                    relativePos,
                    0.0f, // yaw will be in NBT
                    0.0f, // pitch will be in NBT
                    entityNbt
                );

                snapshotEntities.add(data);
                entitiesAdded++;
            }

            // Validate that we have at least one block with valid bounds
            if (blocksAdded == 0) {
                return new LoadResult(false, "Template has no blocks: " + info.templateId(), null, null);
            }

            // Validate bounds are valid (not NaN or infinite)
            var bounds = construction.getBounds();
            if (bounds.getMinX() == Integer.MAX_VALUE || bounds.getMaxX() == Integer.MIN_VALUE) {
                return new LoadResult(false, "Template has invalid bounds: " + info.templateId(), null, null);
            }

            // Build the snapshot carrying block states, NBT and entity data from the template
            ConstructionSnapshot snapshot = ConstructionSnapshot.fromDeserialized(
                snapshotBlocks, snapshotBlockEntityNbt, snapshotEntities, new HashMap<>()
            );

            String message = String.format("Loaded %d blocks, %d entities (skipped loot in %d chests)",
                blocksAdded, entitiesAdded, chestsSkipped);

            Architect.LOGGER.info("Loaded vanilla structure {}: {}", info.constructionId(), message);

            return new LoadResult(true, message, construction, snapshot);

        } catch (Exception e) {
            Architect.LOGGER.error("Failed to load vanilla structure: " + info.templateId(), e);
            return new LoadResult(false, "Error: " + e.getMessage(), null, null);
        }
    }

    /**
     * Extracts entity type from NBT.
     */
    private static String getEntityTypeFromNbt(CompoundTag nbt) {
        if (nbt == null) return null;

        // MC 1.21+ uses "id" tag for entity type
        return nbt.getString("id").orElse(null);
    }

    /**
     * Finds structure info by template path (e.g., "minecraft:desert_pyramid").
     */
    public static Optional<VanillaStructureInfo> findByTemplatePath(ServerLevel level, String templatePath) {
        return discoverStructures(level).stream()
            .filter(info -> info.templateId().toString().equals(templatePath))
            .findFirst();
    }

    /**
     * Finds structure info by construction ID (e.g., "net.minecraft.desert_pyramid").
     */
    public static Optional<VanillaStructureInfo> findByConstructionId(ServerLevel level, String constructionId) {
        return discoverStructures(level).stream()
            .filter(info -> info.constructionId().equals(constructionId))
            .findFirst();
    }

    /**
     * Gets all structures matching a search filter.
     */
    public static List<VanillaStructureInfo> searchStructures(ServerLevel level, String filter) {
        String lowerFilter = filter.toLowerCase();
        return discoverStructures(level).stream()
            .filter(info -> info.templateId().getPath().toLowerCase().contains(lowerFilter) ||
                           info.constructionId().toLowerCase().contains(lowerFilter))
            .toList();
    }
}
