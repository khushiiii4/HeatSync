package com.thermogrid.simulator.failure;

import com.thermogrid.simulator.config.SimulatorProperties;
import com.thermogrid.simulator.failure.FailureExceptions.FailureAlreadyClearedException;
import com.thermogrid.simulator.failure.FailureExceptions.FailureNotFoundException;
import com.thermogrid.simulator.failure.FailureExceptions.InvalidFailureException;
import com.thermogrid.simulator.topology.RackGrid;
import com.thermogrid.simulator.topology.ZoneSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Source of truth for active failures: H2 holds the history, this class keeps the active set in memory and loads it
 * at startup. Expired failures are marked cleared in H2 (cleared_at = their ends_at).
 */
@Component
public class FailureRegistry implements FailureSource {

    private static final Logger log = LoggerFactory.getLogger(FailureRegistry.class);

    private final FailureRepository repo;
    private final RackGrid grid;
    private final SimulatorProperties.Failures limits;
    private final Clock clock;
    private final List<FailureRecord> active = new ArrayList<>();

    public FailureRegistry(FailureRepository repo, RackGrid grid, SimulatorProperties props, Clock clock) {
        this.repo = repo;
        this.grid = grid;
        this.limits = props.failures();
        this.clock = clock;
        List<FailureRecord> loaded = repo.findActive();
        active.addAll(loaded);
        int expired = expireDue(clock.instant());
        log.info("Failure registry loaded: {} active failure(s) restored from H2, {} already expired", active.size(),
                expired);
    }

    public synchronized FailureRecord inject(FailureType type, String targetId, Integer durationSec, Double param,
                                             String injectedBy) {
        validate(type, targetId, durationSec, param);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        int duration = durationSec == null ? 0 : durationSec;
        Instant endsAt = duration > 0 ? now.plusSeconds(duration) : null;
        String by = injectedBy == null || injectedBy.isBlank() ? "anonymous" : injectedBy.strip();
        if (by.length() > 50) {
            by = by.substring(0, 50);
        }
        FailureRecord rec = repo.insert(type, targetId, param, now, endsAt, by);
        active.add(rec);
        log.info("Failure injected: id={} {} on {} param={} endsAt={}", rec.id(), type, targetId, param, endsAt);
        return rec;
    }

    public synchronized FailureRecord clear(long id) {
        FailureRecord rec = repo.findById(id).orElseThrow(() -> new FailureNotFoundException(id));
        if (rec.clearedAt() != null) {
            throw new FailureAlreadyClearedException(id);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        repo.markCleared(id, now);
        active.removeIf(f -> f.id() == id);
        log.info("Failure cleared: id={}", id);
        return new FailureRecord(rec.id(), rec.type(), rec.targetId(), rec.param(), rec.startedAt(), rec.endsAt(), now,
                rec.injectedBy());
    }

    /** Active failures right now (expiring due ones first). */
    public synchronized List<FailureRecord> active() {
        return activeAt(clock.instant());
    }

    /** Whole log, newest data included, oldest first. */
    public synchronized List<FailureRecord> all() {
        expireDue(clock.instant());
        return repo.findAll();
    }

    @Override
    public synchronized List<FailureRecord> activeAt(Instant now) {
        expireDue(now);
        return List.copyOf(active);
    }

    @Override
    public synchronized int clearAll(Instant now) {
        Instant at = now.truncatedTo(ChronoUnit.MILLIS);
        int n = 0;
        for (FailureRecord f : active) {
            if (repo.markCleared(f.id(), at)) {
                n++;
            }
        }
        active.clear();
        return n;
    }

    private int expireDue(Instant now) {
        int n = 0;
        var it = active.iterator();
        while (it.hasNext()) {
            FailureRecord f = it.next();
            if (f.endsAt() != null && !f.endsAt().isAfter(now)) {
                repo.markCleared(f.id(), f.endsAt());
                it.remove();
                n++;
                log.info("Failure expired: id={} {} on {}", f.id(), f.type(), f.targetId());
            }
        }
        return n;
    }

    private void validate(FailureType type, String targetId, Integer durationSec, Double param) {
        if (type == null) {
            throw new InvalidFailureException("type is required: one of COOLING_FAILURE, COOLING_DEGRADED, "
                    + "SENSOR_DROPOUT, HEAT_SPIKE");
        }
        if (targetId == null || targetId.isBlank()) {
            throw new InvalidFailureException("targetId is required");
        }
        if (type.targetsZone()) {
            if (grid.zones().stream().map(ZoneSpec::id).noneMatch(targetId::equals)) {
                throw new InvalidFailureException(type + " needs a zone id, but '" + targetId + "' is not a zone");
            }
        } else if (grid.rack(targetId) == null) {
            throw new InvalidFailureException(type + " needs a rack id, but '" + targetId + "' is not a rack");
        }
        if (durationSec != null && (durationSec < 0 || durationSec > limits.maxDurationSec())) {
            throw new InvalidFailureException("durationSec must be between 0 (until cleared) and "
                    + limits.maxDurationSec());
        }
        switch (type) {
            case COOLING_DEGRADED -> {
                if (param == null || !(param > 0.0 && param < 1.0)) {
                    throw new InvalidFailureException(
                            "COOLING_DEGRADED needs param = remaining efficiency, greater than 0 and less than 1");
                }
            }
            case HEAT_SPIKE -> {
                if (param == null || !(param > 0.0 && param <= limits.maxHeatSpikeKw())) {
                    throw new InvalidFailureException("HEAT_SPIKE needs param = extra kW, greater than 0 and at most "
                            + limits.maxHeatSpikeKw());
                }
            }
            default -> {
                if (param != null) {
                    throw new InvalidFailureException(type + " does not take a param");
                }
            }
        }
    }
}
