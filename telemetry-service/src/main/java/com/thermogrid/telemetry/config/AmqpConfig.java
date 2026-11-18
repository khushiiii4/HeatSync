package com.thermogrid.telemetry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.Exchanges;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.telemetry.service.InvalidReadingException;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.Sleeper;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * RabbitMQ topology owned by telemetry-service (plan 8.4). Exchanges are declared by every service that uses them,
 * and each consumer declares its own queues, so this works whatever the start order.
 *
 * <p>Both reading queues are "lossless": durable, no TTL, bounded length (oldest dropped first) and dead-lettered to
 * {@code thermogrid.dlx} / {@code thermogrid.dlq}. Listener containers retry 3 times (1 s, 2 s, 4 s) and then reject
 * without requeue, which dead-letters the message. Undeserialisable and invalid messages skip the retries.
 */
@Configuration
public class AmqpConfig {

    public static final String RACK_QUEUE = "q.telemetry.rack-readings";
    public static final String ZONE_QUEUE = "q.telemetry.zone-readings";
    public static final String DEAD_LETTER_QUEUE = "thermogrid.dlq";
    /** Bounded wait for the broker to confirm a dead-letter republish. */
    static final long CONFIRM_TIMEOUT_MS = 3000;

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(Exchanges.EVENTS, true, false);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(Exchanges.DEAD_LETTER, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("#");
    }

    @Bean
    public Queue rackReadingQueue(TelemetryProperties props) {
        return lossless(RACK_QUEUE, props.amqp().rackQueueMaxLength());
    }

    @Bean
    public Queue zoneReadingQueue(TelemetryProperties props) {
        return lossless(ZONE_QUEUE, props.amqp().zoneQueueMaxLength());
    }

    @Bean
    public Binding rackReadingBinding(Queue rackReadingQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(rackReadingQueue).to(eventsExchange).with(RoutingKeys.TELEMETRY_RACK_READING);
    }

    @Bean
    public Binding zoneReadingBinding(Queue zoneReadingQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(zoneReadingQueue).to(eventsExchange).with(RoutingKeys.TELEMETRY_ZONE_READING);
    }

    static Queue lossless(String name, int maxLength) {
        return QueueBuilder.durable(name)
                .withArguments(Map.of(
                        "x-max-length", maxLength,
                        "x-overflow", "drop-head",
                        "x-dead-letter-exchange", Exchanges.DEAD_LETTER))
                .build();
    }

    /** JSON in and out. A body that cannot be converted fails fatally and is dead-lettered at once. */
    @Bean
    MessageConverter messageConverter(ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    Sleeper retrySleeper() {
        return Thread::sleep;
    }

    @Bean
    RetryTemplate listenerRetryTemplate(TelemetryProperties props, Sleeper sleeper) {
        return buildRetryTemplate(props.amqp(), sleeper);
    }

    public static RetryTemplate buildRetryTemplate(TelemetryProperties.Amqp cfg, Sleeper sleeper) {
        // Invalid and undeserialisable messages can never succeed: do not retry them. Everything else is retried.
        return DeadLetterRecoverer.retryTemplate(cfg.retryAttempts(), cfg.retryInitialMs(), cfg.retryMultiplier(),
                cfg.retryMaxMs(), sleeper, InvalidReadingException.class);
    }

    /** Republishes an exhausted message to thermogrid.dlx with its failure headers (confirmed), else rejects it. */
    @Bean
    DeadLetterRecoverer deadLetterRecoverer(RabbitOperations rabbit, TelemetryProperties props) {
        return new DeadLetterRecoverer(rabbit, Exchanges.DEAD_LETTER, props.amqp().retryAttempts(),
                CONFIRM_TIMEOUT_MS, InvalidReadingException.class);
    }

    /** Applies the retry advice to the auto-configured listener container factory. */
    @Bean
    ContainerCustomizer<SimpleMessageListenerContainer> retryContainerCustomizer(RetryTemplate listenerRetryTemplate,
                                                                              DeadLetterRecoverer recoverer) {
        return container -> container.setAdviceChain(retryInterceptor(listenerRetryTemplate, recoverer));
    }

    public static org.aopalliance.aop.Advice retryInterceptor(RetryTemplate template) {
        return retryInterceptor(template, new RejectAndDontRequeueRecoverer());
    }

    public static org.aopalliance.aop.Advice retryInterceptor(RetryTemplate template,
                                                              org.springframework.amqp.rabbit.retry.MessageRecoverer recoverer) {
        return RetryInterceptorBuilder.stateless().retryOperations(template).recoverer(recoverer).build();
    }
}
