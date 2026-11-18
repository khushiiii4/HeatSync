package com.thermogrid.simulator.failure;

import java.time.Instant;

/** One row of failure_injection. Active means {@code clearedAt == null}. */
public record FailureRecord(
        long id,
        FailureType type,
        String targetId,
        Double param,
        Instant startedAt,
        Instant endsAt,
        Instant clearedAt,
        String injectedBy) {

    public boolean active() {
        return clearedAt == null;
    }
}
