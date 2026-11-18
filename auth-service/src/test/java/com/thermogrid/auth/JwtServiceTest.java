package com.thermogrid.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thermogrid.auth.config.AuthProperties;
import com.thermogrid.auth.domain.Role;
import com.thermogrid.auth.service.JwtService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789-0123456789-xyz";
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static JwtService service(String secret, Instant now) {
        return new JwtService(new AuthProperties(secret, 28800, List.of()), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void roundTripCarriesUsernameAndRole() {
        JwtService s = service(SECRET, T0);
        var v = s.verify(s.issue("alice", Role.ADMIN)).orElseThrow();
        assertThat(v.username()).isEqualTo("alice");
        assertThat(v.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void tokenExpiresAfterTheTtl() {
        String token = service(SECRET, T0).issue("alice", Role.VIEWER);
        assertThat(service(SECRET, T0.plus(Duration.ofSeconds(28799))).verify(token)).isPresent();
        assertThat(service(SECRET, T0.plus(Duration.ofSeconds(28801))).verify(token)).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = service("another-secret-0123456789-0123456789-xyz", T0).issue("alice", Role.ADMIN);
        assertThat(service(SECRET, T0).verify(token)).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        assertThat(service(SECRET, T0).verify("garbage")).isEmpty();
        assertThat(service(SECRET, T0).verify("")).isEmpty();
    }

    @Test
    void shortOrMissingSecretRefusesToStart() {
        assertThatThrownBy(() -> service("short", T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(null, T0)).isInstanceOf(IllegalStateException.class);
    }
}
