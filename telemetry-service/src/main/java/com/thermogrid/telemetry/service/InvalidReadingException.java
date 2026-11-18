package com.thermogrid.telemetry.service;

/** A message that can never be processed (missing or impossible fields). Not retried: it goes to the DLQ at once. */
public class InvalidReadingException extends RuntimeException {
    public InvalidReadingException(String message) {
        super(message);
    }
}
