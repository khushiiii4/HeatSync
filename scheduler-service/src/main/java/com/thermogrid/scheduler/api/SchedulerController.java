package com.thermogrid.scheduler.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.scheduler.config.SchedulerProperties;
import com.thermogrid.scheduler.domain.Migration;
import com.thermogrid.scheduler.domain.PlacementDecision;
import com.thermogrid.scheduler.domain.Repositories.MigrationRepository;
import com.thermogrid.scheduler.domain.Repositories.PlacementDecisionRepository;
import com.thermogrid.scheduler.domain.Repositories.ZoneCoolingStateRepository;
import com.thermogrid.scheduler.domain.ZoneCoolingState;
import com.thermogrid.scheduler.service.SchedulerCycles;
import com.thermogrid.scheduler.service.SchedulerState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scheduler API (plan section 8.1). DTOs only; roles are enforced in a later phase. */
@RestController
@RequestMapping("/api/scheduler")
public class SchedulerController {

    public record ZoneCooling(String zoneId, String coolingHealth) {
    }

    public record Status(String mode, boolean autopilot, Instant lastCycleAt, int pendingWorkloads,
                         boolean capacityShortage, List<String> cooldownRacks, List<ZoneCooling> zones) {
    }

    public record MigrationView(String id, long workloadId, String fromRackId, String toRackId, String reason,
                                String status, String failureReason, Instant createdAt, Instant appliedAt) {
        static MigrationView of(Migration m) {
            return new MigrationView(m.getId(), m.getWorkloadId(), m.getFromRackId(), m.getToRackId(), m.getReason(),
                    m.getStatus(), m.getFailureReason(), m.getCreatedAt(), m.getAppliedAt());
        }
    }

    public record DecisionView(long id, long workloadId, String chosenRackId, BigDecimal score,
                               List<Map<String, Object>> candidates, String mode, Instant createdAt) {
    }

    public record AutopilotRequest(Boolean enabled) {
    }

    public record AutopilotResponse(boolean autopilot) {
    }

    public record RebalanceResponse(boolean moved, String message) {
    }

    private final SchedulerState state;
    private final MigrationRepository migrations;
    private final PlacementDecisionRepository decisions;
    private final ZoneCoolingStateRepository cooling;
    private final SchedulerCycles cycles;
    private final SchedulerProperties props;
    private final ObjectMapper mapper;

    public SchedulerController(SchedulerState state, MigrationRepository migrations,
                               PlacementDecisionRepository decisions, ZoneCoolingStateRepository cooling,
                               SchedulerCycles cycles, SchedulerProperties props, ObjectMapper mapper) {
        this.state = state;
        this.migrations = migrations;
        this.decisions = decisions;
        this.cooling = cooling;
        this.cycles = cycles;
        this.props = props;
        this.mapper = mapper;
    }

    @GetMapping("/status")
    public Status status() {
        List<ZoneCooling> zones = cooling.findAll().stream()
                .sorted(Comparator.comparing(ZoneCoolingState::getZoneId))
                .map(z -> new ZoneCooling(z.getZoneId(), z.getHealth())).toList();
        return new Status(state.mode().name(), state.autopilot(), state.lastCycleAt(), state.pendingCount(),
                state.capacityShortage(), List.copyOf(state.cooldownRacks()), zones);
    }

    @GetMapping("/migrations")
    public List<MigrationView> migrations(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return migrations.latest(PageRequest.of(0, clamp(limit))).stream().map(MigrationView::of).toList();
    }

    @GetMapping("/decisions")
    public List<DecisionView> decisions(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        return decisions.latest(PageRequest.of(0, clamp(limit))).stream().map(this::view).toList();
    }

    @PostMapping("/autopilot")
    public AutopilotResponse autopilot(@RequestBody AutopilotRequest request) {
        if (request == null || request.enabled() == null) {
            throw new IllegalArgumentException("enabled (true or false) is required");
        }
        state.autopilot(request.enabled());
        return new AutopilotResponse(state.autopilot());
    }

    @PostMapping("/rebalance")
    public RebalanceResponse rebalance() {
        SchedulerCycles.RebalanceResult r = cycles.rebalanceNow();
        return new RebalanceResponse(r.moved(), r.message());
    }

    private int clamp(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return Math.min(limit, props.maxListLimit());
    }

    private DecisionView view(PlacementDecision d) {
        List<Map<String, Object>> cands;
        try {
            cands = mapper.readValue(d.getCandidatesJson(), new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            cands = List.of();
        }
        return new DecisionView(d.getId(), d.getWorkloadId(), d.getChosenRackId(), d.getScore(), cands, d.getMode(),
                d.getCreatedAt());
    }
}
