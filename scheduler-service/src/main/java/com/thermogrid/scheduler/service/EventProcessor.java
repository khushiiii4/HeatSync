package com.thermogrid.scheduler.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.contracts.CoolingUnitStateData;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.RoutingKeys;
import com.thermogrid.contracts.ThermalAlertData;
import com.thermogrid.contracts.WorkloadSubmittedData;
import com.thermogrid.scheduler.config.SchedulerProperties;
import com.thermogrid.scheduler.domain.ProcessedEvent;
import com.thermogrid.scheduler.domain.Repositories.ProcessedEventRepository;
import com.thermogrid.scheduler.domain.Repositories.ZoneCoolingStateRepository;
import com.thermogrid.scheduler.domain.ZoneCoolingState;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Turns RabbitMQ messages into scheduler actions (plan sections 5 and 8.6).
 *
 * <ul>
 * <li>{@code workload.submitted}: idempotent through {@code processed_event}; a fresh event triggers placement at once,
 * an old one is left to the 3 s pending loop;</li>
 * <li>{@code thermal.alert.*}: messages carry absolute state, so no idempotency table is needed. A fresh WARNING or
 * CRITICAL marks the rack as an alert rack and triggers a migration cycle, which re-reads current state;</li>
 * <li>{@code cooling.unit.state}: the zone's health is stored (older than the stored state is ignored); a fresh FAULT
 * triggers a migration cycle that drains the zone.</li>
 * </ul>
 * Events older than 30 s are not trusted for immediate action. The reconcile loop recomputes from service APIs, so a
 * dropped or ignored event costs at most one cycle.
 */
@Component
public class EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    /** The message body cannot be parsed or has the wrong shape; retrying cannot help, so it is dead-lettered at once. */
    public static class MalformedEventException extends RuntimeException {
        public MalformedEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final ObjectMapper mapper;
    private final ProcessedEventRepository processed;
    private final ZoneCoolingStateRepository cooling;
    private final SchedulerState state;
    private final CycleTrigger trigger;
    private final SchedulerProperties props;
    private final Clock clock;

    public EventProcessor(ObjectMapper mapper, ProcessedEventRepository processed, ZoneCoolingStateRepository cooling,
                          SchedulerState state, CycleTrigger trigger, SchedulerProperties props, Clock clock) {
        this.mapper = mapper;
        this.processed = processed;
        this.cooling = cooling;
        this.state = state;
        this.trigger = trigger;
        this.props = props;
        this.clock = clock;
    }

    public void handle(String routingKey, byte[] body) {
        try {
            switch (routingKey) {
                case RoutingKeys.WORKLOAD_SUBMITTED -> onSubmitted(
                        read(body, routingKey, new TypeReference<EventEnvelope<WorkloadSubmittedData>>() { }));
                case RoutingKeys.THERMAL_ALERT_RAISED, RoutingKeys.THERMAL_ALERT_LOWERED,
                        RoutingKeys.THERMAL_ALERT_CLEARED -> onAlert(
                        read(body, routingKey, new TypeReference<EventEnvelope<ThermalAlertData>>() { }));
                case RoutingKeys.COOLING_UNIT_STATE -> onCooling(
                        read(body, routingKey, new TypeReference<EventEnvelope<CoolingUnitStateData>>() { }));
                default -> throw new MalformedEventException("Scheduler does not consume " + routingKey, null);
            }
        } catch (IOException e) {
            throw new MalformedEventException("Malformed " + routingKey + " message", e);
        }
    }

    private <T> EventEnvelope<T> read(byte[] body, String routingKey, TypeReference<EventEnvelope<T>> type)
            throws IOException {
        EventEnvelope<T> env = mapper.readValue(body, type);
        if (env == null || env.eventId() == null || env.data() == null || env.occurredAt() == null) {
            throw new MalformedEventException("Incomplete " + routingKey + " envelope", null);
        }
        if (!routingKey.equals(env.eventType())) {
            throw new MalformedEventException("Routing key " + routingKey + " does not match eventType "
                    + env.eventType(), null);
        }
        return env;
    }

    boolean fresh(Instant occurredAt) {
        return Duration.between(occurredAt, clock.instant()).compareTo(Duration.ofSeconds(props.eventMaxAgeSec())) <= 0;
    }

    void onSubmitted(EventEnvelope<WorkloadSubmittedData> e) {
        if (!markProcessed(e.eventId().toString())) {
            log.debug("Duplicate workload.submitted {} ignored", e.eventId());
            return;
        }
        if (fresh(e.occurredAt())) {
            trigger.requestPlacement();
        } else {
            log.info("workload.submitted {} is older than {} s: left to the pending loop", e.eventId(),
                    props.eventMaxAgeSec());
        }
    }

    void onAlert(EventEnvelope<ThermalAlertData> e) {
        ThermalAlertData d = e.data();
        if (!fresh(e.occurredAt())) {
            log.info("{} for {} is older than {} s: not acted on, reconcile will read current state", e.eventType(),
                    d.rackId(), props.eventMaxAgeSec());
            return;
        }
        boolean hot = "WARNING".equals(d.level()) || "CRITICAL".equals(d.level());
        if (hot && RoutingKeys.THERMAL_ALERT_RAISED.equals(e.eventType())) {
            state.alertRaised(d.rackId(), e.occurredAt());
        }
        if (hot) {
            trigger.requestReconcile();
        }
        // lowered and cleared need no action: the next reconcile reads the new risk level from telemetry.
    }

    void onCooling(EventEnvelope<CoolingUnitStateData> e) {
        CoolingUnitStateData d = e.data();
        if (!fresh(e.occurredAt())) {
            log.info("cooling.unit.state for {} is older than {} s: ignored", d.zoneId(), props.eventMaxAgeSec());
            return;
        }
        if (d.zoneId() == null || d.health() == null) {
            throw new MalformedEventException("cooling.unit.state without zoneId or health", null);
        }
        ZoneCoolingState row = cooling.findById(d.zoneId()).orElse(null);
        if (row != null && e.occurredAt().isBefore(row.getUpdatedAt())) {
            return;   // out of order: a newer state is already stored
        }
        boolean becameFault = "FAULT".equals(d.health()) && (row == null || !"FAULT".equals(row.getHealth()));
        if (row == null) {
            row = new ZoneCoolingState(d.zoneId(), d.health(), d.mode() == null ? "AUTO" : d.mode(), e.occurredAt());
        } else {
            row.update(d.health(), d.mode() == null ? row.getMode() : d.mode(), e.occurredAt());
        }
        cooling.save(row);
        if ("FAULT".equals(d.health())) {
            if (becameFault) {
                log.warn("Cooling FAULT in zone {}: zone excluded from placement, draining", d.zoneId());
            }
            trigger.requestReconcile();
        }
    }

    /** @return true if the id was new */
    boolean markProcessed(String eventId) {
        if (processed.existsById(eventId)) {
            return false;
        }
        try {
            processed.saveAndFlush(new ProcessedEvent(eventId, clock.instant()));
            return true;
        } catch (DataIntegrityViolationException dup) {
            return false;
        }
    }
}
