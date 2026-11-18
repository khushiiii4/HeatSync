package com.thermogrid.cooling.config;

import com.thermogrid.contracts.Exchanges;
import com.thermogrid.contracts.RoutingKeys;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queues owned by the cooling controller (plan 8.4). Both are state queues: durable, bounded, and messages expire
 * (30 s for zone readings, 60 s for alerts) because a late reading is worthless to a control loop. They carry NO
 * dead-letter arguments: an expired, overflowing or invalid state message is discarded, never dead-lettered, and the
 * listener logs and drops what it cannot process. There is nothing to retry: the next reading or alert replaces it.
 * A single consumer keeps zone readings in order. Changing queue arguments on an existing broker needs a one-off
 * migration (docs/messaging.md).
 */
@Configuration
@ConditionalOnProperty(name = "thermogrid.cooling.listeners-enabled", havingValue = "true", matchIfMissing = true)
public class AmqpConfig {

    public static final String ZONE_QUEUE = "q.cooling.zone-readings";
    public static final String ALERT_QUEUE = "q.cooling.alerts";
    public static final String DEAD_LETTER_QUEUE = "thermogrid.dlq";
    public static final String STATE_FACTORY = "stateListenerContainerFactory";

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

    static Queue stateQueue(String name, long ttlMs, CoolingProperties props) {
        return QueueBuilder.durable(name).withArguments(Map.of(
                "x-message-ttl", ttlMs,
                "x-max-length", props.amqp().queueMaxLength(),
                "x-overflow", "drop-head")).build();
    }

    @Bean
    Binding zoneBinding(Queue coolingZoneQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(coolingZoneQueue).to(eventsExchange).with(RoutingKeys.TELEMETRY_ZONE_READING);
    }

    @Bean
    Binding alertBinding(Queue coolingAlertQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(coolingAlertQueue).to(eventsExchange).with(RoutingKeys.THERMAL_ALERT_ALL);
    }

    /** State consumer: no retry, no requeue, no dead-lettering. */
    @Bean(name = STATE_FACTORY)
    SimpleRabbitListenerContainerFactory stateListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
