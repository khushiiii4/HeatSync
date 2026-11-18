package com.thermogrid.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** Why a rack was chosen: the winner, its score and the top three candidates (plan section 7). */
@Entity
@Table(name = "placement_decision")
public class PlacementDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workload_id", nullable = false)
    private long workloadId;

    @Column(name = "chosen_rack_id", nullable = false, length = 8)
    private String chosenRackId;

    @Column(name = "score", nullable = false, precision = 6, scale = 3)
    private BigDecimal score;

    @Column(name = "candidates_json", columnDefinition = "text")
    private String candidatesJson;

    @Column(name = "mode", nullable = false, length = 6)
    private String mode;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(3)")
    private Instant createdAt;

    protected PlacementDecision() {
    }

    public PlacementDecision(long workloadId, String chosenRackId, BigDecimal score, String candidatesJson,
                             String mode, Instant createdAt) {
        this.workloadId = workloadId;
        this.chosenRackId = chosenRackId;
        this.score = score;
        this.candidatesJson = candidatesJson;
        this.mode = mode;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public long getWorkloadId() { return workloadId; }
    public String getChosenRackId() { return chosenRackId; }
    public BigDecimal getScore() { return score; }
    public String getCandidatesJson() { return candidatesJson; }
    public String getMode() { return mode; }
    public Instant getCreatedAt() { return createdAt; }
}
