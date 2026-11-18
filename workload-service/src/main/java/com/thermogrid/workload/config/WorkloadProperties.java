package com.thermogrid.workload.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("thermogrid.workload")
public record WorkloadProperties(
        boolean schedulingEnabled,
        String internalToken,
        @NotBlank String producer,
        @Min(1) int eventVersion,
        @Min(1) int maxBulk,
        @Min(1) int publishAttempts,
        @Min(0) long publishRetryMs,
        @Valid @NotNull Retention retention) {

    public record Retention(@Positive int removedDays, @Positive long intervalMs) {
    }
}
