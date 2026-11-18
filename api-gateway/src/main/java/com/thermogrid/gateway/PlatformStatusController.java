package com.thermogrid.gateway;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** GET /api/platform/services: registered services, instance counts and status (plan section 8.3). */
@RestController
public class PlatformStatusController {

    /** Business services that must be reported DOWN when they have no registered instance. */
    static final List<String> EXPECTED = List.of(
            "AUTH-SERVICE", "TOPOLOGY-SERVICE", "SIMULATOR-SERVICE", "TELEMETRY-SERVICE",
            "WORKLOAD-SERVICE", "SCHEDULER-SERVICE", "COOLING-CONTROLLER-SERVICE");

    /** Only these three fields are exposed: no hosts, ports, metadata or credentials. */
    public record ServiceStatus(String name, int instances, String status) {
    }

    private final ReactiveDiscoveryClient discovery;

    public PlatformStatusController(ReactiveDiscoveryClient discovery) {
        this.discovery = discovery;
    }

    @GetMapping("/api/platform/services")
    public Mono<ResponseEntity<Object>> services() {
        return discovery.getServices()
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collectList()
                .flatMapMany(registered -> {
                    Set<String> names = new LinkedHashSet<>(EXPECTED);
                    names.addAll(registered);
                    return Flux.fromIterable(names);
                })
                .concatMap(name -> discovery.getInstances(name).count()
                        .map(n -> new ServiceStatus(name, n.intValue(), n > 0 ? "UP" : "DOWN")))
                .sort(Comparator.comparing(ServiceStatus::name))
                .collectList()
                .<ResponseEntity<Object>>map(list -> ResponseEntity.ok(list))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(FallbackController.ServiceUnavailable.of("EUREKA-SERVER",
                                "Service registry is unreachable; platform status unavailable."))));
    }
}