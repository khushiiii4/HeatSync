package com.thermogrid.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Column types, keys and indexes against the plan section 7 schema, read back from the generated tables
 * (H2 here; the real MySQL DDL is inspected in the runtime verification).
 */
class SchemaTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    private Map<String, Map<String, Object>> columns(String table) {
        return jdbc.queryForList("SELECT column_name, data_type, character_maximum_length, numeric_precision, "
                        + "numeric_scale, datetime_precision, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = ? ORDER BY ordinal_position", table.toUpperCase())
                .stream().collect(Collectors.toMap(r -> ((String) r.get("COLUMN_NAME")).toLowerCase(), r -> r,
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }

    private void decimal(Map<String, Map<String, Object>> cols, String name, int precision, int scale) {
        Map<String, Object> c = cols.get(name);
        assertThat(c).as(name).isNotNull();
        assertThat(c.get("DATA_TYPE").toString()).as(name).isIn("NUMERIC", "DECIMAL");
        assertThat(((Number) c.get("NUMERIC_PRECISION")).intValue()).as(name + " precision").isEqualTo(precision);
        assertThat(((Number) c.get("NUMERIC_SCALE")).intValue()).as(name + " scale").isEqualTo(scale);
    }

    private void varchar(Map<String, Map<String, Object>> cols, String name, int length) {
        assertThat(cols.get(name).get("DATA_TYPE")).as(name).isEqualTo("CHARACTER VARYING");
        assertThat(((Number) cols.get(name).get("CHARACTER_MAXIMUM_LENGTH")).intValue()).as(name).isEqualTo(length);
    }

    private void datetime3(Map<String, Map<String, Object>> cols, String name) {
        assertThat(cols.get(name).get("DATA_TYPE").toString()).as(name).startsWith("TIMESTAMP");
        assertThat(((Number) cols.get(name).get("DATETIME_PRECISION")).intValue()).as(name).isEqualTo(3);
    }

    private Set<String> uniqueColumnSets(String table) {
        return jdbc.queryForList("SELECT c.constraint_name, k.column_name FROM information_schema.table_constraints c "
                        + "JOIN information_schema.key_column_usage k ON c.constraint_name = k.constraint_name "
                        + "AND c.table_name = k.table_name WHERE c.table_name = ? AND c.constraint_type = 'UNIQUE' "
                        + "ORDER BY c.constraint_name, k.ordinal_position", table.toUpperCase())
                .stream().collect(Collectors.groupingBy(r -> r.get("CONSTRAINT_NAME"),
                        Collectors.mapping(r -> ((String) r.get("COLUMN_NAME")).toLowerCase(), Collectors.toList())))
                .values().stream().map(List::toString).collect(Collectors.toSet());
    }

    private List<String> indexedColumns(String table, String column) {
        return jdbc.queryForList("SELECT index_name FROM information_schema.index_columns "
                + "WHERE table_name = ? AND column_name = ?", String.class, table.toUpperCase(), column.toUpperCase());
    }

    @Test
    void rackReading() {
        var c = columns("rack_reading");
        assertThat(c.keySet()).containsExactlyInAnyOrder("id", "rack_id", "recorded_at", "temp_c", "power_kw", "cpu_load_pct");
        assertThat(c.get("id").get("DATA_TYPE")).isEqualTo("BIGINT");
        varchar(c, "rack_id", 8);
        datetime3(c, "recorded_at");
        decimal(c, "temp_c", 5, 2);
        decimal(c, "power_kw", 5, 2);
        decimal(c, "cpu_load_pct", 5, 1);
        assertThat(uniqueColumnSets("rack_reading")).containsExactly("[rack_id, recorded_at]");
        assertThat(indexedColumns("rack_reading", "recorded_at")).isNotEmpty();
    }

    @Test
    void zoneReading() {
        var c = columns("zone_reading");
        assertThat(c.keySet()).containsExactlyInAnyOrder("id", "zone_id", "recorded_at", "avg_temp_c", "max_temp_c",
                "supply_temp_c", "fan_actual_pct", "unit_health");
        varchar(c, "zone_id", 8);
        datetime3(c, "recorded_at");
        decimal(c, "avg_temp_c", 5, 2);
        decimal(c, "max_temp_c", 5, 2);
        decimal(c, "supply_temp_c", 5, 2);
        assertThat(c.get("fan_actual_pct").get("DATA_TYPE")).isEqualTo("INTEGER");
        // plan says VARCHAR(5); DEGRADED needs 8 (decision D-029)
        varchar(c, "unit_health", 8);
        assertThat(uniqueColumnSets("zone_reading")).containsExactly("[zone_id, recorded_at]");
    }

    @Test
    void rackForecastState() {
        var c = columns("rack_forecast_state");
        assertThat(c.keySet()).containsExactlyInAnyOrder("rack_id", "zone_id", "level_c", "trend_c", "last_reading_at",
                "temp_c", "forecast_60s_c", "power_kw", "cpu_load_pct", "risk_level", "calm_count", "sensor_status",
                "updated_at");
        varchar(c, "rack_id", 8);
        varchar(c, "zone_id", 8);
        assertThat(c.get("level_c").get("DATA_TYPE")).isEqualTo("DOUBLE PRECISION");
        assertThat(c.get("trend_c").get("DATA_TYPE")).isEqualTo("DOUBLE PRECISION");
        datetime3(c, "last_reading_at");
        decimal(c, "temp_c", 5, 2);
        decimal(c, "forecast_60s_c", 5, 2);
        decimal(c, "power_kw", 5, 2);
        decimal(c, "cpu_load_pct", 5, 1);
        varchar(c, "risk_level", 8);
        assertThat(c.get("calm_count").get("DATA_TYPE")).isEqualTo("INTEGER");
        varchar(c, "sensor_status", 5);
        datetime3(c, "updated_at");
        Integer pk = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.key_column_usage k JOIN "
                + "information_schema.table_constraints c ON c.constraint_name = k.constraint_name WHERE "
                + "c.table_name = 'RACK_FORECAST_STATE' AND c.constraint_type = 'PRIMARY KEY' AND "
                + "k.column_name = 'RACK_ID'", Integer.class);
        assertThat(pk).isEqualTo(1);
    }

    @Test
    void alert() {
        var c = columns("alert");
        assertThat(c.keySet()).containsExactlyInAnyOrder("id", "rack_id", "zone_id", "level", "basis", "temp_c", "forecast_c",
                "state", "raised_at", "updated_at", "cleared_at");
        assertThat(c.get("id").get("DATA_TYPE")).isEqualTo("BIGINT");
        varchar(c, "rack_id", 8);
        varchar(c, "zone_id", 8);
        varchar(c, "level", 8);
        varchar(c, "basis", 9);
        decimal(c, "temp_c", 5, 2);
        decimal(c, "forecast_c", 5, 2);
        varchar(c, "state", 7);
        datetime3(c, "raised_at");
        datetime3(c, "updated_at");
        datetime3(c, "cleared_at");
        assertThat(c.get("cleared_at").get("IS_NULLABLE")).isEqualTo("YES");
        List<String> stateIdx = jdbc.queryForList("SELECT k.column_name FROM information_schema.index_columns k "
                + "WHERE k.index_name = (SELECT index_name FROM information_schema.index_columns WHERE table_name = "
                + "'ALERT' AND column_name = 'STATE' LIMIT 1) AND k.table_name = 'ALERT' ORDER BY k.ordinal_position",
                String.class);
        assertThat(stateIdx).containsExactly("STATE", "RACK_ID");
    }
}
