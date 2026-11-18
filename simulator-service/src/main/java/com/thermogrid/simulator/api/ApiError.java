package com.thermogrid.simulator.api;

import java.time.Instant;

/** Same error body as topology-service. */
public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}
