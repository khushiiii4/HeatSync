package com.thermogrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.thermogrid.simulator.failure.FailureRecord;
import com.thermogrid.simulator.failure.FailureRepository;
import com.thermogrid.simulator.failure.FailureType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Schema and persistence against a real H2 FILE database in a temp directory (never the real simdb). */
class FailurePersistenceTest {

    private static final Instant T0 = Instant.parse("2026-11-18T12:45:00Z");

    @TempDir
    Path dir;

    private DriverManagerDataSource fileDb() {
        return new DriverManagerDataSource("jdbc:h2:file:" + dir.resolve("simdb").toAbsolutePath(), "sa", "");
    }

    @Test
    void schemaMatchesThePlanColumnsAndIndex() {
        JdbcTemplate jdbc = TestSupport.withSchema(fileDb());

        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type, character_maximum_length, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'FAILURE_INJECTION' ORDER BY ordinal_position");
        assertThat(cols).extracting(c -> c.get("COLUMN_NAME")).containsExactly(
                "ID", "TYPE", "TARGET_ID", "PARAM", "STARTED_AT", "ENDS_AT", "CLEARED_AT", "INJECTED_BY");
        assertThat(cols.get(0).get("DATA_TYPE")).isEqualTo("BIGINT");
        assertThat(cols.get(1).get("DATA_TYPE")).isEqualTo("CHARACTER VARYING");
        assertThat(((Number) cols.get(1).get("CHARACTER_MAXIMUM_LENGTH")).intValue()).isEqualTo(20);
        assertThat(((Number) cols.get(2).get("CHARACTER_MAXIMUM_LENGTH")).intValue()).isEqualTo(8);
        assertThat(cols.get(3).get("DATA_TYPE")).isEqualTo("DOUBLE PRECISION");
        assertThat(cols.get(3).get("IS_NULLABLE")).isEqualTo("YES");
        assertThat(cols.get(4).get("DATA_TYPE")).isEqualTo("TIMESTAMP");
        assertThat(cols.get(5).get("IS_NULLABLE")).isEqualTo("YES");
        assertThat(cols.get(6).get("IS_NULLABLE")).isEqualTo("YES");
        assertThat(((Number) cols.get(7).get("CHARACTER_MAXIMUM_LENGTH")).intValue()).isEqualTo(50);

        Integer pk = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'FAILURE_INJECTION' AND constraint_type = 'PRIMARY KEY'", Integer.class);
        assertThat(pk).isEqualTo(1);
        List<String> indexed = jdbc.queryForList("SELECT column_name FROM information_schema.index_columns "
                + "WHERE table_name = 'FAILURE_INJECTION' AND column_name = 'CLEARED_AT'", String.class);
        assertThat(indexed).containsExactly("CLEARED_AT");
    }

    @Test
    void schemaScriptIsIdempotent() {
        DriverManagerDataSource ds = fileDb();
        TestSupport.withSchema(ds);
        TestSupport.withSchema(ds);
        TestSupport.withSchema(ds);
    }

    @Test
    void failuresSurviveCloseAndReopenOfTheFileDatabase() {
        FailureRepository first = new FailureRepository(TestSupport.withSchema(fileDb()));
        FailureRecord cooling = first.insert(FailureType.COOLING_FAILURE, "Z3", null, T0, null, "operator");
        FailureRecord spike = first.insert(FailureType.HEAT_SPIKE, "R23", 3.5, T0, T0.plusSeconds(60), "viewer");
        first.markCleared(spike.id(), T0.plusSeconds(60));
        // No connection is held by the first repository now, so the file database was closed.

        FailureRepository second = new FailureRepository(TestSupport.withSchema(fileDb()));
        assertThat(second.findActive()).containsExactly(cooling);
        assertThat(second.findAll()).hasSize(2);
        FailureRecord restored = second.findById(spike.id()).orElseThrow();
        assertThat(restored.type()).isEqualTo(FailureType.HEAT_SPIKE);
        assertThat(restored.param()).isEqualTo(3.5);
        assertThat(restored.startedAt()).isEqualTo(T0);
        assertThat(restored.endsAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(restored.clearedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(restored.injectedBy()).isEqualTo("viewer");
        assertThat(second.findById(cooling.id()).orElseThrow().param()).isNull();
    }

    @Test
    void idsAutoIncrementAndMarkClearedOnlyAffectsActiveRows() {
        FailureRepository repo = new FailureRepository(TestSupport.memoryDb());
        FailureRecord a = repo.insert(FailureType.SENSOR_DROPOUT, "R11", null, T0, null, "operator");
        FailureRecord b = repo.insert(FailureType.SENSOR_DROPOUT, "R12", null, T0, null, "operator");
        assertThat(b.id()).isEqualTo(a.id() + 1);

        assertThat(repo.markCleared(a.id(), T0.plusSeconds(5))).isTrue();
        assertThat(repo.markCleared(a.id(), T0.plusSeconds(99))).isFalse();
        assertThat(repo.findById(a.id()).orElseThrow().clearedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(repo.findActive()).extracting(FailureRecord::id).containsExactly(b.id());
        assertThat(repo.findById(12345)).isEmpty();
    }
}
