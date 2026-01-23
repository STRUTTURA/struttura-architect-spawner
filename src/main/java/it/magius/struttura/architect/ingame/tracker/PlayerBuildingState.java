package it.magius.struttura.architect.ingame.tracker;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.ingame.LikeManager;
import it.magius.struttura.architect.ingame.model.SpawnedBuildingInfo;
import it.magius.struttura.architect.network.NetworkHandler;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which building each player is currently inside.
 * Used for UI display and like functionality.
 */
public class PlayerBuildingState {

    private static PlayerBuildingState instance;

    // Map from player UUID to their current building (null if not in any building)
    private final Map<UUID, SpawnedBuildingInfo> playerCurrentBuilding = new ConcurrentHashMap<>();

    private PlayerBuildingState() {}

    public static PlayerBuildingState getInstance() {
        if (instance == null) {
            instance = new PlayerBuildingState();
        }
        return instance;
    }

    /**
     * Called when a player enters a building.
     */
    public void onPlayerEnterBuilding(ServerPlayer player, SpawnedBuildingInfo info) {
        UUID playerId = player.getUUID();
        SpawnedBuildingInfo previous = playerCurrentBuilding.put(playerId, info);

        if (previous == null || !previous.rdns().equals(info.rdns())) {
            Architect.LOGGER.debug("Player {} entered building: {}", player.getName().getString(), info.rdns());
            // Send packet to client to show building info HUD
            boolean hasLiked = LikeManager.getInstance().hasLiked(info.pk());
            NetworkHandler.sendInGameBuildingState(player, info, hasLiked);
        }
    }

    /**
     * Called when a player exits a building.
     */
    public void onPlayerExitBuilding(ServerPlayer player) {
        UUID playerId = player.getUUID();
        SpawnedBuildingInfo previous = playerCurrentBuilding.remove(playerId);

        if (previous != null) {
            Architect.LOGGER.debug("Player {} exited building: {}", player.getName().getString(), previous.rdns());
            // Send packet to client to hide building info HUD
            NetworkHandler.sendInGameBuildingState(player, null, false);
        }
    }

    /**
     * Checks if a player is currently inside a building.
     */
    public boolean isPlayerInBuilding(ServerPlayer player) {
        return playerCurrentBuilding.containsKey(player.getUUID());
    }

    /**
     * Checks if a player is inside a specific building.
     */
    public boolean isPlayerInBuilding(ServerPlayer player, String rdns) {
        SpawnedBuildingInfo info = playerCurrentBuilding.get(player.getUUID());
        return info != null && info.rdns().equals(rdns);
    }

    /**
     * Gets the building the player is currently inside, if any.
     */
    public SpawnedBuildingInfo getCurrentBuilding(ServerPlayer player) {
        return playerCurrentBuilding.get(player.getUUID());
    }

    /**
     * Gets the building info for a player by UUID.
     */
    public SpawnedBuildingInfo getCurrentBuilding(UUID playerId) {
        return playerCurrentBuilding.get(playerId);
    }

    /**
     * Clears state for a player (e.g., on disconnect).
     */
    public void clearPlayer(ServerPlayer player) {
        playerCurrentBuilding.remove(player.getUUID());
    }

    /**
     * Clears all player states.
     */
    public void clear() {
        playerCurrentBuilding.clear();
    }
}
