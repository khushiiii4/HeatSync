package com.thermogrid.scheduler.config;

import com.thermogrid.contracts.Exchanges;
import com.thermogrid.scheduler.engine.MigrationPlanner;
import com.thermogrid.scheduler.engine.PlacementEngine;
import java.time.Clock;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(SchedulerProperties.class)
@EnableJpaRepositories(basePackages = "com.thermogrid.scheduler.domain", considerNestedRepositories = true)
@EnableFeignClients(basePackages = "com.thermogrid.scheduler.client")
public class SchedulerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(Exchanges.EVENTS, true, false);
    }

    @Bean
    PlacementEngine placementEngine(SchedulerProperties props) {
        return new PlacementEngine(props.tuning());
    }

    @Bean
    MigrationPlanner migrationPlanner(SchedulerProperties props, PlacementEngine engine) {
        return new MigrationPlanner(props.tuning(), engine);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "thermogrid.scheduler.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
