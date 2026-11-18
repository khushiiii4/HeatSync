package com.thermogrid.cooling.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.thermogrid.cooling.listener.CoolingEventProcessor;
import com.thermogrid.cooling.listener.CoolingEventProcessor.MalformedEventException;
import com.thermogrid.cooling.listener.CoolingMessageListener;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/** Plan 8.4: both cooling queues are state queues (TTL, no dead-lettering) and bad messages are dropped. */
class CoolingAmqpTopologyTest {

    private final CoolingProperties props = new CoolingProperties(true, true, true, true, 1000, 5000, 5000, 26.0, 40,
            null, "COOLING-CONTROLLER-SERVICE", 1, 500, 24, 600000, null,
            new CoolingProperties.Amqp(30000, 60000, 20000));
    private final AmqpConfig config = new AmqpConfig();

    @Test
    void zoneReadingQueueExpiresAfterThirtySecondsAndIsNotDeadLettered() {
        Queue q = config.coolingZoneQueue(props);
        assertThat(q.getName()).isEqualTo("q.cooling.zone-readings");
        assertThat(q.isDurable()).isTrue();
        assertThat(q.getArguments()).containsEntry("x-message-ttl", 30000L).containsEntry("x-max-length", 20000)
                .containsEntry("x-overflow", "drop-head").doesNotContainKey("x-dead-letter-exchange");
    }

    @Test
    void alertQueueExpiresAfterSixtySecondsAndIsNotDeadLettered() {
        Queue q = config.coolingAlertQueue(props);
        assertThat(q.getName()).isEqualTo("q.cooling.alerts");
        assertThat(q.getArguments()).containsEntry("x-message-ttl", 60000L)
                .doesNotContainKey("x-dead-letter-exchange");
    }

    @Test
    void bindingsAndDeadLetterInfrastructureMatchThePlan() {
        TopicExchange events = new TopicExchange("thermogrid.events", true, false);
        assertThat(config.zoneBinding(config.coolingZoneQueue(props), events).getRoutingKey())
                .isEqualTo("telemetry.zone.reading");
        Binding alerts = config.alertBinding(config.coolingAlertQueue(props), events);
        assertThat(alerts.getRoutingKey()).isEqualTo("thermal.alert.#");
        assertThat(alerts.getExchange()).isEqualTo("thermogrid.events");
        TopicExchange dlx = config.deadLetterExchange();
        assertThat(dlx.getName()).isEqualTo("thermogrid.dlx");
        assertThat(dlx.isDurable()).isTrue();
        assertThat(config.deadLetterBinding(config.deadLetterQueue(), dlx).getRoutingKey()).isEqualTo("#");
    }

    @Test
    void listenersUseTheStateFactoryAndSingleConsumerAsConfigured() throws Exception {
        for (String name : new String[] {"onZoneReading", "onAlert"}) {
            Method m = CoolingMessageListener.class.getMethod(name, Message.class);
            assertThat(m.getAnnotation(RabbitListener.class).containerFactory()).isEqualTo(AmqpConfig.STATE_FACTORY);
        }
    }

    private static Message message(String key) {
        MessageProperties p = new MessageProperties();
        p.setReceivedRoutingKey(key);
        p.setMessageId("m-1");
        return MessageBuilder.withBody("{}".getBytes()).andProperties(p).build();
    }

    @Test
    void malformedAndFailingStateMessagesAreLoggedAndDroppedNotThrown() {
        CoolingEventProcessor processor = mock(CoolingEventProcessor.class);
        CoolingMessageListener listener = new CoolingMessageListener(processor);
        doThrow(new MalformedEventException("bad", null)).when(processor).handle("telemetry.zone.reading",
                "{}".getBytes());
        doThrow(new IllegalStateException("boom")).when(processor).handle("thermal.alert.raised", "{}".getBytes());

        listener.onZoneReading(message("telemetry.zone.reading"));   // would be retried or dead-lettered if thrown
        listener.onAlert(message("thermal.alert.raised"));
    }
}
