package com.thermogrid.simulator;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns H2's "Database may be already in use" into a plain message. The simulator holds an exclusive lock on its H2
 * file for as long as it runs, which is what keeps it a singleton on one host / data directory.
 */
public class DuplicateInstanceFailureAnalyzer extends AbstractFailureAnalyzer<Throwable> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.contains("may be already in use")) {
                return new FailureAnalysis(
                        "Another simulator-service process already holds the simulator database lock "
                                + "(H2 file simdb). Only one simulator may run per data directory.",
                        "Stop the other simulator-service process, or, if none is running, check for a stale "
                                + "lock on the simdb.mv.db file in the data directory.",
                        cause);
            }
        }
        return null;
    }
}
