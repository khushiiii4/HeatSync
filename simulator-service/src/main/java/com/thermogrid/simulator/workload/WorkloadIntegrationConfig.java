package com.thermogrid.simulator.workload;

import com.thermogrid.contracts.Exchanges;
import com.thermogrid.contracts.Headers;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.simulator.events.SimulatorEventDispatcher;
import feign.RequestInterceptor;
import java.time.Clock;
import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import com.thermogrid.simulator.config.DeadLetterRecoverer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Workload wiring: the heat-source registry, the internal-token header and the RabbitMQ consumer. */
@Configuration
@EnableConfigurationProperties(WorkloadSyncProperties.class)
public class WorkloadIntegrationConfig {

    public static final String QUEUE = "q.simulator.workloads";
    public static final String COOLING_QUEUE = "q.simulator.cooling";
    public static final String DEAD_LETTER_QUEUE = "thermogrid.dlq";
    public static final String LOSSLESS_FACTORY = "simulatorLosslessListenerContainerFactory";
    public static final String STATE_FACTORY = "simulatorStateListenerContainerFactory";
    static final int RETRY_ATTEMPTS = 3;
    /** Bounded wait for the broker to confirm a dead-letter republish. */
    static final long CONFIRM_TIMEOUT_MS = 3000;
    private static final Logger log = LoggerFactory.getLogger(WorkloadIntegrationConfig.class);

    @Bean
    WorkloadLoads workloadLoads(Clock clock, WorkloadSyncProperties props) {
        return new WorkloadLoads(clock, props.tombstoneTtlMs());
    }

    @Bean
    CoolingInputs coolingInputs() {
        return new CoolingInputs();
    }

    /** Sends the shared internal token on every Feign call when one is configured. */
    @Bean
    RequestInterceptor internalTokenInterceptor(WorkloadSyncProperties props) {
        return template -> {
            String token = props.internalToken();
            if (token != null && !token.isBlank()) {
                template.header(Headers.INTERNAL_TOKEN, token);
            }
        };
    }

    /**
     * Lossless queue (plan 8.4): durable, no TTL, bounded, dead-lettered. A failing message is retried 3 times and
     * then republished with its failure headers to thermogrid.dlx; a message the simulator cannot parse is not
     * retried. The cooling queue is a state queue: 60 s TTL and no dead-letter arguments.
     */
    @Configuration
    @ConditionalOnProperty(name = "thermogrid.simulator.workloads.listener-enabled", havingValue = "true",
            matchIfMissing = true)
    static class ListenerConfig {

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
        Queue simulatorWorkloadQueue(WorkloadSyncProperties props) {
            return QueueBuilder.durable(QUEUE).withArguments(Map.of(
                    "x-max-length", props.queueMaxLength(),
                    "x-overflow", "drop-head",
                    "x-dead-letter-exchange", Exchanges.DEAD_LETTER)).build();
        }

        @Bean
        Binding assignedBinding(Queue simulatorWorkloadQueue, TopicExchange eventsExchange) {
            return BindingBuilder.bind(simulatorWorkloadQueue).to(eventsExchange).with(RoutingKeys.WORKLOAD_ASSIGNED);
        }

        @Bean
        Binding removedBinding(Queue simulatorWorkloadQueue, TopicExchange eventsExchange) {
            return BindingBuilder.bind(simulatorWorkloadQueue).to(eventsExchange).with(RoutingKeys.WORKLOAD_REMOVED);
        }

        /** State queue (plan 8.4): the latest cooling state is all that matters, so messages expire after 60 s. */
        @Bean
        Queue simulatorCoolingQueue(WorkloadSyncProperties props) {
            return QueueBuilder.durable(COOLING_QUEUE).withArguments(Map.of(
                    "x-message-ttl", 60000,
                    "x-max-length", props.queueMaxLength(),
                    "x-overflow", "drop-head")).build();
        }

        @Bean
        Binding coolingBinding(Queue simulatorCoolingQueue, TopicExchange eventsExchange) {
            return BindingBuilder.bind(simulatorCoolingQueue).to(eventsExchange).with(RoutingKeys.COOLING_UNIT_STATE);
        }

        @Bean
        DeadLetterRecoverer simulatorDeadLetterRecoverer(RabbitOperations rabbit) {
            return new DeadLetterRecoverer(rabbit, Exchanges.DEAD_LETTER, RETRY_ATTEMPTS, CONFIRM_TIMEOUT_MS);
        }

        /** Lossless consumer: 3 attempts (1, 2, 4 s), malformed messages not retried, then dead-lettered. */
        @Bean(name = LOSSLESS_FACTORY)
        SimpleRabbitListenerContainerFactory losslessListenerContainerFactory(
                SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
                DeadLetterRecoverer simulatorDeadLetterRecoverer) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            configurer.configure(factory, connectionFactory);
            factory.setDefaultRequeueRejected(false);
            factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                    .retryOperations(DeadLetterRecoverer.retryTemplate(RETRY_ATTEMPTS, 1000, 2.0, 4000, null))
                    .recoverer(simulatorDeadLetterRecoverer).build());
            return factory;
        }

        /** State consumer: no retry and no dead-lettering; the listener logs and drops a bad message. */
        @Bean(name = STATE_FACTORY)
        SimpleRabbitListenerContainerFactory stateListenerContainerFactory(
                SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            configurer.configure(factory, connectionFactory);
            factory.setDefaultRequeueRejected(false);
            return factory;
        }

        @Bean
        CoolingMessageListener coolingMessageListener(SimulatorEventDispatcher dispatcher) {
            return new CoolingMessageListener(dispatcher);
        }

        @Bean
        WorkloadMessageListener workloadMessageListener(SimulatorEventDispatcher dispatcher) {
            return new WorkloadMessageListener(dispatcher);
        }
    }

    /** Hands each cooling.unit.state message to the dispatcher. */
    public static class CoolingMessageListener {

        private final SimulatorEventDispatcher dispatcher;

        public CoolingMessageListener(SimulatorEventDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @RabbitListener(queues = COOLING_QUEUE, containerFactory = STATE_FACTORY)
        public void onMessage(Message message) {
            String key = message.getMessageProperties().getReceivedRoutingKey();
            try {
                dispatcher.dispatch(key, message.getBody());
            } catch (RuntimeException e) {
                // State queue (plan 8.4): log and drop. The 10 s heartbeat from the controller replaces it.
                log.warn("Dropping cooling state message {} ({}): {}", message.getMessageProperties().getMessageId(),
                        key, e.toString());
            }
        }
    }

    /** Hands each message to the dispatcher with its routing key. */
    public static class WorkloadMessageListener {

        private final SimulatorEventDispatcher dispatcher;

        public WorkloadMessageListener(SimulatorEventDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @RabbitListener(queues = QUEUE, containerFactory = LOSSLESS_FACTORY)
        public void onMessage(Message message) {
            dispatcher.dispatch(message.getMessageProperties().getReceivedRoutingKey(), message.getBody());
        }
    }
}
