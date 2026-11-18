package com.thermogrid.telemetry.service;

import com.thermogrid.telemetry.config.TelemetryProperties;
import com.thermogrid.telemetry.domain.RackForecastState;
import com.thermogrid.telemetry.domain.SensorStatus;
import com.thermogrid.telemetry.repo.RackForecastStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Marks racks STALE when no accepted reading has arrived for the configured time (10 s). The forecast state itself is
 * not touched, which is what "frozen" means: level, trend and forecast stay as they were. Nothing is fabricated.
 * A rack that has never delivered a reading has no state row, so it is simply absent from the latest-telemetry
 * responses; there is nothing to mark. The next accepted reading restores OK (see {@link RackStateUpdater}).
 */
@Service
public class StaleSensorService {

    private static final Logger log = LoggerFactory.getLogger(StaleSensorService.class);

    private final RackForecastStateRepository states;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration staleAfter;

    public StaleSensorService(RackForecastStateRepository states, TransactionTemplate tx, TelemetryProperties props,
                              Clock clock) {
        this.states = states;
        this.tx = tx;
        this.clock = clock;
        this.staleAfter = Duration.ofSeconds(props.stale().afterSec());
    }

    /** @return the rack ids newly marked STALE by this run */
    public List<String> markStaleRacks() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant cutoff = now.minus(staleAfter);
        return states.findStaleCandidates(cutoff).stream().filter(id -> Boolean.TRUE.equals(tx.execute(st -> markOne(id, cutoff, now)))).toList();
    }

    private boolean markOne(String rackId, Instant cutoff, Instant now) {
        RackForecastState s = states.lockByRackId(rackId).orElse(null);
        // Re-check under the lock: a reading may have arrived since the candidate query.
        if (s == null || s.getSensorStatus() == SensorStatus.STALE || s.getUpdatedAt().isAfter(cutoff)) {
            return false;
        }
        s.markStale(now);
        states.saveAndFlush(s);
        log.warn("Rack {} marked STALE: no reading for {} s", rackId, staleAfter.toSeconds());
        return true;
    }
}
