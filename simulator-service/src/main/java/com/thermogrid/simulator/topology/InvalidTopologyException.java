package com.thermogrid.simulator.topology;

public class InvalidTopologyException extends RuntimeException {
    public InvalidTopologyException(String message) {
        super("Invalid topology from topology-service: " + message);
    }
}
