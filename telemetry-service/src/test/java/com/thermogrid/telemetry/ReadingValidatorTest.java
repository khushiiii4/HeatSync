package com.thermogrid.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.TelemetryRackReadingData;
import com.thermogrid.contracts.TelemetryZoneReadingData;
import com.thermogrid.telemetry.service.InvalidReadingException;
import com.thermogrid.telemetry.service.ReadingValidator;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReadingValidatorTest {

    private static final Instant AT = Instant.parse("2026-11-18T07:10:06.310987654Z");

    private static EventEnvelope<TelemetryRackReadingData> rack(String type, TelemetryRackReadingData d, Instant at) {
        return new EventEnvelope<>(UUID.randomUUID(), type, at, "SIMULATOR-SERVICE", 1, d);
    }

    private static EventEnvelope<TelemetryRackReadingData> rack(TelemetryRackReadingData d) {
        return rack("telemetry.rack.reading", d, AT);
    }

    private static EventEnvelope<TelemetryZoneReadingData> zone(TelemetryZoneReadingData d) {
        return new EventEnvelope<>(UUID.randomUUID(), "telemetry.zone.reading", AT, "SIMULATOR-SERVICE", 1, d);
    }

    private static void rejects(EventEnvelope<TelemetryRackReadingData> e, String fragment) {
        assertThatThrownBy(() -> ReadingValidator.rack(e)).isInstanceOf(InvalidReadingException.class)
                .hasMessageContaining(fragment);
    }

    @Test
    void aValidRackReadingIsMappedWithMillisecondTimeAndPlanScales() {
        var s = ReadingValidator.rack(rack(new TelemetryRackReadingData("R23", "Z2", 29.8449, 7.6, 72.55)));
        assertThat(s.recordedAt()).isEqualTo(Instant.parse("2026-11-18T07:10:06.310Z"));
        assertThat(s.tempScaled()).isEqualByComparingTo("29.84");
        assertThat(s.tempScaled().scale()).isEqualTo(2);
        assertThat(s.powerKw().scale()).isEqualTo(2);
        assertThat(s.cpuLoadPct()).isEqualByComparingTo("72.6");
        assertThat(s.cpuLoadPct().scale()).isEqualTo(1);
        assertThat(s.tempC()).isEqualTo(29.8449);
    }

    @Test
    void invalidRackReadingsAreRejected() {
        rejects(rack(null), "no data");
        rejects(rack("telemetry.rack.reading", new TelemetryRackReadingData("R23", "Z2", 26, 7, 70), null),
                "occurredAt");
        rejects(rack("telemetry.zone.reading", new TelemetryRackReadingData("R23", "Z2", 26, 7, 70), AT),
                "does not match");
        rejects(rack(new TelemetryRackReadingData(null, "Z2", 26, 7, 70)), "rackId");
        rejects(rack(new TelemetryRackReadingData("R23456789", "Z2", 26, 7, 70)), "rackId");
        rejects(rack(new TelemetryRackReadingData("R23", " ", 26, 7, 70)), "zoneId");
        rejects(rack(new TelemetryRackReadingData("R23", "Z2", Double.NaN, 7, 70)), "tempC");
        rejects(rack(new TelemetryRackReadingData("R23", "Z2", Double.POSITIVE_INFINITY, 7, 70)), "tempC");
        rejects(rack(new TelemetryRackReadingData("R23", "Z2", 151, 7, 70)), "tempC");
        rejects(rack(new TelemetryRackReadingData("R23", "Z2", -51, 7, 70)), "tempC");
        rejects(rack(new TelemetryRackReadingData("R23", "Z2", 26, -1, 70)), "powerKw");
        rejects(rack(new TelemetryRackReadingData("R23", "Z2", 26, 1000, 70)), "powerKw");
        rejects(rack(new TelemetryRackReadingData("R23", "Z2", 26, 7, -0.1)), "cpuLoadPct");
    }

    @Test
    void validRangeEdgesAreAccepted() {
        ReadingValidator.rack(rack(new TelemetryRackReadingData("R23", "Z2", -50, 0, 0)));
        ReadingValidator.rack(rack(new TelemetryRackReadingData("R23", "Z2", 150, 999.99, 999.9)));
    }

    @Test
    void aValidZoneReadingIsMappedAndDegradedHealthIsAccepted() {
        var s = ReadingValidator.zone(zone(new TelemetryZoneReadingData("Z2", 27.1, 29.84, 18.0, 10, "DEGRADED")));
        assertThat(s.zoneId()).isEqualTo("Z2");
        assertThat(s.avgTempC()).isEqualByComparingTo("27.10");
        assertThat(s.maxTempC()).isEqualByComparingTo("29.84");
        assertThat(s.supplyTempC()).isEqualByComparingTo("18.00");
        assertThat(s.fanActualPct()).isEqualTo(10);
        assertThat(s.unitHealth()).isEqualTo("DEGRADED");
    }

    @Test
    void invalidZoneReadingsAreRejected() {
        assertThatThrownBy(() -> ReadingValidator.zone(zone(new TelemetryZoneReadingData("Z2", 27, 26, 18, 10, "OK"))))
                .isInstanceOf(InvalidReadingException.class).hasMessageContaining("maxTempC");
        assertThatThrownBy(() -> ReadingValidator.zone(zone(new TelemetryZoneReadingData("Z2", 27, 28, 18, 101, "OK"))))
                .isInstanceOf(InvalidReadingException.class).hasMessageContaining("fanActualPct");
        assertThatThrownBy(() -> ReadingValidator.zone(zone(new TelemetryZoneReadingData("Z2", 27, 28, 18, -1, "OK"))))
                .isInstanceOf(InvalidReadingException.class);
        assertThatThrownBy(() -> ReadingValidator.zone(zone(new TelemetryZoneReadingData("Z2", 27, 28, 18, 10, null))))
                .isInstanceOf(InvalidReadingException.class).hasMessageContaining("unitHealth");
        assertThatThrownBy(() -> ReadingValidator.zone(zone(new TelemetryZoneReadingData("Z2", 27, 28, 18, 10, "TOO_LONG_X"))))
                .isInstanceOf(InvalidReadingException.class);
        assertThatThrownBy(() -> ReadingValidator.zone(zone(new TelemetryZoneReadingData("", 27, 28, 18, 10, "OK"))))
                .isInstanceOf(InvalidReadingException.class).hasMessageContaining("zoneId");
        assertThatThrownBy(() -> ReadingValidator.zone(zone(new TelemetryZoneReadingData("Z2", Double.NaN, 28, 18, 10, "OK"))))
                .isInstanceOf(InvalidReadingException.class);
    }
}
