package com.thermogrid.telemetry.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link StaleSensorService} on a timer. Scheduling is switched on in {@code SchedulingConfig}. */
@Component
public class StaleSensorWatchdog {

    private static final Logger log = LoggerFactory.getLogger(StaleSensorWatchdog.class);

    private final StaleSensorService service;

    public StaleSensorWatchdog(StaleSensorService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${thermogrid.telemetry.stale.check-interval-ms}")
    void check() {
        try {
            service.markStaleRacks();
        } catch (RuntimeException e) {
            log.error("Stale-sensor check failed", e);
        }
    }
}
