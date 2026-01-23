package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.Random;

/**
 * Validator for BOTTOM_WATER position type.
 * Building is on the water floor with water above.
 * TODO: Implement full validation logic.
 */
public class BottomWaterValidator implements PositionValidator {

    public static final BottomWaterValidator INSTANCE = new BottomWaterValidator();

    private BottomWaterValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.BOTTOM_WATER;
    }

    @Override
    public Optional<SpawnPosition> findPosition(ServerLevel level, ChunkPos chunkPos,
                                                 SpawnableBuilding building, SpawnRule rule, Random random) {
        // TODO: Implement BOTTOM_WATER position finding
        // Should find a location on the sea/lake floor
        return Optional.empty();
    }
}
