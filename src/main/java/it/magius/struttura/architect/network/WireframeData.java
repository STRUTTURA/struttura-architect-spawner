package it.magius.struttura.architect.network;

import net.minecraft.core.BlockPos;

/**
 * Dati per il rendering del wireframe condivisi tra server e client.
 */
public class WireframeData {

    /**
     * Dati del wireframe della costruzione in editing.
     */
    public static class ConstructionWireframe {
        public final boolean active;
        public final BlockPos min;
        public final BlockPos max;
        public final String constructionId;

        public ConstructionWireframe(boolean active, BlockPos min, BlockPos max, String constructionId) {
            this.active = active;
            this.min = min;
            this.max = max;
            this.constructionId = constructionId;
        }

        public static ConstructionWireframe empty() {
            return new ConstructionWireframe(false, BlockPos.ZERO, BlockPos.ZERO, "");
        }
    }

    /**
     * Dati del wireframe della selezione.
     */
    public static class SelectionWireframe {
        public final boolean active;
        public final BlockPos pos1;
        public final BlockPos pos2;
        public final boolean hasPos1;
        public final boolean hasPos2;

        public SelectionWireframe(boolean active, BlockPos pos1, BlockPos pos2, boolean hasPos1, boolean hasPos2) {
            this.active = active;
            this.pos1 = pos1;
            this.pos2 = pos2;
            this.hasPos1 = hasPos1;
            this.hasPos2 = hasPos2;
        }

        public static SelectionWireframe empty() {
            return new SelectionWireframe(false, BlockPos.ZERO, BlockPos.ZERO, false, false);
        }

        public boolean isComplete() {
            return hasPos1 && hasPos2;
        }

        public BlockPos getMin() {
            if (!isComplete()) return BlockPos.ZERO;
            return new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
            );
        }

        public BlockPos getMax() {
            if (!isComplete()) return BlockPos.ZERO;
            return new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
            );
        }
    }
}
