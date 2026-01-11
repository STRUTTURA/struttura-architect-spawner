package it.magius.struttura.architect;

import it.magius.struttura.architect.command.StrutturaCommand;
import it.magius.struttura.architect.config.ArchitectConfig;
import it.magius.struttura.architect.entity.EntityFreezeHandler;
import it.magius.struttura.architect.entity.EntitySpawnHandler;
import it.magius.struttura.architect.item.TapeAttackHandler;
import it.magius.struttura.architect.i18n.I18n;
import it.magius.struttura.architect.network.NetworkHandler;
import it.magius.struttura.architect.registry.ConstructionRegistry;
import it.magius.struttura.architect.registry.ModItems;
import it.magius.struttura.architect.session.EditingSession;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Architect implements ModInitializer {
	public static final String MOD_ID = "architect";
	public static final String MOD_VERSION = net.fabricmc.loader.api.FabricLoader.getInstance()
		.getModContainer(MOD_ID)
		.map(container -> container.getMetadata().getVersion().getFriendlyString())
		.orElse("unknown");
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("STRUTTURA: Architect v{} initializing...", MOD_VERSION);

		// Carica la configurazione
		ArchitectConfig.getInstance();

		// Inizializza il sistema i18n
		I18n.init();

		// Registra gli items
		ModItems.init();

		// Registra i packet di rete
		NetworkHandler.registerServer();

		// Registra l'handler per il freeze delle entità
		EntityFreezeHandler.getInstance().register();

		// Registra l'handler per lo spawn automatico di entità
		EntitySpawnHandler.getInstance().register();

		// Registra l'handler per il left-click con il Tape sui blocchi
		TapeAttackHandler.getInstance().register();

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

		// Quando un giocatore si connette, ri-sincronizza il wireframe se aveva una sessione attiva
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			var player = handler.getPlayer();
			EditingSession session = EditingSession.getSession(player.getUUID());
			if (session != null) {
				// Il giocatore aveva una sessione attiva, ri-sincronizza il wireframe
				LOGGER.info("Player {} rejoined with active editing session, syncing wireframe", player.getName().getString());
				NetworkHandler.sendWireframeSync(player);
			}
		});

		// Quando un giocatore si disconnette, termina la sessione di editing
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			var player = handler.getPlayer();
			EditingSession session = EditingSession.getSession(player.getUUID());
			if (session != null) {
				// Registra la costruzione (questo la salva anche su disco) e termina la sessione
				LOGGER.info("Player {} disconnected, saving and ending editing session for {}",
					player.getName().getString(), session.getConstruction().getId());
				ConstructionRegistry.getInstance().register(session.getConstruction());
				EditingSession.endSession(player);
			}
		});

		LOGGER.info("STRUTTURA: Architect v{} initialized", MOD_VERSION);
	}
}
