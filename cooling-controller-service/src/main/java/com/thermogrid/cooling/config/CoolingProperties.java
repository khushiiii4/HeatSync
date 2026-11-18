package com.thermogrid.cooling.config;

import com.thermogrid.cooling.control.CoolingParams;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All tunables of the cooling controller (plan sections 6E and 7). */
@Validated
@ConfigurationProperties("thermogrid.cooling")
public record CoolingProperties(
        boolean schedulingEnabled,
        boolean listenersEnabled,
        boolean singleInstanceLock,
        boolean seedOnStartup,
        @Positive long tickMs,
        @Positive long heartbeatMs,
        @Positive long publishRetryCooldownMs,
        @Positive double defaultTargetC,
        @Positive int initialOutputPct,
        @NotNull List<String> defaultZones,
        @NotBlank String producer,
        @Positive int eventVersion,
        @Positive int maxListLimit,
        @Positive long actionRetentionHours,
        @Positive long retentionIntervalMs,
        @Valid @NotNull CoolingParams control,
        @Valid @NotNull Amqp amqp) {

    public record Amqp(@Positive long zoneQueueTtlMs, @Positive long alertQueueTtlMs, @Positive int queueMaxLength) {
    }
}
