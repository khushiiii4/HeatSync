package com.thermogrid.telemetry.domain;

import com.thermogrid.telemetry.risk.RiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.springframework.data.domain.Persistable;
import org.hibernate.type.SqlTypes;

/**
 * One row per rack: current forecast state and latest values. Always updated under a pessimistic row lock.
 *
 * <p>Implements {@link Persistable} so that a brand-new row is always INSERTed. With an assigned id, Spring Data would
 * otherwise MERGE, and two racing first readings could silently overwrite each other with older data instead of
 * failing on the primary key and being retried.
 */
@Entity
@Table(name = "rack_forecast_state")
public class RackForecastState implements Persistable<String> {

    @Id
    @Column(name = "rack_id", length = 8)
    private String rackId;

    @Column(name = "zone_id", nullable = false, length = 8)
    private String zoneId;

    @Column(name = "level_c", nullable = false)
    private double levelC;

    @Column(name = "trend_c", nullable = false)
    private double trendC;

    @Column(name = "last_reading_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant lastReadingAt;

    @Column(name = "temp_c", nullable = false, precision = 5, scale = 2)
    private BigDecimal tempC;

    @Column(name = "forecast_60s_c", nullable = false, precision = 5, scale = 2)
    private BigDecimal forecast60sC;

    @Column(name = "power_kw", nullable = false, precision = 5, scale = 2)
    private BigDecimal powerKw;

    @Column(name = "cpu_load_pct", nullable = false, precision = 5, scale = 1)
    private BigDecimal cpuLoadPct;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "risk_level", nullable = false, length = 8)
    private RiskLevel riskLevel = RiskLevel.NORMAL;

    @Column(name = "calm_count", nullable = false)
    private int calmCount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sensor_status", nullable = false, length = 5)
    private SensorStatus sensorStatus = SensorStatus.OK;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected RackForecastState() {
    }

    @Override
    public String getId() {
        return rackId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public RackForecastState(String rackId, String zoneId, Instant now) {
        this.rackId = rackId;
        this.zoneId = zoneId;
        this.updatedAt = now;
        this.isNew = true;
    }

    public String getRackId() {
        return rackId;
    }

    public String getZoneId() {
        return zoneId;
    }

    public double getLevelC() {
        return levelC;
    }

    public double getTrendC() {
        return trendC;
    }

    public Instant getLastReadingAt() {
        return lastReadingAt;
    }

    public BigDecimal getTempC() {
        return tempC;
    }

    public BigDecimal getForecast60sC() {
        return forecast60sC;
    }

    public BigDecimal getPowerKw() {
        return powerKw;
    }

    public BigDecimal getCpuLoadPct() {
        return cpuLoadPct;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public int getCalmCount() {
        return calmCount;
    }

    public SensorStatus getSensorStatus() {
        return sensorStatus;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** True until the first reading has been applied. */
    public boolean isEmpty() {
        return lastReadingAt == null;
    }

    public void applyReading(String zoneId, Instant readingAt, double levelC, double trendC, BigDecimal tempC,
                             BigDecimal forecast60sC, BigDecimal powerKw, BigDecimal cpuLoadPct, RiskLevel risk,
                             int calmCount, Instant now) {
        this.zoneId = zoneId;
        this.lastReadingAt = readingAt;
        this.levelC = levelC;
        this.trendC = trendC;
        this.tempC = tempC;
        this.forecast60sC = forecast60sC;
        this.powerKw = powerKw;
        this.cpuLoadPct = cpuLoadPct;
        this.riskLevel = risk;
        this.calmCount = calmCount;
        this.sensorStatus = SensorStatus.OK;
        this.updatedAt = now;
    }

    public void markStale(Instant now) {
        this.sensorStatus = SensorStatus.STALE;
        this.updatedAt = now;
    }
}
