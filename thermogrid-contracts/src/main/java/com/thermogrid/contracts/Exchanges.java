package com.thermogrid.contracts;

/** Exchange names (plan section 8.4). */
public final class Exchanges {
    /** Durable topic exchange carrying all domain events. */
    public static final String EVENTS = "thermogrid.events";
    /** Durable topic dead-letter exchange. */
    public static final String DEAD_LETTER = "thermogrid.dlx";

    private Exchanges() {
    }
}