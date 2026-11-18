package com.thermogrid.contracts;

/** HTTP header names (plan sections 8.1 and 8.2). */
public final class Headers {
    /** Shared token required on every {@code /internal/**} call. */
    public static final String INTERNAL_TOKEN = "X-Internal-Token";
    /** Set by the gateway from the verified JWT; client-supplied values are stripped. */
    public static final String USER_NAME = "X-User-Name";
    /** Set by the gateway from the verified JWT; client-supplied values are stripped. */
    public static final String USER_ROLE = "X-User-Role";

    private Headers() {
    }
}