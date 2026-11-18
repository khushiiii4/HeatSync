package com.thermogrid.telemetry.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;

/** Publisher confirms and returns are tracked per message id (plan 8.6, phase 7). */
class ConfirmingRabbitTemplateTest {

    private static Message message(String id) {
        MessageProperties p = new MessageProperties();
        p.setMessageId(id);
        return MessageBuilder.withBody(new byte[0]).andProperties(p).build();
    }

    @Test
    void everySendCarriesCorrelationDataEqualToTheMessageId() {
        ConfirmingRabbitTemplate template = spy(new ConfirmingRabbitTemplate());
        doNothing().when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        template.send("thermogrid.events", "a.b", message("id-7"));

        ArgumentCaptor<CorrelationData> cd = ArgumentCaptor.forClass(CorrelationData.class);
        verify(template).send(org.mockito.ArgumentMatchers.eq("thermogrid.events"),
                org.mockito.ArgumentMatchers.eq("a.b"), any(Message.class), cd.capture());
        assertThat(cd.getValue().getId()).isEqualTo("id-7");
    }

    @Test
    void acksNacksAndReturnsAreCounted() {
        ConfirmingRabbitTemplate template = new ConfirmingRabbitTemplate();
        template.onConfirm(new CorrelationData("1"), true, null);
        template.onConfirm(new CorrelationData("2"), true, null);
        template.onConfirm(new CorrelationData("3"), false, "queue overflow");
        template.onReturn(new ReturnedMessage(message("4"), 312, "NO_ROUTE", "thermogrid.events", "x.y"));

        assertThat(template.confirmedCount()).isEqualTo(2);
        assertThat(template.nackedCount()).isEqualTo(1);
        assertThat(template.returnedCount()).isEqualTo(1);
    }
}
