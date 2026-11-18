package com.thermogrid.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thermogrid.workload.domain.Priority;
import com.thermogrid.workload.domain.Workload;
import com.thermogrid.workload.service.RetentionJob;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "thermogrid.workload.internal-token=s3cret")
class InternalTokenAndRetentionTest extends WorkloadTestBase {

    @Autowired RetentionJob retention;

    @Test
    void internalEndpointsRequireTheTokenWhenConfigured() throws Exception {
        mvc.perform(get("/internal/workloads")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/workloads").header("X-Internal-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/workloads").header("X-Internal-Token", "s3cret")).andExpect(status().isOk());
        mvc.perform(get("/api/workloads")).andExpect(status().isOk());
    }

    @Test
    void retentionPurgesOnlyOldRemovedWorkloads() {
        Instant now = Instant.now();
        Workload oldRemoved = new Workload("old", 10, Priority.LOW, "t", now.minus(9, ChronoUnit.DAYS));
        oldRemoved.markRemoved(now.minus(8, ChronoUnit.DAYS));
        Workload freshRemoved = new Workload("fresh", 10, Priority.LOW, "t", now);
        freshRemoved.markRemoved(now.minus(1, ChronoUnit.DAYS));
        Workload oldRunning = new Workload("run", 10, Priority.LOW, "t", now.minus(30, ChronoUnit.DAYS));
        oldRunning.assignTo("R11", java.util.UUID.randomUUID().toString(), now.minus(30, ChronoUnit.DAYS));
        repo.saveAll(java.util.List.of(oldRemoved, freshRemoved, oldRunning));

        assertThat(retention.purge()).isEqualTo(1);
        assertThat(repo.findAll()).extracting(Workload::getName).containsExactlyInAnyOrder("fresh", "run");
    }
}
