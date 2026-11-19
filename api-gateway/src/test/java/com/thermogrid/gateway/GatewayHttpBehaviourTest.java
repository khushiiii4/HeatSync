package com.thermogrid.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import reactor.core.publisher.Flux;

/** A test-only route points at a closed port to exercise the circuit-breaker fallback. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "thermogrid.security.enabled=false"
})
class GatewayHttpBehaviourTest {

    @TestConfiguration
    static class DeadRoute {
        @Bean
        RouteLocator slowRoute(RouteLocatorBuilder builder) {
            return builder.routes().route("slow-route", r -> r.path("/api/slow/**")
                    .filters(f -> f.circuitBreaker(c -> c.setName("SLOW-SERVICE")
                            .setFallbackUri("forward:/fallback/SLOW-SERVICE")))
                    .uri("http://localhost:" + SLOW.getAddress().getPort())).build();
        }

        @Bean
        RouteLocator deadRoute(RouteLocatorBuilder builder) {
            return builder.routes().route("dead-route", r -> r.path("/api/dead/**")
                    .filters(f -> f.circuitBreaker(c -> c.setName("DEAD-SERVICE")
                            .setFallbackUri("forward:/fallback/DEAD-SERVICE")))
                    .uri("http://localhost:1")).build();
        }
    }

    /** A downstream that answers after 300 ms, to hold many calls open at the same time. */
    static final HttpServer SLOW = startSlowServer();

    static HttpServer startSlowServer() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.setExecutor(Executors.newFixedThreadPool(100, r -> {
                Thread t = new Thread(r, "slow-stub");
                t.setDaemon(true);
                return t;
            }));
            s.createContext("/", ex -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = "ok".getBytes();
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            s.start();
            return s;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String ORIGIN = "http://localhost:5173";

    @Autowired
    WebTestClient client;
    @MockitoBean
    ReactiveDiscoveryClient discovery;

    @Test
    void preflightIsAnsweredWithCorsHeaders() {
        client.options().uri("/api/workloads")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN)
                .expectHeader().valueMatches(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, ".*POST.*")
                .expectHeader().valueMatches(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "(?i).*authorization.*");
    }

    @Test
    void preflightFromUnknownOriginIsRejected() {
        client.options().uri("/api/workloads")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void manyConcurrentCallsThroughACircuitBreakerRouteAreNotRejected() throws Exception {
        // Regression (Phase 12): the default Resilience4j bulkhead allowed only 25 concurrent calls per route; 60 held
        // open at once produced BulkheadFullException, an opened circuit and 503s for everyone.
        ExecutorService pool = Executors.newFixedThreadPool(60);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                Callable<Integer> call = () -> client.get().uri("/api/slow/x").exchange().expectBody().returnResult()
                        .getStatus().value();
                results.add(pool.submit(call));
            }
            for (Future<Integer> r : results) {
                assertThat(r.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unreachableServiceGivesJson503WithCorsHeaders() {
        client.method(HttpMethod.GET).uri("/api/dead/x")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN)
                .expectHeader().contentTypeCompatibleWith("application/json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.error").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.service").isEqualTo("DEAD-SERVICE")
                .jsonPath("$.message").isNotEmpty();
    }

    @Test
    void platformServicesReportsUpDownAndInstanceCountsWithoutLeakingDetails() {
        when(discovery.getServices()).thenReturn(Flux.just("telemetry-service", "api-gateway"));
        when(discovery.getInstances("TELEMETRY-SERVICE")).thenReturn(Flux.just(inst(), inst(), inst()));
        when(discovery.getInstances("API-GATEWAY")).thenReturn(Flux.just(inst()));
        when(discovery.getInstances("SCHEDULER-SERVICE")).thenReturn(Flux.empty());
        for (String s : PlatformStatusController.EXPECTED) {
            if (!s.equals("TELEMETRY-SERVICE") && !s.equals("SCHEDULER-SERVICE")) {
                when(discovery.getInstances(s)).thenReturn(Flux.empty());
            }
        }

        client.get().uri("/api/platform/services")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.name=='TELEMETRY-SERVICE')].instances").isEqualTo(3)
                .jsonPath("$[?(@.name=='TELEMETRY-SERVICE')].status").isEqualTo("UP")
                .jsonPath("$[?(@.name=='SCHEDULER-SERVICE')].instances").isEqualTo(0)
                .jsonPath("$[?(@.name=='SCHEDULER-SERVICE')].status").isEqualTo("DOWN")
                .jsonPath("$[?(@.name=='API-GATEWAY')].status").isEqualTo("UP");

        byte[] raw = client.get().uri("/api/platform/services").exchange().expectBody().returnResult().getResponseBody();
        String json = new String(raw);
        assertThat(json).doesNotContain("127.0.0.1", "host", "port", "metadata", "uri");
        assertThat(json).contains("\"name\"", "\"instances\"", "\"status\"");
    }

    @Test
    void platformServicesDegradesToJson503WhenRegistryFails() {
        when(discovery.getServices()).thenReturn(Flux.error(new IllegalStateException("registry down")));
        client.get().uri("/api/platform/services")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.service").isEqualTo("EUREKA-SERVER");
    }

    private static DefaultServiceInstance inst() {
        return new DefaultServiceInstance("id", "svc", "127.0.0.1", 1, false);
    }
}