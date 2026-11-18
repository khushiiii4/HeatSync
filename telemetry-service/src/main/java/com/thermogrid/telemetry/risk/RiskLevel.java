package com.thermogrid.telemetry.risk;

/** Declaration order is severity order, so {@code compareTo} orders levels. */
public enum RiskLevel {
    NORMAL, WATCH, WARNING, CRITICAL
}
