package com.thermogrid.simulator.workload;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Startup bootstrap and periodic reconciliation from {@code GET /internal/workloads?status=RUNNING}. A single
 * scheduled check runs every {@code retry-ms}: until the first successful load it retries on every run, afterwards it
 * resyncs once per {@code reconcile-ms}. A failed call changes nothing (the last known state keeps driving the physics).
 */
@Component
public class WorkloadSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(WorkloadSynchronizer.class);

    private final WorkloadClient client;
    private final WorkloadLoads loads;
    private final WorkloadSyncProperties props;
    private final Clock clock;
    private Instant lastSuccess;
    private boolean failing;

    public WorkloadSynchronizer(WorkloadClient client, WorkloadLoads loads, WorkloadSyncProperties props, Clock clock) {
        this.client = client;
        this.loads = loads;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${thermogrid.simulator.workloads.retry-ms}", initialDelay = 0)
    void scheduled() {
        Instant now = clock.instant();
        boolean due;
        synchronized (this) {
            due = lastSuccess == null || !now.isBefore(lastSuccess.plusMillis(props.reconcileMs()));
        }
        if (due) {
            sync();
        }
    }

    /** @return true if the list was fetched and applied */
    public boolean sync() {
        Instant started = clock.instant();
        try {
            List<WorkloadView> running = client.workloads("RUNNING");
            loads.reconcile(running == null ? List.of() : running, started);
            synchronized (this) {
                lastSuccess = started;
                if (failing) {
                    log.info("workload-service reachable again; workloads resynchronised");
                    failing = false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            synchronized (this) {
                if (!failing) {
                    log.warn("Cannot load workloads from workload-service ({}); {}", e.toString(),
                            loads.isLoaded() ? "keeping the last known load"
                                    : "racks keep their static load until it is reachable");
                    failing = true;
                }
            }
            return false;
        }
    }
}
