package com.thermogrid.telemetry.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.Exchanges;
import com.thermogrid.telemetry.config.TelemetryProperties;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.stereotype.Component;

/**
 * Publishes alert events after the database transaction has committed.
 *
 * <p><b>Dual-write limitation (documented, decision D-031):</b> the alert row is the source of truth and is already
 * committed when this runs. If RabbitMQ stays unreachable through the bounded attempts, the event is NOT delivered; an
 * ERROR with the full event is logged and the alert remains correct in the database and in {@code GET /api/alerts}.
 * Consumers that need certainty must reconcile from the API (the scheduler and cooling controller do this by design).
 * Redelivering the reading cannot recover it, because the duplicate reading is ignored. An outbox table is the listed
 * enhancement and is intentionally not part of Phase 3.
 */
@Component
public class AlertEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AlertEventPublisher.class);

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final RabbitOperations rabbit;
    private final ObjectMapper mapper;
    private final TelemetryProperties props;
    private final Sleeper sleeper;

    @org.springframework.beans.factory.annotation.Autowired
    public AlertEventPublisher(RabbitOperations rabbit, ObjectMapper mapper, TelemetryProperties props) {
        this(rabbit, mapper, props, Thread::sleep);
    }

    public AlertEventPublisher(RabbitOperations rabbit, ObjectMapper mapper, TelemetryProperties props,
                               Sleeper sleeper) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.props = props;
        this.sleeper = sleeper;
    }

    /** @return true if the broker accepted the event; false if it was given up on after the bounded attempts */
    public boolean publish(AlertTransition t) {
        UUID id = UUID.randomUUID();
        var envelope = new EventEnvelope<>(id, t.kind().routingKey(), t.occurredAt(), props.producer(),
                props.eventVersion(), t.data());
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(envelope);
        } catch (JsonProcessingException e) {
            log.error("Cannot serialise {} for alert {}", t.kind().routingKey(), t.data().alertId(), e);
            return false;
        }
        Message message = MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(id.toString())
                .build();

        int attempts = props.alerts().publishAttempts();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                rabbit.send(Exchanges.EVENTS, t.kind().routingKey(), message);
                return true;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < attempts) {
                    try {
                        sleeper.sleep(props.alerts().publishRetryMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("ALERT EVENT NOT PUBLISHED after {} attempt(s): {} {} (alert {} stays correct in the database): {}",
                attempts, t.kind().routingKey(), new String(body, StandardCharsets.UTF_8), t.data().alertId(),
                last == null ? "interrupted" : last.toString());
        return false;
    }
}
