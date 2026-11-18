package com.thermogrid.telemetry.listener;

import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.TelemetryZoneReadingData;
import com.thermogrid.telemetry.config.AmqpConfig;
import com.thermogrid.telemetry.service.IngestionService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Consumes {@code telemetry.zone.reading}. Same acknowledgement and failure rules as the rack listener. */
@Component
@ConditionalOnProperty(name = "thermogrid.telemetry.listeners-enabled", havingValue = "true", matchIfMissing = true)
public class ZoneReadingListener {

    private final IngestionService ingestion;

    public ZoneReadingListener(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @RabbitListener(queues = AmqpConfig.ZONE_QUEUE)
    public void onZoneReading(EventEnvelope<TelemetryZoneReadingData> event) {
        ingestion.ingestZone(event);
    }
}
