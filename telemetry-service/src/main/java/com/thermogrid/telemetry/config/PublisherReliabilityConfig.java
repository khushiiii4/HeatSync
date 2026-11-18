package com.thermogrid.telemetry.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Replaces Boot's default RabbitTemplate with one that tracks publisher confirms and returns (phase 7). */
@Configuration
public class PublisherReliabilityConfig {

    @Bean
    ConfirmingRabbitTemplate rabbitTemplate(RabbitTemplateConfigurer configurer, ConnectionFactory connectionFactory) {
        ConfirmingRabbitTemplate template = new ConfirmingRabbitTemplate();
        configurer.configure(template, connectionFactory);
        template.setMandatory(true);
        return template;
    }
}
