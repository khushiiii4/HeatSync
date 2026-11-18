package com.thermogrid.telemetry;

import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.contracts.TelemetryRackReadingData;
import com.thermogrid.contracts.TelemetryZoneReadingData;
import com.thermogrid.telemetry.config.TelemetryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

/** Shared test helpers: a movable clock, plan-value properties and envelope builders. */
public final class TestSupport {

    public static final Instant T0 = Instant.parse("2026-11-18T07:00:00Z");

    private TestSupport() {
    }

    public static final class MutableClock extends Clock {
        private volatile Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void set(Instant t) {
            now = t;
        }

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Plan values written out on purpose, independent of application.yml. */
    public static TelemetryProperties props() {
        return props(new TelemetryProperties.Retention(6, 7, 5000, 20, 600000));
    }

    public static TelemetryProperties props(TelemetryProperties.Retention retention) {
        return new TelemetryProperties(false, false,
                new TelemetryProperties.Forecast(0.4, 0.15, 0.95, 30, 30),
                new TelemetryProperties.Risk(30.0, 34.0, 38.0, 2.0, 5),
                new TelemetryProperties.Stale(10, 2000),
                retention,
                new TelemetryProperties.History(360, 60),
                new TelemetryProperties.Alerts(500, 3, 1),
                new TelemetryProperties.Amqp(3, 1000, 2.0, 4000, 50000, 20000),
                "TELEMETRY-SERVICE", 1);
    }

    public static EventEnvelope<TelemetryRackReadingData> rack(String rackId, String zoneId, Instant at, double temp) {
        return rack(rackId, zoneId, at, temp, 7.6, 72.5);
    }

    public static EventEnvelope<TelemetryRackReadingData> rack(String rackId, String zoneId, Instant at, double temp,
                                                               double power, double cpu) {
        return new EventEnvelope<>(UUID.randomUUID(), RoutingKeys.TELEMETRY_RACK_READING, at, "SIMULATOR-SERVICE", 1,
                new TelemetryRackReadingData(rackId, zoneId, temp, power, cpu));
    }

    public static EventEnvelope<TelemetryZoneReadingData> zone(String zoneId, Instant at, double avg, double max,
                                                               int fan, String health) {
        return new EventEnvelope<>(UUID.randomUUID(), RoutingKeys.TELEMETRY_ZONE_READING, at, "SIMULATOR-SERVICE", 1,
                new TelemetryZoneReadingData(zoneId, avg, max, 18.0, fan, health));
    }
}
