package com.thermogrid.cooling.config;

import com.thermogrid.contracts.Exchanges;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.cooling.listener.CoolingEventProcessor.MalformedEventException;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * Queues owned by the cooling controller (plan 8.4). Both are state queues: durable, bounded, and messages expire
 * (30 s for zone readings, 60 s for alerts) because a late reading is worthless to a control loop; expired and
 * overflowing messages go to the dead-letter exchange. Listeners use a single consumer so readings stay in order.
 * A failing message is retried 3 times (1, 2, 4 s); a malformed one is dead-lettered at once.
 */
@Configuration
@ConditionalOnProperty(name = "thermogrid.cooling.listeners-enabled", havingValue = "true", matchIfMissing = true)
public class AmqpConfig {

    public static final String ZONE_QUEUE = "q.cooling.zone-readings";
    public static final String ALERT_QUEUE = "q.cooling.alerts";
    public static final String DEAD_LETTER_QUEUE = "thermogrid.dlq";

    @Bean
    TopicExchange deadLetterExchange() {
        return new TopicExchange(Exchanges.DEAD_LETTER, true, false);
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("#");
    }

    @Bean
    Queue coolingZoneQueue(CoolingProperties props) {
        return stateQueue(ZONE_QUEUE, props.amqp().zoneQueueTtlMs(), props);
    }

    @Bean
    Queue coolingAlertQueue(CoolingProperties props) {
        return stateQueue(ALERT_QUEUE, props.amqp().alertQueueTtlMs(), props);
    }

    private static Queue stateQueue(String name, long ttlMs, CoolingProperties props) {
        return QueueBuilder.durable(name).withArguments(Map.of(
                "x-message-ttl", ttlMs,
                "x-max-length", props.amqp().queueMaxLength(),
                "x-overflow", "drop-head",
                "x-dead-letter-exchange", Exchanges.DEAD_LETTER)).build();
    }

    @Bean
    Binding zoneBinding(Queue coolingZoneQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(coolingZoneQueue).to(eventsExchange).with(RoutingKeys.TELEMETRY_ZONE_READING);
    }

    @Bean
    Binding alertBinding(Queue coolingAlertQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(coolingAlertQueue).to(eventsExchange).with(RoutingKeys.THERMAL_ALERT_ALL);
    }

    @Bean
    ContainerCustomizer<SimpleMessageListenerContainer> retryContainerCustomizer() {
        SimpleRetryPolicy policy = new SimpleRetryPolicy(3, Map.of(MalformedEventException.class, false), true, true);
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(4000);
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(policy);
        template.setBackOffPolicy(backOff);
        return container -> container.setAdviceChain(RetryInterceptorBuilder.stateless().retryOperations(template)
                .recoverer(new RejectAndDontRequeueRecoverer()).build());
    }
}
