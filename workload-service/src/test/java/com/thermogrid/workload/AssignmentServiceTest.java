package com.thermogrid.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thermogrid.workload.domain.Workload;
import com.thermogrid.workload.domain.WorkloadStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class AssignmentServiceTest extends WorkloadTestBase {

    private long newWorkload(int cpu) throws Exception {
        String body = mvc.perform(post("/api/workloads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"w\",\"cpuDemand\":" + cpu + ",\"priority\":\"LOW\"}"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asLong();
    }

    private ResultActions assign(long id, String to, String from, int version, String migrationId, String reason)
            throws Exception {
        String json = "{\"toRackId\":\"" + to + "\",\"expectedFromRackId\":" + (from == null ? "null" : "\"" + from + "\"")
                + ",\"expectedVersion\":" + version + ",\"migrationId\":\"" + migrationId + "\",\"reason\":\"" + reason + "\"}";
        return mvc.perform(put("/internal/workloads/" + id + "/assignment")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    @Test
    void firstPlacementMovesPendingToRunningAndPublishesAssigned() throws Exception {
        long id = newWorkload(30);
        String m = uuid();
        assign(id, "R41", null, 0, m, "INITIAL_PLACEMENT").andExpect(status().isOk())
                .andExpect(jsonPath("$.workloadId").value(id)).andExpect(jsonPath("$.rackId").value("R41"))
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.status").value("RUNNING"));

        var events = sent();
        assertThat(events).extracting(Sent::routingKey).containsExactly("workload.submitted", "workload.assigned");
        var data = events.get(1).body().get("data");
        assertThat(data.get("workloadId").asLong()).isEqualTo(id);
        assertThat(data.get("cpuDemand").asInt()).isEqualTo(30);
        assertThat(data.get("priority").asText()).isEqualTo("LOW");
        assertThat(data.get("fromRackId").isNull()).isTrue();
        assertThat(data.get("toRackId").asText()).isEqualTo("R41");
        assertThat(data.get("reason").asText()).isEqualTo("INITIAL_PLACEMENT");
        assertThat(data.get("migrationId").asText()).isEqualTo(m);
        assertThat(data.get("version").asInt()).isEqualTo(1);
    }

    @Test
    void migrationCarriesSourceRackAndBumpsVersion() throws Exception {
        long id = newWorkload(30);
        assign(id, "R23", null, 0, uuid(), "INITIAL_PLACEMENT").andExpect(status().isOk());
        assign(id, "R41", "R23", 1, uuid(), "THERMAL_WARNING").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.rackId").value("R41"));
        var last = sent().get(2).body().get("data");
        assertThat(last.get("fromRackId").asText()).isEqualTo("R23");
        assertThat(last.get("toRackId").asText()).isEqualTo("R41");
        assertThat(last.get("version").asInt()).isEqualTo(2);
    }

    @Test
    void staleVersionReturns409AndAppliesNothing() throws Exception {
        long id = newWorkload(20);
        assign(id, "R11", null, 0, uuid(), "INITIAL_PLACEMENT").andExpect(status().isOk());
        int eventsBefore = sent().size();
        assign(id, "R12", "R11", 0, uuid(), "REBALANCE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
        Workload w = repo.findById(id).orElseThrow();
        assertThat(w.getRackId()).isEqualTo("R11");
        assertThat(w.getVersion()).isEqualTo(1);
        assertThat(sent()).hasSize(eventsBefore);
    }

    @Test
    void wrongSourceRackReturns409() throws Exception {
        long id = newWorkload(20);
        assign(id, "R11", null, 0, uuid(), "INITIAL_PLACEMENT").andExpect(status().isOk());
        assign(id, "R12", "R99", 1, uuid(), "REBALANCE").andExpect(status().isConflict());
        assign(id, "R12", null, 1, uuid(), "REBALANCE").andExpect(status().isConflict());
        assertThat(repo.findById(id).orElseThrow().getRackId()).isEqualTo("R11");
    }

    @Test
    void repeatedMigrationIdReturnsSameBodyAndChangesNothing() throws Exception {
        long id = newWorkload(20);
        String m = uuid();
        String first = assign(id, "R11", null, 0, m, "INITIAL_PLACEMENT").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int events = sent().size();
        // Same call again, even with the now-stale expected version and source rack.
        String second = assign(id, "R11", null, 0, m, "INITIAL_PLACEMENT").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        assertThat(repo.findById(id).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(sent()).hasSize(events);
    }

    @Test
    void repeatedMigrationIdIsMatchedCaseInsensitively() throws Exception {
        long id = newWorkload(20);
        String m = uuid();
        assign(id, "R11", null, 0, m, "INITIAL_PLACEMENT").andExpect(status().isOk());
        assign(id, "R11", null, 0, m.toUpperCase(), "INITIAL_PLACEMENT").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void removedWorkloadCannotBeAssigned() throws Exception {
        long id = newWorkload(20);
        mvc.perform(delete("/api/workloads/" + id)).andExpect(status().isOk());
        int events = sent().size();
        assign(id, "R11", null, 0, uuid(), "INITIAL_PLACEMENT").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WORKLOAD_REMOVED"));
        assertThat(repo.findById(id).orElseThrow().getStatus()).isEqualTo(WorkloadStatus.REMOVED);
        assertThat(sent()).hasSize(events);
    }

    @Test
    void removalAfterPlacementReportsTheRack() throws Exception {
        long id = newWorkload(20);
        assign(id, "R44", null, 0, uuid(), "INITIAL_PLACEMENT").andExpect(status().isOk());
        mvc.perform(delete("/api/workloads/" + id)).andExpect(status().isOk());
        var data = sent().get(2).body().get("data");
        assertThat(sent().get(2).routingKey()).isEqualTo("workload.removed");
        assertThat(data.get("rackId").asText()).isEqualTo("R44");
        assertThat(data.get("cpuDemand").asInt()).isEqualTo(20);
    }

    @Test
    void unknownWorkloadReturns404() throws Exception {
        assign(987654, "R11", null, 0, uuid(), "INITIAL_PLACEMENT").andExpect(status().isNotFound());
    }

    @Test
    void invalidRequestsReturn400() throws Exception {
        long id = newWorkload(20);
        assign(id, "R11", null, 0, "not-a-uuid", "INITIAL_PLACEMENT").andExpect(status().isBadRequest());
        assign(id, "R11", null, 0, uuid(), "BECAUSE").andExpect(status().isBadRequest());
        assign(id, "bad rack!", null, 0, uuid(), "SEED").andExpect(status().isBadRequest());
        assign(id, "", null, 0, uuid(), "SEED").andExpect(status().isBadRequest());
        mvc.perform(put("/internal/workloads/" + id + "/assignment").contentType(MediaType.APPLICATION_JSON)
                .content("{\"toRackId\":\"R11\"}")).andExpect(status().isBadRequest());
        assign(id, "R11", null, 0, uuid(), "INITIAL_PLACEMENT").andExpect(status().isOk());
        assign(id, "R11", "R11", 1, uuid(), "REBALANCE").andExpect(status().isBadRequest());
        assertThat(repo.findById(id).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void concurrentAssignmentsWithTheSameVersionApplyExactlyOnce() throws Exception {
        long id = newWorkload(20);
        assign(id, "R11", null, 0, uuid(), "INITIAL_PLACEMENT").andExpect(status().isOk());
        int events = sent().size();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String target = "R2" + (i % 4 + 1);
            results.add(pool.submit(() -> {
                start.await();
                return assign(id, target, "R11", 1, uuid(), "REBALANCE").andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 200) {
                ok++;
            } else if (s == 409) {
                conflict++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(repo.findById(id).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(sent()).hasSize(events + 1);
    }
}
