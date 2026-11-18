package com.thermogrid.workload.api;

import com.thermogrid.workload.domain.Priority;
import com.thermogrid.workload.domain.Workload;
import com.thermogrid.workload.domain.WorkloadStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Request and response bodies of the public and internal endpoints. */
public final class Dtos {

    private Dtos() {
    }

    public record SubmitRequest(
            @NotBlank @Size(max = 60) String name,
            @NotNull @Min(1) @Max(100) Integer cpuDemand,
            @NotNull Priority priority) {
    }

    public record WorkloadDto(long id, String name, int cpuDemand, Priority priority, WorkloadStatus status,
                              String rackId, int version) {
        public static WorkloadDto of(Workload w) {
            return new WorkloadDto(w.getId(), w.getName(), w.getCpuDemand(), w.getPriority(), w.getStatus(),
                    w.getRackId(), w.getVersion());
        }
    }

    public record AssignmentRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,8}", message = "must be a rack id of 1 to 8 characters")
            String toRackId,
            String expectedFromRackId,
            @NotNull @Min(0) Integer expectedVersion,
            @NotBlank String migrationId,
            @NotBlank String reason) {
    }

    public record AssignmentResponse(long workloadId, String rackId, int version, WorkloadStatus status) {
    }

    public record RackSummary(String rackId, int workloadCount, int cpuUnits) {
    }

    /** Running workloads per rack (racks without workloads are absent) plus global counts. */
    public record Summary(int runningCount, int pendingCount, int totalCpuUnits, List<RackSummary> racks) {
    }

    public record SeedResult(boolean created, int workloadCount, int totalCpuUnits) {
    }

    public record ApiError(Instant timestamp, int status, String error, String message, String path) {
    }
}
