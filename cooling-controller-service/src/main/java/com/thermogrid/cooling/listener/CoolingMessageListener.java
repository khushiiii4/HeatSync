package com.thermogrid.cooling.listener;

import com.thermogrid.cooling.config.AmqpConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ZoneReadingListener and AlertListener of the plan: one method per queue. Both queues are state queues (plan 8.4): a
 * message that cannot be processed is logged and dropped, never retried and never dead-lettered, because the next
 * zone reading or alert supersedes it.
 */
@Component
@ConditionalOnProperty(name = "thermogrid.cooling.listeners-enabled", havingValue = "true", matchIfMissing = true)
public class CoolingMessageListener {

    private static final Logger log = LoggerFactory.getLogger(CoolingMessageListener.class);

    private final CoolingEventProcessor processor;

    public CoolingMessageListener(CoolingEventProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(queues = AmqpConfig.ZONE_QUEUE, containerFactory = AmqpConfig.STATE_FACTORY)
    public void onZoneReading(Message m) {
        handle(m);
    }

    @RabbitListener(queues = AmqpConfig.ALERT_QUEUE, containerFactory = AmqpConfig.STATE_FACTORY)
    public void onAlert(Message m) {
        handle(m);
    }

    void handle(Message m) {
        String key = m.getMessageProperties().getReceivedRoutingKey();
        try {
            processor.handle(key, m.getBody());
        } catch (CoolingEventProcessor.MalformedEventException e) {
            log.warn("Dropping malformed state message {} ({}): {}", m.getMessageProperties().getMessageId(), key,
                    e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Dropping state message {} ({}) after a processing failure: {}",
                    m.getMessageProperties().getMessageId(), key, e.toString());
        }
    }
}
