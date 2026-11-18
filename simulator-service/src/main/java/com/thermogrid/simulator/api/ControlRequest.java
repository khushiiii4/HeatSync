package com.thermogrid.simulator.api;

/** Body of POST /api/sim/control: {"action": "PAUSE" or "RESUME" or "RESET"}. */
public record ControlRequest(String action) {
}
