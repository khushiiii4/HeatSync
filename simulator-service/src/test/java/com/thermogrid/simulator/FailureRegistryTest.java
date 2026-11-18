package com.thermogrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thermogrid.simulator.TestSupport.MutableClock;
import com.thermogrid.simulator.config.SimulatorProperties;
import com.thermogrid.simulator.failure.FailureExceptions.FailureAlreadyClearedException;
import com.thermogrid.simulator.failure.FailureExceptions.FailureNotFoundException;
import com.thermogrid.simulator.failure.FailureExceptions.InvalidFailureException;
import com.thermogrid.simulator.failure.FailureRecord;
import com.thermogrid.simulator.failure.FailureRegistry;
import com.thermogrid.simulator.failure.FailureRepository;
import com.thermogrid.simulator.failure.FailureType;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FailureRegistryTest {

    private static final Instant T0 = Instant.parse("2026-11-18T12:45:00Z");

    private MutableClock clock;
    private JdbcTemplate jdbc;
    private FailureRepository repo;
    private FailureRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        jdbc = TestSupport.memoryDb();
        repo = new FailureRepository(jdbc);
        registry = newRegistry();
    }

    private FailureRegistry newRegistry() {
        return new FailureRegistry(repo, TestTopology.grid(), props(), clock);
    }

    private static SimulatorProperties props() {
        return new SimulatorProperties(2000, false, null, TestTopology.GRID, TestTopology.INITIAL,
                new SimulatorProperties.Physics(18.0, 0.06, 0.12, 0.05, 0.0, 0.10,
                        new SimulatorProperties.HealthFactors(1.0, 0.25, 0.0)),
                new SimulatorProperties.Publishing(false, "SIMULATOR-SERVICE", 1, 10000),
                new SimulatorProperties.Failures(86400, 50.0),
                new SimulatorProperties.Bootstrap(1, 1, 1));
    }

    private static void rejects(Runnable r, String fragment) {
        assertThatThrownBy(r::run).isInstanceOf(InvalidFailureException.class).hasMessageContaining(fragment);
    }

    // ---- validation ----

    @Test
    void rejectsInvalidTargets() {
        rejects(() -> registry.inject(FailureType.COOLING_FAILURE, "Z9", 0, null, "op"), "not a zone");
        rejects(() -> registry.inject(FailureType.COOLING_FAILURE, "R11", 0, null, "op"), "not a zone");
        rejects(() -> registry.inject(FailureType.COOLING_DEGRADED, "Z9", 0, 0.5, "op"), "not a zone");
        rejects(() -> registry.inject(FailureType.SENSOR_DROPOUT, "Z1", 0, null, "op"), "not a rack");
        rejects(() -> registry.inject(FailureType.HEAT_SPIKE, "R99", 0, 2.0, "op"), "not a rack");
        rejects(() -> registry.inject(FailureType.HEAT_SPIKE, null, 0, 2.0, "op"), "targetId is required");
        rejects(() -> registry.inject(FailureType.HEAT_SPIKE, " ", 0, 2.0, "op"), "targetId is required");
        rejects(() -> registry.inject(null, "R11", 0, 2.0, "op"), "type is required");
    }

    @Test
    void rejectsInvalidParameters() {
        rejects(() -> registry.inject(FailureType.COOLING_DEGRADED, "Z1", 0, null, "op"), "remaining efficiency");
        rejects(() -> registry.inject(FailureType.COOLING_DEGRADED, "Z1", 0, 0.0, "op"), "remaining efficiency");
        rejects(() -> registry.inject(FailureType.COOLING_DEGRADED, "Z1", 0, 1.0, "op"), "remaining efficiency");
        rejects(() -> registry.inject(FailureType.COOLING_DEGRADED, "Z1", 0, 1.5, "op"), "remaining efficiency");
        rejects(() -> registry.inject(FailureType.COOLING_DEGRADED, "Z1", 0, Double.NaN, "op"), "remaining efficiency");
        rejects(() -> registry.inject(FailureType.HEAT_SPIKE, "R11", 0, null, "op"), "extra kW");
        rejects(() -> registry.inject(FailureType.HEAT_SPIKE, "R11", 0, 0.0, "op"), "extra kW");
        rejects(() -> registry.inject(FailureType.HEAT_SPIKE, "R11", 0, -1.0, "op"), "extra kW");
        rejects(() -> registry.inject(FailureType.HEAT_SPIKE, "R11", 0, 50.5, "op"), "at most 50.0");
        rejects(() -> registry.inject(FailureType.COOLING_FAILURE, "Z1", 0, 0.5, "op"), "does not take a param");
        rejects(() -> registry.inject(FailureType.SENSOR_DROPOUT, "R11", 0, 1.0, "op"), "does not take a param");
        rejects(() -> registry.inject(FailureType.COOLING_FAILURE, "Z1", -1, null, "op"), "durationSec");
        rejects(() -> registry.inject(FailureType.COOLING_FAILURE, "Z1", 86401, null, "op"), "durationSec");
        assertThat(registry.all()).as("nothing stored for rejected requests").isEmpty();
    }

    // ---- activation, expiry, clearing ----

    @Test
    void injectionIsPersistedAndActiveWithNoEndForZeroDuration() {
        FailureRecord f = registry.inject(FailureType.COOLING_FAILURE, "Z3", 0, null, "operator");
        assertThat(f.id()).isPositive();
        assertThat(f.startedAt()).isEqualTo(T0);
        assertThat(f.endsAt()).isNull();
        assertThat(f.clearedAt()).isNull();
        assertThat(f.injectedBy()).isEqualTo("operator");
        assertThat(registry.active()).containsExactly(f);
        assertThat(repo.findActive()).containsExactly(f);
    }

    @Test
    void missingDurationMeansUntilCleared() {
        FailureRecord f = registry.inject(FailureType.COOLING_FAILURE, "Z3", null, null, null);
        assertThat(f.endsAt()).isNull();
        assertThat(f.injectedBy()).isEqualTo("anonymous");
    }

    @Test
    void positiveDurationSetsEndsAtAndExpiryIsPersistedAsCleared() {
        FailureRecord f = registry.inject(FailureType.HEAT_SPIKE, "R23", 30, 4.0, "operator");
        assertThat(f.endsAt()).isEqualTo(T0.plusSeconds(30));

        clock.advance(Duration.ofSeconds(29));
        assertThat(registry.activeAt(clock.instant())).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        assertThat(registry.activeAt(clock.instant())).isEmpty();
        assertThat(registry.active()).isEmpty();
        FailureRecord stored = repo.findById(f.id()).orElseThrow();
        assertThat(stored.clearedAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(repo.findActive()).isEmpty();
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    void clearPersistsClearedAtKeepsTheRowAndRejectsRepeats() {
        FailureRecord f = registry.inject(FailureType.SENSOR_DROPOUT, "R11", 0, null, "op");
        clock.advance(Duration.ofSeconds(10));

        FailureRecord cleared = registry.clear(f.id());

        assertThat(cleared.clearedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(repo.findById(f.id()).orElseThrow().clearedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(registry.active()).isEmpty();
        assertThat(registry.all()).extracting(FailureRecord::id).containsExactly(f.id());
        assertThatThrownBy(() -> registry.clear(f.id())).isInstanceOf(FailureAlreadyClearedException.class);
        assertThatThrownBy(() -> registry.clear(999)).isInstanceOf(FailureNotFoundException.class);
    }

    @Test
    void clearAllMarksEveryActiveFailureButKeepsHistory() {
        registry.inject(FailureType.COOLING_FAILURE, "Z1", 0, null, "op");
        registry.inject(FailureType.HEAT_SPIKE, "R11", 0, 2.0, "op");
        FailureRecord already = registry.inject(FailureType.SENSOR_DROPOUT, "R12", 0, null, "op");
        clock.advance(Duration.ofSeconds(3));
        registry.clear(already.id());
        clock.advance(Duration.ofSeconds(3));

        assertThat(registry.clearAll(clock.instant())).isEqualTo(2);

        assertThat(registry.active()).isEmpty();
        assertThat(registry.all()).hasSize(3).allSatisfy(r -> assertThat(r.clearedAt()).isNotNull());
        assertThat(repo.findById(already.id()).orElseThrow().clearedAt()).isEqualTo(T0.plusSeconds(3));
    }

    @Test
    void activeFailuresAreRestoredAtStartupAndDueOnesAreExpired() {
        FailureRecord forever = registry.inject(FailureType.COOLING_FAILURE, "Z2", 0, null, "op");
        FailureRecord shortLived = registry.inject(FailureType.HEAT_SPIKE, "R21", 10, 3.0, "op");
        FailureRecord longer = registry.inject(FailureType.COOLING_DEGRADED, "Z4", 600, 0.25, "op");

        clock.advance(Duration.ofSeconds(60));   // "restart" one minute later
        FailureRegistry restarted = newRegistry();

        assertThat(restarted.active()).extracting(FailureRecord::id).containsExactly(forever.id(), longer.id());
        assertThat(repo.findById(shortLived.id()).orElseThrow().clearedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(restarted.all()).hasSize(3);
    }
}
