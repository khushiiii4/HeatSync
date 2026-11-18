package com.thermogrid.workload.service;

import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.contracts.WorkloadAssignedData;
import com.thermogrid.contracts.WorkloadRemovedData;
import com.thermogrid.contracts.WorkloadSubmittedData;
import com.thermogrid.workload.api.Dtos.AssignmentRequest;
import com.thermogrid.workload.api.Dtos.AssignmentResponse;
import com.thermogrid.workload.api.Dtos.RackSummary;
import com.thermogrid.workload.api.Dtos.SubmitRequest;
import com.thermogrid.workload.api.Dtos.Summary;
import com.thermogrid.workload.config.WorkloadProperties;
import com.thermogrid.workload.domain.MigrationReason;
import com.thermogrid.workload.domain.Workload;
import com.thermogrid.workload.domain.WorkloadStatus;
import com.thermogrid.workload.event.WorkloadEvent;
import com.thermogrid.workload.repo.WorkloadRepository;
import com.thermogrid.workload.service.WorkloadExceptions.ConflictException;
import com.thermogrid.workload.service.WorkloadExceptions.InvalidRequestException;
import com.thermogrid.workload.service.WorkloadExceptions.NotFoundException;
import com.thermogrid.workload.service.WorkloadExceptions.RemovedException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the workload lifecycle. Every state change records its event with {@link ApplicationEventPublisher} inside the
 * transaction; the event reaches RabbitMQ only after the commit, and not at all after a rollback.
 */
@Service
public class WorkloadService {

    private final WorkloadRepository repo;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final WorkloadProperties props;

    public WorkloadService(WorkloadRepository repo, ApplicationEventPublisher events, Clock clock,
                           WorkloadProperties props) {
        this.repo = repo;
        this.events = events;
        this.clock = clock;
        this.props = props;
    }

    @Transactional
    public Workload submit(SubmitRequest req, String createdBy) {
        Instant now = clock.instant();
        Workload w = repo.saveAndFlush(new Workload(req.name().trim(), req.cpuDemand(), req.priority(), createdBy, now));
        events.publishEvent(new WorkloadEvent(RoutingKeys.WORKLOAD_SUBMITTED,
                new WorkloadSubmittedData(w.getId(), w.getName(), w.getCpuDemand(), w.getPriority().name()), now));
        return w;
    }

    @Transactional
    public List<Workload> submitBulk(List<SubmitRequest> requests, String createdBy) {
        if (requests == null || requests.isEmpty()) {
            throw new InvalidRequestException("At least one workload is required");
        }
        if (requests.size() > props.maxBulk()) {
            throw new InvalidRequestException("At most " + props.maxBulk() + " workloads per call");
        }
        List<Workload> out = new ArrayList<>();
        for (SubmitRequest r : requests) {
            out.add(submit(r, createdBy));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Workload get(long id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Workload> list(WorkloadStatus status, String rackId) {
        return repo.search(status, rackId == null || rackId.isBlank() ? null : rackId);
    }

    /** Marks the workload REMOVED. Removing an already REMOVED workload is a no-op without an event. */
    @Transactional
    public Workload remove(long id) {
        Workload w = get(id);
        if (w.getStatus() == WorkloadStatus.REMOVED) {
            return w;
        }
        Instant now = clock.instant();
        w.markRemoved(now);
        w = repo.saveAndFlush(w);
        events.publishEvent(new WorkloadEvent(RoutingKeys.WORKLOAD_REMOVED,
                new WorkloadRemovedData(w.getId(), w.getRackId(), w.getCpuDemand()), now));
        return w;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        Map<String, int[]> perRack = new TreeMap<>();
        int total = 0;
        int running = 0;
        for (Workload w : repo.findByStatusOrderById(WorkloadStatus.RUNNING)) {
            running++;
            total += w.getCpuDemand();
            int[] acc = perRack.computeIfAbsent(w.getRackId(), k -> new int[2]);
            acc[0]++;
            acc[1] += w.getCpuDemand();
        }
        List<RackSummary> racks = new ArrayList<>();
        perRack.forEach((rack, acc) -> racks.add(new RackSummary(rack, acc[0], acc[1])));
        int pending = repo.findByStatusOrderById(WorkloadStatus.PENDING).size();
        return new Summary(running, pending, total, racks);
    }

    /**
     * Applies a placement or migration (plan section 8.3). Order of checks: unknown (404), REMOVED (422), replay of the
     * last applied migrationId (same 200, nothing changes), stale version or source rack (409). The {@code @Version}
     * column additionally rejects a concurrent writer at flush time (also 409, see ApiExceptionHandler).
     */
    @Transactional
    public AssignmentResponse assign(long id, AssignmentRequest req) {
        String migrationId = canonicalUuid(req.migrationId());
        MigrationReason reason = parseReason(req.reason());
        Workload w = get(id);
        if (w.getStatus() == WorkloadStatus.REMOVED) {
            throw new RemovedException(id);
        }
        if (migrationId.equals(w.getLastMigrationId())) {
            return response(w);
        }
        if (req.expectedVersion() != w.getVersion()) {
            throw new ConflictException("Version is " + w.getVersion() + ", expected " + req.expectedVersion()
                    + "; nothing applied");
        }
        if (!Objects.equals(blankToNull(req.expectedFromRackId()), w.getRackId())) {
            throw new ConflictException("Workload is on " + w.getRackId() + ", expected "
                    + req.expectedFromRackId() + "; nothing applied");
        }
        if (req.toRackId().equals(w.getRackId())) {
            throw new InvalidRequestException("Workload is already on " + w.getRackId());
        }
        Instant now = clock.instant();
        String from = w.getRackId();
        w.assignTo(req.toRackId(), migrationId, now);
        w = repo.saveAndFlush(w);
        events.publishEvent(new WorkloadEvent(RoutingKeys.WORKLOAD_ASSIGNED,
                new WorkloadAssignedData(w.getId(), w.getCpuDemand(), w.getPriority().name(), from, w.getRackId(),
                        reason.name(), migrationId, w.getVersion()), now));
        return response(w);
    }

    /** Inserts RUNNING workloads directly on explicit racks (used by the demo seeder). One event per workload. */
    @Transactional
    public List<Workload> insertRunning(List<Workload> prepared, MigrationReason reason) {
        Instant now = clock.instant();
        List<Workload> saved = repo.saveAllAndFlush(prepared);
        for (Workload w : saved) {
            events.publishEvent(new WorkloadEvent(RoutingKeys.WORKLOAD_ASSIGNED,
                    new WorkloadAssignedData(w.getId(), w.getCpuDemand(), w.getPriority().name(), null,
                            w.getRackId(), reason.name(), w.getLastMigrationId(), w.getVersion()), now));
        }
        return saved;
    }

    private static AssignmentResponse response(Workload w) {
        return new AssignmentResponse(w.getId(), w.getRackId(), w.getVersion(), w.getStatus());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String canonicalUuid(String s) {
        try {
            return UUID.fromString(s).toString();
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("migrationId must be a UUID");
        }
    }

    private static MigrationReason parseReason(String s) {
        try {
            return MigrationReason.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("reason must be one of " + List.of(MigrationReason.values()));
        }
    }
}
