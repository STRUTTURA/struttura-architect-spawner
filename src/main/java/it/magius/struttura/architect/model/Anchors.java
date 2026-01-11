package it.magius.struttura.architect.model;

import net.minecraft.core.BlockPos;

/**
 * Contains anchor points for a construction.
 * Anchors are special positions within the construction that have semantic meaning.
 *
 * All coordinates are stored NORMALIZED (relative to bounds min corner 0,0,0)
 * and must be denormalized when used in the world.
 */
public class Anchors {

    // Entrance point - where the player should be teleported when using TP
    private BlockPos entrance = null;
    // Player rotation (yaw) at entrance
    private float entranceYaw = 0f;

    public Anchors() {
    }

    /**
     * Gets the entrance anchor position (normalized coordinates).
     * @return The entrance position, or null if not set
     */
    public BlockPos getEntrance() {
        return entrance;
    }

    /**
     * Gets the entrance yaw rotation.
     * @return The yaw in degrees
     */
    public float getEntranceYaw() {
        return entranceYaw;
    }

    /**
     * Sets the entrance anchor position and yaw (normalized coordinates).
     * @param pos The entrance position, or null to clear
     * @param yaw The player yaw rotation in degrees
     */
    public void setEntrance(BlockPos pos, float yaw) {
        this.entrance = pos != null ? pos.immutable() : null;
        this.entranceYaw = pos != null ? yaw : 0f;
    }

    /**
     * Sets the entrance anchor position (normalized coordinates).
     * Keeps existing yaw if any.
     * @param pos The entrance position, or null to clear
     */
    public void setEntrance(BlockPos pos) {
        setEntrance(pos, pos != null ? this.entranceYaw : 0f);
    }

    /**
     * Checks if the entrance anchor is set.
     */
    public boolean hasEntrance() {
        return entrance != null;
    }

    /**
     * Clears the entrance anchor.
     */
    public void clearEntrance() {
        this.entrance = null;
        this.entranceYaw = 0f;
    }

    /**
     * Creates a copy of this anchors object.
     */
    public Anchors copy() {
        Anchors copy = new Anchors();
        if (this.entrance != null) {
            copy.entrance = this.entrance.immutable();
            copy.entranceYaw = this.entranceYaw;
        }
        return copy;
    }

    /**
     * Checks if any anchor is set.
     */
    public boolean hasAnyAnchor() {
        return entrance != null;
    }
}
