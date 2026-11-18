package com.thermogrid.cooling.control;

/** Highest active alert level in a zone, lowest to highest. */
public enum AlertLevel {
    NONE, WATCH, WARNING, CRITICAL;

    /** Maps the contract's level string ("NORMAL" means cleared). Unknown values count as NONE. */
    public static AlertLevel of(String level) {
        if (level == null) {
            return NONE;
        }
        return switch (level) {
            case "WATCH" -> WATCH;
            case "WARNING" -> WARNING;
            case "CRITICAL" -> CRITICAL;
            default -> NONE;
        };
    }

    public boolean atLeast(AlertLevel other) {
        return compareTo(other) >= 0;
    }
}
