package it.magius.struttura.architect.model;

import net.minecraft.core.BlockPos;

/**
 * Bounds (limiti spaziali) di una costruzione.
 */
public class ConstructionBounds {
    private int minX, minY, minZ;
    private int maxX, maxY, maxZ;
    private boolean initialized = false;

    public ConstructionBounds() {
        reset();
    }

    public void reset() {
        this.minX = Integer.MAX_VALUE;
        this.minY = Integer.MAX_VALUE;
        this.minZ = Integer.MAX_VALUE;
        this.maxX = Integer.MIN_VALUE;
        this.maxY = Integer.MIN_VALUE;
        this.maxZ = Integer.MIN_VALUE;
        this.initialized = false;
    }

    public void expandToInclude(BlockPos pos) {
        minX = Math.min(minX, pos.getX());
        minY = Math.min(minY, pos.getY());
        minZ = Math.min(minZ, pos.getZ());
        maxX = Math.max(maxX, pos.getX());
        maxY = Math.max(maxY, pos.getY());
        maxZ = Math.max(maxZ, pos.getZ());
        initialized = true;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Alias per isInitialized() - usato per consistenza semantica.
     */
    public boolean isValid() {
        return initialized;
    }

    // Getters per i valori min/max
    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    public int getSizeX() {
        return initialized ? (maxX - minX + 1) : 0;
    }

    public int getSizeY() {
        return initialized ? (maxY - minY + 1) : 0;
    }

    public int getSizeZ() {
        return initialized ? (maxZ - minZ + 1) : 0;
    }

    public BlockPos getMin() {
        return new BlockPos(minX, minY, minZ);
    }

    public BlockPos getMax() {
        return new BlockPos(maxX, maxY, maxZ);
    }

    public BlockPos getCenter() {
        if (!initialized) return BlockPos.ZERO;
        return new BlockPos(
            (minX + maxX) / 2,
            (minY + maxY) / 2,
            (minZ + maxZ) / 2
        );
    }

    @Override
    public String toString() {
        if (!initialized) return "Bounds[empty]";
        return String.format("Bounds[%d,%d,%d -> %d,%d,%d (%dx%dx%d)]",
            minX, minY, minZ, maxX, maxY, maxZ,
            getSizeX(), getSizeY(), getSizeZ());
    }
}
