package com.thermogrid.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.thermogrid.scheduler.service.CycleTrigger;
import com.thermogrid.scheduler.service.EventProcessor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Live-broker verification for the scheduler against a THROWAWAY RabbitMQ (skipped unless
 * THERMOGRID_LIVE_RABBIT_PORT is set): lossless poison handling, duplicate submissions, state-queue dropping.
 */
@SpringBootTest(properties = {"thermogrid.scheduler.listeners-enabled=true"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "THERMOGRID_LIVE_RABBIT_PORT", matches = "\\d+")
class LiveBrokerTest extends SchedulerTestBase {

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.port", () -> System.getenv("THERMOGRID_LIVE_RABBIT_PORT"));
        r.add("spring.rabbitmq.username", () -> "p7test");
        r.add("spring.rabbitmq.password", () -> "p7test");
    }

    @Autowired RabbitTemplate rabbit;
    @MockitoBean CycleTrigger trigger;
    @MockitoSpyBean EventProcessor processor;

    private void publish(String key, String body) {
        rabbit.send("thermogrid.events", key, MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setMessageId(UUID.randomUUID().toString()).build());
    }

    private String submitted(UUID id, int version) {
        return "{\"eventId\":\"" + id + "\",\"eventType\":\"workload.submitted\",\"occurredAt\":\""
                + clock.instant() + "\",\"producer\":\"live\",\"version\":" + version
                + ",\"data\":{\"workloadId\":77,\"name\":\"live-1\",\"cpuDemand\":20,\"priority\":\"LOW\"}}";
    }

    private Message nextDeadLetter(Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < end) {
            Message m = rabbit.receive("thermogrid.dlq", 250);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private void drainDlq() {
        while (rabbit.receive("thermogrid.dlq", 200) != null) {
            // throwaway broker only
        }
    }

    private int depth(String queue) {
        return rabbit.execute(ch -> ch.queueDeclarePassive(queue).getMessageCount());
    }

    @Test
    @Order(1)
    void duplicateWorkloadSubmissionsAreHarmless() {
        UUID id = UUID.randomUUID();
        String body = submitted(id, 1);
        publish("workload.submitted", body);
        publish("workload.submitted", body);
        publish("workload.submitted", body);
        await().atMost(Duration.ofSeconds(10)).until(() -> processed.existsById(id.toString()));
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6)).until(() -> true);
        assertThat(processed.count()).isEqualTo(1);
        verify(trigger, times(1)).requestPlacement();
        System.out.println("LIVE scheduler: 3 deliveries of one workload.submitted -> 1 processed_event, 1 placement trigger");
    }

    @Test
    @Order(2)
    void malformedAndUnsupportedLosslessMessagesAreDeadLetteredAtOnce() {
        drainDlq();
        long start = System.nanoTime();
        publish("workload.submitted", "{not json");
        Message a = nextDeadLetter(Duration.ofSeconds(8));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(a).isNotNull();
        assertThat(ms).isLessThan(2500);
        assertThat(a.getMessageProperties().getHeaders().get("x-retry-attempts")).isEqualTo(1);

        publish("workload.submitted", submitted(UUID.randomUUID(), 9));    // unsupported version
        Message b = nextDeadLetter(Duration.ofSeconds(8));
        assertThat(b).isNotNull();
        assertThat(b.getMessageProperties().getHeaders().get("x-retry-attempts")).isEqualTo(1);
        System.out.println("LIVE scheduler: malformed JSON dead-lettered in " + ms + " ms; unsupported version dead-lettered at once");
    }

    @Test
    @Order(3)
    void aTransientFailureOnTheLosslessQueueIsAttemptedThreeTimesThenDeadLettered() {
        drainDlq();
        AtomicInteger attempts = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            attempts.incrementAndGet();
            throw new DataAccessResourceFailureException("database is down");
        }).when(processor).handle(Mockito.eq("workload.submitted"), Mockito.any());

        long start = System.nanoTime();
        publish("workload.submitted", submitted(UUID.randomUUID(), 1));
        Message dead = nextDeadLetter(Duration.ofSeconds(15));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(dead).isNotNull();
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(ms).isBetween(2800L, 8000L);
        assertThat(dead.getMessageProperties().getHeaders().get("x-retry-attempts")).isEqualTo(3);
        System.out.println("LIVE scheduler: transient failure -> 3 attempts, dead-lettered after " + ms + " ms");
        Mockito.reset(processor);
    }

    @Test
    @Order(4)
    void poisonOnTheStateQueuesIsDroppedWithoutDeadLettering() {
        drainDlq();
        publish("thermal.alert.raised", "{not json");
        publish("cooling.unit.state", "{not json");
        publish("cooling.unit.state", "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"cooling.unit.state\","
                + "\"occurredAt\":\"" + clock.instant() + "\",\"producer\":\"live\",\"version\":9,\"data\":{}}");
        await().pollDelay(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(8)).until(() -> true);
        assertThat(rabbit.receive("thermogrid.dlq", 500)).as("nothing dead-lettered from a state queue").isNull();
        assertThat(depth("q.scheduler.alerts")).isZero();
        assertThat(depth("q.scheduler.cooling")).isZero();
        verify(processor, atLeastOnce()).handle(Mockito.eq("cooling.unit.state"), Mockito.any());
        System.out.println("LIVE scheduler: 3 poison state messages dropped, thermogrid.dlq empty, state queues drained");
    }
}
