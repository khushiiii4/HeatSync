package com.thermogrid.simulator.failure;

/** Exceptions of the failure API, mapped to 400, 404 and 409. */
public final class FailureExceptions {

    private FailureExceptions() {
    }

    public static class InvalidFailureException extends RuntimeException {
        public InvalidFailureException(String message) {
            super(message);
        }
    }

    public static class FailureNotFoundException extends RuntimeException {
        public FailureNotFoundException(long id) {
            super("Failure " + id + " not found");
        }
    }

    public static class FailureAlreadyClearedException extends RuntimeException {
        public FailureAlreadyClearedException(long id) {
            super("Failure " + id + " is already cleared");
        }
    }
}
