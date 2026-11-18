package com.thermogrid.workload.config;

import com.thermogrid.contracts.Exchanges;
import java.time.Clock;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(WorkloadProperties.class)
public class WorkloadConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Durable topic exchange for all domain events, declared on first broker connection. */
    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(Exchanges.EVENTS, true, false);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "thermogrid.workload.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
