package it.magius.struttura.architect.client;

import com.mojang.brigadier.CommandDispatcher;
import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.client.gui.StrutturaSettingsScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;

/**
 * Client-side commands for STRUTTURA.
 * These commands run entirely on the client and don't require server permission.
 */
@Environment(EnvType.CLIENT)
public class ClientCommands {

    /**
     * Registers client-side commands.
     * Call this from ArchitectClient.onInitializeClient()
     */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(ClientCommands::registerCommands);
        Architect.LOGGER.info("Registered client commands");
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
        // NOTE: We intentionally do NOT register any client commands under /struttura
        // because Fabric has a known issue (#3568) where client commands with the same
        // root as server commands cause conflicts - the client dispatcher intercepts
        // the command but fails for subcommands it doesn't know about.
        //
        // Instead, /struttura options is handled server-side via StrutturaCommand.
        // The server sends an OpenOptionsPacket to the client which opens the screen.
    }
}
