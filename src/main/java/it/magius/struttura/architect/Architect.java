package it.magius.struttura.architect;

import it.magius.struttura.architect.command.StrutturaCommand;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.registry.ConstructionRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Architect implements ModInitializer {
	public static final String MOD_ID = "architect";
	public static final String MOD_VERSION = "0.1.0";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("STRUTTURA: Architect v{} initializing...", MOD_VERSION);

		// Inizializza il sistema i18n
		I18n.init();

		// Registra i packet di rete
		NetworkHandler.registerServer();

		// Registra il comando /struttura
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			StrutturaCommand.register(dispatcher);
			LOGGER.info("Registered /struttura command");
		});

		// Inizializza storage quando il server parte
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			// Usa la directory del mondo per lo storage
			var worldPath = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
			ConstructionRegistry.getInstance().initStorage(worldPath);
			ConstructionRegistry.getInstance().loadAll();
			LOGGER.info("Construction storage initialized for world");
		});

		// Salva tutte le costruzioni quando il server si ferma
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			LOGGER.info("Server stopping, saving all constructions...");
			ConstructionRegistry.getInstance().saveAll();
		});

		// Pulisci il registro quando il mondo viene scaricato
		ServerWorldEvents.UNLOAD.register((server, world) -> {
			// Solo per il mondo overworld (evita di pulire piu' volte)
			if (world.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
				LOGGER.info("World unloading, clearing construction registry");
				ConstructionRegistry.getInstance().clear();
			}
		});

		LOGGER.info("STRUTTURA: Architect v{} initialized", MOD_VERSION);
	}
}
