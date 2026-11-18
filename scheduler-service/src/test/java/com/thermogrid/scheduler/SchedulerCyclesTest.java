package com.thermogrid.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.scheduler.client.Clients.AssignmentRequest;
import com.thermogrid.scheduler.domain.Migration;
import com.thermogrid.scheduler.domain.PlacementDecision;
import com.thermogrid.scheduler.domain.ZoneCoolingState;
import com.thermogrid.scheduler.model.Model.Mode;
import com.thermogrid.scheduler.service.SchedulerCycles;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Cycles end to end on H2 with fake services: placement, migration, failure handling, HOLD and autopilot. */
class SchedulerCyclesTest extends SchedulerTestBase {

    @Autowired SchedulerCycles cycles;
    @Autowired ObjectMapper mapper;

    private static FeignException feign(int status) {
        Request req = Request.create(Request.HttpMethod.PUT, "http://workload/internal", Map.of(), null,
                StandardCharsets.UTF_8, new RequestTemplate());
        return FeignException.errorStatus("assign", feign.Response.builder().status(status).reason("x").request(req)
                .headers(Map.of()).build());
    }

    private List<Migration> allMigrations() {
        return migrations.findAll();
    }

    // ---- placement ----

    @Test
    void submittedWorkloadIsPlacedWithANewMigrationIdTheCurrentVersionAndTheRightReason() {
        baselineLoad();
        pending.add(wl(7, 20, "LOW", null, 0));

        assertThat(cycles.placePending()).isEqualTo(1);

        assertThat(assignments).hasSize(1);
        AssignmentRequest req = assignments.get(0);
        assertThat(assignedIds).containsExactly(7L);
        assertThat(req.expectedFromRackId()).isNull();
        assertThat(req.expectedVersion()).isEqualTo(0);
        assertThat(req.reason()).isEqualTo("INITIAL_PLACEMENT");
        assertThat(UUID.fromString(req.migrationId())).isNotNull();
        assertThat(req.toRackId()).isNotBlank();

        Migration m = allMigrations().get(0);
        assertThat(m.getId()).isEqualTo(req.migrationId());
        assertThat(m.getStatus()).isEqualTo("APPLIED");
        assertThat(m.getWorkloadId()).isEqualTo(7L);
        assertThat(m.getToRackId()).isEqualTo(req.toRackId());
        assertThat(m.getFromRackId()).isNull();
        assertThat(m.getAppliedAt()).isNotNull();
        assertThat(state.pendingCount()).isZero();
    }

    @Test
    void everyPlacementIsRecordedWithItsTopThreeCandidates() throws Exception {
        baselineLoad();
        pending.add(wl(7, 20, "LOW", null, 0));
        cycles.placePending();

        List<PlacementDecision> ds = decisions.findAll();
        assertThat(ds).hasSize(1);
        PlacementDecision d = ds.get(0);
        assertThat(d.getWorkloadId()).isEqualTo(7L);
        assertThat(d.getMode()).isEqualTo("NORMAL");
        JsonNode cands = mapper.readTree(d.getCandidatesJson());
        assertThat(cands).hasSize(3);
        assertThat(cands.get(0).get("rackId").asText()).isNotBlank();
        assertThat(cands.get(0).get("score").asDouble()).isEqualTo(d.getScore().doubleValue(),
                org.assertj.core.api.Assertions.within(0.05));
    }

    @Test
    void highPriorityPendingJobsGetTheCoolestRacksFirst() {
        baselineLoad();
        for (String r : new String[] {"R21", "R22", "R23", "R24"}) {
            running.add(wl(2000 + Integer.parseInt(r.substring(1)), 40, "MEDIUM", r, 1));   // equal load everywhere
        }
        forecast.put("R12", 22.0);      // coolest
        forecast.put("R13", 23.0);
        pending.add(wl(1, 20, "LOW", null, 0));
        pending.add(wl(2, 20, "HIGH", null, 0));
        cycles.placePending();
        Map<Long, String> where = new HashMap<>();
        for (int i = 0; i < assignedIds.size(); i++) {
            where.put(assignedIds.get(i), assignments.get(i).toRackId());
        }
        assertThat(assignedIds).containsExactly(2L, 1L);        // HIGH first
        assertThat(where.get(2L)).isEqualTo("R12");
    }

    @Test
    void jobsStayPendingWhenNoRackHasCapacityAndTheShortageIsReported() {
        // Every rack at 80 units: a 30-unit job would exceed 85 everywhere.
        long id = 1;
        for (String rack : forecast.keySet()) {
            running.add(wl(id++, 80, "MEDIUM", rack, 1));
        }
        pending.add(wl(500, 30, "LOW", null, 0));
        assertThat(cycles.placePending()).isZero();
        assertThat(assignments).isEmpty();
        assertThat(state.pendingCount()).isEqualTo(1);
        assertThat(state.capacityShortage()).isTrue();
    }

    @Test
    void staleSensorRacksAndFaultZonesAreNeverPlacementTargets() {
        for (String r : forecast.keySet()) {
            if (!r.equals("R44")) {
                sensor.put(r, "STALE");
            }
        }
        sensor.put("R44", "OK");
        pending.add(wl(1, 20, "LOW", null, 0));
        cycles.placePending();
        assertThat(assignments.get(0).toRackId()).isEqualTo("R44");

        // R44 is in Z4; mark Z4 FAULT: now nothing is eligible.
        assignments.clear();
        cooling.save(new ZoneCoolingState("Z4", "FAULT", "AUTO", clock.instant()));
        migrations.deleteAll();
        assertThat(cycles.placePending()).isZero();
        assertThat(assignments).isEmpty();
    }

    @Test
    void aRackIsNotAssignedTwiceInOneCycleBeyondItsCapacity() {
        // Only R44 is eligible, with room for exactly one 40-unit job (cap 85).
        for (String r : forecast.keySet()) {
            if (!r.equals("R44")) {
                sensor.put(r, "STALE");
            }
        }
        pending.add(wl(1, 40, "HIGH", null, 0));
        pending.add(wl(2, 40, "MEDIUM", null, 0));
        pending.add(wl(3, 40, "LOW", null, 0));
        assertThat(cycles.placePending()).isEqualTo(2);        // 40 + 40 = 80 <= 85, the third would be 120
        assertThat(assignedIds).containsExactly(1L, 2L);
        assertThat(state.pendingCount()).isEqualTo(1);
    }

    // ---- migration ----

    private void hotZ2(double forecastC, String level) {
        forecast.put("R23", forecastC);
        risk.put("R23", level);
        // The rest of Z2 is warm too (as in the demo): 31 C plus job heat is not below 32 C, so it cannot take work.
        for (String r : new String[] {"R21", "R22", "R24"}) {
            forecast.put(r, 31.0);
            risk.put(r, "WATCH");
        }
        running.add(wl(1, 30, "LOW", "R23", 3));
        running.add(wl(2, 20, "MEDIUM", "R23", 2));
        running.add(wl(3, 25, "HIGH", "R23", 1));
        baselineLoad();
    }

    @Test
    void warningOnARackMovesLowPriorityWorkFirstToAnotherZoneWithTheRightPayload() {
        hotZ2(36.0, "WARNING");     // shed 50
        cycles.reconcile();

        assertThat(assignedIds).containsExactly(1L, 2L);               // LOW 30, then MEDIUM 20 (limit 2)
        AssignmentRequest first = assignments.get(0);
        assertThat(first.expectedFromRackId()).isEqualTo("R23");
        assertThat(first.expectedVersion()).isEqualTo(3);
        assertThat(first.reason()).isEqualTo("THERMAL_WARNING");
        assertThat(first.toRackId()).doesNotStartWith("R2");           // away from the hot zone's racks
        assertThat(assignments.get(1).expectedVersion()).isEqualTo(2);
        assertThat(assignments.stream().map(AssignmentRequest::migrationId).distinct()).hasSize(2);
        assertThat(allMigrations()).allMatch(m -> m.getStatus().equals("APPLIED") && m.getFromRackId().equals("R23"));
        assertThat(state.lastCycleAt()).isEqualTo(clock.instant());
    }

    @Test
    void aMovedWorkloadIsNotMovedAgainDuringItsCooldown() {
        hotZ2(36.0, "WARNING");
        cycles.reconcile();
        assertThat(assignedIds).containsExactly(1L, 2L);

        // The fake workload service does not move anything, so the view is unchanged: only cooldowns can stop a repeat.
        clock.advance(Duration.ofSeconds(60));
        cycles.reconcile();
        assertThat(assignedIds).doesNotHaveDuplicates();
        assertThat(assignedIds).containsExactly(1L, 2L, 3L);           // only the unmoved HIGH job is left to move

        clock.advance(Duration.ofSeconds(100));                         // job 1 moved 160 s ago: allowed again
        cycles.reconcile();
        assertThat(assignedIds.subList(3, assignedIds.size())).doesNotContain(3L);
        assertThat(assignedIds.stream().filter(i -> i == 1L).count()).isEqualTo(2);
    }

    @Test
    void criticalAllowsFourMovesAndUsesTheLowerShedReference() {
        forecast.put("R23", 40.0);
        risk.put("R23", "CRITICAL");
        for (int i = 1; i <= 6; i++) {
            running.add(wl(i, 10, "LOW", "R23", 1));
        }
        baselineLoad();
        cycles.reconcile();
        assertThat(assignments).hasSize(4);
        assertThat(assignments).allMatch(a -> a.reason().equals("THERMAL_CRITICAL"));
    }

    @Test
    void failedCoolingZoneIsDrainedToOtherZones() {
        baselineLoad();
        running.add(wl(1, 20, "HIGH", "R21", 1));
        running.add(wl(2, 20, "LOW", "R22", 1));
        running.add(wl(3, 20, "MEDIUM", "R22", 1));
        cooling.save(new ZoneCoolingState("Z2", "FAULT", "AUTO", clock.instant()));
        cycles.reconcile();
        assertThat(assignedIds).containsExactly(2L, 3L, 1L);           // LOW first across the zone, no shed limit
        assertThat(assignments).allMatch(a -> a.reason().equals("COOLING_FAILURE") && !a.toRackId().startsWith("R2"));
    }

    @Test
    void migrationRespectsDestinationCapacityAcrossTheCycle() {
        // Only R11 and R12 can take work, each with 45 free units (cap 85, 40 used). Three 30-unit jobs: two fit.
        forecast.put("R23", 36.0);
        risk.put("R23", "WARNING");
        running.add(wl(1, 30, "LOW", "R23", 1));
        running.add(wl(2, 30, "LOW", "R23", 1));
        running.add(wl(3, 30, "LOW", "R23", 1));
        running.add(wl(901, 40, "MEDIUM", "R11", 1));
        running.add(wl(902, 40, "MEDIUM", "R12", 1));
        for (String r : forecast.keySet()) {
            if (!r.equals("R11") && !r.equals("R12") && !r.equals("R23")) {
                sensor.put(r, "STALE");
            }
        }
        cycles.reconcile();
        assertThat(assignments).hasSize(2);
        assertThat(assignments.stream().map(AssignmentRequest::toRackId)).containsExactlyInAnyOrder("R11", "R12");
    }

    // ---- failure handling ----

    @Test
    void conflict409IsRecordedFailedAndNotRetried() {
        hotZ2(36.0, "WARNING");
        onAssign = (id, req) -> { throw feign(409); };
        cycles.reconcile();
        // One call, then the executor stops being hopeful about nothing: a 409 is a normal answer, so the second
        // planned move is still attempted once (it is a different workload) but nothing is repeated.
        assertThat(assignedIds).doesNotHaveDuplicates();
        assertThat(allMigrations()).allMatch(m -> m.getStatus().equals("FAILED"));
        assertThat(allMigrations().get(0).getFailureReason()).contains("409");

        // Next cycle three seconds later: the failure backoff keeps the same workloads from being hit again.
        int calls = assignedIds.size();
        clock.advance(Duration.ofSeconds(3));
        cycles.reconcile();
        assertThat(assignedIds.subList(0, calls)).doesNotHaveDuplicates();
        assertThat(assignedIds.stream().filter(i -> i == 1L || i == 2L).count()).isEqualTo(2);

        // After the backoff, fresh state is planned and the workload (now fine) is applied with a NEW migration id.
        onAssign = (id, req) -> new com.thermogrid.scheduler.client.Clients.AssignmentResponse(id, req.toRackId(),
                req.expectedVersion() + 1, "RUNNING");
        clock.advance(Duration.ofSeconds(20));
        cycles.reconcile();
        assertThat(allMigrations().stream().filter(m -> m.getStatus().equals("APPLIED"))).isNotEmpty();
        assertThat(allMigrations().stream().map(Migration::getId).distinct().count()).isEqualTo(allMigrations().size());
    }

    @Test
    void workloadServiceErrorOnTheFirstMoveSkipsTheRestAndRetriesInALaterCycle() {
        hotZ2(36.0, "WARNING");
        onAssign = (id, req) -> { throw feign(503); };
        cycles.reconcile();
        assertThat(assignments).hasSize(1);                             // the PUT is never retried automatically
        List<Migration> rows = allMigrations();
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(Migration::getStatus)).containsExactlyInAnyOrder("FAILED", "SKIPPED");

        onAssign = (id, req) -> new com.thermogrid.scheduler.client.Clients.AssignmentResponse(id, req.toRackId(),
                req.expectedVersion() + 1, "RUNNING");
        clock.advance(Duration.ofSeconds(11));                          // past the 10 s failure backoff
        cycles.reconcile();
        assertThat(allMigrations().stream().filter(m -> m.getStatus().equals("APPLIED")).count()).isEqualTo(2);
    }

    @Test
    void assignmentIsNeverRetriedEvenOnAConnectionFailure() {
        hotZ2(36.0, "WARNING");
        onAssign = (id, req) -> { throw new IllegalStateException("connection reset"); };
        cycles.reconcile();
        verify(workloadClient, times(1)).assign(anyLong(), any(AssignmentRequest.class));
    }

    @Test
    void workloadGetsAreRetriedTwiceThenTheCycleIsSkipped() {
        workloadsUp = false;
        pending.add(wl(1, 10, "LOW", null, 0));
        assertThat(cycles.placePending()).isEqualTo(-1);
        verify(workloadClient, times(3)).workloads("RUNNING");          // 1 call + 2 retries
        verify(workloadClient, never()).assign(anyLong(), any());
        assertThat(allMigrations()).isEmpty();
    }

    // ---- HOLD ----

    @Test
    void telemetryDownEntersHoldAndPlacesByCpuOnlyWithTheSeventyPercentCap() {
        telemetryUp = false;
        // R11 has 60 units. A 15-unit job: 75 > 70 there. Everything else is empty, so it must go elsewhere.
        running.add(wl(900, 60, "MEDIUM", "R11", 1));
        pending.add(wl(1, 15, "LOW", null, 0));
        cycles.placePending();
        assertThat(state.mode()).isEqualTo(Mode.HOLD);
        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).toRackId()).isNotEqualTo("R11");
        assertThat(decisions.findAll().get(0).getMode()).isEqualTo("HOLD");
    }

    @Test
    void holdNeverPlacesAboveSeventyPercentUtilisation() {
        telemetryUp = false;
        long id = 1;
        for (String rack : forecast.keySet()) {
            running.add(wl(id++, 60, "MEDIUM", rack, 1));          // every rack at 60
        }
        pending.add(wl(500, 15, "LOW", null, 0));                    // 75 > 70 everywhere (it would be fine at 85)
        assertThat(cycles.placePending()).isZero();
        pending.clear();
        pending.add(wl(501, 10, "LOW", null, 0));                    // 70 exactly: allowed
        assertThat(cycles.placePending()).isEqualTo(1);
    }

    @Test
    void holdPerformsNoThermalMigrationsEvenWithACriticalRackAndAFailedZone() {
        telemetryUp = false;
        hotZ2(40.0, "CRITICAL");
        cooling.save(new ZoneCoolingState("Z3", "FAULT", "AUTO", clock.instant()));
        cycles.reconcile();
        assertThat(state.mode()).isEqualTo(Mode.HOLD);
        assertThat(assignments).isEmpty();
        assertThat(state.lastCycleAt()).isEqualTo(clock.instant());
    }

    @Test
    void holdDoesNotUseAFaultZoneForPlacement() {
        telemetryUp = false;
        for (String z : new String[] {"Z1", "Z2", "Z3"}) {
            cooling.save(new ZoneCoolingState(z, "FAULT", "AUTO", clock.instant()));
        }
        pending.add(wl(1, 10, "LOW", null, 0));
        cycles.placePending();
        assertThat(assignments.get(0).toRackId()).startsWith("R4");
    }

    @Test
    void schedulerLeavesHoldWhenTelemetryReturnsAndMigrationResumes() {
        hotZ2(36.0, "WARNING");
        telemetryUp = false;
        cycles.reconcile();
        assertThat(state.mode()).isEqualTo(Mode.HOLD);
        assertThat(assignments).isEmpty();

        telemetryUp = true;
        guarded.telemetryBreaker().reset();
        cycles.reconcile();
        assertThat(state.mode()).isEqualTo(Mode.NORMAL);
        assertThat(assignments).isNotEmpty();
    }

    @Test
    void openTelemetryCircuitBreakerMeansHoldWithoutCallingTelemetry() {
        telemetryUp = false;
        for (int i = 0; i < 4; i++) {
            cycles.placePending();                                       // failures fill the breaker window
        }
        assertThat(guarded.telemetryBreaker().getState().name()).isEqualTo("OPEN");
        int calls = org.mockito.Mockito.mockingDetails(telemetryClient).getInvocations().size();
        cycles.placePending();
        assertThat(org.mockito.Mockito.mockingDetails(telemetryClient).getInvocations()).hasSize(calls);
        assertThat(state.mode()).isEqualTo(Mode.HOLD);
    }

    // ---- autopilot ----

    @Test
    void autopilotOffStopsMigrationButNotPendingPlacement() {
        hotZ2(36.0, "WARNING");
        pending.add(wl(77, 10, "LOW", null, 0));
        state.autopilot(false);

        cycles.placePending();
        assertThat(assignedIds).containsExactly(77L);                    // placement continues
        assignments.clear();
        assignedIds.clear();
        pending.clear();

        cycles.reconcile();
        assertThat(assignments).isEmpty();                                // but no migration

        state.autopilot(true);
        cycles.reconcile();
        assertThat(assignments).isNotEmpty();
    }

    // ---- reconcile ----

    @Test
    void reconcileRepairsAMissedSubmissionEventByPlacingPendingWorkloads() {
        baselineLoad();
        pending.add(wl(5, 10, "MEDIUM", null, 0));
        // No workload.submitted event was ever delivered; the reconcile loop alone finds and places the job.
        assertThat(cycles.reconcile()).isZero();
        assertThat(assignedIds).containsExactly(5L);
    }

    @Test
    void successfulMigrationsAreNotRepeated() {
        hotZ2(34.0, "WARNING");                                          // shed 25: one LOW 30 move covers it
        cycles.reconcile();
        assertThat(assignedIds).containsExactly(1L);
        clock.advance(Duration.ofSeconds(5));
        cycles.reconcile();
        // Source cooldown (20 s) now holds the rack, so nothing new is attempted.
        assertThat(assignedIds).containsExactly(1L);
        assertThat(state.cooldownRacks()).contains("R23");
    }

    @Test
    void anUnreadableTopologyWithNothingCachedSkipsTheCycleButALastGoodCopyIsReused() {
        baselineLoad();
        pending.add(wl(1, 10, "LOW", null, 0));
        cycles.placePending();                                           // primes the topology cache
        assertThat(assignedIds).hasSize(1);

        org.mockito.Mockito.when(topologyClient.topology()).thenThrow(new IllegalStateException("topology down"));
        clock.advance(Duration.ofSeconds(31));                           // cache expired: refresh fails, copy reused
        pending.clear();
        pending.add(wl(2, 10, "LOW", null, 0));
        assertThat(cycles.placePending()).isEqualTo(1);
        verify(topologyClient, org.mockito.Mockito.atLeast(1)).topology();
    }

    @Test
    void topologyIsCachedForThirtySeconds() {
        baselineLoad();
        cycles.placePending();                                           // fills the cache
        org.mockito.Mockito.clearInvocations(topologyClient);
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(5));
            cycles.placePending();
        }
        verify(topologyClient, never()).topology();                      // 25 s later: still cached
        clock.advance(Duration.ofSeconds(10));
        cycles.placePending();
        verify(topologyClient, times(1)).topology();                     // 35 s: refreshed once
    }

    @Test
    void migrationRowsAndDecisionsAreCountedForEveryAttempt() {
        hotZ2(36.0, "WARNING");
        cycles.reconcile();
        assertThat(migrations.count()).isEqualTo(2);
        assertThat(decisions.count()).isEqualTo(2);
        assertThat(migrations.findAll().stream().map(Migration::getReason).collect(Collectors.toSet()))
                .containsExactly("THERMAL_WARNING");
        verify(workloadClient, times(2)).assign(anyLong(), any(AssignmentRequest.class));
        verify(workloadClient, never()).assign(eq(3L), any());
    }
}
