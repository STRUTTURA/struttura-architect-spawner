package it.magius.struttura.architect.ingame;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.magius.struttura.architect.Architect;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;

/**
 * Defines all Fabric data attachments used by the mod.
 * Attachments provide persistent data storage on game objects like chunks.
 */
public class ModAttachments {

    /**
     * Attachment for chunk spawn data.
     * Stores whether a chunk has been processed and optionally building info.
     */
    public static final AttachmentType<ChunkSpawnData> CHUNK_SPAWN_DATA = AttachmentRegistry.create(
        Identifier.fromNamespaceAndPath(Architect.MOD_ID, "chunk_spawn_data"),
        builder -> builder
            .persistent(ChunkSpawnData.CODEC)
    );

    /**
     * Data stored on each chunk for the spawner system.
     * Includes building metadata that persists even if building is removed from list.
     */
    public record ChunkSpawnData(
        boolean processed,
        String buildingRdns,
        long buildingPk,
        int rotation,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        String buildingName,   // Localized name at spawn time (fallback if building removed from list)
        String buildingAuthor  // Author nickname (fallback if building removed from list)
    ) {
        public static final Codec<ChunkSpawnData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                Codec.BOOL.fieldOf("processed").forGetter(ChunkSpawnData::processed),
                Codec.STRING.optionalFieldOf("buildingRdns", "").forGetter(ChunkSpawnData::buildingRdns),
                Codec.LONG.optionalFieldOf("buildingPk", 0L).forGetter(ChunkSpawnData::buildingPk),
                Codec.INT.optionalFieldOf("rotation", 0).forGetter(ChunkSpawnData::rotation),
                Codec.DOUBLE.optionalFieldOf("minX", 0.0).forGetter(ChunkSpawnData::minX),
                Codec.DOUBLE.optionalFieldOf("minY", 0.0).forGetter(ChunkSpawnData::minY),
                Codec.DOUBLE.optionalFieldOf("minZ", 0.0).forGetter(ChunkSpawnData::minZ),
                Codec.DOUBLE.optionalFieldOf("maxX", 0.0).forGetter(ChunkSpawnData::maxX),
                Codec.DOUBLE.optionalFieldOf("maxY", 0.0).forGetter(ChunkSpawnData::maxY),
                Codec.DOUBLE.optionalFieldOf("maxZ", 0.0).forGetter(ChunkSpawnData::maxZ),
                Codec.STRING.optionalFieldOf("buildingName", "").forGetter(ChunkSpawnData::buildingName),
                Codec.STRING.optionalFieldOf("buildingAuthor", "").forGetter(ChunkSpawnData::buildingAuthor)
            ).apply(instance, ChunkSpawnData::new)
        );

        /**
         * Creates data for a processed chunk with no building.
         */
        public static ChunkSpawnData processedOnly() {
            return new ChunkSpawnData(true, "", 0L, 0, 0, 0, 0, 0, 0, 0, "", "");
        }

        /**
         * Creates data for a processed chunk with a spawned building.
         */
        public static ChunkSpawnData withBuilding(String rdns, long pk, int rotation,
                                                   double minX, double minY, double minZ,
                                                   double maxX, double maxY, double maxZ,
                                                   String name, String author) {
            return new ChunkSpawnData(true, rdns, pk, rotation, minX, minY, minZ, maxX, maxY, maxZ,
                name != null ? name : "", author != null ? author : "");
        }

        /**
         * Checks if this chunk has a building spawned.
         */
        public boolean hasBuilding() {
            return !buildingRdns.isEmpty();
        }
    }

    /**
     * Called during mod initialization to ensure attachments are registered.
     */
    public static void register() {
        // Attachments are registered when the class is loaded
        Architect.LOGGER.info("Chunk attachments registered");
    }
}
