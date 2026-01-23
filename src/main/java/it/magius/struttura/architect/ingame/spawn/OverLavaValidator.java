package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.Random;

/**
 * Validator for OVER_LAVA position type.
 * Building is placed over lava with open sky above.
 * TODO: Implement full validation logic.
 */
public class OverLavaValidator implements PositionValidator {

    public static final OverLavaValidator INSTANCE = new OverLavaValidator();

    private OverLavaValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.OVER_LAVA;
    }

    @Override
    public Optional<SpawnPosition> findPosition(ServerLevel level, ChunkPos chunkPos,
                                                 SpawnableBuilding building, SpawnRule rule, Random random) {
        // TODO: Implement OVER_LAVA position finding
        return Optional.empty();
    }
}
