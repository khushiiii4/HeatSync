package com.thermogrid.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One zone reading. {@code unit_health} is VARCHAR(8), not the plan's VARCHAR(5): the simulator also sends DEGRADED,
 * which is 8 characters (decision D-029).
 */
@Entity
@Table(name = "zone_reading",
        uniqueConstraints = @UniqueConstraint(name = "uk_zone_reading_zone_time", columnNames = {"zone_id", "recorded_at"}),
        indexes = @Index(name = "idx_zone_reading_recorded_at", columnList = "recorded_at"))
public class ZoneReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zone_id", nullable = false, length = 8)
    private String zoneId;

    @Column(name = "recorded_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant recordedAt;

    @Column(name = "avg_temp_c", nullable = false, precision = 5, scale = 2)
    private BigDecimal avgTempC;

    @Column(name = "max_temp_c", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxTempC;

    @Column(name = "supply_temp_c", nullable = false, precision = 5, scale = 2)
    private BigDecimal supplyTempC;

    @Column(name = "fan_actual_pct", nullable = false)
    private int fanActualPct;

    @Column(name = "unit_health", nullable = false, length = 8)
    private String unitHealth;

    protected ZoneReading() {
    }

    public ZoneReading(String zoneId, Instant recordedAt, BigDecimal avgTempC, BigDecimal maxTempC,
                       BigDecimal supplyTempC, int fanActualPct, String unitHealth) {
        this.zoneId = zoneId;
        this.recordedAt = recordedAt;
        this.avgTempC = avgTempC;
        this.maxTempC = maxTempC;
        this.supplyTempC = supplyTempC;
        this.fanActualPct = fanActualPct;
        this.unitHealth = unitHealth;
    }

    public Long getId() {
        return id;
    }

    public String getZoneId() {
        return zoneId;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public BigDecimal getAvgTempC() {
        return avgTempC;
    }

    public BigDecimal getMaxTempC() {
        return maxTempC;
    }

    public BigDecimal getSupplyTempC() {
        return supplyTempC;
    }

    public int getFanActualPct() {
        return fanActualPct;
    }

    public String getUnitHealth() {
        return unitHealth;
    }
}
