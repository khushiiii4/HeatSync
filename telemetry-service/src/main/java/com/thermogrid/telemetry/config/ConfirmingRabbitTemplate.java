package com.thermogrid.telemetry.config;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * RabbitTemplate for event publishers (plan 8.6, phase 7). Every message sent through {@code send(exchange, key,
 * message)} is tracked by its message id, so the broker's publisher confirm (ack or nack) and a "returned" notice for
 * an unroutable message (mandatory publishing) reach a callback that logs them. The callbacks are asynchronous and the
 * template keeps one small entry per unconfirmed message, so nothing grows without bound: if the broker is down the
 * send itself fails fast, which the publishers already handle.
 *
 * <p>A nack or an unroutable message is logged at ERROR with the routing key and message id; it is never retried here,
 * because every publisher either re-sends absolute state (heartbeats, resync) or documents its dual-write limitation.
 */
public class ConfirmingRabbitTemplate extends RabbitTemplate {

    private static final Logger log = LoggerFactory.getLogger(ConfirmingRabbitTemplate.class);

    private final AtomicLong confirmed = new AtomicLong();
    private final AtomicLong nacked = new AtomicLong();
    private final AtomicLong returned = new AtomicLong();

    public ConfirmingRabbitTemplate() {
        setMandatory(true);
        setConfirmCallback(this::onConfirm);
        setReturnsCallback(this::onReturn);
    }

    void onConfirm(CorrelationData correlation, boolean ack, String cause) {
        if (ack) {
            confirmed.incrementAndGet();
        } else {
            nacked.incrementAndGet();
            log.error("Broker NACKED message {}: {}", correlation == null ? "?" : correlation.getId(), cause);
        }
    }

    void onReturn(ReturnedMessage r) {
        long n = returned.incrementAndGet();
        if (n == 1 || n % 100 == 0) {   // consumers may simply not be up yet: do not flood the log
            log.warn("Message {} to {} / {} was UNROUTABLE ({} {}): no queue is bound for it ({} so far)",
                    r.getMessage().getMessageProperties().getMessageId(), r.getExchange(), r.getRoutingKey(),
                    r.getReplyCode(), r.getReplyText(), n);
        }
    }

    @Override
    public void send(String exchange, String routingKey, Message message) {
        String id = message.getMessageProperties().getMessageId();
        send(exchange, routingKey, message, id == null ? null : new CorrelationData(id));
    }

    public long confirmedCount() {
        return confirmed.get();
    }

    public long nackedCount() {
        return nacked.get();
    }

    public long returnedCount() {
        return returned.get();
    }
}
