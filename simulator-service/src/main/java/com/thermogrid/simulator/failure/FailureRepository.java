package com.thermogrid.simulator.failure;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** H2 access for failure_injection. Timestamps are stored as UTC TIMESTAMP values. */
@Repository
public class FailureRepository {

    private static final RowMapper<FailureRecord> MAPPER = FailureRepository::map;

    private final JdbcTemplate jdbc;

    public FailureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public FailureRecord insert(FailureType type, String targetId, Double param, Instant startedAt, Instant endsAt,
                                String injectedBy) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO failure_injection (type, target_id, param, started_at, ends_at, injected_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, type.name());
            ps.setString(2, targetId);
            if (param == null) {
                ps.setNull(3, Types.DOUBLE);
            } else {
                ps.setDouble(3, param);
            }
            ps.setObject(4, utc(startedAt));
            ps.setObject(5, utc(endsAt));
            ps.setString(6, injectedBy);
            return ps;
        }, keys);
        long id = ((Number) keys.getKeys().get("ID")).longValue();
        return new FailureRecord(id, type, targetId, param, startedAt, endsAt, null, injectedBy);
    }

    public Optional<FailureRecord> findById(long id) {
        return jdbc.query("SELECT * FROM failure_injection WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<FailureRecord> findActive() {
        return jdbc.query("SELECT * FROM failure_injection WHERE cleared_at IS NULL ORDER BY id", MAPPER);
    }

    public List<FailureRecord> findAll() {
        return jdbc.query("SELECT * FROM failure_injection ORDER BY id", MAPPER);
    }

    /** @return true if this call cleared the row (it was still active) */
    public boolean markCleared(long id, Instant clearedAt) {
        return jdbc.update("UPDATE failure_injection SET cleared_at = ? WHERE id = ? AND cleared_at IS NULL",
                utc(clearedAt), id) == 1;
    }

    private static LocalDateTime utc(Instant i) {
        return i == null ? null : LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        LocalDateTime t = rs.getObject(col, LocalDateTime.class);
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    private static FailureRecord map(ResultSet rs, int row) throws SQLException {
        double param = rs.getDouble("param");
        Double p = rs.wasNull() ? null : param;
        return new FailureRecord(rs.getLong("id"), FailureType.valueOf(rs.getString("type")),
                rs.getString("target_id"), p, instant(rs, "started_at"), instant(rs, "ends_at"),
                instant(rs, "cleared_at"), rs.getString("injected_by"));
    }
}
