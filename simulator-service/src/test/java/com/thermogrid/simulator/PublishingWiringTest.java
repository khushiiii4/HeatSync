package com.thermogrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.thermogrid.contracts.Exchanges;
import com.thermogrid.simulator.sim.SimulationEngine;
import com.thermogrid.simulator.topology.TopologyClient;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real Spring wiring with publishing ENABLED but RabbitTemplate mocked: no broker is contacted. */
@SpringBootTest(properties = {
        "thermogrid.simulator.publishing.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:sim_publishing_wiring;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@Import(PublishingWiringTest.StubTopology.class)
class PublishingWiringTest {

    @TestConfiguration
    static class StubTopology {
        @Bean
        @Primary
        TopologyClient stubTopologyClient() {
            return TestTopology::payload;
        }
    }

    @MockitoBean
    RabbitTemplate rabbitTemplate;

    @Autowired
    SimulationEngine engine;
    @Autowired
    TopicExchange eventsExchange;

    @Test
    void eventsExchangeIsDurableTopicNamedThermogridEvents() {
        assertThat(eventsExchange.getName()).isEqualTo(Exchanges.EVENTS).isEqualTo("thermogrid.events");
        assertThat(eventsExchange.getType()).isEqualTo("topic");
        assertThat(eventsExchange.isDurable()).isTrue();
        assertThat(eventsExchange.isAutoDelete()).isFalse();
    }

    @Test
    void aTickPublishesSixteenRackAndFourZoneReadingsThroughTheRealBeans() {
        engine.reset();
        assertThat(engine.tick()).isTrue();

        verify(rabbitTemplate, times(16)).send(eq(Exchanges.EVENTS), eq("telemetry.rack.reading"), any(Message.class));
        verify(rabbitTemplate, times(4)).send(eq(Exchanges.EVENTS), eq("telemetry.zone.reading"), any(Message.class));
        verify(rabbitTemplate, times(20)).send(anyString(), anyString(), any(Message.class));
    }
}
