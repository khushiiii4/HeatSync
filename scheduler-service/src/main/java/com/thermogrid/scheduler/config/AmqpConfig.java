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
 * Queues owned by the scheduler (plan section 8.4). Each consumer declares its own queues, so start order does not
 * matter. {@code q.scheduler.workloads} is lossless (durable, bounded, dead-lettered); the two state queues drop
 * messages after 60 s, because alerts and cooling state are absolute and the reconcile loop recomputes them.
 * A failing message is retried 3 times (1 s, 2 s, 4 s) and then dead-lettered to thermogrid.dlq; a malformed message
 * is dead-lettered at once (plan 8.6).
 */
@Configuration
@ConditionalOnProperty(name = "thermogrid.scheduler.listeners-enabled", havingValue = "true", matchIfMissing = true)
public class AmqpConfig {

    public static final String WORKLOADS_QUEUE = "q.scheduler.workloads";
    public static final String ALERTS_QUEUE = "q.scheduler.alerts";
    public static final String COOLING_QUEUE = "q.scheduler.cooling";
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
                "x-overflow", "drop-head",
                "x-dead-letter-exchange", Exchanges.DEAD_LETTER)).build();
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

    /** 3 attempts with 1 s, 2 s, 4 s backoff; malformed messages are never retried; then reject without requeue. */
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
