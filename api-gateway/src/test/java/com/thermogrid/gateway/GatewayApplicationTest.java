package com.thermogrid.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false", "thermogrid.security.enabled=false"})
class GatewayApplicationTest {

    @Autowired
    ApplicationContext context;
    @Autowired
    RouteLocator routeLocator;
    @Autowired
    SecurityProperties security;
    @MockitoBean
    ReactiveDiscoveryClient discovery;

    @Test
    void contextLoadsAndTheTestSwitchesSecurityOff() {
        assertThat(context.containsBean("corsWebFilter")).isTrue();
        assertThat(security.enabled()).isFalse();
    }

    @Test
    void routeTableMatchesPlanSection54() {
        Map<String, String> uris = routeLocator.getRoutes().collectList().block().stream()
                .collect(Collectors.toMap(Route::getId, r -> r.getUri().toString()));
        assertThat(uris).containsOnly(
                Map.entry("auth-route", "lb://AUTH-SERVICE"),
                Map.entry("topology-route", "lb://TOPOLOGY-SERVICE"),
                Map.entry("sim-route", "lb://SIMULATOR-SERVICE"),
                Map.entry("telemetry-route", "lb://TELEMETRY-SERVICE"),
                Map.entry("alerts-route", "lb://TELEMETRY-SERVICE"),
                Map.entry("workloads-route", "lb://WORKLOAD-SERVICE"),
                Map.entry("scheduler-route", "lb://SCHEDULER-SERVICE"),
                Map.entry("cooling-route", "lb://COOLING-CONTROLLER-SERVICE"));
    }

    @Test
    void pathsMapToTheRightRouteAndInternalIsNotRouted() {
        assertThat(matchingRoute("/api/auth/login")).isEqualTo("auth-route");
        assertThat(matchingRoute("/api/telemetry/racks/latest")).isEqualTo("telemetry-route");
        assertThat(matchingRoute("/api/alerts")).isEqualTo("alerts-route");
        assertThat(matchingRoute("/api/cooling/units")).isEqualTo("cooling-route");
        assertThat(matchingRoute("/internal/topology")).isNull();
        assertThat(matchingRoute("/api/internal/topology")).isNull();
        assertThat(matchingRoute("/api/platform/services")).isNull(); // served by the gateway itself
    }

    private String matchingRoute(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, path));
        return routeLocator.getRoutes()
                .filter(r -> Boolean.TRUE.equals(Mono.from(r.getPredicate().apply(exchange)).block()))
                .map(Route::getId).next().block();
    }
}