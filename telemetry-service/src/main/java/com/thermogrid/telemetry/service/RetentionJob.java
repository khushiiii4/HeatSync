package com.thermogrid.telemetry.service;

import com.thermogrid.telemetry.config.TelemetryProperties;
import com.thermogrid.telemetry.repo.AlertRepository;
import com.thermogrid.telemetry.repo.RackReadingRepository;
import com.thermogrid.telemetry.repo.ZoneReadingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention (plan section 7): rack and zone readings 6 hours, CLEARED alerts 7 days. ACTIVE alerts and
 * rack_forecast_state are never deleted. Rows are removed in bounded batches, each in its own short transaction, with
 * a cap on batches per run so a large backlog never blocks the live path. A row exactly at the cutoff is kept.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    public record Result(int rackReadings, int zoneReadings, int alerts) {
    }

    private final RackReadingRepository rackReadings;
    private final ZoneReadingRepository zoneReadings;
    private final AlertRepository alerts;
    private final TransactionTemplate tx;
    private final TelemetryProperties.Retention cfg;
    private final Clock clock;

    public RetentionJob(RackReadingRepository rackReadings, ZoneReadingRepository zoneReadings,
                        AlertRepository alerts, TransactionTemplate tx, TelemetryProperties props, Clock clock) {
        this.rackReadings = rackReadings;
        this.zoneReadings = zoneReadings;
        this.alerts = alerts;
        this.tx = tx;
        this.cfg = props.retention();
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${thermogrid.telemetry.retention.interval-ms}",
            fixedDelayString = "${thermogrid.telemetry.retention.interval-ms}")
    void scheduledRun() {
        try {
            Result r = runOnce();
            if (r.rackReadings() + r.zoneReadings() + r.alerts() > 0) {
                log.info("Retention removed {} rack readings, {} zone readings, {} cleared alerts", r.rackReadings(),
                        r.zoneReadings(), r.alerts());
            }
        } catch (RuntimeException e) {
            log.error("Retention run failed", e);
        }
    }

    public Result runOnce() {
        Instant now = clock.instant();
        Instant readingCutoff = now.minus(Duration.ofHours(cfg.readingsHours()));
        Instant alertCutoff = now.minus(Duration.ofDays(cfg.alertsDays()));
        int racks = purge(page -> rackReadings.findIdsOlderThan(readingCutoff, page), rackReadings::deleteByIdIn);
        int zones = purge(page -> zoneReadings.findIdsOlderThan(readingCutoff, page), zoneReadings::deleteByIdIn);
        int alertRows = purge(page -> alerts.findClearedIdsOlderThan(alertCutoff, page), alerts::deleteByIdIn);
        return new Result(racks, zones, alertRows);
    }

    private int purge(Function<PageRequest, List<Long>> finder, Function<List<Long>, Integer> deleter) {
        int total = 0;
        for (int batch = 0; batch < cfg.maxBatchesPerRun(); batch++) {
            List<Long> ids = finder.apply(PageRequest.of(0, cfg.batchSize()));
            if (ids.isEmpty()) {
                break;
            }
            Integer deleted = tx.execute(st -> deleter.apply(ids));
            total += deleted == null ? 0 : deleted;
            if (ids.size() < cfg.batchSize()) {
                break;
            }
        }
        return total;
    }
}
