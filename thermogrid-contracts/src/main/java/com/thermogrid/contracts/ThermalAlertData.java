package com.thermogrid.contracts;

/**
 * Data shared by {@code thermal.alert.raised}, {@code thermal.alert.lowered} and
 * {@code thermal.alert.cleared}. {@code level} is the new absolute level; NORMAL means cleared.
 * {@code basis} is OBSERVED or PREDICTED.
 */
public record ThermalAlertData(
        long alertId,
        String rackId,
        String zoneId,
        String level,
        String previousLevel,
        String basis,
        double tempC,
        double forecast60C) {
}