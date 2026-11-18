package com.thermogrid.cooling.control;

import java.time.Duration;
import java.time.Instant;

/**
 * Per-zone fail-safe state (plan 6E): no accepted reading for 10 s enters FAILSAFE; three fresh readings leave it.
 * Pure and clock-free: the caller passes the time. The timeout boundary is inclusive (exactly 10 s without a reading is
 * a timeout) and entering FAILSAFE happens once, so an unchanged watchdog result is never reported twice.
 */
public class FailSafeWatchdog {

    private final Duration timeout;
    private final int recoveryReadings;
    private Instant lastReadingAt;
    private boolean active;
    private int freshReadings;

    public FailSafeWatchdog(CoolingParams p, Instant start) {
        this.timeout = Duration.ofSeconds(p.failsafeAfterSec());
        this.recoveryReadings = p.recoveryReadings();
        this.lastReadingAt = start;
    }

    /** @return true exactly once per outage: the moment FAILSAFE is entered */
    public boolean check(Instant now) {
        if (!active && !now.isBefore(lastReadingAt.plus(timeout))) {
            active = true;
            freshReadings = 0;
            return true;
        }
        return false;
    }

    /**
     * Registers an accepted (fresh, new) reading.
     *
     * @return true if this reading completed the recovery from FAILSAFE
     */
    public boolean onReading(Instant receivedAt) {
        lastReadingAt = receivedAt;
        if (!active) {
            return false;
        }
        freshReadings++;
        if (freshReadings >= recoveryReadings) {
            active = false;
            freshReadings = 0;
            return true;
        }
        return false;
    }

    public boolean isActive() {
        return active;
    }

    public int freshReadings() {
        return freshReadings;
    }

    public Instant lastReadingAt() {
        return lastReadingAt;
    }

    /** Restores FAILSAFE from persisted state at startup. */
    public void restoreActive(boolean failsafe) {
        this.active = failsafe;
        this.freshReadings = 0;
    }
}
