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
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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
     * Lossless queue (plan 8.4): durable, bounded, dead-lettered. A message the simulator cannot parse is rejected
     * without requeue (spring.rabbitmq.listener.simple.default-requeue-rejected=false) and lands in thermogrid.dlq.
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
                    "x-overflow", "drop-head",
                    "x-dead-letter-exchange", Exchanges.DEAD_LETTER)).build();
        }

        @Bean
        Binding coolingBinding(Queue simulatorCoolingQueue, TopicExchange eventsExchange) {
            return BindingBuilder.bind(simulatorCoolingQueue).to(eventsExchange).with(RoutingKeys.COOLING_UNIT_STATE);
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

        @RabbitListener(queues = COOLING_QUEUE)
        public void onMessage(Message message) {
            dispatcher.dispatch(message.getMessageProperties().getReceivedRoutingKey(), message.getBody());
        }
    }

    /** Hands each message to the dispatcher with its routing key. */
    public static class WorkloadMessageListener {

        private final SimulatorEventDispatcher dispatcher;

        public WorkloadMessageListener(SimulatorEventDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @RabbitListener(queues = QUEUE)
        public void onMessage(Message message) {
            dispatcher.dispatch(message.getMessageProperties().getReceivedRoutingKey(), message.getBody());
        }
    }
}
