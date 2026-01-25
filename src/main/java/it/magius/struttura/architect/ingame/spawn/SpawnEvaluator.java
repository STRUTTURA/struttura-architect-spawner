package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.ChunkDataManager;
import it.magius.struttura.architect.ingame.InGameManager;
import it.magius.struttura.architect.ingame.cache.BuildingCache;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import it.magius.struttura.architect.ingame.model.SpawnableList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;

import net.minecraft.world.phys.AABB;

import java.util.Optional;
import java.util.Random;

/**
 * Main spawn evaluation engine.
 * Determines if and what building to spawn in a newly discovered chunk.
 *
 * Pipeline: ChunkCandidate → SpawnChanceCheck → BuildingSelection → BiomeCheck → PositionCheck → Spawn
 */
public class SpawnEvaluator {

    /**
     * Evaluates a chunk for building spawn.
     * Called from ChunkDiscoveryHandler after the chunk is marked as processed.
     *
     * @param level the server level
     * @param chunk the chunk to evaluate
     */
    public static void evaluate(ServerLevel level, LevelChunk chunk) {
        // Check if chunk is occupied by a multi-chunk building from this session
        if (OccupiedChunks.isOccupied(chunk.getPos().x, chunk.getPos().z)) {
            return;
        }

        InGameManager manager = InGameManager.getInstance();

        // Double-check InGame is still active
        if (!manager.isActive()) {
            return;
        }

        SpawnableList list = manager.getSpawnableList();
        if (list == null || !list.hasBuildings()) {
            return;
        }

        // Create seeded random for deterministic spawn decisions
        Random random = createSeededRandom(level.getSeed(), manager.getState().getListId(), chunk.getPos());

        // Step 1: Check spawningPercentage
        if (random.nextDouble() > list.getSpawningPercentage()) {
            return;
        }

        // Step 2: Select random building
        SpawnableBuilding building = list.selectRandomBuilding(random);
        if (building == null) {
            return;
        }

        // Step 3: Get biome at chunk center
        BlockPos chunkCenter = chunk.getPos().getMiddleBlockPosition(level.getSeaLevel());
        Holder<Biome> biomeHolder = level.getBiome(chunkCenter);
        String biomeId = biomeHolder.unwrapKey()
            .map(ResourceKey::toString)
            .orElse("unknown");

        // Step 4: Find applicable rule for this biome
        SpawnRule rule = building.findRuleForBiome(biomeId);

        // If no rules defined, use default rule: 100% chance, ON_GROUND, Y range 60-100
        if (rule == null) {
            if (building.getRules().isEmpty()) {
                // Building has no rules at all - use default
                rule = SpawnRule.createDefault();
            } else {
                // Building has rules but none match this biome
                return;
            }
        }

        // Step 5: Check rule's percentage (with download failure penalty if applicable)
        double effectivePercentage = building.getEffectivePercentage(rule);
        if (random.nextDouble() > effectivePercentage) {
            return;
        }

        // Step 6: Find valid position
        PositionValidator validator = PositionValidator.forType(rule.getType());
        Optional<SpawnPosition> positionOpt = validator.findPosition(
            level, chunk.getPos(), building, rule, random
        );

        if (positionOpt.isEmpty()) {
            return;
        }

        SpawnPosition position = positionOpt.get();

        // Step 7: Calculate expected bounds BEFORE spawning
        AABB expectedBounds = BoundsCalculator.calculate(building, position);

        // Step 8: Check if ANY chunk in the building bounds is already occupied
        // This prevents overlapping buildings even when anchor chunks are different
        if (OccupiedChunks.isAnyOccupied(expectedBounds)) {
            return;
        }

        // Step 9: Check if building is available or can be downloaded
        // If already downloading, skip this spawn (chunk will be re-evaluated later)
        BuildingCache cache = BuildingCache.getInstance();
        if (!cache.contains(building.getRdns()) && cache.isDownloading(building.getRdns())) {
            return;
        }

        // Step 10: Mark chunks as occupied BEFORE spawning
        OccupiedChunks.markOccupied(expectedBounds);

        // Step 11: Spawn the building
        InGameBuildingSpawner.spawn(level, chunk, building, position);
    }

    /**
     * Creates a seeded random generator for deterministic spawn decisions.
     * The same world seed + list ID + chunk position will always produce the same decisions.
     */
    private static Random createSeededRandom(long worldSeed, String listId, net.minecraft.world.level.ChunkPos chunkPos) {
        long seed = worldSeed;
        seed ^= (listId != null ? listId.hashCode() * 31L : 0);
        seed ^= (chunkPos.x * 341873128712L);
        seed ^= (chunkPos.z * 132897987541L);
        return new Random(seed);
    }
}
