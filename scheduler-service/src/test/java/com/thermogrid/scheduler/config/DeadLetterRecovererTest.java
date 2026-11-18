package com.thermogrid.scheduler.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.retry.support.RetryTemplate;

/** Retry schedule, immediate dead-lettering of permanent errors and the dead-letter republish (plan 8.6). */
class DeadLetterRecovererTest {

    private final RabbitOperations rabbit = mock(RabbitOperations.class);
    private final RabbitOperations ops = mock(RabbitOperations.class);
    private final DeadLetterRecoverer recoverer = new DeadLetterRecoverer(rabbit, "thermogrid.dlx", 3, 1000);

    @BeforeEach
    void wireInvoke() {
        doAnswer(inv -> ((RabbitOperations.OperationsCallback<?>) inv.getArgument(0)).doInRabbit(ops))
                .when(rabbit).invoke(any(RabbitOperations.OperationsCallback.class));
    }

    private static Message failing() {
        MessageProperties p = new MessageProperties();
        p.setMessageId("m-1");
        p.setContentType("application/json");
        p.setReceivedRoutingKey("workload.submitted");
        p.setReceivedExchange("thermogrid.events");
        p.setConsumerQueue("q.test");
        p.setHeader("custom", "kept");
        return MessageBuilder.withBody("{\"x\":1}".getBytes()).andProperties(p).build();
    }

    @Test
    void anExhaustedMessageIsRepublishedToTheDlxWithItsFailureHistoryAndConfirmed() {
        recoverer.recover(failing(), new IllegalStateException("database is down"));

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(ops).send(eq("thermogrid.dlx"), eq("workload.submitted"), sent.capture(), any(CorrelationData.class));
        verify(ops).waitForConfirmsOrDie(1000);
        var h = sent.getValue().getMessageProperties().getHeaders();
        assertThat(h).containsEntry("x-exception-class", "java.lang.IllegalStateException")
                .containsEntry("x-exception-message", "database is down")
                .containsEntry("x-retry-attempts", 3)
                .containsEntry("x-original-queue", "q.test")
                .containsEntry("x-original-exchange", "thermogrid.events")
                .containsEntry("x-original-routing-key", "workload.submitted")
                .containsEntry("custom", "kept")
                .containsKey("x-exception-stacktrace").containsKey("x-failed-at");
        assertThat(new String(sent.getValue().getBody())).isEqualTo("{\"x\":1}");
        assertThat(sent.getValue().getMessageProperties().getDeliveryMode().name()).isEqualTo("PERSISTENT");
        assertThat(recoverer.deadLettered()).isEqualTo(1);
    }

    @Test
    void aPermanentErrorIsRecordedAsASingleAttempt() {
        recoverer.recover(failing(), new MessageConversionException("not json"));
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(ops).send(eq("thermogrid.dlx"), eq("workload.submitted"), sent.capture(), any(CorrelationData.class));
        assertThat(sent.getValue().getMessageProperties().getHeaders()).containsEntry("x-retry-attempts", 1);
    }

    @Test
    void ifTheRepublishFailsTheMessageIsRejectedSoTheQueueDeadLettersItInsteadOfLosingIt() {
        doThrow(new AmqpConnectException(new java.io.IOException("broker down"))).when(ops)
                .send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
        assertThatThrownBy(() -> recoverer.recover(failing(), new IllegalStateException("x")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(recoverer.deadLettered()).isZero();
    }

    @Test
    void ifTheBrokerDoesNotConfirmTheRepublishTheMessageIsRejected() {
        doThrow(new org.springframework.amqp.AmqpException("nack")).when(ops).waitForConfirmsOrDie(1000);
        assertThatThrownBy(() -> recoverer.recover(failing(), new IllegalStateException("x")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    void aTransientFailureGetsThreeAttemptsWithOneAndTwoSecondWaits() {
        List<Long> waits = new ArrayList<>();
        RetryTemplate template = DeadLetterRecoverer.retryTemplate(3, 1000, 2.0, 4000, waits::add);
        int[] calls = {0};
        assertThatThrownBy(() -> template.execute(ctx -> {
            calls[0]++;
            throw new IllegalStateException("db down");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls[0]).isEqualTo(3);
        assertThat(waits).containsExactly(1000L, 2000L);
    }

    @Test
    void malformedAndUndeserialisableMessagesAreNeverRetried() {
        RetryTemplate template = DeadLetterRecoverer.retryTemplate(3, 1000, 2.0, 4000, d -> { });
        for (RuntimeException t : List.<RuntimeException>of(new MessageConversionException("bad json"),
                new IllegalArgumentException("bad payload"))) {
            int[] calls = {0};
            assertThatThrownBy(() -> template.execute(ctx -> {
                calls[0]++;
                throw t;
            })).isSameAs(t);
            assertThat(calls[0]).as(t.getClass().getSimpleName()).isEqualTo(1);
        }
    }
}
