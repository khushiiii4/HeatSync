package com.thermogrid.telemetry.service;

import com.thermogrid.telemetry.config.TelemetryProperties;
import com.thermogrid.telemetry.domain.RackForecastState;
import com.thermogrid.telemetry.domain.RackReading;
import com.thermogrid.telemetry.forecast.ForecastEngine;
import com.thermogrid.telemetry.repo.RackForecastStateRepository;
import com.thermogrid.telemetry.repo.RackReadingRepository;
import com.thermogrid.telemetry.risk.RiskClassifier;
import com.thermogrid.telemetry.risk.RiskLevel;
import com.thermogrid.telemetry.service.ReadingValidator.RackSample;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One transaction per rack reading: lock the rack's state row, store the reading idempotently, advance the forecast
 * and risk, persist alert changes. Events are returned to the caller and published only after commit.
 */
@Service
public class RackStateUpdater {

    public enum Outcome {
        /** Reading stored and forecast state advanced. */
        APPLIED,
        /** (rackId, recordedAt) already stored: nothing changed. */
        DUPLICATE,
        /** Older than the newest applied reading: stored, forecast state untouched. */
        OUT_OF_ORDER
    }

    public record Result(Outcome outcome, Optional<AlertTransition> transition) {
    }

    private static final BigDecimal MAX_STORED = new BigDecimal("999.99");

    private final RackReadingRepository readings;
    private final RackForecastStateRepository states;
    private final AlertService alertService;
    private final ForecastEngine engine;
    private final RiskClassifier classifier;
    private final Clock clock;

    public RackStateUpdater(RackReadingRepository readings, RackForecastStateRepository states,
                            AlertService alertService, TelemetryProperties props, RiskClassifier classifier,
                            Clock clock) {
        this.readings = readings;
        this.states = states;
        this.alertService = alertService;
        this.engine = props.newForecastEngine();
        this.classifier = classifier;
        this.clock = clock;
    }

    @Transactional
    public Result process(RackSample s) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        RackForecastState state = states.lockByRackId(s.rackId()).orElse(null);

        if (readings.existsByRackIdAndRecordedAt(s.rackId(), s.recordedAt())) {
            return new Result(Outcome.DUPLICATE, Optional.empty());
        }
        readings.saveAndFlush(new RackReading(s.rackId(), s.recordedAt(), s.tempScaled(), s.powerKw(),
                s.cpuLoadPct()));

        if (state != null && !s.recordedAt().isAfter(state.getLastReadingAt())) {
            return new Result(Outcome.OUT_OF_ORDER, Optional.empty());
        }

        ForecastEngine.State previous = state == null ? null
                : new ForecastEngine.State(state.getLevelC(), state.getTrendC());
        ForecastEngine.Result f = engine.update(previous, s.tempC());
        double value = Math.max(s.tempC(), f.forecast());
        RiskLevel previousLevel = state == null ? RiskLevel.NORMAL : state.getRiskLevel();
        int previousCalm = state == null ? 0 : state.getCalmCount();
        RiskClassifier.Step step = classifier.next(previousLevel, previousCalm, value);

        BigDecimal forecastScaled = clamp(BigDecimal.valueOf(f.forecast()).setScale(2, RoundingMode.HALF_UP));
        if (state == null) {
            state = new RackForecastState(s.rackId(), s.zoneId(), now);
        }
        state.applyReading(s.zoneId(), s.recordedAt(), f.state().level(), f.state().trend(), s.tempScaled(),
                forecastScaled, s.powerKw(), s.cpuLoadPct(), step.level(), step.calmCount(), now);
        states.saveAndFlush(state);

        Optional<AlertTransition> transition = alertService.onRiskChange(s.rackId(), s.zoneId(), previousLevel,
                step.level(), s.tempC(), f.forecast(), s.tempScaled(), forecastScaled, s.recordedAt(), now);
        return new Result(Outcome.APPLIED, transition);
    }

    private static BigDecimal clamp(BigDecimal v) {
        if (v.compareTo(MAX_STORED) > 0) {
            return MAX_STORED;
        }
        if (v.compareTo(MAX_STORED.negate()) < 0) {
            return MAX_STORED.negate();
        }
        return v;
    }
}
