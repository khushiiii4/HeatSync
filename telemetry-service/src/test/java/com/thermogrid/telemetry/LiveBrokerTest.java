package com.thermogrid.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.thermogrid.telemetry.repo.RackReadingRepository;
import com.thermogrid.telemetry.service.IngestionService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Live-broker verification of the phase 7 messaging rules, against a REAL RabbitMQ and an in-memory database.
 * Skipped unless THERMOGRID_LIVE_RABBIT_PORT is set (it must point at a THROWAWAY broker: the test publishes poison
 * messages and reads thermogrid.dlq). Optional THERMOGRID_LIVE_RABBIT_CONTAINER lets it restart that container to
 * prove the consumers reconnect.
 */
@SpringBootTest(properties = {"thermogrid.telemetry.listeners-enabled=true",
        "spring.datasource.url=jdbc:h2:mem:telemetry_live;DB_CLOSE_DELAY=-1;MODE=MySQL"})
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "THERMOGRID_LIVE_RABBIT_PORT", matches = "\\d+")
class LiveBrokerTest {

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.port", () -> System.getenv("THERMOGRID_LIVE_RABBIT_PORT"));
        r.add("spring.rabbitmq.username", () -> "p7test");
        r.add("spring.rabbitmq.password", () -> "p7test");
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired RackReadingRepository rackReadings;
    @MockitoSpyBean IngestionService ingestion;

    private static String reading(String rack, Instant at, double temp, int version) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"telemetry.rack.reading\",\"occurredAt\":\""
                + at + "\",\"producer\":\"live-test\",\"version\":" + version + ",\"data\":{\"rackId\":\"" + rack
                + "\",\"zoneId\":\"Z1\",\"tempC\":" + temp + ",\"powerKw\":6.0,\"cpuLoadPct\":50.0}}";
    }

    private void publish(String body) {
        MessageProperties p = new MessageProperties();
        p.setContentType("application/json");
        p.setMessageId(UUID.randomUUID().toString());
        rabbit.send("thermogrid.events", "telemetry.rack.reading",
                MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8)).andProperties(p).build());
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
            // the throwaway broker only
        }
    }

    @Test
    @Order(1)
    void malformedJsonIsDeadLetteredImmediatelyWithoutRetries() {
        drainDlq();
        long start = System.nanoTime();
        publish("{this is not json");
        Message dead = nextDeadLetter(Duration.ofSeconds(8));
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(dead).as("message in thermogrid.dlq").isNotNull();
        assertThat(ms).as("no 1 s + 2 s retry backoff").isLessThan(2500);
        var h = dead.getMessageProperties().getHeaders();
        assertThat(h.get("x-retry-attempts")).isEqualTo(1);
        assertThat(h.get("x-original-routing-key")).isEqualTo("telemetry.rack.reading");
        assertThat(h.get("x-original-queue")).isEqualTo("q.telemetry.rack-readings");
        System.out.println("LIVE malformed JSON dead-lettered after " + ms + " ms, headers " + h.keySet());
    }

    @Test
    @Order(2)
    void aReadingWithAnImpossibleValueOrUnsupportedVersionIsDeadLetteredImmediately() {
        drainDlq();
        publish(reading("R11", Instant.now(), 9999.0, 1));
        publish(reading("R12", Instant.now(), 27.0, 7));
        Message a = nextDeadLetter(Duration.ofSeconds(8));
        Message b = nextDeadLetter(Duration.ofSeconds(8));
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a.getMessageProperties().getHeaders().get("x-retry-attempts")).isEqualTo(1);
        assertThat(rackReadings.count()).isZero();
    }

    @Test
    @Order(3)
    void aTransientFailureIsAttemptedThreeTimesThenDeadLetteredAndReplayedWithoutDuplicates() {
        drainDlq();
        AtomicInteger attempts = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            attempts.incrementAndGet();
            throw new DataAccessResourceFailureException("database is down");
        }).when(ingestion).ingestRack(Mockito.any());

        Instant at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String body = reading("R21", at, 28.5, 1);
        long start = System.nanoTime();
        publish(body);
        Message dead = nextDeadLetter(Duration.ofSeconds(15));
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(dead).isNotNull();
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(ms).as("1 s + 2 s backoff").isBetween(2800L, 8000L);
        var h = dead.getMessageProperties().getHeaders();
        assertThat(h.get("x-retry-attempts")).isEqualTo(3);
        assertThat(String.valueOf(h.get("x-exception-class"))).contains("DataAccessResourceFailureException");
        assertThat(rackReadings.count()).isZero();
        System.out.println("LIVE transient failure: 3 attempts, dead-lettered after " + ms + " ms, exception "
                + h.get("x-exception-class"));

        // Fix the cause, then replay through the exchange with the original routing key (what the UI procedure does).
        Mockito.reset(ingestion);
        String payload = new String(dead.getBody(), StandardCharsets.UTF_8);
        rabbit.send("thermogrid.events", String.valueOf(h.get("x-original-routing-key")),
                MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json").setMessageId(UUID.randomUUID().toString()).build());
        await().atMost(Duration.ofSeconds(10)).until(() -> rackReadings.count() == 1);
        // a second replay of the same message must not create a second row
        rabbit.send("thermogrid.events", "telemetry.rack.reading",
                MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json").setMessageId(UUID.randomUUID().toString()).build());
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6)).until(() -> true);
        assertThat(rackReadings.count()).isEqualTo(1);
    }

    @Test
    @Order(4)
    void duplicateReadingsCreateOneRow() {
        long before = rackReadings.count();
        Instant at = Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String body = reading("R31", at, 26.0, 1);
        publish(body);
        publish(body);
        publish(body);
        await().atMost(Duration.ofSeconds(10)).until(() -> rackReadings.count() > before);
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6)).until(() -> true);
        assertThat(rackReadings.count()).isEqualTo(before + 1);
    }

    @Test
    @Order(5)
    @EnabledIfEnvironmentVariable(named = "THERMOGRID_LIVE_RABBIT_CONTAINER", matches = ".+")
    void consumersReconnectAfterTheBrokerRestarts() throws Exception {
        String container = System.getenv("THERMOGRID_LIVE_RABBIT_CONTAINER");
        Process p = new ProcessBuilder("docker", "restart", container).inheritIO().start();
        assertThat(p.waitFor()).isZero();
        long before = rackReadings.count();
        Instant at = Instant.now().plusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        // Publishing may fail until the broker is back; keep trying, then expect the consumer to have reconnected.
        await().atMost(Duration.ofSeconds(90)).ignoreExceptions().until(() -> {
            publish(reading("R41", at, 25.0, 1));
            return rackReadings.count() > before;
        });
        System.out.println("LIVE reconnect: reading consumed after broker restart");
    }
}
