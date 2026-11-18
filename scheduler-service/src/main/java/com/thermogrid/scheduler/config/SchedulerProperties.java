package com.thermogrid.scheduler.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All tunables of scheduler-service (plan section 5: scores, caps, cooldowns, circuit-breaker settings). */
@Validated
@ConfigurationProperties("thermogrid.scheduler")
public record SchedulerProperties(
        boolean schedulingEnabled,
        boolean listenersEnabled,
        boolean singleInstanceLock,
        @Positive long reconcileMs,
        @Positive long pendingMs,
        @Positive long eventMaxAgeSec,
        @Positive long topologyCacheSec,
        @Min(1) int maxListLimit,
        @Positive long processedEventRetentionHours,
        @Positive long retentionIntervalMs,
        @Valid @NotNull Tuning tuning,
        @Valid @NotNull Resilience resilience,
        @Valid @NotNull Amqp amqp) {

    public record Resilience(@Valid @NotNull Breaker telemetry, @Valid @NotNull Breaker workload,
                             @Min(0) int workloadGetRetries, @Positive long retryWaitMs) {
    }

    /** Count-based circuit breaker settings. */
    public record Breaker(@Positive int windowSize, @Positive int minCalls, @Positive int failureRatePct,
                          @Positive long waitOpenMs, @Positive int halfOpenCalls) {
    }

    public record Amqp(@Positive long stateQueueTtlMs, @Positive int queueMaxLength) {
    }
}
