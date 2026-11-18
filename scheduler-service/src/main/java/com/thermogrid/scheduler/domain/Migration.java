package com.thermogrid.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** One placement or migration attempt (plan section 7). The id is the migrationId sent to workload-service. */
@Entity
@Table(name = "migration", indexes = {
        @Index(name = "idx_migration_workload", columnList = "workload_id, created_at"),
        @Index(name = "idx_migration_from", columnList = "from_rack_id, created_at")})
public class Migration {

    public static final String PLANNED = "PLANNED";
    public static final String APPLIED = "APPLIED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    @Id
    @Column(name = "id", columnDefinition = "char(36)", nullable = false)
    private String id;

    @Column(name = "workload_id", nullable = false)
    private long workloadId;

    @Column(name = "from_rack_id", length = 8)
    private String fromRackId;

    @Column(name = "to_rack_id", length = 8)
    private String toRackId;

    @Column(name = "reason", nullable = false, length = 20)
    private String reason;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "failure_reason", length = 120)
    private String failureReason;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant createdAt;

    @Column(name = "applied_at", columnDefinition = "datetime(3)")
    private Instant appliedAt;

    protected Migration() {
    }

    public Migration(String id, long workloadId, String fromRackId, String toRackId, String reason, Instant createdAt) {
        this.id = id;
        this.workloadId = workloadId;
        this.fromRackId = fromRackId;
        this.toRackId = toRackId;
        this.reason = reason;
        this.status = PLANNED;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public long getWorkloadId() { return workloadId; }
    public String getFromRackId() { return fromRackId; }
    public String getToRackId() { return toRackId; }
    public String getReason() { return reason; }
    public String getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAppliedAt() { return appliedAt; }

    public void markApplied(Instant at) {
        this.status = APPLIED;
        this.appliedAt = at;
        this.failureReason = null;
    }

    public void markFailed(String why) {
        this.status = FAILED;
        this.failureReason = clip(why);
    }

    public void markSkipped(String why) {
        this.status = SKIPPED;
        this.failureReason = clip(why);
    }

    private static String clip(String s) {
        return s == null ? null : s.length() > 120 ? s.substring(0, 120) : s;
    }
}
