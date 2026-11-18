package com.thermogrid.scheduler.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** Direct, Eureka-resolved clients of the three services the scheduler reads (never through the gateway). */
public final class Clients {

    private Clients() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopologyRack(String id, String zoneId, int cpuCapacity, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopologyZone(String id, List<TopologyRack> racks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelemetryRack(String rackId, String zoneId, Double tempC, Double forecast60C, String riskLevel,
                                String sensorStatus) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkloadView(long id, String name, int cpuDemand, String priority, String status, String rackId,
                               int version) {
    }

    public record AssignmentRequest(String toRackId, String expectedFromRackId, int expectedVersion,
                                    String migrationId, String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssignmentResponse(long workloadId, String rackId, int version, String status) {
    }

    @FeignClient(name = "TOPOLOGY-SERVICE", contextId = "schedulerTopologyClient", primary = false)
    public interface TopologyClient {
        @GetMapping("/internal/topology")
        List<TopologyZone> topology();
    }

    @FeignClient(name = "TELEMETRY-SERVICE", contextId = "schedulerTelemetryClient", primary = false)
    public interface TelemetryClient {
        @GetMapping("/internal/telemetry/racks/latest")
        List<TelemetryRack> racksLatest();
    }

    @FeignClient(name = "WORKLOAD-SERVICE", contextId = "schedulerWorkloadClient", primary = false)
    public interface WorkloadClient {
        @GetMapping("/internal/workloads")
        List<WorkloadView> workloads(@RequestParam("status") String status);

        @PutMapping("/internal/workloads/{id}/assignment")
        AssignmentResponse assign(@PathVariable("id") long id, @RequestBody AssignmentRequest request);
    }
}
