package com.thermogrid.simulator.config;

import com.thermogrid.simulator.physics.HealthState;
import com.thermogrid.simulator.physics.PhysicsParams;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All tunable simulation values. Defaults live in application.yml, not in code. */
@Validated
@ConfigurationProperties("thermogrid.simulator")
public record SimulatorProperties(
        @Positive long tickMs,
        boolean schedulingEnabled,
        Long noiseSeed,
        @Valid @NotNull Grid grid,
        @Valid @NotNull Initial initial,
        @Valid @NotNull Physics physics,
        @Valid @NotNull Publishing publishing,
        @Valid @NotNull Failures failures,
        @Valid @NotNull Bootstrap bootstrap) {

    public record Grid(@Positive int zones, @Positive int rows, @Positive int cols) {
        public int expectedRacks() {
            return rows * cols;
        }
    }

    public record Initial(
            @NotNull Double tempC,
            @NotNull @DecimalMin("0") Double coolingOutputPct,
            @NotNull @DecimalMin("0") Double cpuUnits,
            @NotNull HealthState health) {
    }

    public record Physics(
            @NotNull Double supplyTempC,
            @NotNull Double alpha,
            @NotNull Double beta,
            @NotNull Double gamma,
            @NotNull @DecimalMin("0") Double noiseSigmaC,
            @NotNull @DecimalMin("0") Double minCoolingEffectiveness,
            @Valid @NotNull HealthFactors healthFactors) {
    }

    public record HealthFactors(@NotNull Double ok, @NotNull Double degraded, @NotNull Double fault) {
    }

    public record Publishing(boolean enabled, @NotNull String producer, @Min(1) int eventVersion,
                             @Positive long retryCooldownMs) {
    }

    public record Failures(@Positive int maxDurationSec, @Positive double maxHeatSpikeKw) {
    }

    public record Bootstrap(@Min(1) int maxAttempts, @Positive long initialBackoffMs, @Positive long maxBackoffMs) {
    }

    public PhysicsParams physicsParams() {
        return new PhysicsParams(physics.supplyTempC(), physics.alpha(), physics.beta(), physics.gamma(),
                physics.noiseSigmaC(), physics.minCoolingEffectiveness(),
                physics.healthFactors().ok(), physics.healthFactors().degraded(), physics.healthFactors().fault());
    }
}
