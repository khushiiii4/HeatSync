package com.thermogrid.simulator;

import static com.thermogrid.simulator.TestTopology.INITIAL;
import static com.thermogrid.simulator.TestTopology.PLAN_NO_NOISE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.Exchanges;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.contracts.TelemetryRackReadingData;
import com.thermogrid.contracts.TelemetryZoneReadingData;
import com.thermogrid.simulator.TestSupport.MutableClock;
import com.thermogrid.simulator.config.SimulatorProperties;
import com.thermogrid.simulator.physics.HealthState;
import com.thermogrid.simulator.physics.PhysicsModel;
import com.thermogrid.simulator.publish.TelemetryPublisher;
import com.thermogrid.simulator.sim.SimState;
import com.thermogrid.simulator.sim.SimState.RackState;
import com.thermogrid.simulator.sim.SimState.Snapshot;
import com.thermogrid.simulator.sim.SimState.ZoneState;
import com.thermogrid.simulator.sim.SimulationEngine;
import com.thermogrid.simulator.topology.RackGrid;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;

class TelemetryPublisherTest {

    private static final Instant T0 = Instant.parse("2026-11-18T07:10:06.310Z");
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private RabbitOperations rabbit;
    private MutableClock clock;
    private RackGrid grid;
    private PhysicsModel model;
    private TelemetryPublisher publisher;
    private SimState state;

    @BeforeEach
    void setUp() {
        rabbit = mock(RabbitOperations.class);
        clock = new MutableClock(T0);
        grid = TestTopology.grid();
        model = new PhysicsModel(PLAN_NO_NOISE, () -> 0.0);
        publisher = newPublisher(grid);
        state = new SimState(grid, model, INITIAL, clock);
    }

    private TelemetryPublisher newPublisher(RackGrid g) {
        SimulatorProperties props = new SimulatorProperties(2000, false, null, TestTopology.GRID, INITIAL,
                new SimulatorProperties.Physics(18.0, 0.06, 0.12, 0.05, 0.0, 0.10,
                        new SimulatorProperties.HealthFactors(1.0, 0.25, 0.0)),
                new SimulatorProperties.Publishing(true, "SIMULATOR-SERVICE", 1, 10000),
                new SimulatorProperties.Failures(86400, 50.0), new SimulatorProperties.Bootstrap(1, 1, 1));
        return new TelemetryPublisher(rabbit, MAPPER, g, model, props, clock);
    }

    private record Sent(String routingKey, JsonNode json, Message message) {
    }

    private List<Sent> sent(int expectedCalls) throws Exception {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Message> msgs = ArgumentCaptor.forClass(Message.class);
        verify(rabbit, times(expectedCalls)).send(eq(Exchanges.EVENTS), keys.capture(), msgs.capture());
        List<Sent> out = new java.util.ArrayList<>();
        for (int i = 0; i < keys.getAllValues().size(); i++) {
            Message m = msgs.getAllValues().get(i);
            out.add(new Sent(keys.getAllValues().get(i), MAPPER.readTree(m.getBody()), m));
        }
        return out;
    }

    private Snapshot withTemps(Map<String, Double> temps) {
        Snapshot s = state.snapshot();
        Map<String, RackState> racks = new LinkedHashMap<>(s.racks());
        temps.forEach((id, t) -> {
            RackState old = racks.get(id);
            racks.put(id, new RackState(t, old.cpuUnits(), old.powerKw(), old.heatSpikeKw()));
        });
        return new Snapshot(7, T0, racks, s.zones());
    }

    @Test
    void publishesOneRackReadingPerActiveRackAndOneZoneReadingPerZone() throws Exception {
        publisher.publish(withTemps(Map.of()), Set.of());

        List<Sent> all = sent(20);
        List<Sent> racks = all.stream().filter(s -> s.routingKey().equals(RoutingKeys.TELEMETRY_RACK_READING)).toList();
        List<Sent> zones = all.stream().filter(s -> s.routingKey().equals(RoutingKeys.TELEMETRY_ZONE_READING)).toList();
        assertThat(racks).hasSize(16);
        assertThat(zones).hasSize(4);
        assertThat(racks.stream().map(s -> s.json().get("data").get("rackId").asText()).collect(Collectors.toSet()))
                .hasSize(16);
        assertThat(zones.stream().map(s -> s.json().get("data").get("zoneId").asText()).toList())
                .containsExactly("Z1", "Z2", "Z3", "Z4");
        assertThat(publisher.ticksPublished()).isEqualTo(1);
    }

    @Test
    void envelopeCarriesUuidUtcTimestampProducerAndVersion() throws Exception {
        publisher.publish(withTemps(Map.of()), Set.of());

        for (Sent s : sent(20)) {
            JsonNode j = s.json();
            assertThat(j.fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("eventId", "eventType", "occurredAt", "producer", "version", "data");
            UUID id = UUID.fromString(j.get("eventId").asText());
            assertThat(j.get("eventType").asText()).isEqualTo(s.routingKey());
            assertThat(j.get("occurredAt").asText()).isEqualTo("2026-11-18T07:10:06.310Z");
            assertThat(j.get("producer").asText()).isEqualTo("SIMULATOR-SERVICE");
            assertThat(j.get("version").asInt()).isEqualTo(1);
            MessageProperties p = s.message().getMessageProperties();
            assertThat(p.getContentType()).isEqualTo("application/json");
            assertThat(p.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(p.getMessageId()).isEqualTo(id.toString());
        }
        List<String> ids = sent(20).stream().map(s -> s.json().get("eventId").asText()).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void rackPayloadMatchesTheContractRecordAndPlanFields() throws Exception {
        publisher.publish(withTemps(Map.of("R23", 29.8449)), Set.of());

        Sent r23 = sent(20).stream().filter(s -> s.routingKey().equals(RoutingKeys.TELEMETRY_RACK_READING)
                && s.json().get("data").get("rackId").asText().equals("R23")).findFirst().orElseThrow();
        JsonNode data = r23.json().get("data");
        assertThat(data.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("rackId", "zoneId", "tempC", "powerKw", "cpuLoadPct");
        EventEnvelope<TelemetryRackReadingData> env = MAPPER.readValue(r23.message().getBody(),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertThat(env.data()).isEqualTo(new TelemetryRackReadingData("R23", "Z2", 29.84, 6.8, 60.0));
    }

    @Test
    void zonePayloadHasAverageMaxSupplyFanAndHealth() throws Exception {
        // Z2 racks R21..R24 at 26, 28, 30, 32 -> avg 29, max 32.
        publisher.publish(withTemps(Map.of("R21", 26.0, "R22", 28.0, "R23", 30.0, "R24", 32.0)), Set.of());

        Sent z2 = sent(20).stream().filter(s -> s.routingKey().equals(RoutingKeys.TELEMETRY_ZONE_READING)
                && s.json().get("data").get("zoneId").asText().equals("Z2")).findFirst().orElseThrow();
        assertThat(z2.json().get("data").fieldNames()).toIterable().containsExactlyInAnyOrder(
                "zoneId", "avgTempC", "maxTempC", "supplyTempC", "fanActualPct", "unitHealth");
        EventEnvelope<TelemetryZoneReadingData> env = MAPPER.readValue(z2.message().getBody(),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        assertThat(env.data()).isEqualTo(new TelemetryZoneReadingData("Z2", 29.0, 32.0, 18.0, 40, "OK"));
    }

    @Test
    void zoneReadingReportsFaultAndDegradedHealthWithMatchingFan() {
        Snapshot s = state.snapshot();
        Map<String, ZoneState> zones = new LinkedHashMap<>(s.zones());
        zones.put("Z1", new ZoneState(40, HealthState.FAULT));
        zones.put("Z2", new ZoneState(80, HealthState.DEGRADED, 0.5));
        Snapshot degraded = new Snapshot(3, T0, s.racks(), zones);

        publisher.publish(degraded, Set.of());

        ArgumentCaptor<Message> msgs = ArgumentCaptor.forClass(Message.class);
        verify(rabbit, times(20)).send(eq(Exchanges.EVENTS), anyString(), msgs.capture());
        Map<String, JsonNode> byZone = new LinkedHashMap<>();
        for (Message m : msgs.getAllValues()) {
            try {
                JsonNode j = MAPPER.readTree(m.getBody());
                if (j.get("eventType").asText().equals(RoutingKeys.TELEMETRY_ZONE_READING)) {
                    byZone.put(j.get("data").get("zoneId").asText(), j.get("data"));
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        assertThat(byZone.get("Z1").get("unitHealth").asText()).isEqualTo("FAULT");
        assertThat(byZone.get("Z1").get("fanActualPct").asInt()).isZero();
        assertThat(byZone.get("Z2").get("unitHealth").asText()).isEqualTo("DEGRADED");
        assertThat(byZone.get("Z2").get("fanActualPct").asInt()).isEqualTo(40);
        assertThat(byZone.get("Z3").get("unitHealth").asText()).isEqualTo("OK");
    }

    @Test
    void sensorDropoutSuppressesOnlyThatRacksReading() throws Exception {
        publisher.publish(withTemps(Map.of()), Set.of("R23"));

        List<Sent> all = sent(19);
        assertThat(all.stream().filter(s -> s.routingKey().equals(RoutingKeys.TELEMETRY_RACK_READING)))
                .hasSize(15).noneMatch(s -> s.json().get("data").get("rackId").asText().equals("R23"));
        assertThat(all.stream().filter(s -> s.routingKey().equals(RoutingKeys.TELEMETRY_ZONE_READING))).hasSize(4);
    }

    @Test
    void maintenanceRacksPublishNoRackReadingButTheirZoneStillReports() throws Exception {
        RackGrid withMaintenance = TestTopology.grid(Set.of("R11", "R12"));
        TelemetryPublisher p = newPublisher(withMaintenance);
        SimState st = new SimState(withMaintenance, model, INITIAL, clock);

        p.publish(st.snapshot(), Set.of());

        List<Sent> all = sent(18);
        assertThat(all.stream().filter(s -> s.routingKey().equals(RoutingKeys.TELEMETRY_RACK_READING))).hasSize(14);
        assertThat(all.stream().filter(s -> s.routingKey().equals(RoutingKeys.TELEMETRY_ZONE_READING))).hasSize(4);
    }

    // ---- RabbitMQ unavailable ----

    @Test
    void brokerDownDoesNotThrowAbortsTheTickAndIsNotCountedAsPublished() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("Connection refused")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class));

        publisher.publish(state.snapshot(), Set.of());   // must not throw

        verify(rabbit, times(1)).send(anyString(), anyString(), any(Message.class));   // fail fast: first send only
        assertThat(publisher.ticksPublished()).isZero();
        assertThat(publisher.ticksSkipped()).isEqualTo(1);
    }

    @Test
    void brokerIsNotHammeredDuringTheCooldownAndIsRetriedAfterwards() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("Connection refused")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class));
        publisher.publish(state.snapshot(), Set.of());
        verify(rabbit, times(1)).send(anyString(), anyString(), any(Message.class));

        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(2));
            publisher.publish(state.snapshot(), Set.of());
        }
        verify(rabbit, times(1)).send(anyString(), anyString(), any(Message.class));   // still the single attempt
        assertThat(publisher.ticksSkipped()).isEqualTo(5);

        clock.advance(Duration.ofSeconds(3));   // 11 s after the failure, cooldown is 10 s
        publisher.publish(state.snapshot(), Set.of());
        verify(rabbit, times(2)).send(anyString(), anyString(), any(Message.class));   // retried once
    }

    @Test
    void publishingResumesCleanlyWhenTheBrokerComesBack() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                .doNothing().when(rabbit).send(anyString(), anyString(), any(Message.class));
        publisher.publish(state.snapshot(), Set.of());
        assertThat(publisher.ticksPublished()).isZero();

        clock.advance(Duration.ofSeconds(11));
        publisher.publish(state.snapshot(), Set.of());

        assertThat(publisher.ticksPublished()).isEqualTo(1);
        verify(rabbit, times(1 + 20)).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void aBrokenBrokerNeverChangesTheSimulationState() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class));
        SimulationEngine engine = new SimulationEngine(grid, model, state, clock,
                com.thermogrid.simulator.failure.FailureSource.NONE, publisher);

        for (int i = 1; i <= 3; i++) {
            clock.advance(Duration.ofSeconds(2));
            assertThat(engine.tick()).isTrue();
            assertThat(state.snapshot().tick()).isEqualTo(i);
        }
        assertThat(state.snapshot().racks().get("R11").tempC()).isNotEqualTo(26.0);
        assertThat(publisher.ticksPublished()).isZero();
    }

    @Test
    void anUnexpectedPublisherExceptionIsContainedByTheEngine() {
        SimulationEngine engine = new SimulationEngine(grid, model, state, clock,
                com.thermogrid.simulator.failure.FailureSource.NONE,
                (snap, dropped) -> { throw new IllegalStateException("bug in publisher"); });

        assertThat(engine.tick()).isTrue();

        assertThat(state.snapshot().tick()).isEqualTo(1);
    }

    @Test
    void engineHandsThePublisherTheCommittedSnapshotAfterStateIsUpdated() {
        verifyNoInteractions(rabbit);
        SimulationEngine engine = new SimulationEngine(grid, model, state, clock,
                com.thermogrid.simulator.failure.FailureSource.NONE, publisher);

        engine.tick();

        verify(rabbit, times(20)).send(eq(Exchanges.EVENTS), anyString(), any(Message.class));
        assertThat(state.snapshot().tick()).isEqualTo(1);
        engine.pause();
        engine.tick();
        verify(rabbit, times(20)).send(eq(Exchanges.EVENTS), anyString(), any(Message.class));   // paused: nothing new
        verify(rabbit, never()).send(eq("wrong.exchange"), anyString(), any(Message.class));
    }
}
