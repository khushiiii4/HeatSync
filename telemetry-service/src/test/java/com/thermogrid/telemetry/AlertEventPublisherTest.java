package com.thermogrid.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thermogrid.contracts.Exchanges;
import com.thermogrid.contracts.ThermalAlertData;
import com.thermogrid.telemetry.service.AlertEventPublisher;
import com.thermogrid.telemetry.service.AlertTransition;
import com.thermogrid.telemetry.service.AlertTransition.Kind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitOperations;

class AlertEventPublisherTest {

    private static final Instant AT = Instant.parse("2026-11-18T07:10:06.310Z");
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final RabbitOperations rabbit = mock(RabbitOperations.class);
    private final List<Long> sleeps = new ArrayList<>();
    private final AlertEventPublisher publisher = new AlertEventPublisher(rabbit, MAPPER, TestSupport.props(),
            sleeps::add);

    private static AlertTransition transition(Kind kind) {
        return new AlertTransition(kind,
                new ThermalAlertData(412, "R23", "Z2", "WARNING", "WATCH", "PREDICTED", 30.4, 35.2), AT);
    }

    @Test
    void eachKindUsesItsOwnRoutingKeyOnTheEventsExchange() {
        for (Kind k : Kind.values()) {
            assertThat(publisher.publish(transition(k))).isTrue();
        }
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(rabbit, times(3)).send(eq(Exchanges.EVENTS), keys.capture(), any(Message.class));
        assertThat(keys.getAllValues()).containsExactly("thermal.alert.raised", "thermal.alert.lowered",
                "thermal.alert.cleared");
    }

    @Test
    void messageIsAPersistentJsonEnvelopeMatchingThePlanExample() throws Exception {
        publisher.publish(transition(Kind.RAISED));

        ArgumentCaptor<Message> msg = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq("thermogrid.events"), eq("thermal.alert.raised"), msg.capture());
        Message m = msg.getValue();
        assertThat(m.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(m.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        JsonNode j = MAPPER.readTree(m.getBody());
        assertThat(m.getMessageProperties().getMessageId()).isEqualTo(j.get("eventId").asText());
        assertThat(j.get("eventType").asText()).isEqualTo("thermal.alert.raised");
        assertThat(j.get("occurredAt").asText()).isEqualTo("2026-11-18T07:10:06.310Z");
        assertThat(j.get("producer").asText()).isEqualTo("TELEMETRY-SERVICE");
        assertThat(j.get("version").asInt()).isEqualTo(1);
        // plan 8.5: { alertId, rackId, zoneId, level, previousLevel, basis, tempC, forecast60C }
        JsonNode d = j.get("data");
        assertThat(d.get("alertId").asLong()).isEqualTo(412);
        assertThat(d.get("rackId").asText()).isEqualTo("R23");
        assertThat(d.get("zoneId").asText()).isEqualTo("Z2");
        assertThat(d.get("level").asText()).isEqualTo("WARNING");
        assertThat(d.get("previousLevel").asText()).isEqualTo("WATCH");
        assertThat(d.get("basis").asText()).isEqualTo("PREDICTED");
        assertThat(d.get("tempC").asDouble()).isEqualTo(30.4);
        assertThat(d.get("forecast60C").asDouble()).isEqualTo(35.2);
    }

    @Test
    void everyEventGetsAFreshEventId() throws Exception {
        publisher.publish(transition(Kind.RAISED));
        publisher.publish(transition(Kind.RAISED));
        ArgumentCaptor<Message> msg = ArgumentCaptor.forClass(Message.class);
        verify(rabbit, times(2)).send(anyString(), anyString(), msg.capture());
        assertThat(msg.getAllValues().get(0).getMessageProperties().getMessageId())
                .isNotEqualTo(msg.getAllValues().get(1).getMessageProperties().getMessageId());
    }

    @Test
    void aBrokerOutageIsRetriedBoundedTimesThenReportedAsNotPublishedWithoutThrowing() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class));

        boolean published = publisher.publish(transition(Kind.RAISED));

        assertThat(published).isFalse();
        verify(rabbit, times(3)).send(anyString(), anyString(), any(Message.class));
        assertThat(sleeps).containsExactly(1L, 1L);   // 3 attempts, a pause between each
    }

    @Test
    void aTransientFailureIsRecoveredByTheRetry() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("refused"))).doNothing()
                .when(rabbit).send(anyString(), anyString(), any(Message.class));

        assertThat(publisher.publish(transition(Kind.LOWERED))).isTrue();

        verify(rabbit, times(2)).send(anyString(), anyString(), any(Message.class));
    }
}
