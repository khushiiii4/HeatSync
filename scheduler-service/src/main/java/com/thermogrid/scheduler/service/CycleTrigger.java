package com.thermogrid.scheduler.service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs an event-triggered cycle on a single background thread so a RabbitMQ listener never waits for HTTP calls.
 * Requests are coalesced: while one is queued, further identical requests are dropped (the periodic loops cover any
 * remaining gap).
 */
@Component
public class CycleTrigger {

    private static final Logger log = LoggerFactory.getLogger(CycleTrigger.class);

    private final SchedulerCycles cycles;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "scheduler-trigger");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean placementQueued = new AtomicBoolean();
    private final AtomicBoolean reconcileQueued = new AtomicBoolean();

    public CycleTrigger(SchedulerCycles cycles) {
        this.cycles = cycles;
    }

    public void requestPlacement() {
        if (placementQueued.compareAndSet(false, true)) {
            executor.execute(() -> {
                placementQueued.set(false);
                run(cycles::placePending);
            });
        }
    }

    public void requestReconcile() {
        if (reconcileQueued.compareAndSet(false, true)) {
            executor.execute(() -> {
                reconcileQueued.set(false);
                run(cycles::reconcile);
            });
        }
    }

    private static void run(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("Triggered cycle failed: {}", e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
