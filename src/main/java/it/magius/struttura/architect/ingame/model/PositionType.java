package it.magius.struttura.architect.ingame.model;

/**
 * Defines how a building should be positioned in the world.
 */
public enum PositionType {
    /**
     * Building placed on solid ground with open sky above.
     */
    ON_GROUND("onGround"),

    /**
     * Building floating in air (only air in bounds + margin).
     */
    ON_AIR("onAir"),

    /**
     * Building placed over water with open sky above.
     */
    OVER_WATER("overWater"),

    /**
     * Building placed over lava with open sky above.
     */
    OVER_LAVA("overLava"),

    /**
     * Building immersed in water.
     */
    ON_WATER("onWater"),

    /**
     * Building surrounded by solid blocks (underground).
     */
    UNDER_GROUND("underGround"),

    /**
     * Building on the water floor with water above.
     */
    BOTTOM_WATER("bottomWater");

    private final String apiValue;

    PositionType(String apiValue) {
        this.apiValue = apiValue;
    }

    /**
     * Gets the API value used in JSON responses.
     */
    public String getApiValue() {
        return apiValue;
    }

    /**
     * Parses a PositionType from its API value.
     * @param value the API value (e.g., "onGround")
     * @return the matching PositionType, or ON_GROUND as default
     */
    public static PositionType fromApiValue(String value) {
        if (value == null || value.isEmpty()) {
            return ON_GROUND;
        }

        for (PositionType type : values()) {
            if (type.apiValue.equalsIgnoreCase(value)) {
                return type;
            }
        }

        // Fallback to ON_GROUND for unknown values
        return ON_GROUND;
    }
}
