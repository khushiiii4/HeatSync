package com.thermogrid.telemetry.service;

import com.thermogrid.contracts.ThermalAlertData;
import com.thermogrid.telemetry.domain.Alert;
import com.thermogrid.telemetry.domain.AlertBasis;
import com.thermogrid.telemetry.domain.AlertState;
import com.thermogrid.telemetry.repo.AlertRepository;
import com.thermogrid.telemetry.risk.RiskClassifier;
import com.thermogrid.telemetry.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists alert transitions. It must be called inside the transaction that holds the rack's forecast-state row
 * lock: that lock is what guarantees at most one ACTIVE alert per rack, so no unique index is needed (plan section 7).
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertRepository alerts;
    private final RiskClassifier classifier;

    public AlertService(AlertRepository alerts, RiskClassifier classifier) {
        this.alerts = alerts;
        this.classifier = classifier;
    }

    /**
     * @param previous level before this reading
     * @param next     level after hysteresis
     * @return the event to publish, or empty when nothing changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AlertTransition> onRiskChange(String rackId, String zoneId, RiskLevel previous, RiskLevel next,
                                                  double rawTempC, double rawForecastC, BigDecimal tempC,
                                                  BigDecimal forecastC, Instant readingAt, Instant now) {
        if (previous == next) {
            return Optional.empty();
        }
        AlertBasis basis = basis(next, rawTempC);
        List<Alert> active = alerts.findByRackIdAndState(rackId, AlertState.ACTIVE);
        if (active.size() > 1) {
            log.error("Rack {} has {} ACTIVE alerts, expected at most 1; updating the oldest", rackId, active.size());
        }
        Alert alert = active.isEmpty() ? null : active.get(0);

        if (next.compareTo(previous) > 0) {
            if (alert == null) {
                alert = alerts.saveAndFlush(new Alert(rackId, zoneId, next, basis, tempC, forecastC, now));
            } else {
                alert.update(next, basis, tempC, forecastC, now);
                alert = alerts.saveAndFlush(alert);
            }
            return Optional.of(transition(AlertTransition.Kind.RAISED, alert, previous, next, basis, rawTempC,
                    rawForecastC, readingAt));
        }
        if (next == RiskLevel.NORMAL) {
            if (alert == null) {
                log.warn("Rack {} returned to NORMAL but has no ACTIVE alert; no cleared event published", rackId);
                return Optional.empty();
            }
            alert.clear(tempC, forecastC, now);
            alert = alerts.saveAndFlush(alert);
            return Optional.of(transition(AlertTransition.Kind.CLEARED, alert, previous, next, AlertBasis.OBSERVED,
                    rawTempC, rawForecastC, readingAt));
        }
        if (alert == null) {
            alert = alerts.saveAndFlush(new Alert(rackId, zoneId, next, basis, tempC, forecastC, now));
        } else {
            alert.update(next, basis, tempC, forecastC, now);
            alert = alerts.saveAndFlush(alert);
        }
        return Optional.of(transition(AlertTransition.Kind.LOWERED, alert, previous, next, basis, rawTempC,
                rawForecastC, readingAt));
    }

    /** PREDICTED when the observed temperature alone would not justify the level, otherwise OBSERVED. */
    AlertBasis basis(RiskLevel level, double rawTempC) {
        return classifier.classify(rawTempC).compareTo(level) < 0 ? AlertBasis.PREDICTED : AlertBasis.OBSERVED;
    }

    private static AlertTransition transition(AlertTransition.Kind kind, Alert alert, RiskLevel previous,
                                              RiskLevel next, AlertBasis basis, double tempC, double forecastC,
                                              Instant readingAt) {
        ThermalAlertData data = new ThermalAlertData(alert.getId(), alert.getRackId(), alert.getZoneId(), next.name(),
                previous.name(), basis.name(), round2(tempC), round2(forecastC));
        return new AlertTransition(kind, data, readingAt);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
