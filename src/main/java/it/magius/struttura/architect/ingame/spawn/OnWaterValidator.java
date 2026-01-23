package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.Random;

/**
 * Validator for ON_WATER position type.
 * Building is immersed in water.
 * TODO: Implement full validation logic.
 */
public class OnWaterValidator implements PositionValidator {

    public static final OnWaterValidator INSTANCE = new OnWaterValidator();

    private OnWaterValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.ON_WATER;
    }

    @Override
    public Optional<SpawnPosition> findPosition(ServerLevel level, ChunkPos chunkPos,
                                                 SpawnableBuilding building, SpawnRule rule, Random random) {
        // TODO: Implement ON_WATER position finding
        return Optional.empty();
    }
}
