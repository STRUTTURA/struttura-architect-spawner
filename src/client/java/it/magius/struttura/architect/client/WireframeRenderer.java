package it.magius.struttura.architect.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.magius.struttura.architect.config.ArchitectConfig;
import it.magius.struttura.architect.network.WireframeData;
import net.minecraft.client.Minecraft;
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

    // Colore rosso per il wireframe dei singoli blocchi (packed ARGB)
    private static final int BLOCK_COLOR = 0xFFFF0000; // Rosso

    // Colore ciano per il wireframe selezione (packed ARGB)
    private static final int SELECTION_COLOR = 0xFF00FFFF;

    // Outset per l'overlay dei blocchi (leggermente più grande del blocco per essere sempre visibile)
    private static final double BLOCK_OUTSET = 0.01;

    // Dati correnti (aggiornati dal packet)
    private static WireframeData.ConstructionWireframe constructionData = WireframeData.ConstructionWireframe.empty();
    private static WireframeData.SelectionWireframe selectionData = WireframeData.SelectionWireframe.empty();

    // Posizioni dei blocchi per l'overlay (blocchi nella costruzione - rosso)
    private static List<BlockPos> blockPositions = new ArrayList<>();

    // Posizioni dei blocchi di anteprima (blocchi che verranno aggiunti - ciano)
    private static List<BlockPos> previewPositions = new ArrayList<>();

    /**
     * Inizializza il renderer registrando l'evento di rendering.
     */
    public static void init() {
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(WireframeRenderer::onWorldRender);
    }

    private static void onWorldRender(WorldRenderContext context) {
        // Non renderizzare se non ci sono dati
        boolean hasBlocks = !blockPositions.isEmpty();
        boolean hasPreview = !previewPositions.isEmpty();
        if (!constructionData.active && !selectionData.active && !hasBlocks && !hasPreview) {
            return;
        }

        PoseStack poseStack = context.matrices();
        VertexConsumer lineBuffer = context.consumers().getBuffer(RenderTypes.lines());

        // Ottieni la posizione della camera dal cameraRenderState
        Vec3 cameraPos = context.worldState().cameraRenderState.pos;

        // Calcola parametri per il fade basato sulla distanza
        BlockPos playerPos = Minecraft.getInstance().player.blockPosition();
        int fadeStart = ArchitectConfig.getInstance().getWireframeFadeStart();
        int fadeEnd = ArchitectConfig.getInstance().getWireframeFadeEnd();
        int fadeStartSq = fadeStart * fadeStart;
        int fadeEndSq = fadeEnd * fadeEnd;

        poseStack.pushPose();

        // Renderizza wireframe per i blocchi overlay (colore ROSSO, leggermente più grande del blocco)
        if (constructionData.active && hasBlocks) {
            for (BlockPos pos : blockPositions) {
                double distSq = playerPos.distSqr(pos);
                if (distSq >= fadeEndSq) continue;  // Skip blocchi troppo lontani

                float alpha = 1.0f;
                if (distSq > fadeStartSq) {
                    alpha = 1.0f - (float)((distSq - fadeStartSq) / (double)(fadeEndSq - fadeStartSq));
                }

                AABB box = new AABB(
                    pos.getX() - BLOCK_OUTSET, pos.getY() - BLOCK_OUTSET, pos.getZ() - BLOCK_OUTSET,
                    pos.getX() + 1 + BLOCK_OUTSET, pos.getY() + 1 + BLOCK_OUTSET, pos.getZ() + 1 + BLOCK_OUTSET
                );
                renderShapeWithAlpha(poseStack, lineBuffer, box, cameraPos, BLOCK_COLOR, alpha);
            }
        }

        // Renderizza wireframe per i blocchi di anteprima (colore CIANO, blocchi che verranno aggiunti)
        if (hasPreview) {
            for (BlockPos pos : previewPositions) {
                double distSq = playerPos.distSqr(pos);
                if (distSq >= fadeEndSq) continue;  // Skip blocchi troppo lontani

                float alpha = 1.0f;
                if (distSq > fadeStartSq) {
                    alpha = 1.0f - (float)((distSq - fadeStartSq) / (double)(fadeEndSq - fadeStartSq));
                }

                AABB box = new AABB(
                    pos.getX() - BLOCK_OUTSET, pos.getY() - BLOCK_OUTSET, pos.getZ() - BLOCK_OUTSET,
                    pos.getX() + 1 + BLOCK_OUTSET, pos.getY() + 1 + BLOCK_OUTSET, pos.getZ() + 1 + BLOCK_OUTSET
                );
                renderShapeWithAlpha(poseStack, lineBuffer, box, cameraPos, SELECTION_COLOR, alpha);
            }
        }

        // Renderizza wireframe costruzione (se attivo, leggermente più grande per essere sempre visibile)
        if (constructionData.active) {
            BlockPos min = constructionData.min;
            BlockPos max = constructionData.max;
            AABB box = new AABB(
                min.getX() - BLOCK_OUTSET, min.getY() - BLOCK_OUTSET, min.getZ() - BLOCK_OUTSET,
                max.getX() + 1 + BLOCK_OUTSET, max.getY() + 1 + BLOCK_OUTSET, max.getZ() + 1 + BLOCK_OUTSET
            );
            renderShape(poseStack, lineBuffer, box, cameraPos, CONSTRUCTION_COLOR);
        }

        // Renderizza wireframe selezione (se ha entrambe le posizioni, leggermente più grande per essere sempre visibile)
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
                minX - BLOCK_OUTSET, minY - BLOCK_OUTSET, minZ - BLOCK_OUTSET,
                maxX + 1 + BLOCK_OUTSET, maxY + 1 + BLOCK_OUTSET, maxZ + 1 + BLOCK_OUTSET
            );
            renderShape(poseStack, lineBuffer, box, cameraPos, SELECTION_COLOR);
        }

        poseStack.popPose();
    }

    /**
     * Renderizza una forma wireframe.
     */
    private static void renderShape(PoseStack poseStack, VertexConsumer lineBuffer,
            AABB box, Vec3 cameraPos, int color) {
        VoxelShape shape = Shapes.create(box);
        ShapeRenderer.renderShape(
            poseStack, lineBuffer, shape,
            -cameraPos.x, -cameraPos.y, -cameraPos.z,
            color, 1.0f
        );
    }

    /**
     * Renderizza una forma wireframe con alpha personalizzato per il fade basato sulla distanza.
     */
    private static void renderShapeWithAlpha(PoseStack poseStack, VertexConsumer lineBuffer,
            AABB box, Vec3 cameraPos, int color, float alpha) {
        // Estrai componenti colore e applica alpha
        int a = (int)((color >> 24 & 0xFF) * alpha);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int colorWithAlpha = (a << 24) | (r << 16) | (g << 8) | b;

        VoxelShape shape = Shapes.create(box);
        ShapeRenderer.renderShape(
            poseStack, lineBuffer, shape,
            -cameraPos.x, -cameraPos.y, -cameraPos.z,
            colorWithAlpha, alpha
        );
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
     * @param solid blocchi solidi nella costruzione
     * @param air blocchi aria nella costruzione
     * @param preview blocchi di anteprima (verranno aggiunti con select add)
     */
    public static void setBlockPositions(List<BlockPos> solid, List<BlockPos> air, List<BlockPos> preview) {
        // Combina tutti i blocchi della costruzione in una sola lista
        blockPositions = new ArrayList<>(solid.size() + air.size());
        blockPositions.addAll(solid);
        blockPositions.addAll(air);

        // Imposta i blocchi di anteprima
        previewPositions = new ArrayList<>(preview);
    }

    /**
     * Resetta tutti i dati.
     */
    public static void reset() {
        constructionData = WireframeData.ConstructionWireframe.empty();
        selectionData = WireframeData.SelectionWireframe.empty();
        blockPositions = new ArrayList<>();
        previewPositions = new ArrayList<>();
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
