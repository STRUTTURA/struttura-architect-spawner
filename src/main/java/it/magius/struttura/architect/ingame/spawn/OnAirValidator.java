package it.magius.struttura.architect.ingame.spawn;

import it.magius.struttura.architect.ingame.model.PositionType;
import it.magius.struttura.architect.ingame.model.SpawnRule;
import it.magius.struttura.architect.ingame.model.SpawnableBuilding;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.Random;

/**
 * Validator for ON_AIR position type.
 * Building is floating in air (only air in bounds + margin).
 * TODO: Implement full validation logic.
 */
public class OnAirValidator implements PositionValidator {

    public static final OnAirValidator INSTANCE = new OnAirValidator();

    private OnAirValidator() {}

    @Override
    public PositionType getPositionType() {
        return PositionType.ON_AIR;
    }

    @Override
    public Optional<SpawnPosition> findPosition(ServerLevel level, ChunkPos chunkPos,
                                                 SpawnableBuilding building, SpawnRule rule, Random random) {
        // TODO: Implement ON_AIR position finding
        // Should find a location where the entire bounds + margin is air
        return Optional.empty();
    }
}
