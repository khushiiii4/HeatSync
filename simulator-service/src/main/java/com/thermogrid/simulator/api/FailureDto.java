package com.thermogrid.simulator.api;

import com.thermogrid.simulator.failure.FailureRecord;
import java.time.Instant;

/** A failure_injection row as returned by the API (ISO-8601 UTC timestamps). */
public record FailureDto(long id, String type, String targetId, Double param, Instant startedAt, Instant endsAt,
                         Instant clearedAt, String injectedBy) {

    public static FailureDto from(FailureRecord r) {
        return new FailureDto(r.id(), r.type().name(), r.targetId(), r.param(), r.startedAt(), r.endsAt(),
                r.clearedAt(), r.injectedBy());
    }
}
