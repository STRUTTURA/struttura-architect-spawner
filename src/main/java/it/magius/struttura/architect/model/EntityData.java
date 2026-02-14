package it.magius.struttura.architect.model;

import it.magius.struttura.architect.Architect;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
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

        // Negative relative positions should never happen - entity is outside bounds
        if (relativePos.x < -0.5 || relativePos.y < -0.5 || relativePos.z < -0.5) {
            Architect.LOGGER.error("CRITICAL: Entity {} has negative relativePos ({}, {}, {}) - " +
                "entity world pos ({}, {}, {}), bounds min ({}, {}, {})",
                entityType,
                relativePos.x, relativePos.y, relativePos.z,
                entity.getX(), entity.getY(), entity.getZ(),
                bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());
        }

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

        // Remove filled maps from item frames during push
        // Filled maps have a world-specific map_id and cannot be transferred
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
     * Expands bounds to include all block positions occupied by an entity.
     * For hanging entities (item frames, paintings), this includes the block_pos
     * from NBT which may be different from blockPosition().
     * For all entities, includes the blockPosition() and the AABB bounding box corners.
     */
    public static void expandBoundsForEntity(Entity entity, ConstructionBounds bounds) {
        // Always include the entity's block position
        bounds.expandToInclude(entity.blockPosition());

        // Include all blocks covered by the entity's bounding box
        net.minecraft.world.phys.AABB aabb = entity.getBoundingBox();
        bounds.expandToInclude(net.minecraft.core.BlockPos.containing(aabb.minX, aabb.minY, aabb.minZ));
        bounds.expandToInclude(net.minecraft.core.BlockPos.containing(aabb.maxX, aabb.maxY, aabb.maxZ));

        // For hanging entities, also include the block they're attached to
        if (entity instanceof net.minecraft.world.entity.decoration.HangingEntity hanging) {
            bounds.expandToInclude(hanging.getPos());
        }
    }

    /**
     * Verifica se un'entità dovrebbe essere salvata.
     * Esclude Player, Projectile in volo e oggetti caduti a terra.
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

        // Escludi oggetti caduti a terra (dropped items)
        if (entity instanceof ItemEntity) {
            return false;
        }

        return true;
    }

    /**
     * Creates a new EntityData with rotated position and yaw.
     * Used when updating construction coordinates after a rotated placement.
     *
     * @param rotationSteps Number of 90-degree clockwise rotations (0-3)
     * @param pivotX Pivot X coordinate for rotation
     * @param pivotZ Pivot Z coordinate for rotation
     * @return New EntityData with rotated position, yaw, and NBT
     */
    public EntityData withRotation(int rotationSteps, double pivotX, double pivotZ) {
        if (rotationSteps == 0) {
            return this;
        }

        // Rotate position around pivot
        double relX = relativePos.x;
        double relZ = relativePos.z;
        double dx = relX - pivotX;
        double dz = relZ - pivotZ;

        double newDx = dx;
        double newDz = dz;
        for (int i = 0; i < rotationSteps; i++) {
            double temp = newDx;
            newDx = -newDz;
            newDz = temp;
        }

        double newX = pivotX + newDx;
        double newZ = pivotZ + newDz;
        Vec3 newRelativePos = new Vec3(newX, relativePos.y, newZ);

        // Rotate yaw
        float newYaw = yaw + (rotationSteps * 90f);
        // Normalize to -180 to 180 range
        newYaw = ((newYaw + 180f) % 360f) - 180f;
        if (newYaw < -180f) newYaw += 360f;

        // Copy and rotate NBT (block_pos, sleeping_pos, Facing, Rotation)
        CompoundTag newNbt = rotateNbt(nbt.copy(), rotationSteps, (int) pivotX, (int) pivotZ);

        return new EntityData(entityType, newRelativePos, newYaw, pitch, newNbt);
    }

    /**
     * Rotates NBT data for hanging entities and mobs.
     */
    private static CompoundTag rotateNbt(CompoundTag nbt, int rotationSteps, int pivotX, int pivotZ) {
        // Rotate block_pos for hanging entities
        if (nbt.contains("block_pos")) {
            net.minecraft.nbt.Tag rawTag = nbt.get("block_pos");
            if (rawTag instanceof net.minecraft.nbt.IntArrayTag intArrayTag) {
                int[] coords = intArrayTag.getAsIntArray();
                if (coords.length >= 3) {
                    int[] rotated = rotateXZ(coords[0], coords[2], pivotX, pivotZ, rotationSteps);
                    nbt.putIntArray("block_pos", new int[]{rotated[0], coords[1], rotated[1]});
                }
            }
        } else if (nbt.contains("TileX") && nbt.contains("TileY") && nbt.contains("TileZ")) {
            int relX = nbt.getIntOr("TileX", 0);
            int relZ = nbt.getIntOr("TileZ", 0);
            int[] rotated = rotateXZ(relX, relZ, pivotX, pivotZ, rotationSteps);
            nbt.putInt("TileX", rotated[0]);
            nbt.putInt("TileZ", rotated[1]);
        }

        // Rotate sleeping_pos for villagers
        if (nbt.contains("sleeping_pos")) {
            net.minecraft.nbt.Tag sleepingTag = nbt.get("sleeping_pos");
            if (sleepingTag instanceof net.minecraft.nbt.IntArrayTag sleepingIntArray) {
                int[] coords = sleepingIntArray.getAsIntArray();
                if (coords.length >= 3) {
                    int[] rotated = rotateXZ(coords[0], coords[2], pivotX, pivotZ, rotationSteps);
                    nbt.putIntArray("sleeping_pos", new int[]{rotated[0], coords[1], rotated[1]});
                }
            }
        }

        // Rotate Facing for hanging entities (item frames use uppercase "Facing" with 3D values)
        if (nbt.contains("Facing")) {
            int facing = nbt.getByteOr("Facing", (byte) 0);
            if (facing >= 2 && facing <= 5) {
                int newFacing = rotateFacing(facing, rotationSteps);
                nbt.putByte("Facing", (byte) newFacing);
            }
        }

        // Rotate facing for paintings (lowercase "facing" with 2D values: 0=south, 1=west, 2=north, 3=east)
        if (nbt.contains("facing")) {
            int facing2D = nbt.getByteOr("facing", (byte) 0);
            int newFacing2D = rotateFacing2D(facing2D, rotationSteps);
            nbt.putByte("facing", (byte) newFacing2D);
        }

        // Rotate yaw in Rotation tag for mobs
        if (nbt.contains("Rotation")) {
            net.minecraft.nbt.Tag rotationTag = nbt.get("Rotation");
            if (rotationTag instanceof net.minecraft.nbt.ListTag rotationList && rotationList.size() >= 2) {
                float originalYaw = rotationList.getFloatOr(0, 0f);
                float pitch = rotationList.getFloatOr(1, 0f);
                float rotatedYaw = originalYaw + (rotationSteps * 90f);

                net.minecraft.nbt.ListTag newRotation = new net.minecraft.nbt.ListTag();
                newRotation.add(net.minecraft.nbt.FloatTag.valueOf(rotatedYaw));
                newRotation.add(net.minecraft.nbt.FloatTag.valueOf(pitch));
                nbt.put("Rotation", newRotation);
            }
        }

        return nbt;
    }

    /**
     * Rotates XZ coordinates around pivot by rotationSteps (90-degree increments).
     */
    private static int[] rotateXZ(int x, int z, int pivotX, int pivotZ, int rotationSteps) {
        int dx = x - pivotX;
        int dz = z - pivotZ;

        for (int i = 0; i < rotationSteps; i++) {
            int temp = dx;
            dx = -dz;
            dz = temp;
        }

        return new int[]{pivotX + dx, pivotZ + dz};
    }

    /**
     * Rotates horizontal facing values (2-5) by rotationSteps.
     * 2=north, 3=south, 4=west, 5=east
     */
    private static int rotateFacing(int facing, int rotationSteps) {
        // Map facing to direction index: N=0, E=1, S=2, W=3
        int[] facingToDir = {-1, -1, 0, 2, 3, 1}; // facing 2,3,4,5 -> 0,2,3,1
        int[] dirToFacing = {2, 5, 3, 4}; // dir 0,1,2,3 -> 2,5,3,4

        if (facing < 2 || facing > 5) return facing;

        int dir = facingToDir[facing];
        int newDir = (dir + rotationSteps) % 4;
        return dirToFacing[newDir];
    }

    /**
     * Rotates a 2D facing value by the specified number of 90-degree clockwise steps.
     * Used by Painting in MC 1.21.11 which stores "facing" (lowercase) with LEGACY_ID_CODEC_2D.
     * 2D facing values: 0=south(+Z), 1=west(-X), 2=north(-Z), 3=east(+X)
     * Clockwise: south -> west -> north -> east -> south
     */
    private static int rotateFacing2D(int facing2D, int rotationSteps) {
        if (rotationSteps == 0 || facing2D < 0 || facing2D > 3) return facing2D;
        return (facing2D + rotationSteps) % 4;
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
