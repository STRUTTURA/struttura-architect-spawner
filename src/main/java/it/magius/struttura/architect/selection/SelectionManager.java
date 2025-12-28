package it.magius.struttura.architect.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gestisce le selezioni di aree per i giocatori.
 * Ogni giocatore puo' avere una selezione attiva con pos1 e pos2.
 */
public class SelectionManager {

    private static final SelectionManager INSTANCE = new SelectionManager();

    // Mappa giocatore -> selezione
    private final Map<UUID, Selection> selections = new HashMap<>();

    private SelectionManager() {}

    public static SelectionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Imposta la prima posizione della selezione.
     */
    public void setPos1(ServerPlayer player, BlockPos pos) {
        Selection selection = selections.computeIfAbsent(player.getUUID(), k -> new Selection());
        selection.setPos1(pos.immutable());
    }

    /**
     * Imposta la seconda posizione della selezione.
     */
    public void setPos2(ServerPlayer player, BlockPos pos) {
        Selection selection = selections.computeIfAbsent(player.getUUID(), k -> new Selection());
        selection.setPos2(pos.immutable());
    }

    /**
     * Ottiene la selezione corrente di un giocatore.
     */
    public Selection getSelection(ServerPlayer player) {
        return selections.get(player.getUUID());
    }

    /**
     * Verifica se un giocatore ha una selezione completa (entrambe le posizioni).
     */
    public boolean hasCompleteSelection(ServerPlayer player) {
        Selection selection = selections.get(player.getUUID());
        return selection != null && selection.isComplete();
    }

    /**
     * Cancella la selezione di un giocatore.
     */
    public void clearSelection(ServerPlayer player) {
        selections.remove(player.getUUID());
    }

    /**
     * Cancella la selezione per UUID (utile quando un player si disconnette).
     */
    public void clearSelection(UUID playerId) {
        selections.remove(playerId);
    }

    /**
     * Rappresenta una selezione con due posizioni.
     */
    public static class Selection {
        private BlockPos pos1;
        private BlockPos pos2;

        public BlockPos getPos1() {
            return pos1;
        }

        public void setPos1(BlockPos pos1) {
            this.pos1 = pos1;
        }

        public BlockPos getPos2() {
            return pos2;
        }

        public void setPos2(BlockPos pos2) {
            this.pos2 = pos2;
        }

        public boolean isComplete() {
            return pos1 != null && pos2 != null;
        }

        public boolean hasPos1() {
            return pos1 != null;
        }

        public boolean hasPos2() {
            return pos2 != null;
        }

        /**
         * Ottiene la posizione minima del parallelepipedo.
         */
        public BlockPos getMin() {
            if (!isComplete()) return null;
            return new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
            );
        }

        /**
         * Ottiene la posizione massima del parallelepipedo.
         */
        public BlockPos getMax() {
            if (!isComplete()) return null;
            return new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
            );
        }

        /**
         * Calcola il volume della selezione (numero di blocchi).
         */
        public int getVolume() {
            if (!isComplete()) return 0;
            BlockPos min = getMin();
            BlockPos max = getMax();
            int sizeX = max.getX() - min.getX() + 1;
            int sizeY = max.getY() - min.getY() + 1;
            int sizeZ = max.getZ() - min.getZ() + 1;
            return sizeX * sizeY * sizeZ;
        }

        /**
         * Ottiene le dimensioni della selezione.
         */
        public int[] getDimensions() {
            if (!isComplete()) return new int[]{0, 0, 0};
            BlockPos min = getMin();
            BlockPos max = getMax();
            return new int[]{
                max.getX() - min.getX() + 1,
                max.getY() - min.getY() + 1,
                max.getZ() - min.getZ() + 1
            };
        }

        @Override
        public String toString() {
            if (!isComplete()) {
                if (pos1 != null) return String.format("Selection[pos1=%s, pos2=?]", pos1);
                if (pos2 != null) return String.format("Selection[pos1=?, pos2=%s]", pos2);
                return "Selection[empty]";
            }
            int[] dims = getDimensions();
            return String.format("Selection[%s -> %s (%dx%dx%d = %d blocks)]",
                getMin(), getMax(), dims[0], dims[1], dims[2], getVolume());
        }
    }
}
