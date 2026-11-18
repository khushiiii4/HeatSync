package com.thermogrid.cooling.config;

import com.thermogrid.contracts.Exchanges;
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
@EnableConfigurationProperties(CoolingProperties.class)
@EnableJpaRepositories(basePackages = "com.thermogrid.cooling.domain", considerNestedRepositories = true)
@EnableFeignClients(basePackages = "com.thermogrid.cooling.client")
public class CoolingConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(Exchanges.EVENTS, true, false);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "thermogrid.cooling.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
