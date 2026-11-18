package com.thermogrid.scheduler.client;

import com.thermogrid.scheduler.config.SchedulerProperties;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Resilience4j wrappers for the scheduler's outgoing calls (plan section 5.2).
 *
 * <ul>
 * <li>telemetry: circuit breaker, no retry (2 s read timeout is set on the Feign client);</li>
 * <li>workload GET: circuit breaker plus 2 retries;</li>
 * <li>workload PUT (assignment): circuit breaker only, never retried automatically.</li>
 * </ul>
 * HTTP 4xx answers are the service speaking, not a failure of the service, so they do not count against a breaker
 * and are not retried.
 */
@Component
public class Guarded {

    private final CircuitBreaker telemetry;
    private final CircuitBreaker workload;
    private final Retry workloadGetRetry;

    public Guarded(SchedulerProperties props) {
        this.telemetry = breaker("telemetry", props.resilience().telemetry());
        this.workload = breaker("workload", props.resilience().workload());
        this.workloadGetRetry = Retry.of("workload-get", RetryConfig.custom()
                .maxAttempts(props.resilience().workloadGetRetries() + 1)
                .waitDuration(Duration.ofMillis(props.resilience().retryWaitMs()))
                .retryOnException(Guarded::isServiceFailure)
                .build());
    }

    private static CircuitBreaker breaker(String name, SchedulerProperties.Breaker b) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(b.windowSize())
                .minimumNumberOfCalls(b.minCalls())
                .failureRateThreshold(b.failureRatePct())
                .waitDurationInOpenState(Duration.ofMillis(b.waitOpenMs()))
                .permittedNumberOfCallsInHalfOpenState(b.halfOpenCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordException(Guarded::isServiceFailure)
                .build());
    }

    static boolean isServiceFailure(Throwable e) {
        if (e instanceof CallNotPermittedException) {
            return false;
        }
        return !(e instanceof FeignException fe && fe.status() >= 400 && fe.status() < 500);
    }

    public <T> T telemetry(Supplier<T> call) {
        return CircuitBreaker.decorateSupplier(telemetry, call).get();
    }

    public <T> T workloadGet(Supplier<T> call) {
        return Retry.decorateSupplier(workloadGetRetry, CircuitBreaker.decorateSupplier(workload, call)).get();
    }

    /** No retry, ever: the assignment is idempotent by migrationId but a retry storm must not be possible. */
    public <T> T workloadCommand(Supplier<T> call) {
        return CircuitBreaker.decorateSupplier(workload, call).get();
    }

    public CircuitBreaker telemetryBreaker() {
        return telemetry;
    }

    public CircuitBreaker workloadBreaker() {
        return workload;
    }
}
