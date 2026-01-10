package it.magius.struttura.architect.network;

import it.magius.struttura.architect.Architect;
import it.magius.struttura.architect.model.ModInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Packet S2C for sending mod requirements to client before pull.
 * Allows client to validate which mods are missing and show appropriate dialog.
 */
public record ModRequirementsPacket(
    String constructionId,
    Map<String, ModInfo> requiredMods
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ModRequirementsPacket> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Architect.MOD_ID, "mod_requirements"));

    public static final StreamCodec<FriendlyByteBuf, ModRequirementsPacket> STREAM_CODEC =
        StreamCodec.of(ModRequirementsPacket::write, ModRequirementsPacket::read);

    private static ModRequirementsPacket read(FriendlyByteBuf buf) {
        String constructionId = buf.readUtf(256);

        int modCount = buf.readVarInt();
        Map<String, ModInfo> requiredMods = new HashMap<>();

        for (int i = 0; i < modCount; i++) {
            String modId = buf.readUtf(128);
            String displayName = buf.readUtf(256);
            int blockCount = buf.readVarInt();
            int entitiesCount = buf.readVarInt();
            int mobsCount = buf.readVarInt();
            int commandBlocksCount = buf.readVarInt();
            boolean hasVersion = buf.readBoolean();
            String version = hasVersion ? buf.readUtf(64) : null;
            boolean hasDownloadUrl = buf.readBoolean();
            String downloadUrl = hasDownloadUrl ? buf.readUtf(512) : null;

            ModInfo info = new ModInfo(modId, displayName, blockCount, entitiesCount, mobsCount, commandBlocksCount, downloadUrl, version);
            requiredMods.put(modId, info);
        }

        return new ModRequirementsPacket(constructionId, requiredMods);
    }

    private static void write(FriendlyByteBuf buf, ModRequirementsPacket packet) {
        buf.writeUtf(packet.constructionId, 256);

        buf.writeVarInt(packet.requiredMods.size());

        for (ModInfo mod : packet.requiredMods.values()) {
            buf.writeUtf(mod.getModId(), 128);
            buf.writeUtf(mod.getDisplayName(), 256);
            buf.writeVarInt(mod.getBlockCount());
            buf.writeVarInt(mod.getEntitiesCount());
            buf.writeVarInt(mod.getMobsCount());
            buf.writeVarInt(mod.getCommandBlocksCount());
            buf.writeBoolean(mod.getVersion() != null);
            if (mod.getVersion() != null) {
                buf.writeUtf(mod.getVersion(), 64);
            }
            buf.writeBoolean(mod.getDownloadUrl() != null);
            if (mod.getDownloadUrl() != null) {
                buf.writeUtf(mod.getDownloadUrl(), 512);
            }
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
