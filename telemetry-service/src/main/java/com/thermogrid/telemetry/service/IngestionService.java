package com.thermogrid.telemetry.service;

import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.TelemetryRackReadingData;
import com.thermogrid.contracts.TelemetryZoneReadingData;
import com.thermogrid.telemetry.service.RackStateUpdater.Outcome;
import com.thermogrid.telemetry.service.ReadingValidator.RackSample;
import com.thermogrid.telemetry.service.ReadingValidator.ZoneSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

/**
 * Entry point used by the RabbitMQ listeners. Not transactional itself: each reading is one short transaction in
 * {@link RackStateUpdater} or {@link ZoneReadingWriter}, and alert events are published only after that commit.
 *
 * <p>Any exception thrown from here means the reading was NOT durably handled, so the listener container retries and
 * eventually dead-letters the message instead of acknowledging it.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    /** Two instances racing on the first reading of a rack, or a lock timeout: one short in-process retry. */
    private static final int MAX_ATTEMPTS = 3;

    private final RackStateUpdater racks;
    private final ZoneReadingWriter zones;
    private final AlertEventPublisher publisher;

    public IngestionService(RackStateUpdater racks, ZoneReadingWriter zones, AlertEventPublisher publisher) {
        this.racks = racks;
        this.zones = zones;
        this.publisher = publisher;
    }

    public Outcome ingestRack(EventEnvelope<TelemetryRackReadingData> envelope) {
        RackSample sample = ReadingValidator.rack(envelope);
        RackStateUpdater.Result result = withRetry(() -> racks.process(sample));
        result.transition().ifPresent(publisher::publish);
        if (result.outcome() != Outcome.APPLIED) {
            log.debug("Rack reading {} {} -> {}", sample.rackId(), sample.recordedAt(), result.outcome());
        }
        return result.outcome();
    }

    /** @return true if a new row was stored, false for a duplicate */
    public boolean ingestZone(EventEnvelope<TelemetryZoneReadingData> envelope) {
        ZoneSample sample = ReadingValidator.zone(envelope);
        return withRetry(() -> zones.store(sample));
    }

    private <T> T withRetry(java.util.function.Supplier<T> work) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return work.get();
            } catch (DataIntegrityViolationException | TransientDataAccessException e) {
                last = e;
                log.debug("Concurrent write conflict, attempt {}/{}: {}", attempt, MAX_ATTEMPTS, e.toString());
            }
        }
        throw last;
    }
}
