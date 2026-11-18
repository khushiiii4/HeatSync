package com.thermogrid.workload.service;

import com.thermogrid.workload.config.WorkloadProperties;
import com.thermogrid.workload.repo.WorkloadRepository;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Purges REMOVED workloads after the retention period (plan section 7: 7 days). */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final WorkloadRepository repo;
    private final WorkloadProperties props;
    private final Clock clock;

    public RetentionJob(WorkloadRepository repo, WorkloadProperties props, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${thermogrid.workload.retention.interval-ms}",
            initialDelayString = "${thermogrid.workload.retention.interval-ms}")
    public void scheduled() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("Retention run failed: {}", e.toString());
        }
    }

    @Transactional
    public int purge() {
        int n = repo.purgeRemovedBefore(clock.instant().minus(Duration.ofDays(props.retention().removedDays())));
        if (n > 0) {
            log.info("Purged {} REMOVED workloads", n);
        }
        return n;
    }
}
