package com.thermogrid.workload.service;

/** Domain errors mapped to HTTP status codes by ApiExceptionHandler. */
public final class WorkloadExceptions {

    private WorkloadExceptions() {
    }

    /** 404 */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(long id) {
            super("Workload " + id + " not found");
        }
    }

    /** 409: the expected version or source rack no longer matches. Nothing was applied. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** 422: the workload is REMOVED and can no longer be assigned. */
    public static class RemovedException extends RuntimeException {
        public RemovedException(long id) {
            super("Workload " + id + " is REMOVED and cannot be assigned");
        }
    }

    /** 400 */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }
}
