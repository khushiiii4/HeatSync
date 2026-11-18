package com.thermogrid.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.TelemetryRackReadingData;
import com.thermogrid.contracts.TelemetryZoneReadingData;
import com.thermogrid.telemetry.config.AmqpConfig;
import com.thermogrid.telemetry.config.TelemetryProperties;
import com.thermogrid.telemetry.listener.RackReadingListener;
import com.thermogrid.telemetry.listener.ZoneReadingListener;
import com.thermogrid.telemetry.service.InvalidReadingException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.retry.support.RetryTemplate;

/** Queues, bindings, message mapping, retry and dead-letter rules, checked without a broker. */
class AmqpTopologyTest {

    private final TelemetryProperties props = TestSupport.props();
    private final AmqpConfig config = new AmqpConfig();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // ---- topology ----

    @Test
    void readingQueuesAreDurableLosslessAndDeadLetterToTheDlx() {
        Queue rack = config.rackReadingQueue(props);
        Queue zone = config.zoneReadingQueue(props);

        assertThat(rack.getName()).isEqualTo("q.telemetry.rack-readings");
        assertThat(zone.getName()).isEqualTo("q.telemetry.zone-readings");
        for (Queue q : List.of(rack, zone)) {
            assertThat(q.isDurable()).isTrue();
            assertThat(q.isAutoDelete()).isFalse();
            assertThat(q.isExclusive()).isFalse();
            assertThat(q.getArguments()).containsEntry("x-dead-letter-exchange", "thermogrid.dlx")
                    .containsEntry("x-overflow", "drop-head").doesNotContainKey("x-message-ttl");
        }
        assertThat(rack.getArguments()).containsEntry("x-max-length", 50000);
        assertThat(zone.getArguments()).containsEntry("x-max-length", 20000);
    }

    @Test
    void queuesAreBoundToTheEventsExchangeWithTheRightRoutingKeys() {
        TopicExchange events = config.eventsExchange();
        Binding rack = config.rackReadingBinding(config.rackReadingQueue(props), events);
        Binding zone = config.zoneReadingBinding(config.zoneReadingQueue(props), events);

        assertThat(events.getName()).isEqualTo("thermogrid.events");
        assertThat(events.getType()).isEqualTo("topic");
        assertThat(events.isDurable()).isTrue();
        assertThat(rack.getExchange()).isEqualTo("thermogrid.events");
        assertThat(rack.getDestination()).isEqualTo("q.telemetry.rack-readings");
        assertThat(rack.getRoutingKey()).isEqualTo("telemetry.rack.reading");
        assertThat(zone.getExchange()).isEqualTo("thermogrid.events");
        assertThat(zone.getDestination()).isEqualTo("q.telemetry.zone-readings");
        assertThat(zone.getRoutingKey()).isEqualTo("telemetry.zone.reading");
    }

    @Test
    void deadLetterExchangeAndQueueAreDeclaredAndBoundWithHash() {
        TopicExchange dlx = config.deadLetterExchange();
        Queue dlq = config.deadLetterQueue();
        Binding b = config.deadLetterBinding(dlq, dlx);
        assertThat(dlx.getName()).isEqualTo("thermogrid.dlx");
        assertThat(dlx.getType()).isEqualTo("topic");
        assertThat(dlx.isDurable()).isTrue();
        assertThat(dlq.getName()).isEqualTo("thermogrid.dlq");
        assertThat(dlq.isDurable()).isTrue();
        assertThat(b.getExchange()).isEqualTo("thermogrid.dlx");
        assertThat(b.getRoutingKey()).isEqualTo("#");
        assertThat(b.getDestination()).isEqualTo("thermogrid.dlq");
    }

    @Test
    void listenersConsumeTheRightQueues() throws Exception {
        assertThat(queueOf(RackReadingListener.class, "onRackReading")).containsExactly("q.telemetry.rack-readings");
        assertThat(queueOf(ZoneReadingListener.class, "onZoneReading")).containsExactly("q.telemetry.zone-readings");
    }

    private String[] queueOf(Class<?> type, String method) throws Exception {
        Method m = type.getMethod(method, EventEnvelope.class);
        return m.getAnnotation(org.springframework.amqp.rabbit.annotation.RabbitListener.class).queues();
    }

    // ---- message mapping ----

    private Jackson2JsonMessageConverter converter() {
        return new Jackson2JsonMessageConverter(mapper);
    }

    private Message json(String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
    }

    private Object convert(Message m, Class<?> listener, String method) throws Exception {
        Type generic = listener.getMethod(method, EventEnvelope.class).getGenericParameterTypes()[0];
        m.getMessageProperties().setInferredArgumentType(generic);
        return converter().fromMessage(m);
    }

    /** A message exactly as simulator-service publishes it. */
    private static final String SIMULATOR_RACK = "{\"eventId\":\"82539fde-fc6f-4528-a018-d4928769b890\","
            + "\"eventType\":\"telemetry.rack.reading\",\"occurredAt\":\"2026-11-18T14:12:50.546559100Z\","
            + "\"producer\":\"SIMULATOR-SERVICE\",\"version\":1,\"data\":{\"rackId\":\"R11\",\"zoneId\":\"Z1\","
            + "\"tempC\":26.11,\"powerKw\":6.8,\"cpuLoadPct\":60.0}}";
    private static final String SIMULATOR_ZONE = "{\"eventId\":\"2385d980-7067-4b8d-9ff2-f27a5a697a79\","
            + "\"eventType\":\"telemetry.zone.reading\",\"occurredAt\":\"2026-11-18T14:12:50.546559100Z\","
            + "\"producer\":\"SIMULATOR-SERVICE\",\"version\":1,\"data\":{\"zoneId\":\"Z1\",\"avgTempC\":26.25,"
            + "\"maxTempC\":26.38,\"supplyTempC\":18.0,\"fanActualPct\":40,\"unitHealth\":\"OK\"}}";

    @Test
    @SuppressWarnings("unchecked")
    void simulatorRackMessageIsMappedToTheContractRecord() throws Exception {
        EventEnvelope<TelemetryRackReadingData> env = (EventEnvelope<TelemetryRackReadingData>) convert(
                json(SIMULATOR_RACK), RackReadingListener.class, "onRackReading");
        assertThat(env.eventType()).isEqualTo("telemetry.rack.reading");
        assertThat(env.occurredAt()).isEqualTo(Instant.parse("2026-11-18T14:12:50.546559100Z"));
        assertThat(env.data()).isEqualTo(new TelemetryRackReadingData("R11", "Z1", 26.11, 6.8, 60.0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void simulatorZoneMessageIsMappedToTheContractRecord() throws Exception {
        EventEnvelope<TelemetryZoneReadingData> env = (EventEnvelope<TelemetryZoneReadingData>) convert(
                json(SIMULATOR_ZONE), ZoneReadingListener.class, "onZoneReading");
        assertThat(env.data()).isEqualTo(new TelemetryZoneReadingData("Z1", 26.25, 26.38, 18.0, 40, "OK"));
    }

    @Test
    void anUndeserialisableMessageFailsConversionAndTheContainerDeadLettersItAtOnce() {
        Message garbage = json("this is { not json");
        garbage.getMessageProperties().setInferredArgumentType(TelemetryRackReadingData.class);
        assertThatThrownBy(() -> converter().fromMessage(garbage)).isInstanceOf(MessageConversionException.class);

        // The default error handler treats conversion errors as fatal: reject without requeue, no retries.
        var handler = new org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler();
        var wrapped = new ListenerExecutionFailedException("Listener threw exception",
                new MessageConversionException("cannot convert"), garbage);
        assertThatThrownBy(() -> handler.handleError(wrapped)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    // ---- retry and dead-letter rules ----

    private RetryTemplate template(List<Long> sleeps) {
        return AmqpConfig.buildRetryTemplate(props.amqp(), sleeps::add);
    }

    @Test
    void aFailingMessageIsTriedThreeTimesWithOneTwoFourSecondBackoff() {
        List<Long> sleeps = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> template(sleeps).execute(ctx -> {
            calls.incrementAndGet();
            throw new ListenerExecutionFailedException("db down", new IllegalStateException("db down"));
        })).isInstanceOf(ListenerExecutionFailedException.class);

        assertThat(calls).hasValue(3);
        assertThat(sleeps).containsExactly(1000L, 2000L);   // waits between attempts 1-2 and 2-3
    }

    @Test
    void anInvalidReadingIsNotRetried() {
        List<Long> sleeps = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> template(sleeps).execute(ctx -> {
            calls.incrementAndGet();
            throw new ListenerExecutionFailedException("bad", new InvalidReadingException("tempC is NaN"));
        })).isInstanceOf(ListenerExecutionFailedException.class);

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aMessageThatSucceedsOnTheSecondAttemptIsNotDeadLettered() {
        List<Long> sleeps = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        String result = template(sleeps).execute(ctx -> {
            if (calls.incrementAndGet() < 2) {
                throw new IllegalStateException("transient");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(sleeps).containsExactly(1000L);
    }

    @Test
    void whenRetriesAreExhaustedTheRecovererRejectsWithoutRequeueWhichDeadLetters() {
        Message m = json(SIMULATOR_RACK);
        var failure = new ListenerExecutionFailedException("db down", new IllegalStateException("db down"), m);
        // The recoverer wraps an AmqpRejectAndDontRequeueException; the container rejects without requeue on it.
        assertThatThrownBy(() -> new RejectAndDontRequeueRecoverer().recover(m, failure))
                .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class);
        // The retry advice (stateless, 3 attempts) with that recoverer is what AmqpConfig installs.
        assertThat(AmqpConfig.retryInterceptor(template(new ArrayList<>()))).isNotNull();
    }

    @Test
    void retryAndTopologyValuesComeFromConfiguration() {
        assertThat(props.amqp().retryAttempts()).isEqualTo(3);
        assertThat(props.amqp().retryInitialMs()).isEqualTo(1000);
        assertThat(props.amqp().retryMultiplier()).isEqualTo(2.0);
        assertThat(props.amqp().retryMaxMs()).isEqualTo(4000);
    }
}
