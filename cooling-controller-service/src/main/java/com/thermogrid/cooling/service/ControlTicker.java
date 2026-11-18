package com.thermogrid.cooling.service;

import com.thermogrid.cooling.config.CoolingProperties;
import com.thermogrid.cooling.domain.Repositories.CoolingActionRepository;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Scheduled work: the 1 s watchdog and heartbeat tick, and the 24 h retention of cooling_action. */
@Component
public class ControlTicker {

    private static final Logger log = LoggerFactory.getLogger(ControlTicker.class);

    private final CoolingControlService control;
    private final CoolingActionRepository actions;
    private final CoolingProperties props;
    private final Clock clock;

    public ControlTicker(CoolingControlService control, CoolingActionRepository actions, CoolingProperties props,
                         Clock clock) {
        this.control = control;
        this.actions = actions;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${thermogrid.cooling.tick-ms}", initialDelayString = "${thermogrid.cooling.tick-ms}")
    void tick() {
        try {
            control.tick();
        } catch (RuntimeException e) {
            log.warn("Control tick failed: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${thermogrid.cooling.retention-interval-ms}",
            initialDelayString = "${thermogrid.cooling.retention-interval-ms}")
    void retention() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("Retention run failed: {}", e.toString());
        }
    }

    @Transactional
    public int purge() {
        int n = actions.deleteOlderThan(clock.instant().minus(Duration.ofHours(props.actionRetentionHours())));
        if (n > 0) {
            log.info("Purged {} cooling_action rows", n);
        }
        return n;
    }
}
