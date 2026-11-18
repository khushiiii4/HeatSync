package com.thermogrid.contracts;

/** User roles and JWT claim names shared by auth-service (issues tokens) and the gateway (verifies them). */
public final class Roles {
    public static final String VIEWER = "VIEWER";
    public static final String OPERATOR = "OPERATOR";
    public static final String ADMIN = "ADMIN";

    /** JWT issuer written by auth-service and required by the gateway. */
    public static final String JWT_ISSUER = "thermogrid-auth";
    /** JWT claim carrying the role; the username is the standard {@code sub} claim. */
    public static final String JWT_ROLE_CLAIM = "role";

    private Roles() {
    }
}
