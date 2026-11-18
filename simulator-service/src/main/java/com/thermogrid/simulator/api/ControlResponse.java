package com.thermogrid.simulator.api;

public record ControlResponse(String action, boolean paused, long tick) {
}
