package com.thermogrid.telemetry;

import static org.mockito.Mockito.reset;

import com.thermogrid.telemetry.TestSupport.MutableClock;
import com.thermogrid.telemetry.repo.AlertRepository;
import com.thermogrid.telemetry.repo.RackForecastStateRepository;
import com.thermogrid.telemetry.repo.RackReadingRepository;
import com.thermogrid.telemetry.repo.ZoneReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Full application context on in-memory H2 with RabbitTemplate mocked: no MySQL, no broker, no Eureka, no timers.
 * All tests share one context; each test starts from empty tables and a clock at T0.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(IntegrationTestBase.TestBeans.class)
abstract class IntegrationTestBase {

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(TestSupport.T0);
        }
    }

    @MockitoBean
    protected RabbitTemplate rabbit;

    @Autowired
    protected MutableClock clock;
    @Autowired
    protected RackReadingRepository rackReadings;
    @Autowired
    protected ZoneReadingRepository zoneReadings;
    @Autowired
    protected RackForecastStateRepository states;
    @Autowired
    protected AlertRepository alerts;

    @BeforeEach
    void cleanDatabaseAndClock() {
        alerts.deleteAll();
        states.deleteAll();
        rackReadings.deleteAll();
        zoneReadings.deleteAll();
        clock.set(TestSupport.T0);
        reset(rabbit);
    }
}
