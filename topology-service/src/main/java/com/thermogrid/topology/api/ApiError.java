package com.thermogrid.topology.api;

import java.time.Instant;

/** Standard error body (plan section 8.1). */
public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}