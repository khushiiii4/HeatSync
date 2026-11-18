package com.thermogrid.scheduler.snapshot;

import com.thermogrid.scheduler.client.Clients.TelemetryClient;
import com.thermogrid.scheduler.client.Clients.TelemetryRack;
import com.thermogrid.scheduler.client.Clients.TopologyClient;
import com.thermogrid.scheduler.client.Clients.TopologyZone;
import com.thermogrid.scheduler.client.Clients.WorkloadClient;
import com.thermogrid.scheduler.client.Clients.WorkloadView;
import com.thermogrid.scheduler.client.Guarded;
import com.thermogrid.scheduler.config.SchedulerProperties;
import com.thermogrid.scheduler.domain.Repositories.ZoneCoolingStateRepository;
import com.thermogrid.scheduler.model.Model.ClusterView;
import com.thermogrid.scheduler.model.Model.Job;
import com.thermogrid.scheduler.model.Model.Mode;
import com.thermogrid.scheduler.model.Model.Priority;
import com.thermogrid.scheduler.model.Model.Rack;
import com.thermogrid.scheduler.model.Model.RiskLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds one fresh {@link ClusterView} per cycle from the services that own the data (plan section 5.2).
 *
 * <ul>
 * <li>topology: cached for 30 s; if a refresh fails the last good copy is kept and used;</li>
 * <li>telemetry: 2 s timeout and a circuit breaker; any failure or an open circuit gives HOLD mode (the view then has
 * no forecasts and only CPU-based placement is possible);</li>
 * <li>workloads (RUNNING and PENDING): circuit breaker plus 2 retries; if they cannot be read there is no view at all
 * ({@link SnapshotUnavailableException}) and the caller simply tries again next cycle;</li>
 * <li>cooling state: the zones the scheduler has recorded as FAULT from cooling.unit.state events.</li>
 * </ul>
 */
@Service
public class ClusterSnapshotService implements SnapshotSource {

    private static final Logger log = LoggerFactory.getLogger(ClusterSnapshotService.class);

    private final TopologyClient topology;
    private final TelemetryClient telemetry;
    private final WorkloadClient workloads;
    private final Guarded guarded;
    private final ZoneCoolingStateRepository cooling;
    private final SchedulerProperties props;
    private final Clock clock;

    private List<TopologyZone> cachedTopology;
    private Instant cachedAt;
    private boolean telemetryDown;

    public ClusterSnapshotService(TopologyClient topology, TelemetryClient telemetry, WorkloadClient workloads,
                                  Guarded guarded, ZoneCoolingStateRepository cooling, SchedulerProperties props,
                                  Clock clock) {
        this.topology = topology;
        this.telemetry = telemetry;
        this.workloads = workloads;
        this.guarded = guarded;
        this.cooling = cooling;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public ClusterView fetch() {
        Instant now = clock.instant();
        List<TopologyZone> zones = topology(now);

        Map<String, TelemetryRack> byRack = null;
        try {
            List<TelemetryRack> latest = guarded.telemetry(telemetry::racksLatest);
            byRack = new HashMap<>();
            for (TelemetryRack r : latest) {
                byRack.put(r.rackId(), r);
            }
            noteTelemetry(true, null);
        } catch (RuntimeException e) {
            noteTelemetry(false, e);
        }
        Mode mode = byRack == null ? Mode.HOLD : Mode.NORMAL;

        List<WorkloadView> running;
        List<WorkloadView> pending;
        try {
            running = guarded.workloadGet(() -> workloads.workloads("RUNNING"));
            pending = guarded.workloadGet(() -> workloads.workloads("PENDING"));
        } catch (RuntimeException e) {
            throw new SnapshotUnavailableException("workload-service unavailable: " + e.getMessage(), e);
        }

        List<Rack> racks = new java.util.ArrayList<>();
        for (TopologyZone z : zones) {
            for (var r : z.racks()) {
                boolean active = "ACTIVE".equals(r.status());
                TelemetryRack t = byRack == null ? null : byRack.get(r.id());
                if (t == null) {
                    // HOLD: no telemetry at all. NORMAL but unknown rack: not placeable, sensor not OK.
                    racks.add(new Rack(r.id(), r.zoneId(), r.cpuCapacity(), active, null, null, RiskLevel.NORMAL,
                            mode == Mode.HOLD));
                } else {
                    racks.add(new Rack(r.id(), r.zoneId(), r.cpuCapacity(), active, t.forecast60C(), t.tempC(),
                            RiskLevel.parse(t.riskLevel()), "OK".equals(t.sensorStatus())));
                }
            }
        }
        Set<String> faults = new HashSet<>();
        cooling.findAll().forEach(c -> {
            if ("FAULT".equals(c.getHealth())) {
                faults.add(c.getZoneId());
            }
        });
        return new ClusterView(now, mode, racks, faults, toJobs(running), toJobs(pending));
    }

    private static List<Job> toJobs(List<WorkloadView> list) {
        return list.stream()
                .map(w -> new Job(w.id(), w.name(), w.cpuDemand(), Priority.parse(w.priority()), w.rackId(),
                        w.version()))
                .toList();
    }

    private synchronized List<TopologyZone> topology(Instant now) {
        if (cachedTopology != null
                && Duration.between(cachedAt, now).compareTo(Duration.ofSeconds(props.topologyCacheSec())) < 0) {
            return cachedTopology;
        }
        try {
            List<TopologyZone> fresh = topology.topology();
            if (fresh == null || fresh.isEmpty()) {
                throw new IllegalStateException("topology-service returned no zones");
            }
            cachedTopology = fresh;
            cachedAt = now;
        } catch (RuntimeException e) {
            if (cachedTopology == null) {
                throw new SnapshotUnavailableException("topology-service unavailable and nothing cached: "
                        + e.getMessage(), e);
            }
            log.warn("Topology refresh failed ({}); using the last good copy from {}", e.toString(), cachedAt);
        }
        return cachedTopology;
    }

    /** Forgets the cached topology (tests, and a hook for a future refresh endpoint). */
    public synchronized void invalidateTopologyCache() {
        cachedTopology = null;
        cachedAt = null;
    }

    private synchronized void noteTelemetry(boolean up, RuntimeException e) {
        if (up && telemetryDown) {
            log.info("Telemetry reachable again: leaving HOLD mode");
        } else if (!up && !telemetryDown) {
            log.warn("Telemetry unavailable ({}): HOLD mode", e == null ? "?" : e.toString());
        }
        telemetryDown = !up;
    }
}
