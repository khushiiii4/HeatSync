package com.thermogrid.telemetry.api;

import com.thermogrid.telemetry.domain.Alert;
import com.thermogrid.telemetry.domain.RackForecastState;
import com.thermogrid.telemetry.domain.ZoneReading;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Response DTOs. JPA entities are never serialised. Timestamps are ISO-8601 UTC. */
public final class Dtos {

    private Dtos() {
    }

    /** One element of GET /api/telemetry/racks/latest (plan section 8.3). */
    public record RackLatest(String rackId, String zoneId, BigDecimal tempC, BigDecimal forecast60C,
                             double trendCPerMin, String riskLevel, String sensorStatus, BigDecimal powerKw,
                             BigDecimal cpuLoadPct, Instant updatedAt) {

        public static RackLatest from(RackForecastState s, int ticksPerMinute) {
            return new RackLatest(s.getRackId(), s.getZoneId(), s.getTempC(), s.getForecast60sC(),
                    trendPerMin(s, ticksPerMinute), s.getRiskLevel().name(), s.getSensorStatus().name(),
                    s.getPowerKw(), s.getCpuLoadPct(), s.getUpdatedAt());
        }
    }

    /** Internal view for the scheduler: the public fields plus the time of the last accepted reading. */
    public record InternalRack(String rackId, String zoneId, BigDecimal tempC, BigDecimal forecast60C,
                               double trendCPerMin, String riskLevel, String sensorStatus, BigDecimal powerKw,
                               BigDecimal cpuLoadPct, Instant lastReadingAt, Instant updatedAt) {

        public static InternalRack from(RackForecastState s, int ticksPerMinute) {
            return new InternalRack(s.getRackId(), s.getZoneId(), s.getTempC(), s.getForecast60sC(),
                    trendPerMin(s, ticksPerMinute), s.getRiskLevel().name(), s.getSensorStatus().name(),
                    s.getPowerKw(), s.getCpuLoadPct(), s.getLastReadingAt(), s.getUpdatedAt());
        }
    }

    public record ZoneLatest(String zoneId, BigDecimal avgTempC, BigDecimal maxTempC, BigDecimal supplyTempC,
                             int fanActualPct, String unitHealth, Instant recordedAt) {

        public static ZoneLatest from(ZoneReading z) {
            return new ZoneLatest(z.getZoneId(), z.getAvgTempC(), z.getMaxTempC(), z.getSupplyTempC(),
                    z.getFanActualPct(), z.getUnitHealth(), z.getRecordedAt());
        }
    }

    public record HistoryPoint(Instant recordedAt, BigDecimal tempC, BigDecimal forecast60C, BigDecimal powerKw,
                               BigDecimal cpuLoadPct) {
    }

    public record History(String rackId, int minutes, List<HistoryPoint> points) {
    }

    public record AlertView(long id, String rackId, String zoneId, String level, String basis, BigDecimal tempC,
                            BigDecimal forecastC, String state, Instant raisedAt, Instant updatedAt,
                            Instant clearedAt) {

        public static AlertView from(Alert a) {
            return new AlertView(a.getId(), a.getRackId(), a.getZoneId(), a.getLevel().name(), a.getBasis().name(),
                    a.getTempC(), a.getForecastC(), a.getState().name(), a.getRaisedAt(), a.getUpdatedAt(),
                    a.getClearedAt());
        }
    }

    public record ApiError(Instant timestamp, int status, String error, String message, String path) {
    }

    private static double trendPerMin(RackForecastState s, int ticksPerMinute) {
        return Math.round(s.getTrendC() * ticksPerMinute * 100.0) / 100.0;
    }
}
