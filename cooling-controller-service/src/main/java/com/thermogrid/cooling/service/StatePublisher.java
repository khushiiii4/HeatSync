package com.thermogrid.cooling.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.CoolingUnitStateData;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.Exchanges;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.cooling.config.CoolingProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
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
 * Publishes {@code cooling.unit.state} to {@code thermogrid.events}. The caller has already persisted the state, so a
 * failed publish loses nothing durable: after a failure the broker is not tried again for a cooldown (so a dead broker
 * never stalls the control loop), one concise warning is logged, and the next heartbeat carries the latest absolute
 * state. Nothing is buffered in memory.
 */
@Component
public class StatePublisher {

    private static final Logger log = LoggerFactory.getLogger(StatePublisher.class);

    private final RabbitOperations rabbit;
    private final ObjectMapper mapper;
    private final CoolingProperties props;
    private final Clock clock;
    private Instant retryNotBefore = Instant.MIN;
    private boolean lastFailed;
    private long published;
    private long failed;

    public StatePublisher(RabbitOperations rabbit, ObjectMapper mapper, CoolingProperties props, Clock clock) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    /** @return true if the message was handed to the broker */
    public synchronized boolean publish(CoolingUnitStateData data, Instant occurredAt) {
        Instant now = clock.instant();
        if (now.isBefore(retryNotBefore)) {
            failed++;
            return false;
        }
        UUID id = UUID.randomUUID();
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(new EventEnvelope<>(id, RoutingKeys.COOLING_UNIT_STATE, occurredAt,
                    props.producer(), props.eventVersion(), data));
        } catch (JsonProcessingException e) {
            log.error("Cannot serialise cooling.unit.state: {}", e.getMessage());
            return false;
        }
        Message message = MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(id.toString())
                .build();
        try {
            rabbit.send(Exchanges.EVENTS, RoutingKeys.COOLING_UNIT_STATE, message);
            published++;
            if (lastFailed) {
                log.info("RabbitMQ publishing resumed");
                lastFailed = false;
            }
            return true;
        } catch (RuntimeException e) {
            failed++;
            lastFailed = true;
            retryNotBefore = now.plusMillis(props.publishRetryCooldownMs());
            log.warn("RabbitMQ unavailable, cooling.unit.state for {} seq {} not published (next attempt in {} ms; "
                    + "the heartbeat re-sends the state): {}", data.zoneId(), data.seq(),
                    props.publishRetryCooldownMs(), e.toString());
            return false;
        }
    }

    /** Forgets the failure cooldown (used by tests that move the clock backwards). */
    public synchronized void reset() {
        retryNotBefore = Instant.MIN;
        lastFailed = false;
    }

    public synchronized long publishedCount() {
        return published;
    }

    public synchronized long failedCount() {
        return failed;
    }
}
