package com.thermogrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.thermogrid.simulator.topology.TopologyClient;
import com.thermogrid.simulator.workload.CoolingInputs;
import com.thermogrid.simulator.workload.WorkloadLoads;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Live-broker verification for the simulator against a THROWAWAY RabbitMQ (skipped unless
 * THERMOGRID_LIVE_RABBIT_PORT is set): lossless poison handling, workload set semantics, sequence ordering of cooling
 * state, and dropping of bad state messages.
 */
@SpringBootTest(properties = {
        "thermogrid.simulator.workloads.listener-enabled=true",
        "spring.datasource.url=jdbc:h2:mem:sim_live;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@Import(LiveBrokerTest.StubTopology.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "THERMOGRID_LIVE_RABBIT_PORT", matches = "\\d+")
class LiveBrokerTest {

    @TestConfiguration
    static class StubTopology {
        @Bean
        @Primary
        TopologyClient stubTopologyClient() {
            return TestTopology::payload;
        }
    }

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.port", () -> System.getenv("THERMOGRID_LIVE_RABBIT_PORT"));
        r.add("spring.rabbitmq.username", () -> "p7test");
        r.add("spring.rabbitmq.password", () -> "p7test");
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired WorkloadLoads loads;
    @Autowired CoolingInputs cooling;

    private void publish(String key, String body) {
        rabbit.send("thermogrid.events", key, MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").setMessageId(UUID.randomUUID().toString()).build());
    }

    private static String envelope(String type, int version, String data) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\",\"occurredAt\":\""
                + Instant.now() + "\",\"producer\":\"live\",\"version\":" + version + ",\"data\":" + data + "}";
    }

    private static String assigned(long id, String rack, int version) {
        return envelope("workload.assigned", 1, "{\"workloadId\":" + id + ",\"cpuDemand\":30,\"priority\":\"LOW\","
                + "\"toRackId\":\"" + rack + "\",\"reason\":\"SEED\",\"version\":" + version + "}");
    }

    private static String coolingState(String zone, int output, long seq) {
        return envelope("cooling.unit.state", 1, "{\"zoneId\":\"" + zone + "\",\"mode\":\"AUTO\",\"outputPct\":" + output
                + ",\"health\":\"OK\",\"targetTempC\":26.0,\"reason\":\"live\",\"seq\":" + seq + "}");
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

    @Test
    @Order(1)
    void duplicateAssignmentsAreSetSemantics() {
        publish("workload.assigned", assigned(501, "R22", 1));
        publish("workload.assigned", assigned(501, "R22", 1));
        publish("workload.assigned", assigned(501, "R22", 1));
        await().atMost(Duration.ofSeconds(10)).until(() -> loads.workloadCount() >= 1);
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6)).until(() -> true);
        assertThat(loads.workloadCount()).isEqualTo(1);
        System.out.println("LIVE simulator: 3 deliveries of one workload.assigned -> 1 workload");
    }

    @Test
    @Order(2)
    void aLowerSequenceCoolingStateIsIgnored() {
        publish("cooling.unit.state", coolingState("Z3", 80, 10));
        await().atMost(Duration.ofSeconds(10)).until(() -> cooling.outputPct("Z3").isPresent());
        publish("cooling.unit.state", coolingState("Z3", 20, 9));     // older: must not apply
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6)).until(() -> true);
        assertThat(cooling.outputPct("Z3").getAsDouble()).isEqualTo(80.0);
        publish("cooling.unit.state", coolingState("Z3", 90, 11));
        await().atMost(Duration.ofSeconds(10)).until(() -> cooling.outputPct("Z3").getAsDouble() == 90.0);
        System.out.println("LIVE simulator: cooling seq 10 applied (80), seq 9 ignored, seq 11 applied (90)");
    }

    @Test
    @Order(3)
    void malformedLosslessMessageIsDeadLetteredAtOnce() {
        drainDlq();
        long start = System.nanoTime();
        publish("workload.assigned", "{not json");
        Message dead = nextDeadLetter(Duration.ofSeconds(8));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(dead).isNotNull();
        assertThat(ms).isLessThan(2500);
        assertThat(dead.getMessageProperties().getHeaders().get("x-retry-attempts")).isEqualTo(1);
        publish("workload.assigned", envelope("workload.assigned", 1,
                "{\"workloadId\":0,\"cpuDemand\":30,\"priority\":\"LOW\",\"toRackId\":\"R22\",\"version\":1}"));
        assertThat(nextDeadLetter(Duration.ofSeconds(8))).as("invalid workload id").isNotNull();
        System.out.println("LIVE simulator: malformed JSON and invalid payload dead-lettered at once (" + ms + " ms)");
    }

    @Test
    @Order(4)
    void poisonOnTheCoolingStateQueueIsDroppedWithoutDeadLettering() {
        drainDlq();
        publish("cooling.unit.state", "{not json");
        publish("cooling.unit.state", coolingState("Z1", 400, 1));      // impossible output
        await().pollDelay(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(8)).until(() -> true);
        assertThat(rabbit.receive("thermogrid.dlq", 500)).isNull();
        assertThat((int) rabbit.execute(ch -> ch.queueDeclarePassive("q.simulator.cooling").getMessageCount())).isZero();
        assertThat(cooling.outputPct("Z1")).isEmpty();
        System.out.println("LIVE simulator: poison cooling state dropped, thermogrid.dlq empty");
    }
}
