package com.thermogrid.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Full context on in-memory H2 (profile "test"); the startup runner seeds the data. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TopologyApiTest {

    @Autowired MockMvc mvc;

    @Test
    void topologyReturnsZonesWithRacksInDeterministicOrder() throws Exception {
        mvc.perform(get("/api/topology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value("Z1"))
                .andExpect(jsonPath("$[3].id").value("Z4"))
                .andExpect(jsonPath("$[1].targetTempC").value(26.0))
                .andExpect(jsonPath("$[1].racks.length()").value(4))
                .andExpect(jsonPath("$[1].racks[0].id").value("R21"))
                .andExpect(jsonPath("$[1].racks[3].id").value("R24"))
                .andExpect(jsonPath("$[3].racks[3].id").value("R44"))
                .andExpect(jsonPath("$[2].racks[1].zoneId").value("Z3"))
                .andExpect(jsonPath("$[2].racks[1].rowIndex").value(3))
                .andExpect(jsonPath("$[2].racks[1].colIndex").value(2));
    }

    @Test
    void topologyContainsSixteenRacksInRowMajorOrder() throws Exception {
        String json = mvc.perform(get("/api/topology")).andReturn().getResponse().getContentAsString();
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        java.util.List<String> ids = new java.util.ArrayList<>();
        root.forEach(z -> z.get("racks").forEach(r -> ids.add(r.get("id").asText())));
        assertThat(ids).containsExactly("R11", "R12", "R13", "R14", "R21", "R22", "R23", "R24",
                "R31", "R32", "R33", "R34", "R41", "R42", "R43", "R44");
    }

    @Test
    void rackEndpointReturnsTheRequestedRack() throws Exception {
        mvc.perform(get("/api/topology/racks/R23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("R23"))
                .andExpect(jsonPath("$.zoneId").value("Z2"))
                .andExpect(jsonPath("$.rowIndex").value(2))
                .andExpect(jsonPath("$.colIndex").value(3))
                .andExpect(jsonPath("$.cpuCapacity").value(100))
                .andExpect(jsonPath("$.idlePowerKw").value(2.0))
                .andExpect(jsonPath("$.maxPowerKw").value(10.0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void unknownRackReturns404WithStandardErrorBody() throws Exception {
        mvc.perform(get("/api/topology/racks/R99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Rack R99 not found"))
                .andExpect(jsonPath("$.path").value("/api/topology/racks/R99"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void internalTopologyIsIdenticalToThePublicPayload() throws Exception {
        String publicJson = mvc.perform(get("/api/topology")).andReturn().getResponse().getContentAsString();
        var internal = mvc.perform(get("/internal/topology")).andExpect(status().isOk()).andReturn();
        assertThat(internal.getResponse().getContentAsString()).isEqualTo(publicJson);
    }
}