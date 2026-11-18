package com.thermogrid.simulator.api;

/** Body of POST /api/sim/failures. The type stays a String so an unknown value gets a clear 400 message. */
public record FailureRequest(String type, String targetId, Integer durationSec, Double param) {
}
