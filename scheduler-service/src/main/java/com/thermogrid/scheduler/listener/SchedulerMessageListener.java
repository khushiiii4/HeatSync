package com.thermogrid.scheduler.listener;

import com.thermogrid.scheduler.config.AmqpConfig;
import com.thermogrid.scheduler.service.EventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** WorkloadSubmittedListener, AlertListener and CoolingStateListener of the plan: one method per queue. */
@Component
@ConditionalOnProperty(name = "thermogrid.scheduler.listeners-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerMessageListener {

    private static final Logger log = LoggerFactory.getLogger(SchedulerMessageListener.class);

    private final EventProcessor processor;

    public SchedulerMessageListener(EventProcessor processor) {
        this.processor = processor;
    }

    /** Lossless: any exception goes to the retry advice and, finally, to the dead-letter queue. */
    @RabbitListener(queues = AmqpConfig.WORKLOADS_QUEUE, containerFactory = AmqpConfig.LOSSLESS_FACTORY)
    public void onWorkload(Message m) {
        processor.handle(m.getMessageProperties().getReceivedRoutingKey(), m.getBody());
    }

    @RabbitListener(queues = AmqpConfig.ALERTS_QUEUE, containerFactory = AmqpConfig.STATE_FACTORY)
    public void onAlert(Message m) {
        handleState(m);
    }

    @RabbitListener(queues = AmqpConfig.COOLING_QUEUE, containerFactory = AmqpConfig.STATE_FACTORY)
    public void onCooling(Message m) {
        handleState(m);
    }

    /**
     * State queues (plan 8.4): a message that cannot be processed is logged and dropped, never retried or
     * dead-lettered. The next absolute-state message or the 5 s reconcile loop makes up for it.
     */
    void handleState(Message m) {
        String key = m.getMessageProperties().getReceivedRoutingKey();
        try {
            processor.handle(key, m.getBody());
        } catch (EventProcessor.MalformedEventException e) {
            log.warn("Dropping malformed state message {} ({}): {}", m.getMessageProperties().getMessageId(), key,
                    e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Dropping state message {} ({}) after a processing failure: {}",
                    m.getMessageProperties().getMessageId(), key, e.toString());
        }
    }
}
