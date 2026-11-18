package com.thermogrid.workload.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One workload (plan section 7). {@code rackId} is a plain reference to topology-service, not a foreign key. */
@Entity
@Table(name = "workload", indexes = {
        @Index(name = "idx_workload_status", columnList = "status"),
        @Index(name = "idx_workload_rack", columnList = "rack_id")})
public class Workload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "cpu_demand", nullable = false)
    private int cpuDemand;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "priority", nullable = false, length = 6)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 8)
    private WorkloadStatus status = WorkloadStatus.PENDING;

    @Column(name = "rack_id", length = 8)
    private String rackId;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "last_migration_id", columnDefinition = "char(36)")
    private String lastMigrationId;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant updatedAt;

    protected Workload() {
    }

    public Workload(String name, int cpuDemand, Priority priority, String createdBy, Instant now) {
        this.name = name;
        this.cpuDemand = cpuDemand;
        this.priority = priority;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getCpuDemand() { return cpuDemand; }
    public Priority getPriority() { return priority; }
    public WorkloadStatus getStatus() { return status; }
    public String getRackId() { return rackId; }
    public int getVersion() { return version; }
    public String getLastMigrationId() { return lastMigrationId; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** Place or move onto a rack. */
    public void assignTo(String rack, String migrationId, Instant now) {
        this.rackId = rack;
        this.status = WorkloadStatus.RUNNING;
        this.lastMigrationId = migrationId;
        this.updatedAt = now;
    }

    /** Terminal. The rack is kept for history. */
    public void markRemoved(Instant now) {
        this.status = WorkloadStatus.REMOVED;
        this.updatedAt = now;
    }
}
