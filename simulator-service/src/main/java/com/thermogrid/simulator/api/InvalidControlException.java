package com.thermogrid.simulator.api;

public class InvalidControlException extends RuntimeException {
    public InvalidControlException(String message) {
        super(message);
    }
}
