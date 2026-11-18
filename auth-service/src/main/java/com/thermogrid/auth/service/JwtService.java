package com.thermogrid.auth.service;

import com.thermogrid.auth.config.AuthProperties;
import com.thermogrid.auth.domain.Role;
import com.thermogrid.contracts.Roles;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/** Issues and verifies HS256 JWTs. The secret comes from configuration and is never logged. */
@Service
public class JwtService {

    public record Verified(String username, Role role) {
    }

    private final SecretKey key;
    private final long ttlSeconds;
    private final Clock clock;

    public JwtService(AuthProperties props, Clock clock) {
        String secret = props.jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "THERMOGRID_JWT_SECRET must be set to a random string of at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = props.tokenTtlSeconds();
        this.clock = clock;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String issue(String username, Role role) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(Roles.JWT_ISSUER)
                .subject(username)
                .claim(Roles.JWT_ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** Empty for any token that is malformed, tampered, expired, from another issuer or lacks required claims. */
    public Optional<Verified> verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer(Roles.JWT_ISSUER)
                    .clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            String sub = c.getSubject();
            String role = c.get(Roles.JWT_ROLE_CLAIM, String.class);
            if (sub == null || sub.isBlank() || role == null || c.getExpiration() == null) return Optional.empty();
            return Optional.of(new Verified(sub, Role.valueOf(role)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
