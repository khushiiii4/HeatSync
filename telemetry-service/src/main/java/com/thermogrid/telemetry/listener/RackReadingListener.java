package com.thermogrid.telemetry.listener;

import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.TelemetryRackReadingData;
import com.thermogrid.telemetry.config.AmqpConfig;
import com.thermogrid.telemetry.service.IngestionService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code telemetry.rack.reading}. The message is acknowledged only when this method returns normally. Any
 * exception goes to the container's retry advice and, after the last attempt, to the dead-letter queue.
 */
@Component
@ConditionalOnProperty(name = "thermogrid.telemetry.listeners-enabled", havingValue = "true", matchIfMissing = true)
public class RackReadingListener {

    private final IngestionService ingestion;

    public RackReadingListener(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @RabbitListener(queues = AmqpConfig.RACK_QUEUE)
    public void onRackReading(EventEnvelope<TelemetryRackReadingData> event) {
        ingestion.ingestRack(event);
    }
}
