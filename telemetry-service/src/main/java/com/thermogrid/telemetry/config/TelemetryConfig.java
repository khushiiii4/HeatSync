package com.thermogrid.telemetry.config;

import com.thermogrid.telemetry.risk.RiskClassifier;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableConfigurationProperties(TelemetryProperties.class)
public class TelemetryConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RiskClassifier riskClassifier(TelemetryProperties props) {
        return props.newRiskClassifier();
    }

    /** Watchdog and retention timers. Switched off in tests so they only run when a test calls them. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "thermogrid.telemetry.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
