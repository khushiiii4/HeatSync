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

/** One reading of one rack. (rack_id, recorded_at) is unique, which is the idempotency key (plan 8.6). */
@Entity
@Table(name = "rack_reading",
        uniqueConstraints = @UniqueConstraint(name = "uk_rack_reading_rack_time", columnNames = {"rack_id", "recorded_at"}),
        indexes = @Index(name = "idx_rack_reading_recorded_at", columnList = "recorded_at"))
public class RackReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rack_id", nullable = false, length = 8)
    private String rackId;

    @Column(name = "recorded_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant recordedAt;

    @Column(name = "temp_c", nullable = false, precision = 5, scale = 2)
    private BigDecimal tempC;

    @Column(name = "power_kw", nullable = false, precision = 5, scale = 2)
    private BigDecimal powerKw;

    @Column(name = "cpu_load_pct", nullable = false, precision = 5, scale = 1)
    private BigDecimal cpuLoadPct;

    protected RackReading() {
    }

    public RackReading(String rackId, Instant recordedAt, BigDecimal tempC, BigDecimal powerKw, BigDecimal cpuLoadPct) {
        this.rackId = rackId;
        this.recordedAt = recordedAt;
        this.tempC = tempC;
        this.powerKw = powerKw;
        this.cpuLoadPct = cpuLoadPct;
    }

    public Long getId() {
        return id;
    }

    public String getRackId() {
        return rackId;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public BigDecimal getTempC() {
        return tempC;
    }

    public BigDecimal getPowerKw() {
        return powerKw;
    }

    public BigDecimal getCpuLoadPct() {
        return cpuLoadPct;
    }
}
