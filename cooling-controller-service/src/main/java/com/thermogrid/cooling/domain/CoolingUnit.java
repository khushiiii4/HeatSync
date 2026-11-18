package com.thermogrid.cooling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** One cooling unit per zone (plan section 7). {@code seq} increases on every published state message. */
@Entity
@Table(name = "cooling_unit")
public class CoolingUnit {

    @Id
    @Column(name = "zone_id", length = 8, nullable = false)
    private String zoneId;

    @Column(name = "mode", nullable = false, length = 10)
    private String mode;

    @Column(name = "output_pct", nullable = false)
    private int outputPct;

    @Column(name = "target_temp_c", nullable = false, precision = 4, scale = 1)
    private BigDecimal targetTempC;

    @Column(name = "health", nullable = false, length = 5)
    private String health;

    @Column(name = "floor_pct", nullable = false)
    private int floorPct = 30;

    @Column(name = "hold_until", columnDefinition = "datetime(3)")
    private Instant holdUntil;

    @Column(name = "last_reading_at", columnDefinition = "datetime(3)")
    private Instant lastReadingAt;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant updatedAt;

    protected CoolingUnit() {
    }

    public CoolingUnit(String zoneId, String mode, int outputPct, BigDecimal targetTempC, String health, int floorPct,
                       Instant now) {
        this.zoneId = zoneId;
        this.mode = mode;
        this.outputPct = outputPct;
        this.targetTempC = targetTempC;
        this.health = health;
        this.floorPct = floorPct;
        this.updatedAt = now;
    }

    public String getZoneId() { return zoneId; }
    public String getMode() { return mode; }
    public int getOutputPct() { return outputPct; }
    public BigDecimal getTargetTempC() { return targetTempC; }
    public String getHealth() { return health; }
    public int getFloorPct() { return floorPct; }
    public Instant getHoldUntil() { return holdUntil; }
    public Instant getLastReadingAt() { return lastReadingAt; }
    public long getSeq() { return seq; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setMode(String mode) { this.mode = mode; }
    public void setOutputPct(int outputPct) { this.outputPct = outputPct; }
    public void setHealth(String health) { this.health = health; }
    public void setFloorPct(int floorPct) { this.floorPct = floorPct; }
    public void setHoldUntil(Instant holdUntil) { this.holdUntil = holdUntil; }
    public void setLastReadingAt(Instant lastReadingAt) { this.lastReadingAt = lastReadingAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    /** Next sequence number for a published message. */
    public long nextSeq() {
        return ++seq;
    }
}
