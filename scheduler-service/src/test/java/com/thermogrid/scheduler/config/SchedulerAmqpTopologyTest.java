package com.thermogrid.scheduler.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.thermogrid.scheduler.listener.SchedulerMessageListener;
import com.thermogrid.scheduler.service.EventProcessor;
import com.thermogrid.scheduler.service.EventProcessor.MalformedEventException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/** Queue inventory and policies of plan 8.4 for the scheduler, and the state-queue drop rule. */
class SchedulerAmqpTopologyTest {

    private final SchedulerProperties.Amqp amqp = new SchedulerProperties.Amqp(60000, 50000);
    private final SchedulerProperties props = new SchedulerProperties(true, true, true, 5000, 3000, 30, 30, 500,
            24, 600000, null, null, amqp);
    private final AmqpConfig config = new AmqpConfig();

    @Test
    void workloadsQueueIsLosslessDurableBoundedAndDeadLettered() {
        Queue q = config.schedulerWorkloadsQueue(props);
        assertThat(q.getName()).isEqualTo("q.scheduler.workloads");
        assertThat(q.isDurable()).isTrue();
        assertThat(q.getArguments()).containsEntry("x-dead-letter-exchange", "thermogrid.dlx")
                .containsEntry("x-max-length", 50000).containsEntry("x-overflow", "drop-head")
                .doesNotContainKey("x-message-ttl");
    }

    @Test
    void alertAndCoolingQueuesAreStateQueuesWithSixtySecondTtlAndNoDeadLettering() {
        for (Queue q : new Queue[] {config.schedulerAlertsQueue(props), config.schedulerCoolingQueue(props)}) {
            assertThat(q.isDurable()).isTrue();
            assertThat(q.getArguments()).containsEntry("x-message-ttl", 60000L)
                    .doesNotContainKey("x-dead-letter-exchange").doesNotContainKey("x-dead-letter-routing-key");
        }
        assertThat(config.schedulerAlertsQueue(props).getName()).isEqualTo("q.scheduler.alerts");
        assertThat(config.schedulerCoolingQueue(props).getName()).isEqualTo("q.scheduler.cooling");
    }

    @Test
    void bindingsMatchThePlan() {
        TopicExchange events = new TopicExchange("thermogrid.events", true, false);
        Binding w = config.submittedBinding(config.schedulerWorkloadsQueue(props), events);
        Binding a = config.alertsBinding(config.schedulerAlertsQueue(props), events);
        Binding c = config.coolingBinding(config.schedulerCoolingQueue(props), events);
        assertThat(w.getRoutingKey()).isEqualTo("workload.submitted");
        assertThat(a.getRoutingKey()).isEqualTo("thermal.alert.#");
        assertThat(c.getRoutingKey()).isEqualTo("cooling.unit.state");
        assertThat(w.getExchange()).isEqualTo("thermogrid.events");
    }

    @Test
    void deadLetterExchangeAndQueueAreDurableAndBoundWithHash() {
        TopicExchange dlx = config.deadLetterExchange();
        Queue dlq = config.deadLetterQueue();
        Binding b = config.deadLetterBinding(dlq, dlx);
        assertThat(dlx.getName()).isEqualTo("thermogrid.dlx");
        assertThat(dlx.isDurable()).isTrue();
        assertThat(dlq.getName()).isEqualTo("thermogrid.dlq");
        assertThat(dlq.isDurable()).isTrue();
        assertThat(b.getRoutingKey()).isEqualTo("#");
    }

    @Test
    void eachListenerUsesTheFactoryOfItsQueueType() throws Exception {
        assertThat(factoryOf("onWorkload")).isEqualTo(AmqpConfig.LOSSLESS_FACTORY);
        assertThat(factoryOf("onAlert")).isEqualTo(AmqpConfig.STATE_FACTORY);
        assertThat(factoryOf("onCooling")).isEqualTo(AmqpConfig.STATE_FACTORY);
    }

    private static String factoryOf(String method) throws Exception {
        Method m = SchedulerMessageListener.class.getMethod(method, Message.class);
        return m.getAnnotation(RabbitListener.class).containerFactory();
    }

    private static Message message(String key) {
        MessageProperties p = new MessageProperties();
        p.setReceivedRoutingKey(key);
        p.setMessageId("m-1");
        return MessageBuilder.withBody("{}".getBytes()).andProperties(p).build();
    }

    @Test
    void aMalformedOrFailingStateMessageIsDroppedWithoutAnExceptionSoItIsNeverRetriedOrDeadLettered() {
        EventProcessor processor = mock(EventProcessor.class);
        SchedulerMessageListener listener = new SchedulerMessageListener(processor);
        doThrow(new MalformedEventException("bad", null)).when(processor).handle("thermal.alert.raised", "{}".getBytes());
        doThrow(new IllegalStateException("db down")).when(processor).handle("cooling.unit.state", "{}".getBytes());

        listener.onAlert(message("thermal.alert.raised"));      // must not throw
        listener.onCooling(message("cooling.unit.state"));       // must not throw
        verify(processor, never()).handle("workload.submitted", "{}".getBytes());
    }

    @Test
    void aFailingLosslessMessagePropagatesSoTheRetryAdviceAndTheDeadLetterQueueSeeIt() {
        EventProcessor processor = mock(EventProcessor.class);
        SchedulerMessageListener listener = new SchedulerMessageListener(processor);
        doThrow(new IllegalStateException("db down")).when(processor).handle(anyString(), org.mockito.ArgumentMatchers.any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> listener.onWorkload(message("workload.submitted")))
                .isInstanceOf(IllegalStateException.class);
    }
}
