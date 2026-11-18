package com.thermogrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.simulator.failure.FailureRepository;
import com.thermogrid.simulator.sim.SimulationEngine;
import com.thermogrid.simulator.topology.TopologyClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Failure and control endpoints on the full application context (in-memory H2, stub topology, no RabbitMQ). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SimFailureApiTest.StubTopology.class)
class SimFailureApiTest {

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
    FailureRepository repo;

    @BeforeEach
    void clean() {
        engine.reset();
    }

    private ResultActions inject(String body) throws Exception {
        return mvc.perform(post("/api/sim/failures").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long injectOk(String body) throws Exception {
        String res = inject(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("id").asLong();
    }

    private ResultActions control(String body) throws Exception {
        return mvc.perform(post("/api/sim/control").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---- inject ----

    @Test
    void postCreatesTheFailureAndReturnsTheStoredRecord() throws Exception {
        inject("{\"type\":\"COOLING_FAILURE\",\"targetId\":\"Z3\",\"durationSec\":0}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.type").value("COOLING_FAILURE"))
                .andExpect(jsonPath("$.targetId").value("Z3"))
                .andExpect(jsonPath("$.startedAt").value(org.hamcrest.Matchers.matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")))
                .andExpect(jsonPath("$.endsAt").doesNotExist())
                .andExpect(jsonPath("$.clearedAt").doesNotExist())
                .andExpect(jsonPath("$.injectedBy").value("anonymous"));
    }

    @Test
    void positiveDurationReturnsEndsAtAndTheGatewayUserIsRecorded() throws Exception {
        String res = mvc.perform(post("/api/sim/failures").contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Name", "operator")
                        .content("{\"type\":\"HEAT_SPIKE\",\"targetId\":\"R23\",\"durationSec\":120,\"param\":3.5}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode j = mapper.readTree(res);
        assertThat(j.get("param").asDouble()).isEqualTo(3.5);
        assertThat(j.get("injectedBy").asText()).isEqualTo("operator");
        assertThat(java.time.Instant.parse(j.get("endsAt").asText()))
                .isEqualTo(java.time.Instant.parse(j.get("startedAt").asText()).plusSeconds(120));
    }

    @Test
    void degradedAndDropoutAreAccepted() throws Exception {
        inject("{\"type\":\"COOLING_DEGRADED\",\"targetId\":\"Z1\",\"durationSec\":0,\"param\":0.25}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.param").value(0.25));
        inject("{\"type\":\"SENSOR_DROPOUT\",\"targetId\":\"R11\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void invalidRequestsGetA400WithAClearMessage() throws Exception {
        String[][] cases = {
                {"{\"type\":\"COOLING_FAILURE\",\"targetId\":\"Z9\"}", "not a zone"},
                {"{\"type\":\"COOLING_FAILURE\",\"targetId\":\"R11\"}", "not a zone"},
                {"{\"type\":\"SENSOR_DROPOUT\",\"targetId\":\"R99\"}", "not a rack"},
                {"{\"type\":\"SENSOR_DROPOUT\",\"targetId\":\"Z1\"}", "not a rack"},
                {"{\"type\":\"COOLING_DEGRADED\",\"targetId\":\"Z1\",\"param\":1.5}", "remaining efficiency"},
                {"{\"type\":\"COOLING_DEGRADED\",\"targetId\":\"Z1\"}", "remaining efficiency"},
                {"{\"type\":\"HEAT_SPIKE\",\"targetId\":\"R11\",\"param\":-2}", "extra kW"},
                {"{\"type\":\"HEAT_SPIKE\",\"targetId\":\"R11\"}", "extra kW"},
                {"{\"type\":\"COOLING_FAILURE\",\"targetId\":\"Z1\",\"param\":0.5}", "does not take a param"},
                {"{\"type\":\"COOLING_FAILURE\",\"targetId\":\"Z1\",\"durationSec\":-5}", "durationSec"},
                {"{\"type\":\"EXPLODE\",\"targetId\":\"Z1\"}", "Unknown type"},
                {"{\"targetId\":\"Z1\"}", "type is required"},
                {"{\"type\":\"COOLING_FAILURE\"}", "targetId is required"},
                {"{\"type\":\"COOLING_FAILURE\",\"targetId\":\"Z1\",\"durationSec\":\"soon\"}", "valid JSON"},
                {"this is not json", "valid JSON"},
        };
        int stored = repo.findAll().size();
        for (String[] c : cases) {
            inject(c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.path").value("/api/sim/failures"))
                    .andExpect(jsonPath("$.timestamp").exists())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(c[1])));
        }
        assertThat(repo.findAll()).as("rejected requests store nothing").hasSize(stored);
    }

    // ---- list ----

    @Test
    void listFiltersActiveAndKeepsHistory() throws Exception {
        int history = repo.findAll().size();   // earlier tests leave cleared rows behind: history is never deleted
        long keep = injectOk("{\"type\":\"COOLING_FAILURE\",\"targetId\":\"Z2\"}");
        long gone = injectOk("{\"type\":\"SENSOR_DROPOUT\",\"targetId\":\"R11\"}");
        mvc.perform(delete("/api/sim/failures/" + gone)).andExpect(status().isOk());

        mvc.perform(get("/api/sim/failures").param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(keep));
        mvc.perform(get("/api/sim/failures"))
                .andExpect(jsonPath("$", hasSize(history + 2)))
                .andExpect(jsonPath("$[" + (history + 1) + "].id").value(gone))
                .andExpect(jsonPath("$[" + (history + 1) + "].clearedAt").exists());
        mvc.perform(get("/api/sim/failures").param("active", "false")).andExpect(jsonPath("$", hasSize(history + 2)));
        mvc.perform(get("/api/sim/failures").param("active", "maybe")).andExpect(status().isBadRequest());
    }

    // ---- clear ----

    @Test
    void deleteClearsPersistsClearedAtAndReportsMissingAndRepeated() throws Exception {
        long id = injectOk("{\"type\":\"COOLING_FAILURE\",\"targetId\":\"Z2\"}");

        mvc.perform(delete("/api/sim/failures/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.clearedAt").exists());
        assertThat(repo.findById(id).orElseThrow().clearedAt()).isNotNull();

        mvc.perform(delete("/api/sim/failures/" + id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
        mvc.perform(delete("/api/sim/failures/987654")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Failure 987654 not found"))
                .andExpect(jsonPath("$.path").value("/api/sim/failures/987654"));
        mvc.perform(delete("/api/sim/failures/abc")).andExpect(status().isBadRequest());
    }

    // ---- state ----

    @Test
    void stateListsRealActiveFailuresAndStaysCompatible() throws Exception {
        mvc.perform(get("/api/sim/state")).andExpect(jsonPath("$.activeFailures", hasSize(0)));
        long id = injectOk("{\"type\":\"HEAT_SPIKE\",\"targetId\":\"R23\",\"param\":2.5}");

        JsonNode body = mapper.readTree(mvc.perform(get("/api/sim/state")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("tick", "timestamp", "paused", "racks", "zones", "activeFailures");
        assertThat(body.get("activeFailures")).hasSize(1);
        assertThat(body.get("activeFailures").get(0).get("id").asLong()).isEqualTo(id);
        assertThat(body.get("activeFailures").get(0).get("type").asText()).isEqualTo("HEAT_SPIKE");
        assertThat(body.get("activeFailures").get(0).get("targetId").asText()).isEqualTo("R23");
        assertThat(body.get("racks")).hasSize(16);

        mvc.perform(delete("/api/sim/failures/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/sim/state")).andExpect(jsonPath("$.activeFailures", hasSize(0)));
    }

    @Test
    void injectedCoolingFailureShowsInZoneHealthAfterATickAndClearingRestoresIt() throws Exception {
        long id = injectOk("{\"type\":\"COOLING_FAILURE\",\"targetId\":\"Z3\"}");
        engine.tick();
        mvc.perform(get("/api/sim/state")).andExpect(jsonPath("$.zones[2].health").value("FAULT"))
                .andExpect(jsonPath("$.zones[0].health").value("OK"));

        mvc.perform(delete("/api/sim/failures/" + id)).andExpect(status().isOk());
        engine.tick();
        mvc.perform(get("/api/sim/state")).andExpect(jsonPath("$.zones[2].health").value("OK"));
    }

    // ---- control ----

    @Test
    void pauseStopsTicksResumeContinuesAndResetRestoresInitialState() throws Exception {
        engine.tick();
        engine.tick();
        control("{\"action\":\"PAUSE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("PAUSE")).andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.tick").value(2));
        engine.tick();
        mvc.perform(get("/api/sim/state")).andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.tick").value(2));

        control("{\"action\":\"RESUME\"}").andExpect(jsonPath("$.paused").value(false));
        engine.tick();
        mvc.perform(get("/api/sim/state")).andExpect(jsonPath("$.tick").value(3));

        long id = injectOk("{\"type\":\"COOLING_FAILURE\",\"targetId\":\"Z1\"}");
        control("{\"action\":\"PAUSE\"}");
        control("{\"action\":\"RESET\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("RESET")).andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.tick").value(0));
        mvc.perform(get("/api/sim/state"))
                .andExpect(jsonPath("$.tick").value(0))
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.activeFailures", hasSize(0)))
                .andExpect(jsonPath("$.racks[0].tempC").value(26.0))
                .andExpect(jsonPath("$.zones[0].coolingOutputPct").value(40.0));
        assertThat(repo.findById(id).orElseThrow().clearedAt()).as("history kept, marked cleared").isNotNull();
        assertThat(repo.findAll()).extracting(f -> f.id()).contains(id);
        engine.tick();
        mvc.perform(get("/api/sim/state")).andExpect(jsonPath("$.tick").value(1));
    }

    @Test
    void invalidControlActionsAreRejected() throws Exception {
        for (String body : new String[] {"{\"action\":\"EXPLODE\"}", "{}", "{\"action\":\"pause\"}", "nope"}) {
            control(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        }
    }
}
