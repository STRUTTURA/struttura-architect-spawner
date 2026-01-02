package it.magius.struttura.architect.model;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Wrapper per i dati di un'entità salvata in una costruzione.
 * Memorizza posizione relativa ai bounds, rotazione e dati NBT completi.
 */
public class EntityData {

    private final String entityType;      // es: "minecraft:armor_stand"
    private final Vec3 relativePos;       // Posizione relativa a bounds.min
    private final float yaw;
    private final float pitch;
    private final CompoundTag nbt;        // Dati NBT completi dell'entità

    public EntityData(String entityType, Vec3 relativePos, float yaw, float pitch, CompoundTag nbt) {
        this.entityType = entityType;
        this.relativePos = relativePos;
        this.yaw = yaw;
        this.pitch = pitch;
        this.nbt = nbt;
    }

    /**
     * Crea un EntityData da un'entità del mondo.
     * La posizione viene convertita in coordinate relative ai bounds minimi.
     * @param entity L'entità da salvare
     * @param bounds I bounds della costruzione
     * @param registries Il provider dei registri per serializzare correttamente gli ItemStack
     */
    public static EntityData fromEntity(Entity entity, ConstructionBounds bounds, HolderLookup.Provider registries) {
        // Ottieni il tipo di entità come stringa
        String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();

        // Calcola posizione relativa ai bounds minimi
        Vec3 relativePos = new Vec3(
            entity.getX() - bounds.getMinX(),
            entity.getY() - bounds.getMinY(),
            entity.getZ() - bounds.getMinZ()
        );

        // Salva l'NBT completo dell'entità usando MC 1.21.11 API
        // IMPORTANTE: usa createWithContext per serializzare correttamente gli ItemStack
        // che in MC 1.21+ usano data components che richiedono il registry context
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        entity.saveAsPassenger(output);
        CompoundTag nbt = output.buildResult();

        // Rimuovi l'UUID dall'NBT per evitare conflitti al respawn
        nbt.remove("UUID");

        // Normalizza coordinate per entità hanging (item frame, painting, etc.)
        // MC 1.21.11 usa "block_pos" come IntArrayTag [x, y, z], non CompoundTag!
        if (nbt.contains("block_pos")) {
            net.minecraft.nbt.Tag rawTag = nbt.get("block_pos");
            if (rawTag instanceof net.minecraft.nbt.IntArrayTag intArrayTag) {
                int[] coords = intArrayTag.getAsIntArray();
                if (coords.length >= 3) {
                    int x = coords[0];
                    int y = coords[1];
                    int z = coords[2];

                    // Crea un nuovo IntArrayTag con le coordinate relative
                    int[] relativeCoords = new int[] {
                        x - bounds.getMinX(),
                        y - bounds.getMinY(),
                        z - bounds.getMinZ()
                    };
                    nbt.putIntArray("block_pos", relativeCoords);
                }
            }
        }
        // Fallback per vecchi formati (TileX/Y/Z)
        else if (nbt.contains("TileX") && nbt.contains("TileY") && nbt.contains("TileZ")) {
            int tileX = nbt.getIntOr("TileX", 0);
            int tileY = nbt.getIntOr("TileY", 0);
            int tileZ = nbt.getIntOr("TileZ", 0);

            nbt.putInt("TileX", tileX - bounds.getMinX());
            nbt.putInt("TileY", tileY - bounds.getMinY());
            nbt.putInt("TileZ", tileZ - bounds.getMinZ());
        }

        // Normalizza sleeping_pos per villager che dormono
        // MC 1.21.11 usa IntArrayTag per sleeping_pos, come block_pos
        if (nbt.contains("sleeping_pos")) {
            net.minecraft.nbt.Tag sleepingTag = nbt.get("sleeping_pos");
            if (sleepingTag instanceof net.minecraft.nbt.IntArrayTag sleepingIntArray) {
                int[] coords = sleepingIntArray.getAsIntArray();
                if (coords.length >= 3) {
                    int x = coords[0];
                    int y = coords[1];
                    int z = coords[2];

                    int[] relativeCoords = new int[] {
                        x - bounds.getMinX(),
                        y - bounds.getMinY(),
                        z - bounds.getMinZ()
                    };
                    nbt.putIntArray("sleeping_pos", relativeCoords);
                }
            }
        }

        // Rimuovi Brain per evitare riferimenti a posizioni assolute (home, job_site, meeting_point)
        // Il villager ricreerà questi riferimenti automaticamente quando interagirà con il mondo
        nbt.remove("Brain");

        // Rimuovi le mappe scritte (filled_map) dagli item frame durante il push
        // Le mappe scritte hanno un map_id specifico del mondo e non possono essere trasferite
        if (entityType.equals("minecraft:item_frame") || entityType.equals("minecraft:glow_item_frame")) {
            removeFilledMapFromItemFrame(nbt);
        }

        return new EntityData(entityType, relativePos, entity.getYRot(), entity.getXRot(), nbt);
    }

    /**
     * Rimuove le mappe scritte (filled_map) dagli item frame durante il push.
     * Le mappe scritte hanno un map_id specifico del mondo e non possono essere trasferite.
     * L'item frame verrà salvato vuoto.
     *
     * In MC 1.21+, le mappe scritte sono "minecraft:map" con il component "minecraft:map_id".
     * Le mappe vuote sono "minecraft:map" SENZA il component map_id.
     */
    private static void removeFilledMapFromItemFrame(CompoundTag nbt) {
        // In MC 1.21.11, l'item frame ha un tag "Item" che contiene l'item
        if (!nbt.contains("Item")) {
            return;
        }

        net.minecraft.nbt.Tag itemTag = nbt.get("Item");
        if (!(itemTag instanceof CompoundTag itemCompound)) {
            return;
        }

        // Controlla se l'item è una mappa (qualsiasi tipo)
        // In MC 1.21+: TUTTE le mappe devono essere rimosse perché:
        // 1. filled_map: ha un map_id specifico del mondo
        // 2. map: anche se vuota, il gioco può assegnarle un map_id al respawn
        // Legacy: "minecraft:filled_map" (per compatibilità con dati vecchi)
        String itemId = itemCompound.getStringOr("id", "");

        boolean isMap = itemId.equals("minecraft:filled_map") || itemId.equals("minecraft:map");

        if (isMap) {
            nbt.remove("Item");
        }
    }

    /**
     * Rimuove le mappe dagli item frame durante lo spawn (pull).
     * L'item frame verrà spawnato vuoto invece che saltato.
     * Questo è un metodo pubblico chiamato da NetworkHandler durante lo spawn.
     */
    public static void removeMapFromItemFrameNbt(CompoundTag nbt) {
        if (!nbt.contains("Item")) {
            return;
        }

        net.minecraft.nbt.Tag itemTag = nbt.get("Item");
        if (!(itemTag instanceof CompoundTag itemCompound)) {
            return;
        }

        String itemId = itemCompound.getStringOr("id", "");
        boolean isMap = itemId.equals("minecraft:filled_map") || itemId.equals("minecraft:map");

        if (isMap) {
            nbt.remove("Item");
        }
    }

    /**
     * Verifica se un'entità dovrebbe essere salvata.
     * Esclude Player e Projectile in volo.
     */
    public static boolean shouldSaveEntity(Entity entity) {
        // Mai salvare i player
        if (entity instanceof Player) {
            return false;
        }

        // Escludi proiettili in volo
        if (entity instanceof Projectile) {
            return false;
        }

        return true;
    }

    // Getters
    public String getEntityType() {
        return entityType;
    }

    public Vec3 getRelativePos() {
        return relativePos;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public CompoundTag getNbt() {
        return nbt;
    }

    /**
     * Estrae il namespace del mod dall'entityType.
     * Es: "create:contraption" -> "create"
     */
    public String getModNamespace() {
        int colonIndex = entityType.indexOf(':');
        if (colonIndex > 0) {
            return entityType.substring(0, colonIndex);
        }
        return "minecraft";
    }
}
