package com.thermogrid.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thermogrid.scheduler.domain.Migration;
import com.thermogrid.scheduler.domain.PlacementDecision;
import com.thermogrid.scheduler.domain.ZoneCoolingState;
import com.thermogrid.scheduler.model.Model.Mode;
import com.thermogrid.scheduler.service.SchedulerCycles;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** The three read endpoints and the two optional controls. */
class SchedulerApiTest extends SchedulerTestBase {

    @Autowired SchedulerCycles cycles;

    private void migration(String workload, String from, String to, String status, int ageSec) {
        Migration m = new Migration(UUID.randomUUID().toString(), Long.parseLong(workload), from, to,
                "THERMAL_WARNING", clock.instant().minusSeconds(ageSec));
        if (status.equals("APPLIED")) {
            m.markApplied(clock.instant().minusSeconds(ageSec));
        } else if (status.equals("FAILED")) {
            m.markFailed("409 stale");
        }
        migrations.save(m);
    }

    @Test
    void statusReportsModeAutopilotLastCyclePendingAndZoneCooling() throws Exception {
        cooling.save(new ZoneCoolingState("Z3", "FAULT", "AUTO", clock.instant()));
        cooling.save(new ZoneCoolingState("Z1", "OK", "AUTO", clock.instant()));
        state.pending(3, true);
        state.lastCycleAt(clock.instant());
        mvc.perform(get("/api/scheduler/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("NORMAL"))
                .andExpect(jsonPath("$.autopilot").value(true))
                .andExpect(jsonPath("$.lastCycleAt").value("2026-11-18T07:10:00Z"))
                .andExpect(jsonPath("$.pendingWorkloads").value(3))
                .andExpect(jsonPath("$.capacityShortage").value(true))
                .andExpect(jsonPath("$.cooldownRacks").isArray())
                .andExpect(jsonPath("$.zones.length()").value(2))
                .andExpect(jsonPath("$.zones[0].zoneId").value("Z1"))
                .andExpect(jsonPath("$.zones[1].zoneId").value("Z3"))
                .andExpect(jsonPath("$.zones[1].coolingHealth").value("FAULT"));
        state.mode(Mode.HOLD);
        mvc.perform(get("/api/scheduler/status")).andExpect(jsonPath("$.mode").value("HOLD"));
    }

    @Test
    void migrationsAreNewestFirstWithALimit() throws Exception {
        migration("1", "R23", "R41", "APPLIED", 30);
        migration("2", "R23", "R42", "FAILED", 20);
        migration("3", null, "R11", "APPLIED", 10);
        mvc.perform(get("/api/scheduler/migrations")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].workloadId").value(3))
                .andExpect(jsonPath("$[1].workloadId").value(2))
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].failureReason").value("409 stale"))
                .andExpect(jsonPath("$[2].fromRackId").value("R23"))
                .andExpect(jsonPath("$[0].fromRackId").doesNotExist())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].reason").value("THERMAL_WARNING"));
        mvc.perform(get("/api/scheduler/migrations?limit=2")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/scheduler/migrations?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/scheduler/migrations?limit=abc")).andExpect(status().isBadRequest());
    }

    @Test
    void decisionsShowTheChosenRackScoreAndCandidates() throws Exception {
        decisions.save(new PlacementDecision(7, "R41", new BigDecimal("0.428"),
                "[{\"rackId\":\"R31\",\"score\":0.423},{\"rackId\":\"R41\",\"score\":0.428}]", "NORMAL",
                clock.instant().minusSeconds(5)));
        decisions.save(new PlacementDecision(8, "R12", new BigDecimal("0.300"), "[]", "HOLD", clock.instant()));
        mvc.perform(get("/api/scheduler/decisions?limit=20")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].workloadId").value(8))
                .andExpect(jsonPath("$[0].mode").value("HOLD"))
                .andExpect(jsonPath("$[1].chosenRackId").value("R41"))
                .andExpect(jsonPath("$[1].score").value(0.428))
                .andExpect(jsonPath("$[1].candidates.length()").value(2))
                .andExpect(jsonPath("$[1].candidates[0].rackId").value("R31"));
        mvc.perform(get("/api/scheduler/decisions?limit=1")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void limitIsCappedAtTheConfiguredMaximum() throws Exception {
        mvc.perform(get("/api/scheduler/migrations?limit=100000")).andExpect(status().isOk());
    }

    @Test
    void autopilotCanBeSwitchedAndValidatesItsBody() throws Exception {
        mvc.perform(post("/api/scheduler/autopilot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.autopilot").value(false));
        assertThat(state.autopilot()).isFalse();
        mvc.perform(get("/api/scheduler/status")).andExpect(jsonPath("$.autopilot").value(false));
        mvc.perform(post("/api/scheduler/autopilot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.autopilot").value(true));
        mvc.perform(post("/api/scheduler/autopilot").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/scheduler/autopilot").contentType(MediaType.APPLICATION_JSON).content("nonsense"))
                .andExpect(status().isBadRequest());
        assertThat(state.autopilot()).isTrue();
    }

    @Test
    void rebalanceRunsOneMoveWhenUtilisationIsSkewedAndNothingWhenBalanced() throws Exception {
        mvc.perform(post("/api/scheduler/rebalance")).andExpect(status().isOk())
                .andExpect(jsonPath("$.moved").value(false)).andExpect(jsonPath("$.message").value("no rebalance needed"));

        running.add(wl(1, 40, "LOW", "R11", 1));
        running.add(wl(2, 40, "MEDIUM", "R11", 1));
        mvc.perform(post("/api/scheduler/rebalance")).andExpect(status().isOk())
                .andExpect(jsonPath("$.moved").value(true));
        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).reason()).isEqualTo("REBALANCE");
        assertThat(assignments.get(0).expectedFromRackId()).isEqualTo("R11");
    }

    @Test
    void rebalanceRefusesInHoldMode() throws Exception {
        telemetryUp = false;
        running.add(wl(1, 80, "LOW", "R11", 1));
        mvc.perform(post("/api/scheduler/rebalance")).andExpect(jsonPath("$.moved").value(false))
                .andExpect(jsonPath("$.message").value("HOLD mode: no thermal or balancing migrations"));
        assertThat(assignments).isEmpty();
    }

    @Test
    void statusAfterACycleShowsCooldownRackAndFreshTimestamp() throws Exception {
        forecast.put("R23", 36.0);
        risk.put("R23", "WARNING");
        running.add(wl(1, 30, "LOW", "R23", 1));
        cycles.reconcile();
        clock.advance(Duration.ofSeconds(5));
        cycles.reconcile();
        mvc.perform(get("/api/scheduler/status")).andExpect(jsonPath("$.cooldownRacks[0]").value("R23"))
                .andExpect(jsonPath("$.lastCycleAt").value("2026-11-18T07:10:05Z"));
    }
}
