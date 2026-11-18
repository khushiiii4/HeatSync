package com.thermogrid.workload.service;

import com.thermogrid.workload.api.Dtos.SeedResult;
import com.thermogrid.workload.domain.MigrationReason;
import com.thermogrid.workload.domain.Priority;
import com.thermogrid.workload.domain.Workload;
import com.thermogrid.workload.domain.WorkloadStatus;
import com.thermogrid.workload.repo.WorkloadRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the demo layout of plan section 10: 36 workloads of 10 to 30 CPU units, 720 units in total, on explicit
 * racks. Z2 (R21 to R24) carries 80, 85, 85 and 80 units and every Z2 rack holds at least one LOW job; the other
 * three zones carry 25 to 35 units per rack. Idempotent: if seeded workloads are still alive nothing is added.
 */
@Component
public class DemoSeeder {

    public static final String SEED_USER = "seed";

    record Spec(String rack, String name, int cpu, Priority priority) {
    }

    /** The exact layout. Racks are R{row}{col}; zone Zn is row n. */
    static final List<Spec> LAYOUT = List.of(
            // Z1: 4 racks, 25 to 35 units
            new Spec("R11", "web-frontend-11a", 20, Priority.HIGH), new Spec("R11", "api-cache-11b", 15, Priority.MEDIUM),
            new Spec("R12", "report-gen-12", 30, Priority.MEDIUM),
            new Spec("R13", "batch-etl-13a", 20, Priority.LOW), new Spec("R13", "auth-proxy-13b", 15, Priority.HIGH),
            new Spec("R14", "search-index-14a", 20, Priority.MEDIUM), new Spec("R14", "log-compact-14b", 15, Priority.LOW),
            // Z2: warm zone, 80 to 85 units per rack, at least one LOW per rack
            new Spec("R21", "web-frontend-21a", 25, Priority.HIGH), new Spec("R21", "api-gateway-21b", 20, Priority.MEDIUM),
            new Spec("R21", "batch-etl-21c", 20, Priority.LOW), new Spec("R21", "cache-warm-21d", 15, Priority.MEDIUM),
            new Spec("R22", "ml-inference-22a", 30, Priority.MEDIUM), new Spec("R22", "web-frontend-22b", 20, Priority.HIGH),
            new Spec("R22", "report-gen-22c", 15, Priority.LOW), new Spec("R22", "batch-etl-22d", 20, Priority.LOW),
            new Spec("R23", "batch-etl-7", 30, Priority.LOW), new Spec("R23", "payments-23b", 20, Priority.HIGH),
            new Spec("R23", "search-index-23c", 20, Priority.MEDIUM), new Spec("R23", "log-compact-23d", 15, Priority.LOW),
            new Spec("R24", "db-replica-24a", 25, Priority.MEDIUM), new Spec("R24", "web-frontend-24b", 25, Priority.HIGH),
            new Spec("R24", "video-transcode-24c", 20, Priority.LOW), new Spec("R24", "api-cache-24d", 10, Priority.MEDIUM),
            // Z3
            new Spec("R31", "web-frontend-31a", 20, Priority.HIGH), new Spec("R31", "log-compact-31b", 15, Priority.LOW),
            new Spec("R32", "payments-32", 30, Priority.HIGH),
            new Spec("R33", "db-replica-33a", 25, Priority.MEDIUM), new Spec("R33", "batch-etl-33b", 10, Priority.LOW),
            new Spec("R34", "report-gen-34a", 15, Priority.LOW), new Spec("R34", "api-cache-34b", 10, Priority.MEDIUM),
            // Z4
            new Spec("R41", "search-index-41a", 20, Priority.MEDIUM), new Spec("R41", "batch-etl-41b", 15, Priority.LOW),
            new Spec("R42", "video-transcode-42", 30, Priority.LOW),
            new Spec("R43", "web-frontend-43a", 20, Priority.HIGH), new Spec("R43", "api-gateway-43b", 15, Priority.MEDIUM),
            new Spec("R44", "report-gen-44", 30, Priority.MEDIUM));

    private final WorkloadRepository repo;
    private final WorkloadService service;
    private final Clock clock;

    public DemoSeeder(WorkloadRepository repo, WorkloadService service, Clock clock) {
        this.repo = repo;
        this.service = service;
        this.clock = clock;
    }

    @Transactional
    public SeedResult seed() {
        if (repo.existsByCreatedByAndStatusNot(SEED_USER, WorkloadStatus.REMOVED)) {
            List<Workload> live = repo.search(WorkloadStatus.RUNNING, null).stream()
                    .filter(w -> SEED_USER.equals(w.getCreatedBy())).toList();
            return new SeedResult(false, live.size(), live.stream().mapToInt(Workload::getCpuDemand).sum());
        }
        Instant now = clock.instant();
        List<Workload> prepared = new ArrayList<>();
        for (Spec s : LAYOUT) {
            Workload w = new Workload(s.name(), s.cpu(), s.priority(), SEED_USER, now);
            w.assignTo(s.rack(), UUID.randomUUID().toString(), now);
            prepared.add(w);
        }
        List<Workload> saved = service.insertRunning(prepared, MigrationReason.SEED);
        return new SeedResult(true, saved.size(), saved.stream().mapToInt(Workload::getCpuDemand).sum());
    }
}
