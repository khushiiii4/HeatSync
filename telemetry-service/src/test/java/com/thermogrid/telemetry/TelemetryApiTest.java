package com.thermogrid.telemetry;

import static com.thermogrid.telemetry.TestSupport.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.telemetry.forecast.ForecastEngine;
import com.thermogrid.telemetry.service.IngestionService;
import com.thermogrid.telemetry.service.StaleSensorService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class TelemetryApiTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    IngestionService ingestion;
    @Autowired
    StaleSensorService watchdog;

    private Instant t(int ticks) {
        return T0.plusSeconds(2L * ticks);
    }

    private void rack(String id, String zone, int tick, double temp, double power, double cpu) {
        ingestion.ingestRack(TestSupport.rack(id, zone, t(tick), temp, power, cpu));
    }

    private JsonNode json(String url) throws Exception {
        return mapper.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    // ---- racks latest ----

    @Test
    void latestRacksHaveThePlanFieldsAndAreOrderedByRackId() throws Exception {
        clock.set(T0);
        rack("R23", "Z2", 0, 29.84, 7.6, 72.5);
        rack("R11", "Z1", 0, 26.0, 6.8, 60.0);
        rack("R41", "Z4", 0, 27.5, 5.0, 40.0);

        JsonNode body = json("/api/telemetry/racks/latest");

        assertThat(body).hasSize(3);
        assertThat(body.get(0).get("rackId").asText()).isEqualTo("R11");
        assertThat(body.get(1).get("rackId").asText()).isEqualTo("R23");
        assertThat(body.get(2).get("rackId").asText()).isEqualTo("R41");
        JsonNode r23 = body.get(1);
        assertThat(r23.fieldNames()).toIterable().containsExactlyInAnyOrder("rackId", "zoneId", "tempC",
                "forecast60C", "trendCPerMin", "riskLevel", "sensorStatus", "powerKw", "cpuLoadPct", "updatedAt");
        assertThat(r23.get("zoneId").asText()).isEqualTo("Z2");
        assertThat(r23.get("tempC").asDouble()).isEqualTo(29.84);
        assertThat(r23.get("forecast60C").asDouble()).isEqualTo(29.84);
        assertThat(r23.get("trendCPerMin").asDouble()).isZero();
        assertThat(r23.get("riskLevel").asText()).isEqualTo("NORMAL");
        assertThat(r23.get("sensorStatus").asText()).isEqualTo("OK");
        assertThat(r23.get("powerKw").asDouble()).isEqualTo(7.6);
        assertThat(r23.get("cpuLoadPct").asDouble()).isEqualTo(72.5);
        assertThat(r23.get("updatedAt").asText()).isEqualTo("2026-11-18T07:00:00Z");
    }

    @Test
    void latestRacksReportTrendPerMinuteForecastRiskAndStaleStatus() throws Exception {
        for (int i = 0; i < 3; i++) {
            rack("R23", "Z2", i, 26.0, 7.0, 70.0);
        }
        rack("R23", "Z2", 3, 29.5, 7.0, 70.0);

        ForecastEngine engine = new ForecastEngine(0.4, 0.15, 0.95, 30);
        ForecastEngine.State s = null;
        ForecastEngine.Result last = null;
        for (double y : new double[] {26, 26, 26, 29.5}) {
            last = engine.update(s, y);
            s = last.state();
        }

        JsonNode r = json("/api/telemetry/racks/latest").get(0);
        assertThat(r.get("forecast60C").asDouble()).isEqualTo(Math.round(last.forecast() * 100.0) / 100.0);
        assertThat(r.get("trendCPerMin").asDouble()).isEqualTo(Math.round(last.state().trend() * 30 * 100.0) / 100.0);
        assertThat(r.get("riskLevel").asText()).isEqualTo("WATCH");

        clock.set(T0.plusSeconds(60));
        watchdog.markStaleRacks();
        assertThat(json("/api/telemetry/racks/latest").get(0).get("sensorStatus").asText()).isEqualTo("STALE");
    }

    @Test
    void noRacksYetGivesAnEmptyArrayNotFabricatedRows() throws Exception {
        mvc.perform(get("/api/telemetry/racks/latest")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/telemetry/zones/latest")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/alerts")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/internal/telemetry/racks/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- internal ----

    @Test
    void internalEndpointExposesEverythingTheSchedulerNeedsInRackOrder() throws Exception {
        rack("R33", "Z3", 0, 27.0, 6.0, 55.0);
        rack("R12", "Z1", 0, 25.0, 4.0, 30.0);
        rack("R12", "Z1", 1, 25.5, 4.0, 30.0);

        JsonNode body = json("/internal/telemetry/racks/latest");

        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("rackId").asText()).isEqualTo("R12");
        assertThat(body.get(1).get("rackId").asText()).isEqualTo("R33");
        JsonNode r = body.get(0);
        assertThat(r.fieldNames()).toIterable().containsExactlyInAnyOrder("rackId", "zoneId", "tempC", "forecast60C",
                "trendCPerMin", "riskLevel", "sensorStatus", "powerKw", "cpuLoadPct", "lastReadingAt", "updatedAt");
        assertThat(r.get("zoneId").asText()).isEqualTo("Z1");
        assertThat(r.get("tempC").asDouble()).isEqualTo(25.5);
        assertThat(r.get("forecast60C").asDouble()).isGreaterThan(25.5);
        assertThat(r.get("trendCPerMin").asDouble()).isPositive();
        assertThat(r.get("riskLevel").asText()).isEqualTo("NORMAL");
        assertThat(r.get("sensorStatus").asText()).isEqualTo("OK");
        assertThat(r.get("powerKw").asDouble()).isEqualTo(4.0);
        assertThat(r.get("cpuLoadPct").asDouble()).isEqualTo(30.0);
        assertThat(Instant.parse(r.get("lastReadingAt").asText())).isEqualTo(t(1));
    }

    // ---- zones ----

    @Test
    void latestZonesReturnTheNewestReadingPerZoneInZoneOrder() throws Exception {
        ingestion.ingestZone(TestSupport.zone("Z2", t(0), 27.0, 28.0, 40, "OK"));
        ingestion.ingestZone(TestSupport.zone("Z2", t(5), 29.0, 31.0, 0, "FAULT"));
        ingestion.ingestZone(TestSupport.zone("Z1", t(2), 26.0, 26.5, 40, "OK"));
        ingestion.ingestZone(TestSupport.zone("Z2", t(3), 28.0, 29.0, 10, "DEGRADED"));

        JsonNode body = json("/api/telemetry/zones/latest");

        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("zoneId").asText()).isEqualTo("Z1");
        JsonNode z2 = body.get(1);
        assertThat(z2.fieldNames()).toIterable().containsExactlyInAnyOrder("zoneId", "avgTempC", "maxTempC",
                "supplyTempC", "fanActualPct", "unitHealth", "recordedAt");
        assertThat(z2.get("zoneId").asText()).isEqualTo("Z2");
        assertThat(z2.get("avgTempC").asDouble()).isEqualTo(29.0);
        assertThat(z2.get("maxTempC").asDouble()).isEqualTo(31.0);
        assertThat(z2.get("supplyTempC").asDouble()).isEqualTo(18.0);
        assertThat(z2.get("fanActualPct").asInt()).isZero();
        assertThat(z2.get("unitHealth").asText()).isEqualTo("FAULT");
        assertThat(Instant.parse(z2.get("recordedAt").asText())).isEqualTo(t(5));
    }

    // ---- history ----

    @Test
    void historyReturnsTheWindowInTimeOrderWithAReplayedForecast() throws Exception {
        for (int i = 0; i < 20; i++) {
            rack("R23", "Z2", i, 26.0 + 0.3 * i, 7.0, 70.0);
        }
        clock.set(t(20));   // 40 s after the first reading, so a 10-minute window holds all of it

        JsonNode h = json("/api/telemetry/racks/R23/history?minutes=10");

        assertThat(h.get("rackId").asText()).isEqualTo("R23");
        assertThat(h.get("minutes").asInt()).isEqualTo(10);
        JsonNode pts = h.get("points");
        assertThat(pts).hasSize(20);
        assertThat(Instant.parse(pts.get(0).get("recordedAt").asText())).isEqualTo(t(0));
        assertThat(Instant.parse(pts.get(19).get("recordedAt").asText())).isEqualTo(t(19));
        assertThat(pts.get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("recordedAt", "tempC", "forecast60C", "powerKw", "cpuLoadPct");

        ForecastEngine engine = new ForecastEngine(0.4, 0.15, 0.95, 30);
        ForecastEngine.State s = null;
        for (int i = 0; i < 20; i++) {
            ForecastEngine.Result f = engine.update(s, 26.0 + 0.3 * i);
            s = f.state();
            assertThat(pts.get(i).get("forecast60C").asDouble()).as("point " + i)
                    .isEqualTo(Math.round(f.forecast() * 100.0) / 100.0);
        }
        // the live state forecast and the last replayed point agree
        assertThat(pts.get(19).get("forecast60C").asDouble())
                .isEqualTo(json("/api/telemetry/racks/latest").get(0).get("forecast60C").asDouble());
    }

    @Test
    void historyOnlyIncludesTheRequestedMinutes() throws Exception {
        rack("R23", "Z2", 0, 26.0, 7.0, 70.0);        // 07:00:00
        rack("R23", "Z2", 40, 27.0, 7.0, 70.0);       // 07:01:20
        rack("R23", "Z2", 80, 28.0, 7.0, 70.0);       // 07:02:40
        clock.set(T0.plusSeconds(170));               // window of 1 minute starts 07:01:50

        JsonNode pts = json("/api/telemetry/racks/R23/history?minutes=1").get("points");
        assertThat(pts).hasSize(1);
        assertThat(Instant.parse(pts.get(0).get("recordedAt").asText())).isEqualTo(t(80));
        assertThat(json("/api/telemetry/racks/R23/history").get("minutes").asInt()).as("default is 10").isEqualTo(10);
    }

    @Test
    void historyValidatesMinutesAndRack() throws Exception {
        rack("R23", "Z2", 0, 26.0, 7.0, 70.0);
        for (String bad : new String[] {"0", "-3", "361", "abc"}) {
            mvc.perform(get("/api/telemetry/racks/R23/history?minutes=" + bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        }
        mvc.perform(get("/api/telemetry/racks/R23/history?minutes=360")).andExpect(status().isOk());
        mvc.perform(get("/api/telemetry/racks/R99/history?minutes=10")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/telemetry/racks/R99/history"));
    }

    // ---- alerts ----

    @Test
    void alertsListFiltersByStateAndLimitNewestFirst() throws Exception {
        for (String rack : new String[] {"R11", "R12", "R13"}) {
            rack(rack, "Z1", 0, 26.0, 7.0, 70.0);
            rack(rack, "Z1", 1, 40.0, 7.0, 70.0);   // each raises
        }
        // R11 cools down and clears
        for (int i = 2; i < 40; i++) {
            rack("R11", "Z1", i, 20.0, 7.0, 70.0);
        }

        JsonNode active = json("/api/alerts?state=ACTIVE&limit=50");
        assertThat(active).hasSize(2);
        assertThat(active.get(0).get("rackId").asText()).isEqualTo("R13");
        assertThat(active.get(1).get("rackId").asText()).isEqualTo("R12");
        JsonNode a = active.get(0);
        assertThat(a.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "rackId", "zoneId", "level", "basis",
                "tempC", "forecastC", "state", "raisedAt", "updatedAt", "clearedAt");
        assertThat(a.get("level").asText()).isEqualTo("CRITICAL");
        assertThat(a.get("basis").asText()).isEqualTo("OBSERVED");
        assertThat(a.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(a.get("tempC").asDouble()).isEqualTo(40.0);
        assertThat(a.get("clearedAt").isNull()).isTrue();

        assertThat(json("/api/alerts?state=ALL")).hasSize(3);
        assertThat(json("/api/alerts?state=CLEARED")).hasSize(1);
        assertThat(json("/api/alerts?state=CLEARED").get(0).get("rackId").asText()).isEqualTo("R11");
        assertThat(json("/api/alerts?state=CLEARED").get(0).get("clearedAt").isNull()).isFalse();
        assertThat(json("/api/alerts?state=ALL&limit=2")).hasSize(2);
        assertThat(json("/api/alerts")).as("default is ACTIVE, limit 50").hasSize(2);
        assertThat(json("/api/alerts?state=active")).hasSize(2);
    }

    @Test
    void alertsValidateStateAndLimit() throws Exception {
        for (String q : new String[] {"state=BOGUS", "limit=0", "limit=501", "limit=abc", "state=ACTIVE&limit=-1"}) {
            mvc.perform(get("/api/alerts?" + q)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.path").value("/api/alerts"));
        }
        mvc.perform(get("/api/alerts?limit=500")).andExpect(status().isOk());
    }
}
