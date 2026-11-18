package com.thermogrid.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static com.thermogrid.telemetry.TestSupport.T0;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thermogrid.telemetry.service.IngestionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Phase 12: DELETE /api/telemetry/synthetic removes only the flood generator's SYN racks. */
@AutoConfigureMockMvc
class SyntheticCleanupTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    IngestionService ingestion;

    @Test
    void deletesSyntheticRacksAndReadingsButNeverRealOnes() throws Exception {
        clock.set(T0);
        Instant t = T0;
        ingestion.ingestRack(TestSupport.rack("R11", "Z1", t, 26.0, 6.0, 50.0));
        ingestion.ingestRack(TestSupport.rack("SYN001", "ZSYN", t, 24.0, 5.0, 40.0));
        ingestion.ingestRack(TestSupport.rack("SYN002", "ZSYN", t.plusMillis(1), 24.0, 5.0, 40.0));
        ingestion.ingestRack(TestSupport.rack("SYN001", "ZSYN", t.plusMillis(2), 24.1, 5.0, 40.0));
        assertThat(rackReadings.countByRackId("SYN001")).isEqualTo(2);

        mvc.perform(delete("/api/telemetry/synthetic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readings").value(3))
                .andExpect(jsonPath("$.forecastStates").value(2));

        assertThat(rackReadings.countByRackId("SYN001")).isZero();
        assertThat(rackReadings.countByRackId("SYN002")).isZero();
        assertThat(rackReadings.countByRackId("R11")).isEqualTo(1);
        assertThat(states.findAll()).extracting("rackId").containsExactly("R11");
    }
}
