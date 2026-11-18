package com.thermogrid.cooling.service;

import com.thermogrid.contracts.CoolingUnitStateData;
import com.thermogrid.contracts.EventEnvelope;
import com.thermogrid.contracts.TelemetryZoneReadingData;
import com.thermogrid.contracts.ThermalAlertData;
import com.thermogrid.cooling.config.CoolingProperties;
import com.thermogrid.cooling.control.AlertLevel;
import com.thermogrid.cooling.control.CoolingParams;
import com.thermogrid.cooling.control.EnergyModel;
import com.thermogrid.cooling.control.FailSafeWatchdog;
import com.thermogrid.cooling.control.FaultDetector;
import com.thermogrid.cooling.control.FaultDetector.Transition;
import com.thermogrid.cooling.control.ProportionalController;
import com.thermogrid.cooling.control.ProportionalController.Step;
import com.thermogrid.cooling.control.ProportionalController.ZoneControl;
import com.thermogrid.cooling.control.ReadingGate;
import com.thermogrid.cooling.domain.CoolingAction;
import com.thermogrid.cooling.domain.CoolingUnit;
import com.thermogrid.cooling.domain.Repositories.CoolingActionRepository;
import com.thermogrid.cooling.domain.Repositories.CoolingUnitRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The control loop: ZoneReadingListener, AlertListener, FailSafeWatchdog and StatePublisher of plan section 5 wired
 * together. One lock guards all units, so readings, alerts, the watchdog tick and operator requests never interleave.
 *
 * <p>For every accepted zone reading: debounce the unit health, apply the proportional law (AUTO only), then, if the
 * output moved by 2 points or more, or the mode or health changed, <b>persist</b> the unit, record a
 * {@code cooling_action} and <b>publish</b> {@code cooling.unit.state}. A 1 s tick runs the fail-safe watchdog and a
 * heartbeat that re-publishes every unit at least every {@code heartbeat-ms}.
 *
 * <p>Persist first, publish second, and never publish a state the database does not hold (phase 7). If the write
 * fails, control continues in memory (cooling must not stop), nothing is published for that change, the unit is marked
 * persist-pending and the write is retried every tick. Once it succeeds the latest absolute state is published. A state
 * that was published is therefore always recoverable after a restart, which keeps {@code seq} monotonic for the
 * simulator (it ignores lower sequence numbers).
 */
@Service
public class CoolingControlService {

    private static final Logger log = LoggerFactory.getLogger(CoolingControlService.class);

    public static final String AUTO = "AUTO";
    public static final String MANUAL = "MANUAL";
    public static final String FAILSAFE = "FAILSAFE";

    /** Read model of one unit. */
    public record UnitView(String zoneId, String mode, int outputPct, BigDecimal targetTempC, String health,
                           int floorPct, Instant holdUntil, Instant lastReadingAt, Instant updatedAt, long seq,
                           double estimatedPowerKw) {
    }

    /** Modelled energy figures (formula only, never measured). */
    public record Summary(boolean modelled, int baselineOutputPct, double baselineKwPerZone, double totalPowerKw,
                          double baselineTotalKw, double savingKw, double savingPct, double modelledEnergySavedKwh,
                          double modelledEnergyUsedKwh, double modelledBaselineEnergyKwh, Instant since,
                          List<ZoneKw> zones, String note) {
    }

    public record ZoneKw(String zoneId, int outputPct, double powerKw) {
    }

    private static final class Runtime {
        CoolingUnit unit;
        ZoneControl control = new ZoneControl();
        final FaultDetector fault;
        final FailSafeWatchdog watchdog;
        final ReadingGate gate;
        String modeBeforeFailsafe = AUTO;
        int manualOutput;
        Instant lastPublishAt;
        /** True while the latest in-memory state could not be written to the database (and so was not published). */
        boolean persistPending;

        Runtime(CoolingUnit unit, CoolingParams p, Instant start) {
            this.unit = unit;
            this.fault = new FaultDetector(p);
            this.watchdog = new FailSafeWatchdog(p, start);
            this.gate = new ReadingGate(p);
            this.manualOutput = unit.getOutputPct();
        }
    }

    private final CoolingUnitRepository unitRepo;
    private final CoolingActionRepository actionRepo;
    private final StatePublisher publisher;
    private final CoolingProperties props;
    private final Clock clock;
    private final ProportionalController controller;
    private final AlertTracker alerts = new AlertTracker();
    private final EnergyModel.Meter meter;
    private final Instant startedAt;
    private final Map<String, Runtime> units = new LinkedHashMap<>();
    private long acceptedReadings;
    private long rejectedReadings;
    private long persistFailures;

    public CoolingControlService(CoolingUnitRepository unitRepo, CoolingActionRepository actionRepo,
                                 StatePublisher publisher, CoolingProperties props, Clock clock) {
        this.unitRepo = unitRepo;
        this.actionRepo = actionRepo;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
        this.controller = new ProportionalController(props.control());
        this.startedAt = clock.instant();
        this.meter = new EnergyModel.Meter(startedAt);
    }

    /** (Re)builds the in-memory units from the database. The watchdog starts with a full grace period. */
    public synchronized void load() {
        units.clear();
        alerts.clear();
        Instant now = clock.instant();
        unitRepo.findAll().stream().sorted((a, b) -> a.getZoneId().compareTo(b.getZoneId())).forEach(u -> {
            Runtime r = new Runtime(u, props.control(), now);
            r.fault.restore("FAULT".equals(u.getHealth()));
            r.watchdog.restoreActive(FAILSAFE.equals(u.getMode()));
            units.put(u.getZoneId(), r);
        });
        log.info("Cooling controller loaded {} units", units.size());
    }

    // ------------------------------------------------------------------ events

    public synchronized void onZoneReading(EventEnvelope<TelemetryZoneReadingData> event) {
        TelemetryZoneReadingData d = event.data();
        Runtime r = units.get(d.zoneId());
        if (r == null) {
            log.debug("Reading for unknown zone {} ignored", d.zoneId());
            return;
        }
        Instant now = clock.instant();
        if (!r.gate.accept(event.occurredAt(), now)) {
            rejectedReadings++;
            return;
        }
        if (Double.isNaN(d.maxTempC())) {
            rejectedReadings++;
            return;
        }
        acceptedReadings++;
        CoolingUnit u = r.unit;
        int oldOut = u.getOutputPct();
        String oldMode = u.getMode();
        String oldHealth = u.getHealth();

        boolean recovered = r.watchdog.onReading(now);
        u.setLastReadingAt(now);

        Transition t = r.fault.observe(d.unitHealth());
        u.setHealth(r.fault.isFaulty() ? "FAULT" : "OK");
        boolean anyFault = anyConfirmedFault();
        if (t != Transition.NONE) {
            applyFloorToAll(anyFault);
        }
        String reason = null;
        if (t == Transition.CONFIRMED_FAULT) {
            reason = "unit FAULT confirmed by " + props.control().faultConfirmReadings() + " readings";
        } else if (t == Transition.CONFIRMED_RECOVERY) {
            reason = "unit recovered after " + props.control().okConfirmReadings() + " OK readings";
        }

        if (r.watchdog.isActive()) {
            // Still in FAILSAFE: hold the fail-safe output until enough fresh readings have arrived.
            if (reason != null || !oldHealth.equals(u.getHealth())) {
                commit(r, oldOut, oldMode, oldHealth, reason, now);
            }
            return;
        }
        if (recovered) {
            u.setMode(r.modeBeforeFailsafe);
            r.control = new ZoneControl();
            if (MANUAL.equals(u.getMode())) {
                u.setOutputPct(r.manualOutput);
            }
            reason = "fail-safe cleared after " + props.control().recoveryReadings() + " fresh readings";
        }
        if (AUTO.equals(u.getMode())) {
            Step s = controller.step(r.control, d.maxTempC(), u.getTargetTempC().doubleValue(), u.getOutputPct(),
                    anyFault, alerts.zoneLevel(d.zoneId()), now);
            u.setHoldUntil(r.control.holdUntil());
            int delta = s.output() - u.getOutputPct();
            if (Math.abs(delta) >= props.control().minPublishDelta()) {
                u.setOutputPct(s.output());
                if (reason == null) {
                    reason = reasonFor(s, d.maxTempC());
                }
            }
        }
        commit(r, oldOut, oldMode, oldHealth, reason, now);
    }

    public synchronized void onAlert(EventEnvelope<ThermalAlertData> event) {
        ThermalAlertData d = event.data();
        boolean changed = alerts.apply(d.zoneId(), d.rackId(), d.level(), event.occurredAt());
        if (changed) {
            log.info("Alert {} on {} ({}): zone {} level now {}", d.level(), d.rackId(), event.eventType(),
                    d.zoneId(), alerts.zoneLevel(d.zoneId()));
        }
    }

    // ------------------------------------------------------------------ periodic

    /** Fail-safe watchdog, heartbeat and energy integration. Called every second. */
    public synchronized void tick() {
        Instant now = clock.instant();
        for (Runtime r : units.values()) {
            if (r.watchdog.check(now)) {
                enterFailsafe(r, now);
            }
        }
        for (Runtime r : units.values()) {
            if (r.persistPending || r.lastPublishAt == null
                    || !now.isBefore(r.lastPublishAt.plusMillis(props.heartbeatMs()))) {
                saveAndPublish(r, r.persistPending ? "persistence recovered" : "heartbeat", now);
            }
        }
        meter.advance(now, outputs());
    }

    private void enterFailsafe(Runtime r, Instant now) {
        CoolingUnit u = r.unit;
        int oldOut = u.getOutputPct();
        String oldMode = u.getMode();
        if (!FAILSAFE.equals(oldMode)) {
            r.modeBeforeFailsafe = oldMode;
        }
        u.setMode(FAILSAFE);
        u.setOutputPct(props.control().failsafeOutput());
        r.control = new ZoneControl();
        log.warn("FAILSAFE for {}: no zone reading for {} s, output {}", u.getZoneId(),
                props.control().failsafeAfterSec(), u.getOutputPct());
        commit(r, oldOut, oldMode, u.getHealth(),
                "fail-safe: no zone reading for " + props.control().failsafeAfterSec() + " s", now);
    }

    // ------------------------------------------------------------------ operator

    /**
     * Manual or automatic mode (optional endpoint). MANUAL holds a fixed output; stale data still triggers FAILSAFE,
     * and the requested mode is restored after the fail-safe recovery.
     */
    public synchronized UnitView setMode(String zoneId, String mode, Integer outputPct) {
        Runtime r = units.get(zoneId);
        if (r == null) {
            throw new UnknownZoneException(zoneId);
        }
        if (!AUTO.equals(mode) && !MANUAL.equals(mode)) {
            throw new IllegalArgumentException("mode must be AUTO or MANUAL");
        }
        if (MANUAL.equals(mode) && (outputPct == null || outputPct < 0 || outputPct > 100)) {
            throw new IllegalArgumentException("outputPct (0 to 100) is required for MANUAL");
        }
        Instant now = clock.instant();
        CoolingUnit u = r.unit;
        if (MANUAL.equals(mode)) {
            r.manualOutput = outputPct;
        }
        if (FAILSAFE.equals(u.getMode())) {
            r.modeBeforeFailsafe = mode;            // takes effect when the fail-safe ends
            return view(r);
        }
        int oldOut = u.getOutputPct();
        String oldMode = u.getMode();
        u.setMode(mode);
        if (MANUAL.equals(mode)) {
            u.setOutputPct(outputPct);
        } else {
            r.control = new ZoneControl();
        }
        commit(r, oldOut, oldMode, u.getHealth(), "operator set " + mode
                + (MANUAL.equals(mode) ? " output " + outputPct : ""), now);
        return view(r);
    }

    // ------------------------------------------------------------------ helpers

    private boolean anyConfirmedFault() {
        return units.values().stream().anyMatch(x -> x.fault.isFaulty());
    }

    private void applyFloorToAll(boolean anyFault) {
        int floor = anyFault ? props.control().floorFault() : props.control().floorNormal();
        for (Runtime x : units.values()) {
            x.unit.setFloorPct(floor);
        }
    }

    private String reasonFor(Step s, double maxTemp) {
        if (s.boost() > 0 && s.boost() >= s.raw() && s.boost() >= s.floor() && s.desired() == s.boost()) {
            return s.boostLevel() + " alert boost";
        }
        if (s.desired() == s.floor() && s.floor() >= s.raw()) {
            return "minimum output " + s.floor() + (s.floor() == props.control().floorFault()
                    ? " (a unit is FAULT)" : "");
        }
        return String.format("zone max %.1f C, control temperature %.1f C", maxTemp, s.hot());
    }

    /**
     * Saves and publishes if something worth reporting changed: the output moved (the caller already applied the
     * 2-point rule), the mode changed, or the health changed. Records the matching cooling_action.
     */
    private void commit(Runtime r, int oldOut, String oldMode, String oldHealth, String reason, Instant now) {
        CoolingUnit u = r.unit;
        boolean outputChanged = u.getOutputPct() != oldOut;
        boolean modeChanged = !u.getMode().equals(oldMode);
        boolean healthChanged = !u.getHealth().equals(oldHealth);
        if (!outputChanged && !modeChanged && !healthChanged) {
            return;
        }
        String why = reason != null ? reason : modeChanged ? "mode " + oldMode + " to " + u.getMode()
                : healthChanged ? "health " + oldHealth + " to " + u.getHealth() : "output change";
        try {
            actionRepo.save(new CoolingAction(u.getZoneId(), oldOut, u.getOutputPct(), u.getMode(), why, now));
        } catch (RuntimeException e) {
            log.warn("Could not record cooling_action for {}: {}", u.getZoneId(), e.toString());
        }
        saveAndPublish(r, why, now);
    }

    /**
     * Persist first, publish second. If the database write fails nothing is published and the unit stays
     * persist-pending, so the message never claims a state that was not stored.
     *
     * @return true if the state was persisted (published or deferred only by a broker problem)
     */
    private boolean saveAndPublish(Runtime r, String reason, Instant now) {
        CoolingUnit u = r.unit;
        u.nextSeq();
        u.setUpdatedAt(now);
        u.setHoldUntil(r.control.holdUntil());
        try {
            r.unit = unitRepo.saveAndFlush(u);
        } catch (RuntimeException e) {
            if (!r.persistPending) {
                log.warn("Could not persist cooling_unit {}: {}. Control continues in memory; nothing is published "
                        + "until the write succeeds", u.getZoneId(), e.toString());
            }
            r.persistPending = true;
            persistFailures++;
            return false;
        }
        if (r.persistPending) {
            log.info("Persistence of cooling_unit {} recovered", u.getZoneId());
            r.persistPending = false;
        }
        r.lastPublishAt = now;
        publisher.publish(new CoolingUnitStateData(u.getZoneId(), u.getMode(), u.getOutputPct(), u.getHealth(),
                u.getTargetTempC().doubleValue(), reason, u.getSeq()), now);
        return true;
    }

    /** True if any unit holds a state the database does not. */
    public synchronized boolean persistenceDegraded() {
        return units.values().stream().anyMatch(r -> r.persistPending);
    }

    public synchronized long persistFailures() {
        return persistFailures;
    }

    private Map<String, Integer> outputs() {
        Map<String, Integer> out = new LinkedHashMap<>();
        units.forEach((z, r) -> out.put(z, r.unit.getOutputPct()));
        return out;
    }

    private UnitView view(Runtime r) {
        CoolingUnit u = r.unit;
        return new UnitView(u.getZoneId(), u.getMode(), u.getOutputPct(), u.getTargetTempC(), u.getHealth(),
                u.getFloorPct(), u.getHoldUntil(), u.getLastReadingAt(), u.getUpdatedAt(), u.getSeq(),
                round2(EnergyModel.coolingKw(u.getOutputPct())));
    }

    public synchronized List<UnitView> unitViews() {
        List<UnitView> out = new ArrayList<>();
        units.values().forEach(r -> out.add(view(r)));
        return out;
    }

    public synchronized Summary summary() {
        meter.advance(clock.instant(), outputs());
        List<ZoneKw> zones = new ArrayList<>();
        double total = 0;
        for (Runtime r : units.values()) {
            double kw = EnergyModel.coolingKw(r.unit.getOutputPct());
            total += kw;
            zones.add(new ZoneKw(r.unit.getZoneId(), r.unit.getOutputPct(), round2(kw)));
        }
        double baseline = EnergyModel.baselineKwPerZone() * units.size();
        double saving = baseline - total;
        return new Summary(true, EnergyModel.BASELINE_OUTPUT_PCT, round2(EnergyModel.baselineKwPerZone()),
                round2(total), round2(baseline), round2(saving), baseline == 0 ? 0 : round2(100.0 * saving / baseline),
                round4(meter.savedKwh()), round4(meter.actualKwh()), round4(meter.baselineKwh()), startedAt, zones,
                "Modelled from coolingKw = 0.5 + 4.0 x (output/100)^3 against a static 70 percent policy; "
                        + "not a measurement.");
    }

    public synchronized long acceptedReadings() {
        return acceptedReadings;
    }

    public synchronized long rejectedReadings() {
        return rejectedReadings;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** Unknown zone id on an operator request (404). */
    public static class UnknownZoneException extends RuntimeException {
        public UnknownZoneException(String zoneId) {
            super("Unknown zone " + zoneId);
        }
    }
}
