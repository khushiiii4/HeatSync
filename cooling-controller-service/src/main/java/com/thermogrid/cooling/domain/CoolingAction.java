package com.thermogrid.cooling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** One recorded change of output, mode or health (plan section 7). Kept 24 hours. */
@Entity
@Table(name = "cooling_action", indexes = @Index(name = "idx_action_zone", columnList = "zone_id, created_at"))
public class CoolingAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zone_id", nullable = false, length = 8)
    private String zoneId;

    @Column(name = "old_output_pct", nullable = false)
    private int oldOutputPct;

    @Column(name = "new_output_pct", nullable = false)
    private int newOutputPct;

    @Column(name = "mode", nullable = false, length = 10)
    private String mode;

    @Column(name = "reason", nullable = false, length = 160)
    private String reason;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant createdAt;

    protected CoolingAction() {
    }

    public CoolingAction(String zoneId, int oldOutputPct, int newOutputPct, String mode, String reason, Instant at) {
        this.zoneId = zoneId;
        this.oldOutputPct = oldOutputPct;
        this.newOutputPct = newOutputPct;
        this.mode = mode;
        this.reason = reason == null ? "" : reason.length() > 160 ? reason.substring(0, 160) : reason;
        this.createdAt = at;
    }

    public Long getId() { return id; }
    public String getZoneId() { return zoneId; }
    public int getOldOutputPct() { return oldOutputPct; }
    public int getNewOutputPct() { return newOutputPct; }
    public String getMode() { return mode; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
