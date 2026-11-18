package com.thermogrid.telemetry;

import static com.thermogrid.telemetry.TestSupport.T0;
import static org.assertj.core.api.Assertions.assertThat;

import com.thermogrid.telemetry.domain.AlertState;
import com.thermogrid.telemetry.service.IngestionService;
import com.thermogrid.telemetry.service.RackStateUpdater.Outcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Several consumer threads (as with 1 to 3 instances or concurrent listeners) hitting the same rack. */
class ConcurrencyTest extends IntegrationTestBase {

    @Autowired
    IngestionService ingestion;

    private <T> List<T> runTogether(int threads, java.util.function.IntFunction<Callable<T>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier start = new CyclicBarrier(threads);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<T> task = work.apply(i);
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSameReadingDeliveredToManyThreadsIsStoredOnceAndAppliedOnce() throws Exception {
        Instant at = T0.plusSeconds(2);
        List<Outcome> outcomes = runTogether(8,
                i -> () -> ingestion.ingestRack(TestSupport.rack("R31", "Z3", at, 41.0)));

        assertThat(rackReadings.countByRackId("R31")).isEqualTo(1);
        assertThat(states.count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.APPLIED)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.DUPLICATE)).hasSize(7);
        assertThat(alerts.findByRackIdAndState("R31", AlertState.ACTIVE)).as("one alert only").hasSize(1);
    }

    @Test
    void manyDistinctReadingsOfOneRackAreAllStoredAndTheStateEndsAtTheNewest() throws Exception {
        // the rack already exists, so every thread contends for the same state-row lock
        ingestion.ingestRack(TestSupport.rack("R32", "Z3", T0, 26.0));
        int n = 16;
        runTogether(n, i -> () -> ingestion.ingestRack(
                TestSupport.rack("R32", "Z3", T0.plusSeconds(2L * (i + 1)), 26.0 + (i % 3))));

        assertThat(rackReadings.countByRackId("R32")).isEqualTo(n + 1);
        assertThat(states.count()).isEqualTo(1);
        assertThat(states.findById("R32").orElseThrow().getLastReadingAt()).isEqualTo(T0.plusSeconds(2L * n));
        assertThat(alerts.findByRackIdAndState("R32", AlertState.ACTIVE).size()).isLessThanOrEqualTo(1);
    }

    @Test
    void concurrentFirstReadingsOfANewRackCreateExactlyOneStateRow() throws Exception {
        int n = 8;
        List<Outcome> outcomes = runTogether(n, i -> () -> ingestion.ingestRack(
                TestSupport.rack("R33", "Z3", T0.plusSeconds(2L * i), 26.0)));

        assertThat(outcomes).doesNotContainNull();
        assertThat(rackReadings.countByRackId("R33")).isEqualTo(n);
        assertThat(states.count()).isEqualTo(1);
        assertThat(states.findById("R33").orElseThrow().getLastReadingAt()).isEqualTo(T0.plusSeconds(2L * (n - 1)));
    }

    @Test
    void concurrentHotReadingsNeverProduceTwoActiveAlertsForOneRack() throws Exception {
        ingestion.ingestRack(TestSupport.rack("R34", "Z3", T0, 26.0));
        runTogether(12, i -> () -> ingestion.ingestRack(
                TestSupport.rack("R34", "Z3", T0.plusSeconds(2L * (i + 1)), 36.0 + i)));

        assertThat(alerts.findByRackIdAndState("R34", AlertState.ACTIVE)).hasSize(1);
        assertThat(alerts.count()).isEqualTo(1);
    }

    @Test
    void concurrentZoneDuplicatesAreStoredOnce() throws Exception {
        runTogether(8, i -> () -> ingestion.ingestZone(TestSupport.zone("Z3", T0, 27.0, 28.0, 40, "OK")));
        assertThat(zoneReadings.countByZoneId("Z3")).isEqualTo(1);
    }
}
