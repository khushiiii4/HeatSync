package com.thermogrid.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** JSON examples are copied from plan section 8.5. */
class EventJsonRoundTripTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String ENVELOPE_EXAMPLE = """
            { "eventId": "0b8e5c1e-6f3a-4a9e-8d57-1c2b3a4d5e6f",
              "eventType": "workload.assigned",
              "occurredAt": "2026-11-18T07:10:06.310Z",
              "producer": "workload-service",
              "version": 1,
              "data": { "workloadId": 2, "cpuDemand": 30, "priority": "LOW", "fromRackId": "R23",
                        "toRackId": "R41", "reason": "THERMAL_WARNING",
                        "migrationId": "5b0c8e2a-41d7-4c1e-9b7a-2f6d0e8a1c33", "version": 3 } }
            """;

    /** Lets inline JSON samples use single quotes. */
    private static String j(String s) {
        return s.replace('\'', '"');
    }

    record Case(String routingKey, Class<?> type, String dataJson) {
    }

    static Stream<Arguments> nine() {
        return Stream.of(
                new Case(RoutingKeys.TELEMETRY_RACK_READING, TelemetryRackReadingData.class,
                        j("{ 'rackId': 'R23', 'zoneId': 'Z2', 'tempC': 29.84, 'powerKw': 7.6, 'cpuLoadPct': 72.5 }")),
                new Case(RoutingKeys.TELEMETRY_ZONE_READING, TelemetryZoneReadingData.class,
                        j("{ 'zoneId': 'Z2', 'avgTempC': 27.1, 'maxTempC': 29.84, 'supplyTempC': 18.0, 'fanActualPct': 78, 'unitHealth': 'OK' }")),
                new Case(RoutingKeys.THERMAL_ALERT_RAISED, ThermalAlertData.class, ALERT),
                new Case(RoutingKeys.THERMAL_ALERT_LOWERED, ThermalAlertData.class, ALERT),
                new Case(RoutingKeys.THERMAL_ALERT_CLEARED, ThermalAlertData.class, ALERT),
                new Case(RoutingKeys.WORKLOAD_SUBMITTED, WorkloadSubmittedData.class,
                        j("{ 'workloadId': 18, 'name': 'batch-etl-18', 'cpuDemand': 30, 'priority': 'LOW' }")),
                new Case(RoutingKeys.WORKLOAD_ASSIGNED, WorkloadAssignedData.class,
                        j("{ 'workloadId': 2, 'cpuDemand': 30, 'priority': 'LOW', 'fromRackId': 'R23', 'toRackId': 'R41', 'reason': 'THERMAL_WARNING', 'migrationId': '5b0c8e2a-41d7-4c1e-9b7a-2f6d0e8a1c33', 'version': 3 }")),
                new Case(RoutingKeys.WORKLOAD_REMOVED, WorkloadRemovedData.class,
                        j("{ 'workloadId': 18, 'rackId': 'R41', 'cpuDemand': 30 }")),
                new Case(RoutingKeys.COOLING_UNIT_STATE, CoolingUnitStateData.class,
                        j("{ 'zoneId': 'Z2', 'mode': 'AUTO', 'outputPct': 78, 'health': 'OK', 'targetTempC': 26.0, 'reason': 'hotspot forecast above target', 'seq': 1042 }")))
                .map(c -> Arguments.of(c.routingKey(), c.type(), c.dataJson()));
    }

    private static final String ALERT = """
            { "alertId": 412, "rackId": "R23", "zoneId": "Z2", "level": "WARNING",
              "previousLevel": "WATCH", "basis": "PREDICTED", "tempC": 30.4, "forecast60C": 35.2 }""";

    @ParameterizedTest(name = "{0}")
    @MethodSource("nine")
    void payloadSurvivesRoundTripWithPlanFieldNames(String routingKey, Class<?> type, String dataJson)
            throws Exception {
        Object parsed = MAPPER.readValue(dataJson, type);
        assertThat(parsed).isInstanceOf(type);

        JsonNode reserialized = MAPPER.readTree(MAPPER.writeValueAsString(parsed));
        // Same field names and values as the plan example: nothing renamed, added or dropped.
        assertThat(reserialized).isEqualTo(MAPPER.readTree(dataJson));
        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(parsed), type)).isEqualTo(parsed);
    }

    @ParameterizedTest(name = "envelope for {0}")
    @MethodSource("nine")
    void everyPayloadTravelsInsideTheEnvelope(String routingKey, Class<?> type, String dataJson)
            throws Exception {
        Object data = MAPPER.readValue(dataJson, type);
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-11-18T07:10:06.310Z");
        EventEnvelope<Object> envelope = new EventEnvelope<>(id, routingKey, at, "test-service", 1, data);

        String json = MAPPER.writeValueAsString(envelope);
        EventEnvelope<?> back = MAPPER.readValue(json,
                MAPPER.getTypeFactory().constructParametricType(EventEnvelope.class, type));

        assertThat(back).isEqualTo(envelope);
        assertThat(back.data()).isInstanceOf(type);
    }

    @Test
    void envelopeMatchesPlanExample() throws Exception {
        EventEnvelope<WorkloadAssignedData> env = MAPPER.readValue(ENVELOPE_EXAMPLE,
                new TypeReference<EventEnvelope<WorkloadAssignedData>>() { });

        assertThat(env.eventId()).isEqualTo(UUID.fromString("0b8e5c1e-6f3a-4a9e-8d57-1c2b3a4d5e6f"));
        assertThat(env.eventType()).isEqualTo(RoutingKeys.WORKLOAD_ASSIGNED);
        assertThat(env.occurredAt()).isEqualTo(Instant.parse("2026-11-18T07:10:06.310Z"));
        assertThat(env.producer()).isEqualTo("workload-service");
        assertThat(env.version()).isEqualTo(1);
        assertThat(env.data().toRackId()).isEqualTo("R41");
        assertThat(env.data().version()).isEqualTo(3);

        JsonNode out = MAPPER.readTree(MAPPER.writeValueAsString(env));
        assertThat(out).isEqualTo(MAPPER.readTree(ENVELOPE_EXAMPLE));
        assertThat(out.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("eventId", "eventType", "occurredAt", "producer", "version", "data");
        assertThat(out.get("eventId").isTextual()).isTrue();
        assertThat(out.get("occurredAt").asText()).isEqualTo("2026-11-18T07:10:06.310Z");
    }

    @Test
    void firstPlacementHasNullFromRackId() throws Exception {
        String json = j("{ 'workloadId': 18, 'cpuDemand': 30, 'priority': 'LOW', 'fromRackId': null, 'toRackId': 'R41', 'reason': 'INITIAL_PLACEMENT', 'migrationId': '5b0c8e2a-41d7-4c1e-9b7a-2f6d0e8a1c33', 'version': 1 }");
        WorkloadAssignedData d = MAPPER.readValue(json, WorkloadAssignedData.class);
        assertThat(d.fromRackId()).isNull();
        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(d)).get("fromRackId").isNull()).isTrue();
    }
}