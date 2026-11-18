package com.thermogrid.gateway;

/** Roles in ascending order of power: each role includes everything below it. */
public enum Role {
    VIEWER, OPERATOR, ADMIN;

    public boolean atLeast(Role minimum) {
        return this.ordinal() >= minimum.ordinal();
    }
}
