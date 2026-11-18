package com.thermogrid.cooling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.thermogrid.cooling.service.CoolingControlService;
import com.thermogrid.cooling.service.UnitSeeder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Live-broker verification for the cooling controller against a THROWAWAY RabbitMQ (skipped unless
 * THERMOGRID_LIVE_RABBIT_PORT is set): both queues are state queues, so poison is dropped, never dead-lettered.
 */
@SpringBootTest(properties = {"thermogrid.cooling.listeners-enabled=true"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "THERMOGRID_LIVE_RABBIT_PORT", matches = "\\d+")
class LiveBrokerTest {

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.port", () -> System.getenv("THERMOGRID_LIVE_RABBIT_PORT"));
        r.add("spring.rabbitmq.username", () -> "p7test");
        r.add("spring.rabbitmq.password", () -> "p7test");
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired CoolingControlService control;
    @Autowired UnitSeeder seeder;

    @BeforeEach
    void seed() {
        seeder.seed(null);
        control.load();
    }

    private void publish(String key, String body) {
        rabbit.send("thermogrid.events", key, MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setMessageId(UUID.randomUUID().toString()).build());
    }

    private int depth(String queue) {
        return rabbit.execute(ch -> ch.queueDeclarePassive(queue).getMessageCount());
    }

    private static String zoneReading(Instant at, int version) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"telemetry.zone.reading\",\"occurredAt\":\""
                + at + "\",\"producer\":\"live\",\"version\":" + version
                + ",\"data\":{\"zoneId\":\"Z2\",\"avgTempC\":29.5,\"maxTempC\":30.0,\"supplyTempC\":18.0,"
                + "\"fanActualPct\":40,\"unitHealth\":\"OK\"}}";
    }

    @Test
    void poisonOnTheStateQueuesIsDroppedAndNeverDeadLettered() {
        while (rabbit.receive("thermogrid.dlq", 200) != null) {
            // throwaway broker only
        }
        long rejected = control.rejectedReadings();
        publish("telemetry.zone.reading", "{not json");
        publish("telemetry.zone.reading", zoneReading(Instant.now(), 9));                 // unsupported version
        publish("thermal.alert.raised", "{\"eventType\":\"thermal.alert.raised\"}");      // incomplete envelope
        await().pollDelay(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(8)).until(() -> true);

        assertThat(rabbit.receive("thermogrid.dlq", 500)).as("nothing dead-lettered").isNull();
        assertThat(depth("q.cooling.zone-readings")).isZero();
        assertThat(depth("q.cooling.alerts")).isZero();
        assertThat(control.rejectedReadings()).isEqualTo(rejected);                         // none reached the control law
        System.out.println("LIVE cooling: 3 poison state messages dropped, thermogrid.dlq empty, queues drained");
    }

    @Test
    void aValidReadingIsConsumedAndDrivesTheControlLaw() {
        long accepted = control.acceptedReadings();
        publish("telemetry.zone.reading", zoneReading(Instant.now(), 1));
        await().atMost(Duration.ofSeconds(10)).until(() -> control.acceptedReadings() > accepted);
        assertThat(control.unitViews().stream().filter(u -> u.zoneId().equals("Z2")).findFirst().orElseThrow()
                .outputPct()).isEqualTo(50);
        System.out.println("LIVE cooling: valid zone reading consumed, Z2 output 40 -> 50");
    }
}
