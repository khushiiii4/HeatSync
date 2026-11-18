package com.thermogrid.scheduler;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.thermogrid.scheduler.client.Clients.AssignmentRequest;
import com.thermogrid.scheduler.client.Clients.AssignmentResponse;
import com.thermogrid.scheduler.client.Clients.TelemetryClient;
import com.thermogrid.scheduler.client.Clients.TelemetryRack;
import com.thermogrid.scheduler.client.Clients.TopologyClient;
import com.thermogrid.scheduler.client.Clients.TopologyRack;
import com.thermogrid.scheduler.client.Clients.TopologyZone;
import com.thermogrid.scheduler.client.Clients.WorkloadClient;
import com.thermogrid.scheduler.client.Clients.WorkloadView;
import com.thermogrid.scheduler.client.Guarded;
import com.thermogrid.scheduler.domain.Repositories.MigrationRepository;
import com.thermogrid.scheduler.domain.Repositories.PlacementDecisionRepository;
import com.thermogrid.scheduler.domain.Repositories.ProcessedEventRepository;
import com.thermogrid.scheduler.domain.Repositories.ZoneCoolingStateRepository;
import com.thermogrid.scheduler.model.Model.Mode;
import com.thermogrid.scheduler.service.SchedulerState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full context on in-memory H2 with the three Feign clients mocked and a movable clock. The fake cluster is a plain set
 * of mutable fields that the mocks read, so each test describes the world and then runs a cycle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SchedulerTestBase.ClockConfig.class)
abstract class SchedulerTestBase {

    static final Instant START = Instant.parse("2026-11-18T07:10:00Z");

    static class MutableClock extends Clock {
        private volatile Instant now = START;

        void advance(Duration d) {
            now = now.plus(d);
        }

        void set(Instant t) {
            now = t;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @MockitoBean protected TopologyClient topologyClient;
    @MockitoBean protected TelemetryClient telemetryClient;
    @MockitoBean protected WorkloadClient workloadClient;

    @Autowired protected MutableClock clock;
    @Autowired protected MockMvc mvc;
    @Autowired protected Guarded guarded;
    @Autowired protected com.thermogrid.scheduler.snapshot.ClusterSnapshotService snapshots;
    @Autowired protected SchedulerState state;
    @Autowired protected MigrationRepository migrations;
    @Autowired protected PlacementDecisionRepository decisions;
    @Autowired protected ProcessedEventRepository processed;
    @Autowired protected ZoneCoolingStateRepository cooling;

    // ---- the fake world ----
    protected final Map<String, Double> forecast = new LinkedHashMap<>();
    protected final Map<String, String> risk = new LinkedHashMap<>();
    protected final Map<String, String> sensor = new LinkedHashMap<>();
    protected final List<WorkloadView> running = new CopyOnWriteArrayList<>();
    protected final List<WorkloadView> pending = new CopyOnWriteArrayList<>();
    protected volatile boolean telemetryUp = true;
    protected volatile boolean workloadsUp = true;
    protected final List<AssignmentRequest> assignments = new CopyOnWriteArrayList<>();
    protected final List<Long> assignedIds = new CopyOnWriteArrayList<>();
    /** Per-call hook for the assignment PUT; throw to simulate failures. */
    protected volatile java.util.function.BiFunction<Long, AssignmentRequest, AssignmentResponse> onAssign;

    static final String[] ZONES = {"Z1", "Z2", "Z3", "Z4"};

    @BeforeEach
    void resetWorld() {
        Mockito.reset(topologyClient, telemetryClient, workloadClient);
        migrations.deleteAll();
        decisions.deleteAll();
        processed.deleteAll();
        cooling.deleteAll();
        snapshots.invalidateTopologyCache();
        guarded.telemetryBreaker().reset();
        guarded.workloadBreaker().reset();
        clock.set(START);
        state.clearAlerts();
        state.cooldownRacks(java.util.Set.of());
        state.autopilot(true);
        state.mode(Mode.NORMAL);
        state.pending(0, false);
        forecast.clear();
        risk.clear();
        sensor.clear();
        running.clear();
        pending.clear();
        assignments.clear();
        assignedIds.clear();
        telemetryUp = true;
        workloadsUp = true;
        onAssign = (id, req) -> new AssignmentResponse(id, req.toRackId(), req.expectedVersion() + 1, "RUNNING");
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 4; col++) {
                String id = "R" + row + col;
                forecast.put(id, 25.0);
                risk.put(id, "NORMAL");
                sensor.put(id, "OK");
            }
        }
        when(topologyClient.topology()).thenAnswer(i -> topology());
        when(telemetryClient.racksLatest()).thenAnswer(i -> {
            if (!telemetryUp) {
                throw new IllegalStateException("telemetry down");
            }
            return telemetry();
        });
        when(workloadClient.workloads(anyString())).thenAnswer(i -> {
            if (!workloadsUp) {
                throw new IllegalStateException("workload down");
            }
            return "RUNNING".equals(i.getArgument(0)) ? List.copyOf(running) : List.copyOf(pending);
        });
        when(workloadClient.assign(anyLong(), any(AssignmentRequest.class))).thenAnswer(i -> {
            long id = i.getArgument(0);
            AssignmentRequest req = i.getArgument(1);
            assignments.add(req);
            assignedIds.add(id);
            return onAssign.apply(id, req);
        });
    }

    protected List<TopologyZone> topology() {
        List<TopologyZone> zones = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            List<TopologyRack> racks = new ArrayList<>();
            for (int col = 1; col <= 4; col++) {
                racks.add(new TopologyRack("R" + row + col, "Z" + row, 100, "ACTIVE"));
            }
            zones.add(new TopologyZone("Z" + row, racks));
        }
        return zones;
    }

    protected List<TelemetryRack> telemetry() {
        List<TelemetryRack> out = new ArrayList<>();
        for (var e : forecast.entrySet()) {
            String id = e.getKey();
            out.add(new TelemetryRack(id, "Z" + id.charAt(1), e.getValue() - 1.0, e.getValue(), risk.get(id),
                    sensor.get(id)));
        }
        return out;
    }

    protected static WorkloadView wl(long id, int cpu, String priority, String rack, int version) {
        return new WorkloadView(id, "w" + id, cpu, priority, rack == null ? "PENDING" : "RUNNING", rack, version);
    }

    /** Puts the standard layout in place: 40 units on every rack of Z1, Z3 and Z4. */
    protected void baselineLoad() {
        long id = 1000;
        for (String rack : forecast.keySet()) {
            if (!rack.startsWith("R2")) {
                running.add(wl(id++, 40, "MEDIUM", rack, 1));
            }
        }
    }
}
