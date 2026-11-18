package com.thermogrid.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Last cooling state seen per zone, rebuilt from cooling.unit.state heartbeats (plan section 7). */
@Entity
@Table(name = "zone_cooling_state")
public class ZoneCoolingState {

    @Id
    @Column(name = "zone_id", length = 8, nullable = false)
    private String zoneId;

    @Column(name = "health", nullable = false, length = 5)
    private String health;

    @Column(name = "mode", nullable = false, length = 10)
    private String mode;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant updatedAt;

    protected ZoneCoolingState() {
    }

    public ZoneCoolingState(String zoneId, String health, String mode, Instant updatedAt) {
        this.zoneId = zoneId;
        this.health = health;
        this.mode = mode;
        this.updatedAt = updatedAt;
    }

    public String getZoneId() { return zoneId; }
    public String getHealth() { return health; }
    public String getMode() { return mode; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String health, String mode, Instant at) {
        this.health = health;
        this.mode = mode;
        this.updatedAt = at;
    }
}
