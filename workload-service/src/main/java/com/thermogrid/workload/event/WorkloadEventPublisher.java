package com.thermogrid.workload.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.Exchanges;
import com.thermogrid.workload.config.WorkloadProperties;
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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends {@link WorkloadEvent}s to {@code thermogrid.events} strictly after the surrounding transaction has committed.
 * A failed send is retried a few times and then logged: the database change is already durable, so it is never rolled
 * back (the dual-write risk of plan section 15). The simulator's 60-second reconciliation repairs a lost
 * assigned/removed event.
 */
@Component
public class WorkloadEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkloadEventPublisher.class);

    private final RabbitOperations rabbit;
    private final ObjectMapper mapper;
    private final WorkloadProperties props;

    public WorkloadEventPublisher(RabbitOperations rabbit, ObjectMapper mapper, WorkloadProperties props) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.props = props;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(WorkloadEvent event) {
        publish(event);
    }

    /** @return true if the message was handed to the broker */
    boolean publish(WorkloadEvent event) {
        UUID id = UUID.randomUUID();
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(new EventEnvelope<>(id, event.routingKey(), event.occurredAt(),
                    props.producer(), props.eventVersion(), event.data()));
        } catch (JsonProcessingException e) {
            log.error("Cannot serialise {}: {}", event.routingKey(), e.getMessage());
            return false;
        }
        Message message = MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(id.toString())
                .build();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= props.publishAttempts(); attempt++) {
            try {
                rabbit.send(Exchanges.EVENTS, event.routingKey(), message);
                return true;
            } catch (RuntimeException e) {
                last = e;
                log.warn("Publishing {} failed (attempt {}/{}): {}", event.routingKey(), attempt,
                        props.publishAttempts(), e.toString());
                if (attempt < props.publishAttempts() && props.publishRetryMs() > 0) {
                    try {
                        Thread.sleep(props.publishRetryMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.error("Giving up publishing {} {} after the commit; the database change stands: {}", event.routingKey(),
                event.data(), last == null ? "interrupted" : last.toString());
        return false;
    }
}
