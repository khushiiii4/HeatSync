package com.thermogrid.topology.api;

public class RackNotFoundException extends RuntimeException {
    public RackNotFoundException(String rackId) {
        super("Rack " + rackId + " not found");
    }
}