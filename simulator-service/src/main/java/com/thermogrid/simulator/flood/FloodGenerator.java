package com.thermogrid.simulator.flood;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.Exchanges;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.contracts.TelemetryRackReadingData;
import com.thermogrid.simulator.config.SimulatorProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.stereotype.Component;

/**
 * Load-test generator (plan 8.2 POST /api/sim/flood, section 12.3 P-02). Publishes synthetic
 * {@code telemetry.rack.reading} events for racks {@code SYN001}, {@code SYN002}, ... in zone {@code ZSYN} at a
 * requested rate, on the normal exchange, so telemetry-service ingests them like real readings. Every message has a
 * distinct millisecond timestamp (so the unique (rackId, recordedAt) key never rejects it) and a normal temperature
 * (so no alert is raised). The real simulation is not touched. One flood at a time. Clean up with
 * {@code DELETE /api/telemetry/synthetic}.
 */
@Component
public class FloodGenerator {

    private static final Logger log = LoggerFactory.getLogger(FloodGenerator.class);
    public static final String RACK_PREFIX = "SYN";
    public static final String ZONE_ID = "ZSYN";
    public static final int MAX_RATE = 5000;
    public static final int MAX_SECONDS = 300;
    public static final int MAX_RACKS = 200;

    public record Status(boolean running, int ratePerSec, int durationSec, int racks, long planned, long sent,
                         long failed, Instant startedAt, Instant finishedAt) {
    }

    private final RabbitOperations rabbit;
    private final ObjectMapper mapper;
    private final SimulatorProperties props;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "flood-generator");
        t.setDaemon(true);
        return t;
    });

    private volatile Status status = new Status(false, 0, 0, 0, 0, 0, 0, null, null);

    public FloodGenerator(RabbitOperations rabbit, ObjectMapper mapper, SimulatorProperties props, Clock clock) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    public Status status() {
        return status;
    }

    /** Starts a flood; returns null if one is already running. Arguments must be validated by the caller. */
    public synchronized Status start(int ratePerSec, int durationSec, int racks) {
        if (status.running()) {
            return null;
        }
        long planned = (long) ratePerSec * durationSec;
        Instant started = clock.instant();
        status = new Status(true, ratePerSec, durationSec, racks, planned, 0, 0, started, null);
        executor.execute(() -> run(ratePerSec, durationSec, racks, planned, started));
        return status;
    }

    private void run(int rate, int seconds, int racks, long planned, Instant started) {
        long sent = 0;
        long failed = 0;
        long t0 = System.nanoTime();
        // Timestamps end at "now" and count up by 1 ms per message, so they are unique and never in the future.
        Instant base = started.minusMillis(planned);
        try {
            while (sent + failed < planned) {
                long elapsedNanos = System.nanoTime() - t0;
                long due = Math.min(planned, (long) (elapsedNanos / 1_000_000_000.0 * rate) + 1);
                while (sent + failed < due) {
                    long n = sent + failed;
                    try {
                        rabbit.send(Exchanges.EVENTS, RoutingKeys.TELEMETRY_RACK_READING, message(n, racks, base));
                        sent++;
                    } catch (RuntimeException e) {
                        failed++;
                        if (failed == 1) {
                            log.warn("Flood: publish failed, counting and continuing: {}", e.toString());
                        }
                    }
                }
                status = new Status(true, rate, seconds, racks, planned, sent, failed, started, null);
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            status = new Status(false, rate, seconds, racks, planned, sent, failed, started, clock.instant());
            log.info("Flood finished: {} sent, {} failed of {} planned", sent, failed, planned);
        }
    }

    private Message message(long n, int racks, Instant base) {
        String rackId = String.format("%s%03d", RACK_PREFIX, (int) (n % racks) + 1);
        UUID id = UUID.randomUUID();
        var data = new TelemetryRackReadingData(rackId, ZONE_ID, 24.0 + (n % 7) * 0.1, 5.0, 40.0);
        var envelope = new EventEnvelope<>(id, RoutingKeys.TELEMETRY_RACK_READING, base.plusMillis(n),
                props.publishing().producer(), props.publishing().eventVersion(), data);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise flood message", e);
        }
        return MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(id.toString())
                .build();
    }
}
