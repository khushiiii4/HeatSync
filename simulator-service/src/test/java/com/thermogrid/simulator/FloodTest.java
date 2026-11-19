package com.thermogrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thermogrid.simulator.api.ApiExceptionHandler;
import com.thermogrid.simulator.api.FloodController;
import com.thermogrid.simulator.config.SimulatorProperties;
import com.thermogrid.simulator.flood.FloodGenerator;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Phase 12 flood generator: pacing-independent facts (count, unique keys, prefix) and request validation. */
class FloodTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private SimulatorProperties props() {
        SimulatorProperties p = mock(SimulatorProperties.class, Mockito.RETURNS_DEEP_STUBS);
        when(p.publishing().producer()).thenReturn("simulator-service");
        when(p.publishing().eventVersion()).thenReturn(1);
        return p;
    }

    private static void awaitFinished(FloodGenerator gen) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (gen.status().running() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
    }

    @Test
    void publishesTheRequestedCountWithUniqueRackAndTimeKeysAndOnlySynPrefixedRacks() throws Exception {
        RabbitOperations rabbit = mock(RabbitOperations.class);
        FloodGenerator gen = new FloodGenerator(rabbit, mapper, props(), Clock.systemUTC());

        assertThat(gen.start(200, 1, 5)).isNotNull();
        awaitFinished(gen);

        assertThat(gen.status().running()).isFalse();
        assertThat(gen.status().sent()).isEqualTo(200);
        assertThat(gen.status().failed()).isZero();
        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        Mockito.verify(rabbit, Mockito.times(200)).send(eq("thermogrid.events"), eq("telemetry.rack.reading"),
                sent.capture());
        Set<String> keys = new HashSet<>();
        for (Message m : sent.getAllValues()) {
            JsonNode env = mapper.readTree(m.getBody());
            String rack = env.get("data").get("rackId").asText();
            assertThat(rack).startsWith("SYN");
            assertThat(rack.length()).isLessThanOrEqualTo(8);
            assertThat(keys.add(rack + "@" + env.get("occurredAt").asText())).isTrue();
        }
        assertThat(keys).hasSize(200);
    }

    @Test
    void countsFailedPublishesAndKeepsGoing() throws Exception {
        RabbitOperations rabbit = mock(RabbitOperations.class);
        Mockito.doThrow(new IllegalStateException("broker down")).when(rabbit)
                .send(any(String.class), any(String.class), any(Message.class));
        FloodGenerator gen = new FloodGenerator(rabbit, mapper, props(), Clock.systemUTC());
        gen.start(50, 1, 3);
        awaitFinished(gen);
        assertThat(gen.status().sent()).isZero();
        assertThat(gen.status().failed()).isEqualTo(50);
    }

    @Test
    void validatesTheRequestAndRejectsASecondFlood() throws Exception {
        FloodGenerator gen = mock(FloodGenerator.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FloodController(gen))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        for (String bad : List.of("{}", "{\"ratePerSec\":0,\"durationSec\":5}",
                "{\"ratePerSec\":5001,\"durationSec\":5}", "{\"ratePerSec\":10,\"durationSec\":0}",
                "{\"ratePerSec\":10,\"durationSec\":301}",
                "{\"ratePerSec\":10,\"durationSec\":5,\"racks\":201}")) {
            mvc.perform(post("/api/sim/flood").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        FloodGenerator.Status st = new FloodGenerator.Status(true, 10, 5, 50, 50, 0, 0, null, null);
        when(gen.start(10, 5, 50)).thenReturn(st);
        mvc.perform(post("/api/sim/flood").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ratePerSec\":10,\"durationSec\":5}")).andExpect(status().isAccepted());
        when(gen.start(10, 5, 50)).thenReturn(null);
        when(gen.status()).thenReturn(st);
        mvc.perform(post("/api/sim/flood").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ratePerSec\":10,\"durationSec\":5}")).andExpect(status().isConflict());
    }
}
