package com.thermogrid.scheduler.service;

import com.thermogrid.scheduler.config.SchedulerProperties;
import com.thermogrid.scheduler.domain.Repositories.ProcessedEventRepository;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keeps processed_event small: ids older than the retention window can no longer be redelivered. */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final ProcessedEventRepository repo;
    private final SchedulerProperties props;
    private final Clock clock;

    public RetentionJob(ProcessedEventRepository repo, SchedulerProperties props, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${thermogrid.scheduler.retention-interval-ms}",
            initialDelayString = "${thermogrid.scheduler.retention-interval-ms}")
    void scheduled() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("Retention run failed: {}", e.toString());
        }
    }

    @Transactional
    public int purge() {
        int n = repo.deleteOlderThan(clock.instant().minus(Duration.ofHours(props.processedEventRetentionHours())));
        if (n > 0) {
            log.info("Purged {} processed_event rows", n);
        }
        return n;
    }
}
