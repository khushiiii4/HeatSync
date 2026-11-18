package com.thermogrid.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.thermogrid.workload.domain.Priority;
import com.thermogrid.workload.domain.Workload;
import com.thermogrid.workload.domain.WorkloadStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class WorkloadControllerTest extends WorkloadTestBase {

    private long submit(String name, int cpu, String priority) throws Exception {
        String body = mvc.perform(post("/api/workloads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"cpuDemand\":" + cpu + ",\"priority\":\"" + priority + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asLong();
    }

    @Test
    void submitCreatesPendingWorkloadAndPublishesSubmitted() throws Exception {
        mvc.perform(post("/api/workloads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"batch-etl-18\",\"cpuDemand\":30,\"priority\":\"LOW\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("batch-etl-18"))
                .andExpect(jsonPath("$.cpuDemand").value(30))
                .andExpect(jsonPath("$.priority").value("LOW"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.rackId").doesNotExist())
                .andExpect(jsonPath("$.version").value(0));
        var events = sent();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).routingKey()).isEqualTo("workload.submitted");
        JsonNode env = events.get(0).body();
        assertThat(env.get("eventType").asText()).isEqualTo("workload.submitted");
        assertThat(env.get("producer").asText()).isEqualTo("WORKLOAD-SERVICE");
        assertThat(env.get("version").asInt()).isEqualTo(1);
        assertThat(env.get("data").get("name").asText()).isEqualTo("batch-etl-18");
        assertThat(env.get("data").get("cpuDemand").asInt()).isEqualTo(30);
        assertThat(env.get("data").get("priority").asText()).isEqualTo("LOW");
    }

    @Test
    void submitValidatesInput() throws Exception {
        String[] bad = {
                "{\"name\":\"x\",\"cpuDemand\":0,\"priority\":\"LOW\"}",
                "{\"name\":\"x\",\"cpuDemand\":101,\"priority\":\"LOW\"}",
                "{\"name\":\"x\",\"cpuDemand\":10,\"priority\":\"URGENT\"}",
                "{\"name\":\"\",\"cpuDemand\":10,\"priority\":\"LOW\"}",
                "{\"cpuDemand\":10,\"priority\":\"LOW\"}",
                "{\"name\":\"" + "n".repeat(61) + "\",\"cpuDemand\":10,\"priority\":\"LOW\"}",
                "{\"name\":\"x\",\"cpuDemand\":10}",
                "not json"};
        for (String b : bad) {
            mvc.perform(post("/api/workloads").contentType(MediaType.APPLICATION_JSON).content(b))
                    .andExpect(status().isBadRequest());
        }
        assertThat(repo.count()).isZero();
        assertThat(sent()).isEmpty();
        mvc.perform(post("/api/workloads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"cpuDemand\":1,\"priority\":\"LOW\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/workloads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"cpuDemand\":100,\"priority\":\"HIGH\"}")).andExpect(status().isCreated());
    }

    @Test
    void getListAndFilters() throws Exception {
        long a = submit("a", 10, "LOW");
        long b = submit("b", 20, "HIGH");
        Workload w = repo.findById(b).orElseThrow();
        w.assignTo("R11", "5b0c8e2a-41d7-4c1e-9b7a-2f6d0e8a1c33", Instant.now());
        repo.save(w);

        mvc.perform(get("/api/workloads/" + a)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("a"));
        mvc.perform(get("/api/workloads/99999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(get("/api/workloads")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/workloads?status=PENDING")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(a));
        mvc.perform(get("/api/workloads?status=RUNNING&rackId=R11")).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(b));
        mvc.perform(get("/api/workloads?rackId=R99")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/workloads?status=BOGUS")).andExpect(status().isBadRequest());
    }

    @Test
    void deleteRemovesOnceAndPublishesRemovedOnce() throws Exception {
        long id = submit("a", 25, "MEDIUM");
        mvc.perform(delete("/api/workloads/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"));
        mvc.perform(delete("/api/workloads/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"));
        mvc.perform(delete("/api/workloads/424242")).andExpect(status().isNotFound());
        var events = sent();
        assertThat(events).extracting(Sent::routingKey).containsExactly("workload.submitted", "workload.removed");
        JsonNode data = events.get(1).body().get("data");
        assertThat(data.get("workloadId").asLong()).isEqualTo(id);
        assertThat(data.get("cpuDemand").asInt()).isEqualTo(25);
        assertThat(data.get("rackId").isNull()).isTrue();
    }

    @Test
    void summaryCountsRunningPerRackAndPending() throws Exception {
        submit("p", 10, "LOW");
        long r1 = submit("r1", 20, "LOW");
        long r2 = submit("r2", 30, "LOW");
        long r3 = submit("r3", 15, "LOW");
        long gone = submit("gone", 50, "LOW");
        place(r1, "R11");
        place(r2, "R11");
        place(r3, "R23");
        place(gone, "R23");
        mvc.perform(delete("/api/workloads/" + gone)).andExpect(status().isOk());
        mvc.perform(get("/api/workloads/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.runningCount").value(3))
                .andExpect(jsonPath("$.pendingCount").value(1))
                .andExpect(jsonPath("$.totalCpuUnits").value(65))
                .andExpect(jsonPath("$.racks.length()").value(2))
                .andExpect(jsonPath("$.racks[0].rackId").value("R11"))
                .andExpect(jsonPath("$.racks[0].workloadCount").value(2))
                .andExpect(jsonPath("$.racks[0].cpuUnits").value(50))
                .andExpect(jsonPath("$.racks[1].rackId").value("R23"))
                .andExpect(jsonPath("$.racks[1].cpuUnits").value(15));
    }

    private void place(long id, String rack) {
        Workload w = repo.findById(id).orElseThrow();
        w.assignTo(rack, java.util.UUID.randomUUID().toString(), Instant.now());
        repo.saveAndFlush(w);
    }

    @Test
    void bulkSubmitsAndValidatesEveryItem() throws Exception {
        mvc.perform(post("/api/workloads/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"name\":\"a\",\"cpuDemand\":10,\"priority\":\"LOW\"},"
                                + "{\"name\":\"b\",\"cpuDemand\":11,\"priority\":\"HIGH\"}]"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(2));
        assertThat(repo.count()).isEqualTo(2);
        mvc.perform(post("/api/workloads/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"name\":\"a\",\"cpuDemand\":10,\"priority\":\"LOW\"},"
                                + "{\"name\":\"b\",\"cpuDemand\":999,\"priority\":\"HIGH\"}]"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/workloads/bulk").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());
        assertThat(repo.count()).isEqualTo(2);
    }

    @Test
    void seedDemoReproducesTheDocumentedLayout() throws Exception {
        mvc.perform(post("/api/workloads/seed-demo")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.workloadCount").value(36))
                .andExpect(jsonPath("$.totalCpuUnits").value(720));

        var all = repo.findAll();
        assertThat(all).hasSize(36).allMatch(w -> w.getStatus() == WorkloadStatus.RUNNING)
                .allMatch(w -> w.getCpuDemand() >= 10 && w.getCpuDemand() <= 30)
                .allMatch(w -> w.getRackId() != null);
        assertThat(all.stream().mapToInt(Workload::getCpuDemand).sum()).isEqualTo(720);

        Map<String, Integer> perRack = new HashMap<>();
        Map<String, Boolean> hasLow = new HashMap<>();
        for (Workload w : all) {
            perRack.merge(w.getRackId(), w.getCpuDemand(), Integer::sum);
            if (w.getPriority() == Priority.LOW) {
                hasLow.put(w.getRackId(), true);
            }
        }
        assertThat(perRack).hasSize(16);
        for (var e : perRack.entrySet()) {
            boolean z2 = e.getKey().startsWith("R2");
            if (z2) {
                assertThat(e.getValue()).as(e.getKey()).isBetween(75, 90);
                assertThat(hasLow).as("LOW job on " + e.getKey()).containsKey(e.getKey());
            } else {
                assertThat(e.getValue()).as(e.getKey()).isBetween(25, 40);
            }
        }
        // Every seeded workload is announced to the simulator as a SEED assignment with no source rack.
        var events = sent();
        assertThat(events).hasSize(36).allMatch(s -> s.routingKey().equals("workload.assigned"));
        assertThat(events).allMatch(s -> s.body().get("data").get("reason").asText().equals("SEED")
                && s.body().get("data").get("fromRackId").isNull());

        // Seeding again adds nothing and publishes nothing.
        mvc.perform(post("/api/workloads/seed-demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.workloadCount").value(36));
        assertThat(repo.count()).isEqualTo(36);
        assertThat(sent()).hasSize(36);
    }

    @Test
    void internalListSupportsStatusAndRack() throws Exception {
        mvc.perform(post("/api/workloads/seed-demo")).andExpect(status().isCreated());
        submit("pending-one", 10, "LOW");
        mvc.perform(get("/internal/workloads?status=RUNNING")).andExpect(jsonPath("$.length()").value(36));
        mvc.perform(get("/internal/workloads?status=PENDING")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/internal/workloads?status=RUNNING&rackId=R23")).andExpect(jsonPath("$.length()").value(4));
    }
}
