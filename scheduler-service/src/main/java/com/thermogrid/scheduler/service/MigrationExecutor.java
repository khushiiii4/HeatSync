package com.thermogrid.scheduler.service;

import com.thermogrid.scheduler.client.Clients.AssignmentRequest;
import com.thermogrid.scheduler.client.Clients.WorkloadClient;
import com.thermogrid.scheduler.client.Guarded;
import com.thermogrid.scheduler.domain.Migration;
import com.thermogrid.scheduler.domain.Repositories.MigrationRepository;
import com.thermogrid.scheduler.engine.MigrationPlanner.PlannedMove;
import com.thermogrid.scheduler.model.Model.Job;
import com.thermogrid.scheduler.model.Model.Reason;
import com.thermogrid.scheduler.engine.PlacementEngine.Placement;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies placements and migrations through {@code PUT /internal/workloads/{id}/assignment} and records the result.
 *
 * <p>Each attempt gets a new migrationId and carries the workload's current version and source rack. The call is made
 * exactly once: a 409 (stale state) or an outage is recorded as FAILED and the next cycle plans from fresh state, so
 * there is no retry storm. The scheduler never publishes {@code workload.assigned}; workload-service does after commit.
 */
@Component
public class MigrationExecutor {

    private static final Logger log = LoggerFactory.getLogger(MigrationExecutor.class);

    public enum Outcome {
        APPLIED, FAILED, UNAVAILABLE
    }

    private final WorkloadClient workloads;
    private final Guarded guarded;
    private final MigrationRepository migrations;
    private final DecisionRecorder decisions;
    private final Clock clock;

    public MigrationExecutor(WorkloadClient workloads, Guarded guarded, MigrationRepository migrations,
                             DecisionRecorder decisions, Clock clock) {
        this.workloads = workloads;
        this.guarded = guarded;
        this.migrations = migrations;
        this.decisions = decisions;
        this.clock = clock;
    }

    /** Records the decision and the migration row, performs the PUT once, records APPLIED or FAILED. */
    public Outcome execute(Job job, String fromRackId, String toRackId, Reason reason, Placement placement) {
        decisions.record(job.id(), placement);
        String id = UUID.randomUUID().toString();
        Migration m = migrations.save(new Migration(id, job.id(), fromRackId, toRackId, reason.name(),
                clock.instant()));
        try {
            guarded.workloadCommand(() -> workloads.assign(job.id(),
                    new AssignmentRequest(toRackId, fromRackId, job.version(), id, reason.name())));
            m.markApplied(clock.instant());
            migrations.save(m);
            log.info("Applied {} workload {} {} -> {} ({})", reason, job.id(), fromRackId, toRackId, id);
            return Outcome.APPLIED;
        } catch (FeignException fe) {
            int s = fe.status();
            if (s == 409) {
                fail(m, "409 stale version or source rack; will re-plan from fresh state");
                return Outcome.FAILED;
            }
            if (s >= 400 && s < 500) {
                fail(m, s + " rejected by workload-service");
                return Outcome.FAILED;
            }
            fail(m, "workload-service error " + (s > 0 ? s : "(no response)"));
            return Outcome.UNAVAILABLE;
        } catch (CallNotPermittedException e) {
            fail(m, "workload-service circuit open");
            return Outcome.UNAVAILABLE;
        } catch (RuntimeException e) {
            fail(m, "workload-service unreachable: " + e.getClass().getSimpleName());
            return Outcome.UNAVAILABLE;
        }
    }

    public Outcome execute(PlannedMove move) {
        return execute(move.job(), move.fromRackId(), move.toRackId(), move.reason(), move.placement());
    }

    /**
     * Executes moves in order. When workload-service turns out to be unavailable the rest are recorded SKIPPED instead
     * of being sent, to be re-planned in a later cycle.
     *
     * @return number of moves applied
     */
    public int executeAll(List<PlannedMove> moves) {
        int applied = 0;
        for (int i = 0; i < moves.size(); i++) {
            Outcome o = execute(moves.get(i));
            if (o == Outcome.APPLIED) {
                applied++;
            } else if (o == Outcome.UNAVAILABLE) {
                for (PlannedMove rest : moves.subList(i + 1, moves.size())) {
                    Migration skipped = new Migration(UUID.randomUUID().toString(), rest.job().id(),
                            rest.fromRackId(), rest.toRackId(), rest.reason().name(), clock.instant());
                    skipped.markSkipped("workload-service unavailable; re-planned next cycle");
                    migrations.save(skipped);
                }
                break;
            }
        }
        return applied;
    }

    private void fail(Migration m, String why) {
        m.markFailed(why);
        migrations.save(m);
        log.warn("Migration {} of workload {} FAILED: {}", m.getId(), m.getWorkloadId(), why);
    }
}
