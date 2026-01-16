package it.magius.struttura.architect.i18n;

import it.magius.struttura.architect.Architect;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the preferred language of each player based on Minecraft client settings.
 * Provides BCP 47 language codes for external APIs.
 */
public class PlayerLanguageTracker {

    private static final PlayerLanguageTracker INSTANCE = new PlayerLanguageTracker();

    // Map player UUID -> Minecraft language code (e.g., "en_us", "it_it")
    private final Map<UUID, String> playerLanguages = new ConcurrentHashMap<>();

    private PlayerLanguageTracker() {}

    public static PlayerLanguageTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Updates a player's language.
     * Called when the client sends its settings.
     *
     * @param player the player
     * @param languageCode the Minecraft language code (e.g., "en_us", "it_it", "de_de")
     */
    public void setPlayerLanguage(ServerPlayer player, String languageCode) {
        String previousLang = playerLanguages.put(player.getUUID(), languageCode);
        if (previousLang == null || !previousLang.equals(languageCode)) {
            String bcp47 = LanguageUtils.fromMinecraft(languageCode);
            Architect.LOGGER.debug("Player {} language set to: {} (BCP 47: {})",
                player.getName().getString(), languageCode, bcp47);
        }
    }

    /**
     * Gets a player's Minecraft language code.
     *
     * @param player the player
     * @return the Minecraft language code, or "en_us" if not set
     */
    public String getPlayerLanguage(ServerPlayer player) {
        return playerLanguages.getOrDefault(player.getUUID(), "en_us");
    }

    /**
     * Gets the BCP 47 language code for a player (e.g., "en-US", "it-IT").
     * This is the format used for external APIs and data storage.
     *
     * @param player the player
     * @return the BCP 47 language tag
     */
    public String getBcp47LanguageCode(ServerPlayer player) {
        String minecraftCode = getPlayerLanguage(player);
        return LanguageUtils.fromMinecraft(minecraftCode);
    }

    /**
     * Gets the simple 2-letter code from the player's language.
     * Used for loading translation files.
     *
     * @param player the player
     * @return the simple 2-letter code
     * @deprecated Use getBcp47LanguageCode() for external data, or let I18n handle file loading
     */
    @Deprecated
    public String getSimpleLanguageCode(ServerPlayer player) {
        String bcp47 = getBcp47LanguageCode(player);
        return LanguageUtils.toSimple(bcp47);
    }

    /**
     * Removes a player from the tracker (when they disconnect).
     *
     * @param player the player
     */
    public void removePlayer(ServerPlayer player) {
        playerLanguages.remove(player.getUUID());
        Architect.LOGGER.debug("Player {} removed from language tracker", player.getName().getString());
    }

    /**
     * Checks if a player has a registered language.
     *
     * @param player the player
     * @return true if the language is registered
     */
    public boolean hasLanguage(ServerPlayer player) {
        return playerLanguages.containsKey(player.getUUID());
    }
}
