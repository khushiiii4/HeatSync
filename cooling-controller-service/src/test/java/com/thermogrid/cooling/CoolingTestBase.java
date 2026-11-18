package com.thermogrid.cooling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.TelemetryZoneReadingData;
import com.thermogrid.contracts.ThermalAlertData;
import com.thermogrid.cooling.domain.Repositories.CoolingActionRepository;
import com.thermogrid.cooling.domain.Repositories.CoolingUnitRepository;
import com.thermogrid.cooling.service.CoolingControlService;
import com.thermogrid.cooling.service.UnitSeeder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Full context on in-memory H2, a mocked broker connection and a movable clock. Four zones, target 26 C. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CoolingTestBase.ClockConfig.class)
abstract class CoolingTestBase {

    static final Instant START = Instant.parse("2026-11-18T07:10:00Z");

    static class MutableClock extends Clock {
        private volatile Instant now = START;

        void advance(Duration d) { now = now.plus(d); }
        void set(Instant t) { now = t; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @MockitoBean protected RabbitTemplate rabbit;

    @Autowired protected MutableClock clock;
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected CoolingControlService control;
    @Autowired protected UnitSeeder seeder;
    @Autowired protected com.thermogrid.cooling.service.StatePublisher publisher;
    @Autowired protected CoolingUnitRepository units;
    @Autowired protected CoolingActionRepository actions;

    protected static final String[] ZONES = {"Z1", "Z2", "Z3", "Z4"};

    /** A published cooling.unit.state. */
    protected record Sent(String routingKey, JsonNode envelope) {
        JsonNode data() { return envelope.get("data"); }
        String zone() { return data().get("zoneId").asText(); }
        int output() { return data().get("outputPct").asInt(); }
        String health() { return data().get("health").asText(); }
        String mode() { return data().get("mode").asText(); }
        long seq() { return data().get("seq").asLong(); }
        String reason() { return data().get("reason").asText(); }
    }

    @BeforeEach
    void resetCooling() {
        Mockito.reset(rabbit);
        publisher.reset();
        actions.deleteAll();
        units.deleteAll();
        clock.set(START);
        seeder.seed(null);                 // four default zones, target 26.0 C, output 40
        control.load();
    }

    /** Messages sent to the broker so far, in order. */
    protected List<Sent> sent() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Message> msg = ArgumentCaptor.forClass(Message.class);
        Mockito.verify(rabbit, Mockito.atLeast(0)).send(Mockito.eq("thermogrid.events"), key.capture(), msg.capture());
        List<Sent> out = new ArrayList<>();
        for (int i = 0; i < key.getAllValues().size(); i++) {
            try {
                out.add(new Sent(key.getAllValues().get(i), mapper.readTree(msg.getAllValues().get(i).getBody())));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    protected List<Sent> sentFor(String zone) {
        return sent().stream().filter(s -> s.zone().equals(zone)).toList();
    }

    /** A zone reading that occurred "now" (the clock's current instant). */
    protected void reading(String zone, double maxTemp, String health) {
        readingAt(zone, maxTemp, health, clock.instant());
    }

    protected void readingAt(String zone, double maxTemp, String health, Instant occurredAt) {
        control.onZoneReading(new EventEnvelope<>(UUID.randomUUID(), "telemetry.zone.reading", occurredAt, "T", 1,
                new TelemetryZoneReadingData(zone, maxTemp - 0.5, maxTemp, 18.0, 40, health)));
    }

    /** One reading for every zone, then 2 s pass (one telemetry tick). */
    protected void tickAll(double maxTemp, String health) {
        for (String z : ZONES) {
            reading(z, maxTemp, health);
        }
        clock.advance(Duration.ofSeconds(2));
    }

    protected void alert(String type, String zone, String rack, String level) {
        control.onAlert(new EventEnvelope<>(UUID.randomUUID(), type, clock.instant(), "T", 1,
                new ThermalAlertData(1, rack, zone, level, "NORMAL", "OBSERVED", 30, 33)));
    }

    protected CoolingControlService.UnitView unit(String zone) {
        return control.unitViews().stream().filter(u -> u.zoneId().equals(zone)).findFirst().orElseThrow();
    }
}
