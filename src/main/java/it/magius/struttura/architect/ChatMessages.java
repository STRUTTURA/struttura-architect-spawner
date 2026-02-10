package it.magius.struttura.architect;

import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.i18n.LanguageUtils;
import it.magius.struttura.architect.model.Construction;
import it.magius.struttura.architect.model.Room;
import it.magius.struttura.architect.session.EditingSession;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class ChatMessages {

    private static final String PREFIX = "§a[Struttura] ";

    public enum Level { INFO, ERROR }

    // Send translated message to a single player
    public static void send(ServerPlayer player, Level level, String key, Object... args) {
        String color = level == Level.ERROR ? "§c" : "§f";
        player.sendSystemMessage(Component.literal(
            PREFIX + color + I18n.tr(player, key, args)
        ));
    }

    // Send raw (non-translated) message to a single player
    public static void sendRaw(ServerPlayer player, Level level, String message) {
        String color = level == Level.ERROR ? "§c" : "§f";
        player.sendSystemMessage(Component.literal(PREFIX + color + message));
    }

    // Broadcast translated message to all players on server
    public static void broadcast(MinecraftServer server, Level level, String key, Object... args) {
        if (server == null) return;
        String color = level == Level.ERROR ? "§c" : "§f";
        String text = PREFIX + color + I18n.tr(key, args);
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(Component.literal(text));
            }
        });
    }

    // Broadcast raw message to all players on server
    public static void broadcastRaw(MinecraftServer server, Level level, String message) {
        if (server == null) return;
        String color = level == Level.ERROR ? "§c" : "§f";
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(Component.literal(PREFIX + color + message));
            }
        });
    }

    // Send message with a target name prefix from editing session
    // Format: "[Struttura] §dTitle: §c/§fmessage"  or  "[Struttura] §dTitle§7/§eRoom: §c/§fmessage"
    public static void sendTarget(ServerPlayer player, String targetName, Level level, String message) {
        String color = level == Level.ERROR ? "§c" : "§f";
        player.sendSystemMessage(Component.literal(PREFIX + targetName + ": " + color + message));
    }

    // Build target name from editing session: uses construction title (fallback to rDNS short name)
    public static String formatTargetName(ServerPlayer player, EditingSession session) {
        Construction construction = session.getConstruction();

        // Try player language (BCP 47), then simple code, then any available title
        String bcp47 = I18n.getPlayerLanguage(player);
        String title = construction.getTitleWithFallback(bcp47);
        if (title.isEmpty()) {
            title = construction.getTitleWithFallback(LanguageUtils.toSimple(bcp47));
        }

        // Fallback to last part of rDNS if no title set
        if (title.isEmpty()) {
            String id = construction.getId();
            title = id.contains(".") ? id.substring(id.lastIndexOf('.') + 1) : id;
        }

        if (session.isInRoom()) {
            Room room = session.getCurrentRoomObject();
            String roomName = room != null ? room.getName() : session.getCurrentRoom();
            return "§d" + title + "§7/§e" + roomName;
        } else {
            return "§d" + title;
        }
    }
}
