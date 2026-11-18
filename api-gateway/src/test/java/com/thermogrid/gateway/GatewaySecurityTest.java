package com.thermogrid.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** Security enabled: every plan 9.6 / 8.1 case at the gateway, with an echo endpoint to observe forwarded headers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "thermogrid.security.enabled=true",
        "thermogrid.security.jwt-secret=gateway-test-secret-0123456789-0123456789"
})
class GatewaySecurityTest {

    static final String SECRET = "gateway-test-secret-0123456789-0123456789";
    static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    static final String ORIGIN = "http://localhost:5173";

    /** Reflects what a downstream service would see in the identity headers. */
    @RestController
    static class Echo {
        @RequestMapping("/api/echo")
        Map<String, String> echo(ServerHttpRequest req) {
            return Map.of("user", req.getHeaders().getFirst("X-User-Name") == null ? "" : req.getHeaders().getFirst("X-User-Name"),
                    "role", req.getHeaders().getFirst("X-User-Role") == null ? "" : req.getHeaders().getFirst("X-User-Role"));
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        Echo echo() {
            return new Echo();
        }
    }

    @Autowired WebTestClient client;
    @MockitoBean ReactiveDiscoveryClient discovery;

    static String token(String user, String role, Instant expiry) {
        return Jwts.builder().issuer("thermogrid-auth").subject(user).claim("role", role)
                .issuedAt(Date.from(Instant.now().minusSeconds(60))).expiration(Date.from(expiry))
                .signWith(KEY, Jwts.SIG.HS256).compact();
    }

    static String bearer(String role) {
        return "Bearer " + token(role.toLowerCase(), role, Instant.now().plusSeconds(3600));
    }

    private int status(HttpMethod m, String uri, String auth) {
        WebTestClient.RequestHeadersSpec<?> spec = client.method(m).uri(uri);
        if (auth != null) spec = spec.header(HttpHeaders.AUTHORIZATION, auth);
        return spec.exchange().returnResult(String.class).getStatus().value();
    }

    // ---- 401 cases -----------------------------------------------------------------------------------------

    @Test
    void requestWithoutTokenIs401() {
        client.get().uri("/api/topology").exchange().expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody().jsonPath("$.status").isEqualTo(401);
    }

    @Test
    void expiredTokenIs401() {
        String expired = token("viewer", "VIEWER", Instant.now().minusSeconds(10));
        assertThat(status(HttpMethod.GET, "/api/topology", "Bearer " + expired)).isEqualTo(401);
    }

    @Test
    void tamperedTokenIs401() {
        String[] p = token("viewer", "VIEWER", Instant.now().plusSeconds(3600)).split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"thermogrid-auth\",\"sub\":\"viewer\",\"role\":\"ADMIN\",\"exp\":"
                        + (Instant.now().getEpochSecond() + 3600) + "}").getBytes(StandardCharsets.UTF_8));
        assertThat(status(HttpMethod.GET, "/api/topology", "Bearer " + p[0] + "." + forgedPayload + "." + p[2])).isEqualTo(401);
    }

    @Test
    void tokenSignedWithAnotherKeyIs401() {
        SecretKey other = Keys.hmacShaKeyFor("some-other-secret-0123456789-0123456789".getBytes(StandardCharsets.UTF_8));
        String t = Jwts.builder().issuer("thermogrid-auth").subject("x").claim("role", "ADMIN")
                .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(other, Jwts.SIG.HS256).compact();
        assertThat(status(HttpMethod.GET, "/api/topology", "Bearer " + t)).isEqualTo(401);
    }

    @Test
    void unsignedAlgNoneTokenIs401() {
        String h = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        String b = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"thermogrid-auth\",\"sub\":\"a\",\"role\":\"ADMIN\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}").getBytes());
        assertThat(status(HttpMethod.GET, "/api/topology", "Bearer " + h + "." + b + ".")).isEqualTo(401);
    }

    @Test
    void malformedHeadersAre401() {
        assertThat(status(HttpMethod.GET, "/api/topology", "Bearer not-a-jwt")).isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/api/topology", "Bearer ")).isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/api/topology", "Basic dXNlcjpwdw==")).isEqualTo(401);
    }

    @Test
    void tokensMissingRequiredClaimsAre401() {
        String noRole = Jwts.builder().issuer("thermogrid-auth").subject("x")
                .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(KEY, Jwts.SIG.HS256).compact();
        String noExp = Jwts.builder().issuer("thermogrid-auth").subject("x").claim("role", "ADMIN")
                .signWith(KEY, Jwts.SIG.HS256).compact();
        String badRole = token("x", "SUPERUSER", Instant.now().plusSeconds(60));
        String wrongIssuer = Jwts.builder().issuer("someone-else").subject("x").claim("role", "ADMIN")
                .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(KEY, Jwts.SIG.HS256).compact();
        for (String t : new String[] {noRole, noExp, badRole, wrongIssuer}) {
            assertThat(status(HttpMethod.GET, "/api/topology", "Bearer " + t)).isEqualTo(401);
        }
    }

    @Test
    void loginRouteIsTheOnlyPublicOne() {
        // Reaches the route (no auth-service in this test, so the circuit breaker answers 503), never 401.
        assertThat(status(HttpMethod.POST, "/api/auth/login", null)).isNotEqualTo(401).isNotEqualTo(403);
        assertThat(status(HttpMethod.GET, "/api/auth/login", null)).isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/api/auth/me", null)).isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/api/platform/services", null)).isEqualTo(401);
        assertThat(status(HttpMethod.GET, "/actuator/health", null)).isEqualTo(401);
    }

    // ---- 403 and the role matrix ---------------------------------------------------------------------------

    @Test
    void viewerPostIs403() {
        assertThat(status(HttpMethod.POST, "/api/workloads", bearer("VIEWER"))).isEqualTo(403);
        assertThat(status(HttpMethod.DELETE, "/api/workloads/abc", bearer("VIEWER"))).isEqualTo(403);
        assertThat(status(HttpMethod.POST, "/api/scheduler/rebalance", bearer("VIEWER"))).isEqualTo(403);
        assertThat(status(HttpMethod.POST, "/api/cooling/units/Z2/mode", bearer("VIEWER"))).isEqualTo(403);
        assertThat(status(HttpMethod.POST, "/api/sim/failures", bearer("VIEWER"))).isEqualTo(403);
    }

    @Test
    void viewerCanReadEverywhere() {
        for (String p : new String[] {"/api/topology", "/api/telemetry/racks/latest", "/api/alerts", "/api/workloads",
                "/api/scheduler/decisions", "/api/cooling/units", "/api/sim/state", "/api/platform/services"}) {
            assertThat(status(HttpMethod.GET, p, bearer("VIEWER"))).as(p).isNotIn(401, 403);
        }
    }

    @Test
    void operatorCanDoOperationalWritesButNotSimControlOrSeeding() {
        assertThat(status(HttpMethod.POST, "/api/workloads", bearer("OPERATOR"))).isNotIn(401, 403);
        assertThat(status(HttpMethod.DELETE, "/api/workloads/abc", bearer("OPERATOR"))).isNotIn(401, 403);
        assertThat(status(HttpMethod.POST, "/api/scheduler/autopilot", bearer("OPERATOR"))).isNotIn(401, 403);
        assertThat(status(HttpMethod.POST, "/api/cooling/units/Z2/mode", bearer("OPERATOR"))).isNotIn(401, 403);
        assertThat(status(HttpMethod.POST, "/api/sim/failures", bearer("OPERATOR"))).isNotIn(401, 403);
        assertThat(status(HttpMethod.POST, "/api/sim/control", bearer("OPERATOR"))).isEqualTo(403);
        assertThat(status(HttpMethod.POST, "/api/workloads/seed-demo", bearer("OPERATOR"))).isEqualTo(403);
    }

    @Test
    void adminCanDoEverything() {
        assertThat(status(HttpMethod.POST, "/api/sim/control", bearer("ADMIN"))).isNotIn(401, 403);
        assertThat(status(HttpMethod.POST, "/api/workloads/seed-demo", bearer("ADMIN"))).isNotIn(401, 403);
        assertThat(status(HttpMethod.POST, "/api/workloads", bearer("ADMIN"))).isNotIn(401, 403);
        assertThat(status(HttpMethod.GET, "/api/topology", bearer("ADMIN"))).isNotIn(401, 403);
    }

    @Test
    void encodedAndDoubledPathsCannotSlipPastTheRoleRules() {
        assertThat(status(HttpMethod.POST, "/api/sim/%63ontrol", bearer("OPERATOR"))).isEqualTo(400);
        assertThat(status(HttpMethod.POST, "/api//sim/control", bearer("OPERATOR"))).isIn(400, 403);
        assertThat(status(HttpMethod.POST, "/api/sim/control;x=1", bearer("OPERATOR"))).isEqualTo(400);
        assertThat(status(HttpMethod.POST, "/api/sim/control/", bearer("OPERATOR"))).isEqualTo(403);
    }

    // ---- headers -------------------------------------------------------------------------------------------

    @Test
    void forgedIdentityHeadersAreStrippedAndReplacedFromTheToken() {
        client.get().uri("/api/echo")
                .header(HttpHeaders.AUTHORIZATION, bearer("VIEWER"))
                .header("X-User-Role", "ADMIN")
                .header("X-User-Name", "root")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.role").isEqualTo("VIEWER").jsonPath("$.user").isEqualTo("viewer");
    }

    @Test
    void forgedRoleHeaderDoesNotGrantAdminPowers() {
        client.post().uri("/api/sim/control").header(HttpHeaders.AUTHORIZATION, bearer("VIEWER"))
                .header("X-User-Role", "ADMIN").exchange().expectStatus().isForbidden();
    }

    @Test
    void forgedHeadersWithoutATokenStillGet401() {
        client.get().uri("/api/echo").header("X-User-Role", "ADMIN").header("X-User-Name", "root")
                .exchange().expectStatus().isUnauthorized();
    }

    // ---- /internal, CORS, preflight ------------------------------------------------------------------------

    @Test
    void internalPathsAre404ThroughTheGatewayWithOrWithoutCredentials() {
        assertThat(status(HttpMethod.GET, "/internal/topology", null)).isEqualTo(404);
        assertThat(status(HttpMethod.GET, "/internal/topology", bearer("ADMIN"))).isEqualTo(404);
        assertThat(status(HttpMethod.GET, "/internal/workloads", bearer("VIEWER"))).isEqualTo(404);
        client.get().uri("/internal/topology").header("X-Internal-Token", "anything").exchange().expectStatus().isNotFound();
    }

    @Test
    void rejectionsStillCarryCorsHeadersSoTheBrowserCanReadThem() {
        client.get().uri("/api/topology").header(HttpHeaders.ORIGIN, ORIGIN).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN);
        client.post().uri("/api/sim/control").header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, bearer("VIEWER")).exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN);
    }

    @Test
    void preflightNeedsNoToken() {
        client.options().uri("/api/workloads")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange().expectStatus().isOk();
    }

    @Test
    void gatewayRefusesToStartWithoutASecretWhenSecurityIsOn() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JwtAuthFilter(new SecurityProperties(true, ""), java.time.Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JwtAuthFilter(new SecurityProperties(true, "short"), java.time.Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
        // flux import keeps the mocked discovery client usable by the circuit-breaker routes
        assertThat(Flux.empty().collectList().block()).isEmpty();
    }
}
