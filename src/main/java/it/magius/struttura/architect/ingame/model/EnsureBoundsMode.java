package it.magius.struttura.architect.ingame.model;

/**
 * Defines how the spawn area should be cleared before placing a building.
 */
public enum EnsureBoundsMode {
    /** Leave existing blocks untouched */
    NONE("none"),
    /** Clear the entire spawn area */
    ALL("all"),
    /** Clear only blocks above the entrance anchor (entranceY+1 and up) */
    ABOVE_ENTRANCE("aboveEntrance");

    private final String apiValue;

    EnsureBoundsMode(String apiValue) {
        this.apiValue = apiValue;
    }

    public String toApi() {
        return apiValue;
    }

    /**
     * Converts an API string value to the enum.
     * Supports backward compatibility with boolean values.
     *
     * @param value the API value ("none", "all", "aboveEntrance", "true", "false") or null
     * @return the corresponding enum value, defaults to NONE
     */
    public static EnsureBoundsMode fromApi(String value) {
        if (value == null || value.isEmpty()) {
            return NONE;
        }
        // Backward compatibility with boolean strings
        if ("true".equals(value)) {
            return ALL;
        }
        if ("false".equals(value)) {
            return NONE;
        }
        for (EnsureBoundsMode mode : values()) {
            if (mode.apiValue.equals(value)) {
                return mode;
            }
        }
        return NONE;
    }
}
