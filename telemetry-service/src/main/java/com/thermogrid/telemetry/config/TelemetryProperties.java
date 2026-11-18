package com.thermogrid.telemetry.config;

import com.thermogrid.telemetry.forecast.ForecastEngine;
import com.thermogrid.telemetry.risk.RiskClassifier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All tunables of telemetry-service. Values live in application.yml. */
@Validated
@ConfigurationProperties("thermogrid.telemetry")
public record TelemetryProperties(
        boolean schedulingEnabled,
        boolean listenersEnabled,
        @Valid @NotNull Forecast forecast,
        @Valid @NotNull Risk risk,
        @Valid @NotNull Stale stale,
        @Valid @NotNull Retention retention,
        @Valid @NotNull History history,
        @Valid @NotNull Alerts alerts,
        @Valid @NotNull Amqp amqp,
        @NotBlank String producer,
        @Min(1) int eventVersion) {

    public record Forecast(@Positive double alpha, @Positive double beta, @Positive double phi,
                           @Min(1) int horizonTicks, @Positive int ticksPerMinute) {
    }

    public record Risk(double watchC, double warningC, double criticalC, @DecimalMin("0") double calmMarginC,
                       @Min(1) int calmReadings) {
    }

    public record Stale(@Positive long afterSec, @Positive long checkIntervalMs) {
    }

    public record Retention(@Positive int readingsHours, @Positive int alertsDays, @Positive int batchSize,
                            @Positive int maxBatchesPerRun, @Positive long intervalMs) {
    }

    public record History(@Positive int maxMinutes, @Min(0) int warmupReadings) {
    }

    public record Alerts(@Positive int maxLimit, @Min(1) int publishAttempts, @Min(0) long publishRetryMs) {
    }

    public record Amqp(@Min(1) int retryAttempts, @Positive long retryInitialMs, @Positive double retryMultiplier,
                       @Positive long retryMaxMs, @Positive int rackQueueMaxLength, @Positive int zoneQueueMaxLength) {
    }

    public ForecastEngine newForecastEngine() {
        return new ForecastEngine(forecast.alpha(), forecast.beta(), forecast.phi(), forecast.horizonTicks());
    }

    public RiskClassifier newRiskClassifier() {
        return new RiskClassifier(risk.watchC(), risk.warningC(), risk.criticalC(), risk.calmMarginC(),
                risk.calmReadings());
    }
}
