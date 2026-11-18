package com.thermogrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.simulator.config.SimulatorProperties;
import com.thermogrid.simulator.sim.SimulationEngine;
import com.thermogrid.simulator.topology.RackGrid;
import com.thermogrid.simulator.topology.TopologyClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/** Full context on in-memory H2 with the topology Feign client mocked: no MySQL, Eureka or topology-service. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SimApiTest.StubTopology.class)
class SimApiTest {

    /** Replaces the Feign client before the grid bean is built at startup, so no network is involved. */
    @TestConfiguration
    static class StubTopology {
        @Bean
        @Primary
        TopologyClient stubTopologyClient() {
            return TestTopology::payload;
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    SimulationEngine engine;
    @Autowired
    RackGrid grid;
    @Autowired
    SimulatorProperties props;

    @BeforeEach
    void freshSimulation() {
        engine.resume();
        engine.reset();
    }

    @Test
    void configurationBindsThePlanConstants() {
        assertThat(props.tickMs()).isEqualTo(2000);
        assertThat(props.physics().supplyTempC()).isEqualTo(18.0);
        assertThat(props.physics().alpha()).isEqualTo(0.06);
        assertThat(props.physics().beta()).isEqualTo(0.12);
        assertThat(props.physics().gamma()).isEqualTo(0.05);
        assertThat(props.physics().noiseSigmaC()).isEqualTo(0.05);
        assertThat(props.physics().minCoolingEffectiveness()).isEqualTo(0.10);
        assertThat(props.initial().coolingOutputPct()).isEqualTo(40.0);
        assertThat(props.initial().cpuUnits()).isEqualTo(60.0);
        assertThat(props.initial().health().name()).isEqualTo("OK");
        assertThat(props.physicsParams().healthFactor(com.thermogrid.simulator.physics.HealthState.DEGRADED))
                .isEqualTo(0.25);
        assertThat(grid.racks()).hasSize(16);
    }

    @Test
    void stateEndpointReturnsGroundTruthForAllRacksAndZones() throws Exception {
        mvc.perform(get("/api/sim/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tick").value(0))
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.racks", hasSize(16)))
                .andExpect(jsonPath("$.zones", hasSize(4)))
                .andExpect(jsonPath("$.activeFailures", hasSize(0)))
                .andExpect(jsonPath("$.racks[0].id").value("R11"))
                .andExpect(jsonPath("$.racks[0].tempC").value(26.0))
                .andExpect(jsonPath("$.racks[0].cpuUnits").value(60.0))
                .andExpect(jsonPath("$.racks[0].powerKw").value(6.8))
                .andExpect(jsonPath("$.racks[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.zones[1].id").value("Z2"))
                .andExpect(jsonPath("$.zones[1].coolingOutputPct").value(40.0))
                .andExpect(jsonPath("$.zones[1].health").value("OK"));
    }

    @Test
    void stateReflectsATickAndUsesIsoUtcTimestamp() throws Exception {
        engine.tick();
        JsonNode body = mapper.readTree(mvc.perform(get("/api/sim/state")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(body.get("tick").asLong()).isEqualTo(1);
        assertThat(body.get("timestamp").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
        assertThat(body.get("racks").get(0).get("tempC").asDouble()).isNotEqualTo(26.0);
    }

    @Test
    void pausedFlagIsExposedAndTicksStop() throws Exception {
        engine.pause();
        engine.tick();
        mvc.perform(get("/api/sim/state"))
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.tick").value(0));
    }

    @Test
    void responseExposesOnlyDtoFieldsNotInternalObjects() throws Exception {
        JsonNode body = mapper.readTree(mvc.perform(get("/api/sim/state")).andReturn().getResponse()
                .getContentAsString());
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("tick", "timestamp", "paused", "racks", "zones", "activeFailures");
        assertThat(body.get("racks").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "zoneId", "rowIndex", "colIndex", "status", "tempC", "cpuUnits", "cpuCapacity", "powerKw",
                "heatSpikeKw");
        assertThat(body.get("zones").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "name", "rowIndex", "coolingOutputPct", "health");
    }

    @Test
    void stateIsASnapshotCopyLaterTicksDoNotChangeAnAlreadyBuiltResponse() throws Exception {
        String first = mvc.perform(get("/api/sim/state")).andReturn().getResponse().getContentAsString();
        engine.tick();
        engine.tick();
        assertThat(mapper.readTree(first).get("tick").asLong()).isZero();
        assertThat(mapper.readTree(mvc.perform(get("/api/sim/state")).andReturn().getResponse()
                .getContentAsString()).get("tick").asLong()).isEqualTo(2);
    }
}
