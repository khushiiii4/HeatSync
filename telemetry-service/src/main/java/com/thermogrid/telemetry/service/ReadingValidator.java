package com.thermogrid.telemetry.service;

import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.contracts.TelemetryRackReadingData;
import com.thermogrid.contracts.TelemetryZoneReadingData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Validates incoming envelopes and maps them to storage-ready samples (millisecond time, DECIMAL scales). */
public final class ReadingValidator {

    /** Plausible physical range. Anything outside is a sensor or producer bug, not data. */
    /** Only schema version 1 of the readings is understood (plan 8.5). */
    static final int SUPPORTED_VERSION = 1;
    static final double MIN_TEMP_C = -50.0;
    static final double MAX_TEMP_C = 150.0;
    static final double MAX_POWER_KW = 999.99;
    static final double MAX_PCT = 999.9;

    public record RackSample(String rackId, String zoneId, Instant recordedAt, double tempC, BigDecimal tempScaled,
                             BigDecimal powerKw, BigDecimal cpuLoadPct) {
    }

    public record ZoneSample(String zoneId, Instant recordedAt, BigDecimal avgTempC, BigDecimal maxTempC,
                             BigDecimal supplyTempC, int fanActualPct, String unitHealth) {
    }

    private ReadingValidator() {
    }

    public static RackSample rack(EventEnvelope<TelemetryRackReadingData> env) {
        TelemetryRackReadingData d = common(env, RoutingKeys.TELEMETRY_RACK_READING);
        id("rackId", d.rackId());
        id("zoneId", d.zoneId());
        range("tempC", d.tempC(), MIN_TEMP_C, MAX_TEMP_C);
        range("powerKw", d.powerKw(), 0.0, MAX_POWER_KW);
        range("cpuLoadPct", d.cpuLoadPct(), 0.0, MAX_PCT);
        return new RackSample(d.rackId(), d.zoneId(), time(env), d.tempC(), scale(d.tempC(), 2),
                scale(d.powerKw(), 2), scale(d.cpuLoadPct(), 1));
    }

    public static ZoneSample zone(EventEnvelope<TelemetryZoneReadingData> env) {
        TelemetryZoneReadingData d = common(env, RoutingKeys.TELEMETRY_ZONE_READING);
        id("zoneId", d.zoneId());
        range("avgTempC", d.avgTempC(), MIN_TEMP_C, MAX_TEMP_C);
        range("maxTempC", d.maxTempC(), MIN_TEMP_C, MAX_TEMP_C);
        range("supplyTempC", d.supplyTempC(), MIN_TEMP_C, MAX_TEMP_C);
        if (d.maxTempC() < d.avgTempC() - 0.01) {
            throw new InvalidReadingException("maxTempC is below avgTempC");
        }
        if (d.fanActualPct() < 0 || d.fanActualPct() > 100) {
            throw new InvalidReadingException("fanActualPct must be 0 to 100");
        }
        if (d.unitHealth() == null || d.unitHealth().isBlank() || d.unitHealth().length() > 8) {
            throw new InvalidReadingException("unitHealth is missing or longer than 8 characters");
        }
        return new ZoneSample(d.zoneId(), time(env), scale(d.avgTempC(), 2), scale(d.maxTempC(), 2),
                scale(d.supplyTempC(), 2), d.fanActualPct(), d.unitHealth());
    }

    private static <T> T common(EventEnvelope<T> env, String expectedType) {
        if (env == null || env.data() == null) {
            throw new InvalidReadingException("Message has no data");
        }
        if (env.eventId() == null) {
            throw new InvalidReadingException("eventId is missing");
        }
        if (env.occurredAt() == null) {
            throw new InvalidReadingException("occurredAt is missing");
        }
        if (env.version() != SUPPORTED_VERSION) {
            throw new InvalidReadingException("Unsupported event version " + env.version());
        }
        if (!expectedType.equals(env.eventType())) {
            throw new InvalidReadingException("eventType " + env.eventType() + " does not match " + expectedType);
        }
        return env.data();
    }

    private static Instant time(EventEnvelope<?> env) {
        return env.occurredAt().truncatedTo(ChronoUnit.MILLIS);   // DATETIME(3) precision
    }

    private static void id(String field, String value) {
        if (value == null || value.isBlank() || value.length() > 8) {
            throw new InvalidReadingException(field + " is missing or longer than 8 characters");
        }
    }

    private static void range(String field, double v, double min, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < min || v > max) {
            throw new InvalidReadingException(field + " is not a valid number in " + min + ".." + max + ": " + v);
        }
    }

    private static BigDecimal scale(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }
}
