package com.thermogrid.telemetry.service;

import com.thermogrid.telemetry.api.Dtos;
import com.thermogrid.telemetry.config.TelemetryProperties;
import com.thermogrid.telemetry.domain.Alert;
import com.thermogrid.telemetry.domain.AlertState;
import com.thermogrid.telemetry.domain.RackReading;
import com.thermogrid.telemetry.forecast.ForecastEngine;
import com.thermogrid.telemetry.repo.AlertRepository;
import com.thermogrid.telemetry.repo.RackForecastStateRepository;
import com.thermogrid.telemetry.repo.RackReadingRepository;
import com.thermogrid.telemetry.repo.ZoneReadingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side. Everything here is read-only. */
@Service
@Transactional(readOnly = true)
public class TelemetryQueryService {

    public static class RackNotFoundException extends RuntimeException {
        public RackNotFoundException(String rackId) {
            super("No telemetry for rack " + rackId);
        }
    }

    public static class InvalidQueryException extends RuntimeException {
        public InvalidQueryException(String message) {
            super(message);
        }
    }

    private final RackForecastStateRepository states;
    private final RackReadingRepository readings;
    private final ZoneReadingRepository zones;
    private final AlertRepository alerts;
    private final TelemetryProperties props;
    private final ForecastEngine engine;
    private final Clock clock;

    public TelemetryQueryService(RackForecastStateRepository states, RackReadingRepository readings,
                                 ZoneReadingRepository zones, AlertRepository alerts, TelemetryProperties props,
                                 Clock clock) {
        this.states = states;
        this.readings = readings;
        this.zones = zones;
        this.alerts = alerts;
        this.props = props;
        this.engine = props.newForecastEngine();
        this.clock = clock;
    }

    /** Racks that have delivered at least one reading, by rack id. Racks never heard from are absent. */
    public List<Dtos.RackLatest> latestRacks() {
        int tpm = props.forecast().ticksPerMinute();
        return states.findAllByOrderByRackIdAsc().stream().map(s -> Dtos.RackLatest.from(s, tpm)).toList();
    }

    public List<Dtos.InternalRack> internalRacks() {
        int tpm = props.forecast().ticksPerMinute();
        return states.findAllByOrderByRackIdAsc().stream().map(s -> Dtos.InternalRack.from(s, tpm)).toList();
    }

    public List<Dtos.ZoneLatest> latestZones() {
        return zones.findLatestPerZone().stream().map(Dtos.ZoneLatest::from).toList();
    }

    /**
     * Temperature history of one rack. The table stores no forecast, so the forecast series is replayed through the
     * same {@link ForecastEngine} over the stored readings, starting a few readings before the window so it is settled.
     */
    public Dtos.History history(String rackId, int minutes) {
        if (minutes < 1 || minutes > props.history().maxMinutes()) {
            throw new InvalidQueryException("minutes must be between 1 and " + props.history().maxMinutes());
        }
        if (!states.existsById(rackId)) {
            throw new RackNotFoundException(rackId);
        }
        Instant from = clock.instant().minus(Duration.ofMinutes(minutes));
        long tickSec = Math.max(1, 60 / props.forecast().ticksPerMinute());
        Instant replayFrom = from.minusSeconds(tickSec * props.history().warmupReadings());

        List<Dtos.HistoryPoint> points = new ArrayList<>();
        ForecastEngine.State state = null;
        for (RackReading r : readings.findByRackIdAndRecordedAtGreaterThanEqualOrderByRecordedAtAsc(rackId,
                replayFrom)) {
            ForecastEngine.Result f = engine.update(state, r.getTempC().doubleValue());
            state = f.state();
            if (!r.getRecordedAt().isBefore(from)) {
                points.add(new Dtos.HistoryPoint(r.getRecordedAt(), r.getTempC(),
                        BigDecimal.valueOf(f.forecast()).setScale(2, RoundingMode.HALF_UP), r.getPowerKw(),
                        r.getCpuLoadPct()));
            }
        }
        return new Dtos.History(rackId, minutes, points);
    }

    /** @param state ACTIVE, CLEARED or ALL (case-insensitive) */
    public List<Dtos.AlertView> alerts(String state, int limit) {
        if (limit < 1 || limit > props.alerts().maxLimit()) {
            throw new InvalidQueryException("limit must be between 1 and " + props.alerts().maxLimit());
        }
        PageRequest page = PageRequest.of(0, limit);
        List<Alert> rows;
        String s = state == null ? "ACTIVE" : state.strip().toUpperCase();
        switch (s) {
            case "ALL" -> rows = alerts.findAllByOrderByIdDesc(page);
            case "ACTIVE" -> rows = alerts.findByStateOrderByIdDesc(AlertState.ACTIVE, page);
            case "CLEARED" -> rows = alerts.findByStateOrderByIdDesc(AlertState.CLEARED, page);
            default -> throw new InvalidQueryException("state must be ACTIVE, CLEARED or ALL");
        }
        return rows.stream().map(Dtos.AlertView::from).toList();
    }
}
