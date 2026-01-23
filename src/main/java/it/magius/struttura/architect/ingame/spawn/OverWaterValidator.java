package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.Random;

/**
 * Validator for OVER_WATER position type.
 * Building is placed over water with open sky above.
 * TODO: Implement full validation logic.
 */
public class OverWaterValidator implements PositionValidator {

    public static final OverWaterValidator INSTANCE = new OverWaterValidator();

    private OverWaterValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.OVER_WATER;
    }

    @Override
    public Optional<SpawnPosition> findPosition(ServerLevel level, ChunkPos chunkPos,
                                                 SpawnableBuilding building, SpawnRule rule, Random random) {
        // TODO: Implement OVER_WATER position finding
        // Should find a location over water surface
        return Optional.empty();
    }
}
