package com.thermogrid.scheduler.listener;

import com.thermogrid.scheduler.config.AmqpConfig;
import com.thermogrid.scheduler.service.EventProcessor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** WorkloadSubmittedListener, AlertListener and CoolingStateListener of the plan: one method per queue. */
@Component
@ConditionalOnProperty(name = "thermogrid.scheduler.listeners-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerMessageListener {

    private final EventProcessor processor;

    public SchedulerMessageListener(EventProcessor processor) {
        this.processor = processor;
    }

    @RabbitListener(queues = AmqpConfig.WORKLOADS_QUEUE)
    public void onWorkload(Message m) {
        processor.handle(m.getMessageProperties().getReceivedRoutingKey(), m.getBody());
    }

    @RabbitListener(queues = AmqpConfig.ALERTS_QUEUE)
    public void onAlert(Message m) {
        processor.handle(m.getMessageProperties().getReceivedRoutingKey(), m.getBody());
    }

    @RabbitListener(queues = AmqpConfig.COOLING_QUEUE)
    public void onCooling(Message m) {
        processor.handle(m.getMessageProperties().getReceivedRoutingKey(), m.getBody());
    }
}
