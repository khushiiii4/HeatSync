package com.thermogrid.telemetry.domain;

import com.thermogrid.telemetry.risk.RiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An alert on one rack. At most one ACTIVE alert per rack, enforced in AlertService under the rack's row lock. */
@Entity
@Table(name = "alert", indexes = @Index(name = "idx_alert_state_rack", columnList = "state, rack_id"))
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rack_id", nullable = false, length = 8)
    private String rackId;

    @Column(name = "zone_id", nullable = false, length = 8)
    private String zoneId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "level", nullable = false, length = 8)
    private RiskLevel level;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "basis", nullable = false, length = 9)
    private AlertBasis basis;

    @Column(name = "temp_c", nullable = false, precision = 5, scale = 2)
    private BigDecimal tempC;

    @Column(name = "forecast_c", nullable = false, precision = 5, scale = 2)
    private BigDecimal forecastC;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "state", nullable = false, length = 7)
    private AlertState state = AlertState.ACTIVE;

    @Column(name = "raised_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant raisedAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant updatedAt;

    @Column(name = "cleared_at", columnDefinition = "datetime(3)")
    private Instant clearedAt;

    protected Alert() {
    }

    public Alert(String rackId, String zoneId, RiskLevel level, AlertBasis basis, BigDecimal tempC,
                 BigDecimal forecastC, Instant now) {
        this.rackId = rackId;
        this.zoneId = zoneId;
        this.level = level;
        this.basis = basis;
        this.tempC = tempC;
        this.forecastC = forecastC;
        this.raisedAt = now;
        this.updatedAt = now;
    }

    public void update(RiskLevel level, AlertBasis basis, BigDecimal tempC, BigDecimal forecastC, Instant now) {
        this.level = level;
        this.basis = basis;
        this.tempC = tempC;
        this.forecastC = forecastC;
        this.updatedAt = now;
    }

    public void clear(BigDecimal tempC, BigDecimal forecastC, Instant now) {
        this.state = AlertState.CLEARED;
        this.tempC = tempC;
        this.forecastC = forecastC;
        this.updatedAt = now;
        this.clearedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getRackId() {
        return rackId;
    }

    public String getZoneId() {
        return zoneId;
    }

    public RiskLevel getLevel() {
        return level;
    }

    public AlertBasis getBasis() {
        return basis;
    }

    public BigDecimal getTempC() {
        return tempC;
    }

    public BigDecimal getForecastC() {
        return forecastC;
    }

    public AlertState getState() {
        return state;
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClearedAt() {
        return clearedAt;
    }
}
