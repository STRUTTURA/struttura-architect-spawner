package it.magius.struttura.architect.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.magius.struttura.architect.network.WireframeData;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Renderizza i wireframe per costruzioni e selezioni.
 * Usa WorldRenderEvents.BEFORE_DEBUG_RENDER per disegnare i box wireframe.
 */
public class WireframeRenderer {

    // Colore fucsia per il wireframe costruzione (packed ARGB)
    private static final int CONSTRUCTION_COLOR = 0xFFFF00FF; // Alpha, Red, Green, Blue

    // Colore ciano per il wireframe selezione (packed ARGB)
    private static final int SELECTION_COLOR = 0xFF00FFFF;

    // Inset per l'overlay dei blocchi (per non sovrapporsi al wireframe)
    private static final double BLOCK_INSET = 0.01;

    // Dati correnti (aggiornati dal packet)
    private static WireframeData.ConstructionWireframe constructionData = WireframeData.ConstructionWireframe.empty();
    private static WireframeData.SelectionWireframe selectionData = WireframeData.SelectionWireframe.empty();

    // Posizioni dei blocchi per l'overlay (tutti i blocchi, senza distinzione)
    private static List<BlockPos> blockPositions = new ArrayList<>();

    /**
     * Inizializza il renderer registrando l'evento di rendering.
     */
    public static void init() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(WireframeRenderer::onWorldRender);
    }

    private static void onWorldRender(WorldRenderContext context) {
        // Non renderizzare se non ci sono dati
        boolean hasBlocks = !blockPositions.isEmpty();
        if (!constructionData.active && !selectionData.active && !hasBlocks) {
            return;
        }

        PoseStack poseStack = context.matrices();
        VertexConsumer lineBuffer = context.consumers().getBuffer(RenderTypes.lines());

        // Ottieni la posizione della camera dal cameraRenderState
        Vec3 cameraPos = context.worldState().cameraRenderState.pos;

        poseStack.pushPose();

        // Renderizza wireframe per i blocchi overlay
        if (constructionData.active && hasBlocks) {
            for (BlockPos pos : blockPositions) {
                AABB box = new AABB(
                    pos.getX() + BLOCK_INSET, pos.getY() + BLOCK_INSET, pos.getZ() + BLOCK_INSET,
                    pos.getX() + 1 - BLOCK_INSET, pos.getY() + 1 - BLOCK_INSET, pos.getZ() + 1 - BLOCK_INSET
                );
                VoxelShape shape = Shapes.create(box);
                ShapeRenderer.renderShape(
                    poseStack, lineBuffer, shape,
                    -cameraPos.x, -cameraPos.y, -cameraPos.z,
                    CONSTRUCTION_COLOR, 1.0f
                );
            }
        }

        // Renderizza wireframe costruzione (se attivo)
        if (constructionData.active) {
            BlockPos min = constructionData.min;
            BlockPos max = constructionData.max;
            AABB box = new AABB(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1
            );
            VoxelShape shape = Shapes.create(box);
            ShapeRenderer.renderShape(
                poseStack, lineBuffer, shape,
                -cameraPos.x, -cameraPos.y, -cameraPos.z,
                CONSTRUCTION_COLOR, 1.0f
            );
        }

        // Renderizza wireframe selezione (se ha entrambe le posizioni)
        if (selectionData.active && selectionData.hasPos1 && selectionData.hasPos2) {
            BlockPos pos1 = selectionData.pos1;
            BlockPos pos2 = selectionData.pos2;
            int minX = Math.min(pos1.getX(), pos2.getX());
            int minY = Math.min(pos1.getY(), pos2.getY());
            int minZ = Math.min(pos1.getZ(), pos2.getZ());
            int maxX = Math.max(pos1.getX(), pos2.getX());
            int maxY = Math.max(pos1.getY(), pos2.getY());
            int maxZ = Math.max(pos1.getZ(), pos2.getZ());
            AABB box = new AABB(
                minX, minY, minZ,
                maxX + 1, maxY + 1, maxZ + 1
            );
            VoxelShape shape = Shapes.create(box);
            ShapeRenderer.renderShape(
                poseStack, lineBuffer, shape,
                -cameraPos.x, -cameraPos.y, -cameraPos.z,
                SELECTION_COLOR, 1.0f
            );
        }

        poseStack.popPose();
    }

    /**
     * Aggiorna i dati del wireframe costruzione.
     */
    public static void setConstructionData(WireframeData.ConstructionWireframe data) {
        constructionData = data;
    }

    /**
     * Aggiorna i dati del wireframe selezione.
     */
    public static void setSelectionData(WireframeData.SelectionWireframe data) {
        selectionData = data;
    }

    /**
     * Ottiene i dati del wireframe costruzione.
     */
    public static WireframeData.ConstructionWireframe getConstructionData() {
        return constructionData;
    }

    /**
     * Ottiene i dati del wireframe selezione.
     */
    public static WireframeData.SelectionWireframe getSelectionData() {
        return selectionData;
    }

    /**
     * Imposta le posizioni dei blocchi per l'overlay.
     */
    public static void setBlockPositions(List<BlockPos> solid, List<BlockPos> air) {
        // Combina tutti i blocchi in una sola lista
        blockPositions = new ArrayList<>(solid.size() + air.size());
        blockPositions.addAll(solid);
        blockPositions.addAll(air);
    }

    /**
     * Resetta tutti i dati.
     */
    public static void reset() {
        constructionData = WireframeData.ConstructionWireframe.empty();
        selectionData = WireframeData.SelectionWireframe.empty();
        blockPositions = new ArrayList<>();
    }

    /**
     * Controlla se il wireframe della costruzione è attivo.
     */
    public static boolean isConstructionActive() {
        return constructionData.active;
    }

    /**
     * Controlla se il wireframe della selezione è attivo.
     */
    public static boolean isSelectionActive() {
        return selectionData.active;
    }
}
