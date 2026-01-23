package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.Random;

/**
 * Validator for UNDER_GROUND position type.
 * Building is surrounded by solid blocks (underground).
 * TODO: Implement full validation logic.
 */
public class UnderGroundValidator implements PositionValidator {

    public static final UnderGroundValidator INSTANCE = new UnderGroundValidator();

    private UnderGroundValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.UNDER_GROUND;
    }

    @Override
    public Optional<SpawnPosition> findPosition(ServerLevel level, ChunkPos chunkPos,
                                                 SpawnableBuilding building, SpawnRule rule, Random random) {
        // TODO: Implement UNDER_GROUND position finding
        // Should find a cavity or create one underground
        return Optional.empty();
    }
}
