package com.thermogrid.scheduler.config;

import com.thermogrid.contracts.Exchanges;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.scheduler.service.EventProcessor.MalformedEventException;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queues owned by the scheduler (plan section 8.4). Each consumer declares its own queues, so start order does not
 * matter. {@code q.scheduler.workloads} is lossless (durable, no TTL, bounded, dead-lettered). The two state queues
 * expire messages after 60 s and carry NO dead-letter arguments: a bad or expired state message is dropped, because
 * alerts and cooling state are absolute and the reconcile loop recomputes them.
 *
 * <p>Two listener container factories: the lossless one retries 3 times (1 s, 2 s, 4 s) and then republishes the
 * message with its failure headers to thermogrid.dlx (malformed messages are not retried); the state one has no
 * retry. Changing queue arguments on an existing broker needs a one-off migration (docs/messaging.md).
 */
@Configuration
@ConditionalOnProperty(name = "thermogrid.scheduler.listeners-enabled", havingValue = "true", matchIfMissing = true)
public class AmqpConfig {

    public static final String WORKLOADS_QUEUE = "q.scheduler.workloads";
    public static final String ALERTS_QUEUE = "q.scheduler.alerts";
    public static final String COOLING_QUEUE = "q.scheduler.cooling";
    public static final String DEAD_LETTER_QUEUE = "thermogrid.dlq";
    public static final String LOSSLESS_FACTORY = "losslessListenerContainerFactory";
    public static final String STATE_FACTORY = "stateListenerContainerFactory";
    static final int RETRY_ATTEMPTS = 3;
    /** Bounded wait for the broker to confirm a dead-letter republish. */
    static final long CONFIRM_TIMEOUT_MS = 3000;

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
    Queue schedulerWorkloadsQueue(SchedulerProperties props) {
        return QueueBuilder.durable(WORKLOADS_QUEUE).withArguments(Map.of(
                "x-max-length", props.amqp().queueMaxLength(),
                "x-overflow", "drop-head",
                "x-dead-letter-exchange", Exchanges.DEAD_LETTER)).build();
    }

    @Bean
    Queue schedulerAlertsQueue(SchedulerProperties props) {
        return stateQueue(ALERTS_QUEUE, props);
    }

    @Bean
    Queue schedulerCoolingQueue(SchedulerProperties props) {
        return stateQueue(COOLING_QUEUE, props);
    }

    private static Queue stateQueue(String name, SchedulerProperties props) {
        return QueueBuilder.durable(name).withArguments(Map.of(
                "x-message-ttl", props.amqp().stateQueueTtlMs(),
                "x-max-length", props.amqp().queueMaxLength(),
                "x-overflow", "drop-head")).build();
    }

    @Bean
    Binding submittedBinding(Queue schedulerWorkloadsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(schedulerWorkloadsQueue).to(eventsExchange).with(RoutingKeys.WORKLOAD_SUBMITTED);
    }

    @Bean
    Binding alertsBinding(Queue schedulerAlertsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(schedulerAlertsQueue).to(eventsExchange).with(RoutingKeys.THERMAL_ALERT_ALL);
    }

    @Bean
    Binding coolingBinding(Queue schedulerCoolingQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(schedulerCoolingQueue).to(eventsExchange).with(RoutingKeys.COOLING_UNIT_STATE);
    }

    /** Republishes an exhausted lossless message to thermogrid.dlx with failure headers, else rejects it. */
    @Bean
    DeadLetterRecoverer schedulerDeadLetterRecoverer(RabbitOperations rabbit) {
        return new DeadLetterRecoverer(rabbit, Exchanges.DEAD_LETTER, RETRY_ATTEMPTS, CONFIRM_TIMEOUT_MS,
                MalformedEventException.class);
    }

    /** Lossless consumer: 3 attempts (1, 2, 4 s), malformed messages not retried, then dead-lettered. */
    @Bean(name = LOSSLESS_FACTORY)
    SimpleRabbitListenerContainerFactory losslessListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            DeadLetterRecoverer schedulerDeadLetterRecoverer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryOperations(DeadLetterRecoverer.retryTemplate(RETRY_ATTEMPTS, 1000, 2.0, 4000, null,
                        MalformedEventException.class))
                .recoverer(schedulerDeadLetterRecoverer).build());
        return factory;
    }

    /** State consumer: no retry and no dead-lettering; the listener logs and drops what it cannot process. */
    @Bean(name = STATE_FACTORY)
    SimpleRabbitListenerContainerFactory stateListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
