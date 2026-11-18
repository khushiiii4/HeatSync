package com.thermogrid.cooling.listener;

import com.thermogrid.cooling.config.AmqpConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** ZoneReadingListener and AlertListener of the plan: one method per queue. */
@Component
@ConditionalOnProperty(name = "thermogrid.cooling.listeners-enabled", havingValue = "true", matchIfMissing = true)
public class CoolingMessageListener {

    private final CoolingEventProcessor processor;

    public CoolingMessageListener(CoolingEventProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(queues = AmqpConfig.ZONE_QUEUE)
    public void onZoneReading(Message m) {
        processor.handle(m.getMessageProperties().getReceivedRoutingKey(), m.getBody());
    }

    @RabbitListener(queues = AmqpConfig.ALERT_QUEUE)
    public void onAlert(Message m) {
        processor.handle(m.getMessageProperties().getReceivedRoutingKey(), m.getBody());
    }
}
