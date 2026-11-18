package com.thermogrid.simulator.failure;

import java.time.Instant;
import java.util.List;

/** What the engine needs from the failure registry. */
public interface FailureSource {

    FailureSource NONE = new FailureSource() {
        @Override
        public List<FailureRecord> activeAt(Instant now) {
            return List.of();
        }

        @Override
        public int clearAll(Instant now) {
            return 0;
        }
    };

    /** Expires anything due at {@code now} (persisting cleared_at) and returns what is still active. */
    List<FailureRecord> activeAt(Instant now);

    /** Marks every active failure cleared (RESET). History rows are kept. */
    int clearAll(Instant now);
}
