package com.thermogrid.cooling.control;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides whether a zone reading may be used. A reading is rejected if it is not newer than the last accepted one (a
 * duplicate or out-of-order delivery) or if it is older than {@code staleReadingSec} when it arrives (a backlog replay
 * after an outage). Rejected readings count for nothing: not for control, not for fault debounce, not for recovery.
 */
public class ReadingGate {

    private final Duration maxAge;
    private Instant lastAccepted;

    public ReadingGate(CoolingParams p) {
        this.maxAge = Duration.ofSeconds(p.staleReadingSec());
    }

    public boolean accept(Instant occurredAt, Instant now) {
        if (lastAccepted != null && !occurredAt.isAfter(lastAccepted)) {
            return false;
        }
        if (Duration.between(occurredAt, now).compareTo(maxAge) > 0) {
            return false;
        }
        lastAccepted = occurredAt;
        return true;
    }
}
