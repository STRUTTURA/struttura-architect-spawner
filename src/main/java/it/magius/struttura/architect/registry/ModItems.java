package it.magius.struttura.architect.registry;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.item.ConstructionHammerItem;
import it.magius.struttura.architect.item.MeasuringTapeItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.function.Function;

/**
 * Registry per tutti gli items del mod Architect.
 */
public class ModItems {

    public static final Item CONSTRUCTION_HAMMER = register("construction_hammer",
            ConstructionHammerItem::new,
            new Item.Properties().stacksTo(1));

    // TODO: Re-enable tape registration when keystone feature is implemented
    // public static final Item MEASURING_TAPE = register("measuring_tape",
    //         MeasuringTapeItem::new,
    //         new Item.Properties().stacksTo(1));
    public static final Item MEASURING_TAPE = null;

    private static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties settings) {
        ResourceKey<Item> key = ResourceKey.create(
                Registries.ITEM,
                Identifier.fromNamespaceAndPath(Architect.MOD_ID, name)
        );
        return Items.registerItem(key, factory, settings);
    }

    /**
     * Chiamato durante l'inizializzazione per assicurarsi che tutti gli items siano registrati.
     */
    public static void init() {
        Architect.LOGGER.info("Registering items...");
    }
}
