package com.thermogrid.cooling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thermogrid.cooling.domain.CoolingAction;
import com.thermogrid.cooling.listener.CoolingEventProcessor;
import com.thermogrid.cooling.listener.CoolingEventProcessor.MalformedEventException;
import com.thermogrid.cooling.service.ControlTicker;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** REST endpoints, RabbitMQ message mapping and retention. */
class CoolingApiAndEventsTest extends CoolingTestBase {

    @Autowired CoolingEventProcessor processor;
    @Autowired ControlTicker ticker;

    // ---- REST ----

    @Test
    void unitsListsEveryZoneWithOutputModeHealthAndModelledPower() throws Exception {
        reading("Z2", 30.0, "OK");
        mvc.perform(get("/api/cooling/units")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].zoneId").value("Z1"))
                .andExpect(jsonPath("$[1].zoneId").value("Z2"))
                .andExpect(jsonPath("$[1].mode").value("AUTO"))
                .andExpect(jsonPath("$[1].outputPct").value(50))
                .andExpect(jsonPath("$[1].health").value("OK"))
                .andExpect(jsonPath("$[1].targetTempC").value(26.0))
                .andExpect(jsonPath("$[1].floorPct").value(30))
                .andExpect(jsonPath("$[1].estimatedPowerKw").value(1.0))      // 0.5 + 4 * 0.125
                .andExpect(jsonPath("$[1].updatedAt").exists());
    }

    @Test
    void actionsAreNewestFirstAndLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofSeconds(2));
            reading("Z2", 40.0, "OK");
        }
        mvc.perform(get("/api/cooling/actions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].newOutputPct").value(70))
                .andExpect(jsonPath("$[0].oldOutputPct").value(60))
                .andExpect(jsonPath("$[2].newOutputPct").value(50))
                .andExpect(jsonPath("$[0].zoneId").value("Z2"))
                .andExpect(jsonPath("$[0].mode").value("AUTO"))
                .andExpect(jsonPath("$[0].reason").isNotEmpty());
        mvc.perform(get("/api/cooling/actions?limit=2")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/cooling/actions?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/cooling/actions?limit=abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/cooling/actions?limit=100000")).andExpect(status().isOk());
    }

    @Test
    void summaryIsLabelledModelledAndComparesAgainstTheSeventyPercentBaseline() throws Exception {
        // All four zones at 40 percent: 4 x (0.5 + 4 x 0.064) = 3.024 kW against 4 x 1.872 = 7.488 kW.
        mvc.perform(get("/api/cooling/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.modelled").value(true))
                .andExpect(jsonPath("$.baselineOutputPct").value(70))
                .andExpect(jsonPath("$.baselineKwPerZone").value(1.87))
                .andExpect(jsonPath("$.totalPowerKw").value(3.02))
                .andExpect(jsonPath("$.baselineTotalKw").value(7.49))
                .andExpect(jsonPath("$.savingKw").value(4.46))
                .andExpect(jsonPath("$.savingPct").value(59.62))
                .andExpect(jsonPath("$.zones.length()").value(4))
                .andExpect(jsonPath("$.zones[0].powerKw").value(0.76))
                .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("not a measurement")));
        // After an hour the integrated modelled saving is 4.464 kWh.
        control.tick();
        clock.advance(Duration.ofHours(1));
        mvc.perform(get("/api/cooling/summary"))
                .andExpect(jsonPath("$.modelledEnergySavedKwh").value(org.hamcrest.Matchers.closeTo(4.464, 0.05)))
                .andExpect(jsonPath("$.modelledEnergyUsedKwh").value(org.hamcrest.Matchers.closeTo(3.024, 0.05)));
    }

    @Test
    void manualModeEndpointSetsAndReleasesAFixedOutput() throws Exception {
        mvc.perform(post("/api/cooling/units/Z3/mode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"MANUAL\",\"outputPct\":65}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MANUAL")).andExpect(jsonPath("$.outputPct").value(65));
        assertThat(actions.findAll()).anyMatch(a -> a.getReason().contains("operator"));
        assertThat(sentFor("Z3").get(0).mode()).isEqualTo("MANUAL");
        mvc.perform(post("/api/cooling/units/Z3/mode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"AUTO\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("AUTO"));
    }

    @Test
    void manualModeEndpointValidatesItsInput() throws Exception {
        String url = "/api/cooling/units/Z3/mode";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"MANUAL\",\"outputPct\":101}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"MANUAL\",\"outputPct\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"FAILSAFE\",\"outputPct\":50}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("garbage")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/cooling/units/Z9/mode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"AUTO\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        assertThat(unit("Z3").mode()).isEqualTo("AUTO");
    }

    // ---- message mapping ----

    private byte[] json(String type, String dataJson, String occurredAt) {
        return ("{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\",\"occurredAt\":\""
                + occurredAt + "\",\"producer\":\"TEST\",\"version\":1,\"data\":" + dataJson + "}").getBytes();
    }

    @Test
    void aRawZoneReadingMessageDrivesTheControlLoop() {
        String now = clock.instant().toString();
        processor.handle("telemetry.zone.reading",
                json("telemetry.zone.reading", "{\"zoneId\":\"Z2\",\"avgTempC\":28.0,\"maxTempC\":31.0,"
                        + "\"supplyTempC\":18.0,\"fanActualPct\":40,\"unitHealth\":\"OK\"}", now));
        assertThat(unit("Z2").outputPct()).isEqualTo(50);
        assertThat(sentFor("Z2")).hasSize(1);
    }

    @Test
    void rawAlertMessagesOfAllThreeKindsAreMapped() {
        String now = clock.instant().toString();
        processor.handle("thermal.alert.raised", json("thermal.alert.raised",
                "{\"alertId\":1,\"rackId\":\"R23\",\"zoneId\":\"Z2\",\"level\":\"CRITICAL\",\"previousLevel\":\"WARNING\","
                        + "\"basis\":\"PREDICTED\",\"tempC\":33.0,\"forecast60C\":38.5}", now));
        reading("Z2", 20.0, "OK");
        assertThat(unit("Z2").outputPct()).isEqualTo(50);                 // boost 100, slewed 40 -> 50
        processor.handle("thermal.alert.lowered", json("thermal.alert.lowered",
                "{\"alertId\":1,\"rackId\":\"R23\",\"zoneId\":\"Z2\",\"level\":\"WARNING\",\"previousLevel\":\"CRITICAL\","
                        + "\"basis\":\"OBSERVED\",\"tempC\":33.0,\"forecast60C\":34.5}", now));
        processor.handle("thermal.alert.cleared", json("thermal.alert.cleared",
                "{\"alertId\":1,\"rackId\":\"R23\",\"zoneId\":\"Z2\",\"level\":\"NORMAL\",\"previousLevel\":\"WARNING\","
                        + "\"basis\":\"OBSERVED\",\"tempC\":28.0,\"forecast60C\":28.0}", now));
    }

    @Test
    void malformedMessagesAreRejectedForDeadLettering() {
        String now = clock.instant().toString();
        assertThatThrownBy(() -> processor.handle("telemetry.zone.reading", "{not json".getBytes()))
                .isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> processor.handle("telemetry.zone.reading", "{}".getBytes()))
                .isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> processor.handle("cooling.unit.state", json("cooling.unit.state", "{}", now)))
                .isInstanceOf(MalformedEventException.class);                      // not consumed here
        assertThatThrownBy(() -> processor.handle("thermal.alert.raised", json("telemetry.zone.reading",
                "{\"zoneId\":\"Z1\"}", now))).isInstanceOf(MalformedEventException.class);   // key/eventType mismatch
    }

    @Test
    void duplicateDeliveryOfTheSameZoneReadingIsHarmless() {
        String now = clock.instant().toString();
        byte[] body = json("telemetry.zone.reading", "{\"zoneId\":\"Z2\",\"avgTempC\":28.0,\"maxTempC\":31.0,"
                + "\"supplyTempC\":18.0,\"fanActualPct\":40,\"unitHealth\":\"OK\"}", now);
        processor.handle("telemetry.zone.reading", body);
        processor.handle("telemetry.zone.reading", body);
        processor.handle("telemetry.zone.reading", body);
        assertThat(unit("Z2").outputPct()).isEqualTo(50);
        assertThat(sentFor("Z2")).hasSize(1);
    }

    @Test
    void publishedMessageMatchesTheContractFields() {
        reading("Z2", 30.0, "OK");
        Sent s = sentFor("Z2").get(0);
        assertThat(Map.of("zoneId", s.data().has("zoneId"), "mode", s.data().has("mode"),
                "outputPct", s.data().has("outputPct"), "health", s.data().has("health"),
                "targetTempC", s.data().has("targetTempC"), "reason", s.data().has("reason"),
                "seq", s.data().has("seq")).values()).containsOnly(true);
        assertThat(s.envelope().get("version").asInt()).isEqualTo(1);
        assertThat(UUID.fromString(s.envelope().get("eventId").asText())).isNotNull();
        assertThat(s.envelope().get("occurredAt").asText()).isEqualTo(clock.instant().toString());
    }

    // ---- retention ----

    @Test
    void retentionPurgesActionsOlderThanTwentyFourHours() {
        actions.save(new CoolingAction("Z1", 40, 50, "AUTO", "old", clock.instant().minus(Duration.ofHours(25))));
        actions.save(new CoolingAction("Z1", 50, 60, "AUTO", "recent", clock.instant().minus(Duration.ofHours(23))));
        assertThat(ticker.purge()).isEqualTo(1);
        assertThat(actions.findAll()).extracting(CoolingAction::getReason).containsExactly("recent");
    }
}
