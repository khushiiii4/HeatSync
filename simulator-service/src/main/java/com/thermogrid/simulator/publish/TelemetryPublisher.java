package com.thermogrid.simulator.publish;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.Exchanges;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.contracts.TelemetryRackReadingData;
import com.thermogrid.contracts.TelemetryZoneReadingData;
import com.thermogrid.simulator.config.SimulatorProperties;
import com.thermogrid.simulator.physics.PhysicsModel;
import com.thermogrid.simulator.physics.RackSpec;
import com.thermogrid.simulator.sim.SimState.RackState;
import com.thermogrid.simulator.sim.SimState.Snapshot;
import com.thermogrid.simulator.sim.SimState.ZoneState;
import com.thermogrid.simulator.sim.TickPublisher;
import com.thermogrid.simulator.topology.RackGrid;
import com.thermogrid.simulator.topology.ZoneSpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes one {@code telemetry.rack.reading} per active, non-dropped rack and one {@code telemetry.zone.reading}
 * per zone, every committed tick, on the durable topic exchange {@code thermogrid.events}.
 *
 * <p>Failure handling (plan section 5 "RabbitMQ unavailable"): publishing fails fast, the tick's remaining readings
 * are skipped and a single concise warning is logged. For {@code retry-cooldown-ms} the broker is not tried again, so
 * a dead broker costs at most one short connection attempt per cooldown. Nothing is buffered in application memory.
 */
@Component
@ConditionalOnProperty(name = "thermogrid.simulator.publishing.enabled", havingValue = "true", matchIfMissing = true)
public class TelemetryPublisher implements TickPublisher {

    private static final Logger log = LoggerFactory.getLogger(TelemetryPublisher.class);

    private final RabbitOperations rabbit;
    private final ObjectMapper mapper;
    private final RackGrid grid;
    private final PhysicsModel model;
    private final SimulatorProperties props;
    private final Clock clock;

    private Instant retryNotBefore = Instant.MIN;
    private boolean lastAttemptFailed;
    private long ticksPublished;
    private long ticksSkipped;

    public TelemetryPublisher(RabbitOperations rabbit, ObjectMapper mapper, RackGrid grid, PhysicsModel model,
                              SimulatorProperties props, Clock clock) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.grid = grid;
        this.model = model;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public synchronized void publish(Snapshot snapshot, Set<String> droppedRackIds) {
        Instant now = clock.instant();
        if (now.isBefore(retryNotBefore)) {
            ticksSkipped++;
            return;
        }
        try {
            for (Event e : build(snapshot, droppedRackIds)) {
                rabbit.send(Exchanges.EVENTS, e.routingKey(), e.message());
            }
            ticksPublished++;
            if (lastAttemptFailed) {
                log.info("RabbitMQ publishing resumed at tick {}", snapshot.tick());
                lastAttemptFailed = false;
            }
        } catch (RuntimeException e) {
            ticksSkipped++;
            lastAttemptFailed = true;
            retryNotBefore = now.plusMillis(props.publishing().retryCooldownMs());
            log.warn("RabbitMQ unavailable, readings for tick {} not published (next attempt in {} ms): {}",
                    snapshot.tick(), props.publishing().retryCooldownMs(), rootMessage(e));
        }
    }

    public synchronized long ticksPublished() {
        return ticksPublished;
    }

    public synchronized long ticksSkipped() {
        return ticksSkipped;
    }

    private record Event(String routingKey, Message message) {
    }

    private List<Event> build(Snapshot s, Set<String> dropped) {
        List<Event> events = new ArrayList<>();
        for (RackSpec rack : grid.racks()) {
            if (!rack.active() || dropped.contains(rack.id())) {
                continue;
            }
            RackState st = s.racks().get(rack.id());
            TelemetryRackReadingData data = new TelemetryRackReadingData(rack.id(), rack.zoneId(),
                    round2(st.tempC()), round2(st.powerKw()), round2(st.cpuUnits() / rack.cpuCapacity() * 100.0));
            events.add(event(RoutingKeys.TELEMETRY_RACK_READING, s.timestamp(), data));
        }
        for (ZoneSpec zone : grid.zones()) {
            events.add(event(RoutingKeys.TELEMETRY_ZONE_READING, s.timestamp(), zoneData(zone, s)));
        }
        return events;
    }

    TelemetryZoneReadingData zoneData(ZoneSpec zone, Snapshot s) {
        List<RackSpec> inZone = grid.racks().stream().filter(r -> r.zoneId().equals(zone.id())).toList();
        List<RackSpec> basis = inZone.stream().filter(RackSpec::active).toList();
        if (basis.isEmpty()) {
            basis = inZone;
        }
        double sum = 0.0;
        double max = Double.NEGATIVE_INFINITY;
        for (RackSpec r : basis) {
            double t = s.racks().get(r.id()).tempC();
            sum += t;
            max = Math.max(max, t);
        }
        ZoneState z = s.zones().get(zone.id());
        // Actual fan = commanded output scaled by the health factor in force (FAULT -> 0).
        int fanActual = (int) Math.round(z.outputPct() * model.healthFactor(z.health(), z.healthFactorOverride()));
        return new TelemetryZoneReadingData(zone.id(), round2(sum / basis.size()), round2(max),
                model.params().supplyTempC(), fanActual, z.health().name());
    }

    private Event event(String routingKey, Instant occurredAt, Object data) {
        UUID id = UUID.randomUUID();
        var envelope = new EventEnvelope<>(id, routingKey, occurredAt, props.publishing().producer(),
                props.publishing().eventVersion(), data);
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + routingKey, e);
        }
        Message message = MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(id.toString())
                .build();
        return new Event(routingKey, message);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) {
            r = r.getCause();
        }
        return r.getClass().getSimpleName() + ": " + r.getMessage();
    }
}
