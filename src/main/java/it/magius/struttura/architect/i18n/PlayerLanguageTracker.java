package it.magius.struttura.architect.i18n;

import it.magius.struttura.architect.Architect;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Traccia la lingua preferita di ogni giocatore basandosi sulle impostazioni client di Minecraft.
 */
public class PlayerLanguageTracker {

    private static final PlayerLanguageTracker INSTANCE = new PlayerLanguageTracker();

    // Mappa UUID giocatore -> codice lingua (es: "en_us", "it_it")
    private final Map<UUID, String> playerLanguages = new ConcurrentHashMap<>();

    private PlayerLanguageTracker() {}

    public static PlayerLanguageTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Aggiorna la lingua di un giocatore.
     * Chiamato quando il client invia le sue impostazioni.
     *
     * @param player il giocatore
     * @param languageCode il codice lingua Minecraft (es: "en_us", "it_it", "de_de")
     */
    public void setPlayerLanguage(ServerPlayer player, String languageCode) {
        String previousLang = playerLanguages.put(player.getUUID(), languageCode);
        if (previousLang == null || !previousLang.equals(languageCode)) {
            Architect.LOGGER.debug("Player {} language set to: {}", player.getName().getString(), languageCode);
        }
    }

    /**
     * Ottiene la lingua di un giocatore.
     *
     * @param player il giocatore
     * @return il codice lingua Minecraft, o "en_us" se non impostato
     */
    public String getPlayerLanguage(ServerPlayer player) {
        return playerLanguages.getOrDefault(player.getUUID(), "en_us");
    }

    /**
     * Ottiene il codice lingua semplificato (es: "en", "it") da un codice Minecraft (es: "en_us", "it_it").
     *
     * @param player il giocatore
     * @return il codice lingua a 2 lettere
     */
    public String getSimpleLanguageCode(ServerPlayer player) {
        String fullCode = getPlayerLanguage(player);
        // Minecraft usa formato "xx_yy" (es: en_us, it_it, de_de)
        if (fullCode.contains("_")) {
            return fullCode.split("_")[0];
        }
        return fullCode;
    }

    /**
     * Rimuove un giocatore dal tracker (quando si disconnette).
     *
     * @param player il giocatore
     */
    public void removePlayer(ServerPlayer player) {
        playerLanguages.remove(player.getUUID());
        Architect.LOGGER.debug("Player {} removed from language tracker", player.getName().getString());
    }

    /**
     * Verifica se un giocatore ha una lingua registrata.
     *
     * @param player il giocatore
     * @return true se la lingua è registrata
     */
    public boolean hasLanguage(ServerPlayer player) {
        return playerLanguages.containsKey(player.getUUID());
    }
}
