package com.thermogrid.telemetry;

import static com.thermogrid.telemetry.TestSupport.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.Exchanges;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.telemetry.domain.Alert;
import com.thermogrid.telemetry.domain.AlertBasis;
import com.thermogrid.telemetry.domain.AlertState;
import com.thermogrid.telemetry.domain.RackForecastState;
import com.thermogrid.telemetry.domain.SensorStatus;
import com.thermogrid.telemetry.forecast.ForecastEngine;
import com.thermogrid.telemetry.risk.RiskLevel;
import com.thermogrid.telemetry.service.AlertService;
import com.thermogrid.telemetry.service.IngestionService;
import com.thermogrid.telemetry.service.InvalidReadingException;
import com.thermogrid.telemetry.service.RackStateUpdater.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class IngestionTest extends IntegrationTestBase {

    @Autowired
    IngestionService ingestion;
    @Autowired
    ObjectMapper mapper;
    @MockitoSpyBean
    AlertService alertService;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate tx;

    private final ForecastEngine engine = new ForecastEngine(0.4, 0.15, 0.95, 30);

    private Instant t(int ticks) {
        return T0.plusSeconds(2L * ticks);
    }

    private Outcome rack(String id, int tick, double temp) {
        return ingestion.ingestRack(TestSupport.rack(id, "Z2", t(tick), temp));
    }

    private record Sent(String key, JsonNode json) {
    }

    private List<Sent> sentEvents() throws Exception {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Message> msgs = ArgumentCaptor.forClass(Message.class);
        verify(rabbit, org.mockito.Mockito.atLeast(0)).send(eq(Exchanges.EVENTS), keys.capture(), msgs.capture());
        List<Sent> out = new ArrayList<>();
        for (int i = 0; i < keys.getAllValues().size(); i++) {
            out.add(new Sent(keys.getAllValues().get(i), mapper.readTree(msgs.getAllValues().get(i).getBody())));
        }
        return out;
    }

    // ---- storing ----

    @Test
    void firstReadingIsStoredAndInitialisesTheForecastState() {
        assertThat(ingestion.ingestRack(TestSupport.rack("R23", "Z2", t(0), 29.84, 7.6, 72.5)))
                .isEqualTo(Outcome.APPLIED);

        assertThat(rackReadings.countByRackId("R23")).isEqualTo(1);
        var r = rackReadings.findAll().get(0);
        assertThat(r.getRecordedAt()).isEqualTo(t(0));
        assertThat(r.getTempC()).isEqualByComparingTo("29.84");
        assertThat(r.getPowerKw()).isEqualByComparingTo("7.60");
        assertThat(r.getCpuLoadPct()).isEqualByComparingTo("72.5");

        RackForecastState s = states.findById("R23").orElseThrow();
        assertThat(s.getZoneId()).isEqualTo("Z2");
        assertThat(s.getLevelC()).isEqualTo(29.84);
        assertThat(s.getTrendC()).isZero();
        assertThat(s.getForecast60sC()).isEqualByComparingTo("29.84");
        assertThat(s.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
        assertThat(s.getSensorStatus()).isEqualTo(SensorStatus.OK);
        assertThat(s.getCalmCount()).isZero();
        assertThat(s.getLastReadingAt()).isEqualTo(t(0));
        assertThat(s.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void forecastStateFollowsTheEngineReadingByReading() {
        double[] temps = {26, 26.3, 26.6, 26.9, 27.2, 27.5};
        ForecastEngine.State expected = null;
        ForecastEngine.Result last = null;
        for (int i = 0; i < temps.length; i++) {
            rack("R11", i, temps[i]);
            last = engine.update(expected, temps[i]);
            expected = last.state();
        }
        RackForecastState s = states.findById("R11").orElseThrow();
        assertThat(s.getLevelC()).isCloseTo(expected.level(), within(1e-12));
        assertThat(s.getTrendC()).isCloseTo(expected.trend(), within(1e-12));
        assertThat(s.getForecast60sC().doubleValue()).isCloseTo(last.forecast(), within(0.0051));
    }

    // ---- duplicates ----

    @Test
    void duplicateRackReadingCreatesNoRowAndNoStateChange() {
        rack("R23", 0, 26.0);
        rack("R23", 1, 27.0);
        RackForecastState before = states.findById("R23").orElseThrow();

        assertThat(rack("R23", 1, 99.0)).isEqualTo(Outcome.DUPLICATE);
        assertThat(rack("R23", 0, 26.0)).isEqualTo(Outcome.DUPLICATE);

        assertThat(rackReadings.countByRackId("R23")).isEqualTo(2);
        RackForecastState after = states.findById("R23").orElseThrow();
        assertThat(after.getLevelC()).isEqualTo(before.getLevelC());
        assertThat(after.getLastReadingAt()).isEqualTo(before.getLastReadingAt());
        assertThat(after.getTempC()).isEqualByComparingTo(before.getTempC());
        assertThat(alerts.count()).isZero();
    }

    @Test
    void timestampsAreCompared_atMillisecondPrecision() {
        ingestion.ingestRack(TestSupport.rack("R23", "Z2", Instant.parse("2026-11-18T07:00:00.123456789Z"), 26.0));
        assertThat(ingestion.ingestRack(TestSupport.rack("R23", "Z2", Instant.parse("2026-11-18T07:00:00.123Z"), 26.0)))
                .isEqualTo(Outcome.DUPLICATE);
        assertThat(rackReadings.countByRackId("R23")).isEqualTo(1);
    }

    @Test
    void duplicateZoneReadingCreatesNoRow() {
        assertThat(ingestion.ingestZone(TestSupport.zone("Z2", t(0), 27.1, 29.8, 78, "OK"))).isTrue();
        assertThat(ingestion.ingestZone(TestSupport.zone("Z2", t(0), 99.0, 99.0, 10, "FAULT"))).isFalse();
        assertThat(ingestion.ingestZone(TestSupport.zone("Z2", t(1), 27.2, 29.9, 78, "OK"))).isTrue();
        assertThat(ingestion.ingestZone(TestSupport.zone("Z3", t(0), 27.2, 29.9, 78, "OK"))).isTrue();
        assertThat(zoneReadings.countByZoneId("Z2")).isEqualTo(2);
        assertThat(zoneReadings.countByZoneId("Z3")).isEqualTo(1);
    }

    @Test
    void zoneReadingStoresDegradedHealthWhichIsLongerThanThePlansVarchar5() {
        assertThat(ingestion.ingestZone(TestSupport.zone("Z2", t(0), 27.1, 29.8, 10, "DEGRADED"))).isTrue();
        assertThat(zoneReadings.findLatestPerZone()).singleElement()
                .satisfies(z -> assertThat(z.getUnitHealth()).isEqualTo("DEGRADED"));
    }

    // ---- out of order ----

    @Test
    void outOfOrderReadingIsStoredButNeverMovesTheForecastStateBackwards() {
        rack("R23", 0, 26.0);
        rack("R23", 5, 28.0);
        RackForecastState before = states.findById("R23").orElseThrow();

        assertThat(rack("R23", 3, 35.0)).isEqualTo(Outcome.OUT_OF_ORDER);

        assertThat(rackReadings.countByRackId("R23")).isEqualTo(3);
        RackForecastState after = states.findById("R23").orElseThrow();
        assertThat(after.getLastReadingAt()).isEqualTo(t(5));
        assertThat(after.getLevelC()).isEqualTo(before.getLevelC());
        assertThat(after.getTrendC()).isEqualTo(before.getTrendC());
        assertThat(after.getTempC()).isEqualByComparingTo("28.00");
        assertThat(after.getForecast60sC()).isEqualByComparingTo(before.getForecast60sC());
        assertThat(after.getRiskLevel()).isEqualTo(before.getRiskLevel());
        assertThat(alerts.count()).as("a late hot reading raises nothing").isZero();
    }

    // ---- alerts ----

    @Test
    void fullAlertLifecycleRaisesLowersAndClearsWithCorrectEventsAndASingleActiveAlert() throws Exception {
        int tick = 0;
        for (int i = 0; i < 3; i++) {
            rack("R23", tick++, 26.0);
        }
        assertThat(sentEvents()).as("steady NORMAL publishes nothing").isEmpty();

        // 29.5 is below 30, but the forecast (30.53) crosses it: PREDICTED WATCH
        rack("R23", tick++, 29.5);
        assertThat(sentEvents()).hasSize(1);
        Alert alert = alerts.findAll().get(0);
        assertThat(alert.getLevel()).isEqualTo(RiskLevel.WATCH);
        assertThat(alert.getBasis()).isEqualTo(AlertBasis.PREDICTED);
        assertThat(alert.getState()).isEqualTo(AlertState.ACTIVE);
        assertThat(alert.getTempC()).isEqualByComparingTo("29.50");
        assertThat(alert.getForecastC().doubleValue()).isGreaterThanOrEqualTo(30.0);
        long alertId = alert.getId();

        // repeating the same level must not publish again
        rack("R23", tick++, 29.5);
        assertThat(sentEvents()).as("unchanged risk -> no event").hasSize(1);

        // 35 observed, but the jump makes the forecast cross 38: CRITICAL on a PREDICTED basis, same alert re-raised
        rack("R23", tick++, 35.0);
        assertThat(sentEvents()).hasSize(2);
        assertThat(alerts.findAll()).hasSize(1);
        assertThat(alerts.findAll().get(0).getId()).isEqualTo(alertId);
        assertThat(alerts.findAll().get(0).getLevel()).isEqualTo(RiskLevel.CRITICAL);

        // cool to 31 until the level drops (needs five calm readings): lowered, still ACTIVE
        for (int i = 0; i < 80 && alerts.findAll().get(0).getLevel() == RiskLevel.CRITICAL; i++) {
            rack("R23", tick++, 31.0);
        }
        assertThat(alerts.findAll().get(0).getLevel()).isNotEqualTo(RiskLevel.CRITICAL);
        assertThat(alerts.findAll().get(0).getState()).isEqualTo(AlertState.ACTIVE);

        // cool to 25 until the alert clears
        for (int i = 0; i < 120 && alerts.findAll().get(0).getState() == AlertState.ACTIVE; i++) {
            rack("R23", tick++, 25.0);
        }
        Alert cleared = alerts.findAll().get(0);
        assertThat(cleared.getState()).isEqualTo(AlertState.CLEARED);
        assertThat(cleared.getClearedAt()).isNotNull();

        List<Sent> events = sentEvents();
        assertThat(events.get(0).key()).isEqualTo(RoutingKeys.THERMAL_ALERT_RAISED);
        assertThat(events.get(1).key()).isEqualTo(RoutingKeys.THERMAL_ALERT_RAISED);
        assertThat(events.get(events.size() - 1).key()).isEqualTo(RoutingKeys.THERMAL_ALERT_CLEARED);
        assertThat(events.subList(2, events.size() - 1)).isNotEmpty()
                .allSatisfy(e -> assertThat(e.key()).isEqualTo(RoutingKeys.THERMAL_ALERT_LOWERED));
        assertThat(events).extracting(e -> e.json().get("data").get("alertId").asLong()).containsOnly(alertId);
        // every event previousLevel is the level announced by the one before it
        assertThat(events.get(0).json().get("data").get("previousLevel").asText()).isEqualTo("NORMAL");
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).json().get("data").get("previousLevel").asText())
                    .isEqualTo(events.get(i - 1).json().get("data").get("level").asText());
        }
        assertThat(events.get(0).json().get("data").get("level").asText()).isEqualTo("WATCH");
        assertThat(events.get(0).json().get("data").get("basis").asText()).isEqualTo("PREDICTED");
        assertThat(events.get(1).json().get("data").get("level").asText()).isEqualTo("CRITICAL");
        assertThat(events.get(events.size() - 1).json().get("data").get("level").asText()).isEqualTo("NORMAL");
        assertThat(events.get(events.size() - 1).json().get("data").get("basis").asText()).isEqualTo("OBSERVED");
        assertThat(alerts.findByRackIdAndState("R23", AlertState.ACTIVE)).isEmpty();

        // the rack heats up again: a NEW alert, again only one ACTIVE
        rack("R23", tick++, 40.0);
        assertThat(alerts.findByRackIdAndState("R23", AlertState.ACTIVE)).hasSize(1);
        assertThat(alerts.findAll()).hasSize(2);
        assertThat(alerts.findByRackIdAndState("R23", AlertState.ACTIVE).get(0).getId()).isNotEqualTo(alertId);
    }

    @Test
    void alertEventPayloadMatchesTheContractRecordAndEnvelope() throws Exception {
        rack("R23", 0, 36.0);   // first reading: forecast equals the reading, so WARNING is OBSERVED

        List<Sent> events = sentEvents();
        assertThat(events).hasSize(1);
        JsonNode j = events.get(0).json();
        assertThat(events.get(0).key()).isEqualTo("thermal.alert.raised");
        assertThat(j.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("eventId", "eventType", "occurredAt", "producer", "version", "data");
        assertThat(java.util.UUID.fromString(j.get("eventId").asText())).isNotNull();
        assertThat(j.get("eventType").asText()).isEqualTo("thermal.alert.raised");
        assertThat(j.get("producer").asText()).isEqualTo("TELEMETRY-SERVICE");
        assertThat(j.get("version").asInt()).isEqualTo(1);
        assertThat(Instant.parse(j.get("occurredAt").asText())).isEqualTo(t(0));
        JsonNode d = j.get("data");
        assertThat(d.fieldNames()).toIterable().containsExactlyInAnyOrder("alertId", "rackId", "zoneId", "level",
                "previousLevel", "basis", "tempC", "forecast60C");
        assertThat(d.get("alertId").asLong()).isEqualTo(alerts.findAll().get(0).getId());
        assertThat(d.get("rackId").asText()).isEqualTo("R23");
        assertThat(d.get("zoneId").asText()).isEqualTo("Z2");
        assertThat(d.get("level").asText()).isEqualTo("WARNING");
        assertThat(d.get("previousLevel").asText()).isEqualTo("NORMAL");
        assertThat(d.get("basis").asText()).isEqualTo("OBSERVED");
        assertThat(d.get("tempC").asDouble()).isEqualTo(36.0);
        assertThat(d.get("forecast60C").asDouble()).isEqualTo(36.0);
    }

    @Test
    void alertsOnDifferentRacksAreIndependent() {
        for (int i = 0; i < 3; i++) {
            rack("R21", i, 26.0);
            rack("R22", i, 26.0);
        }
        rack("R21", 3, 39.0);
        assertThat(alerts.findByRackIdAndState("R21", AlertState.ACTIVE)).hasSize(1);
        assertThat(alerts.findByRackIdAndState("R22", AlertState.ACTIVE)).isEmpty();
        assertThat(states.findById("R22").orElseThrow().getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
    }

    @Test
    void aFirstEverReadingAlreadyAboveThresholdRaisesImmediately() {
        rack("R44", 0, 40.0);
        Alert a = alerts.findAll().get(0);
        assertThat(a.getLevel()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(a.getBasis()).isEqualTo(AlertBasis.OBSERVED);
    }

    // ---- failures ----

    @Test
    void anInvalidMessageIsRejectedWithoutStoringAnything() {
        assertThatThrownBy(() -> ingestion.ingestRack(TestSupport.rack("R23", "Z2", t(0), Double.NaN)))
                .isInstanceOf(InvalidReadingException.class);
        assertThatThrownBy(() -> ingestion.ingestRack(TestSupport.rack("", "Z2", t(0), 26)))
                .isInstanceOf(InvalidReadingException.class);
        assertThatThrownBy(() -> ingestion.ingestRack(TestSupport.rack("R23", "Z2", t(0), 500)))
                .isInstanceOf(InvalidReadingException.class);
        assertThatThrownBy(() -> ingestion.ingestRack(null)).isInstanceOf(InvalidReadingException.class);
        assertThat(rackReadings.count()).isZero();
        assertThat(states.count()).isZero();
    }

    @Test
    void aFailureDuringProcessingRollsEverythingBackAndPropagatesSoTheMessageIsNotAcknowledged() {
        for (int i = 0; i < 3; i++) {
            rack("R23", i, 26.0);
        }
        RackForecastState before = states.findById("R23").orElseThrow();
        tx.executeWithoutResult(st -> doThrow(new IllegalStateException("alert store failed")).when(alertService)
                .onRiskChange(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(), any(), any(), any(), any()));

        assertThatThrownBy(() -> rack("R23", 3, 40.0)).isInstanceOf(IllegalStateException.class);

        assertThat(rackReadings.countByRackId("R23")).as("reading row rolled back").isEqualTo(3);
        RackForecastState after = states.findById("R23").orElseThrow();
        assertThat(after.getLastReadingAt()).isEqualTo(before.getLastReadingAt());
        assertThat(after.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
        assertThat(alerts.count()).isZero();
        org.mockito.Mockito.reset(alertService);

        // the redelivered message now succeeds
        assertThat(rack("R23", 3, 40.0)).isEqualTo(Outcome.APPLIED);
        assertThat(alerts.count()).isEqualTo(1);
    }

    @Test
    void aBrokerOutageAfterCommitKeepsTheAlertInTheDatabaseAndDoesNotFailTheReading() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class));
        rack("R23", 0, 26.0);

        assertThat(rack("R23", 1, 40.0)).isEqualTo(Outcome.APPLIED);

        assertThat(alerts.findByRackIdAndState("R23", AlertState.ACTIVE)).hasSize(1);
        assertThat(rackReadings.countByRackId("R23")).isEqualTo(2);
        verify(rabbit, org.mockito.Mockito.times(3)).send(eq(Exchanges.EVENTS), eq("thermal.alert.raised"),
                any(Message.class));
    }

    @Test
    void duplicatesAndOutOfOrderPublishNothing() {
        rack("R23", 0, 26.0);
        rack("R23", 1, 40.0);   // raises
        org.mockito.Mockito.clearInvocations(rabbit);
        rack("R23", 1, 40.0);   // duplicate
        rack("R23", 0, 50.0);   // out of order
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class));
        assertThat(Duration.ZERO).isZero();
    }
}
