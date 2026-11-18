package com.thermogrid.gateway;

import com.thermogrid.contracts.Roles;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;

/** Verifies the signature (HS256 only), expiry, issuer and required claims of a ThermoGrid JWT. */
public class JwtVerifier {

    /** A verified identity taken from the token's claims. */
    public record Identity(String username, Role role) {
    }

    private final SecretKey key;
    private final Clock clock;

    public JwtVerifier(String secret, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "THERMOGRID_JWT_SECRET must be set to a random string of at least 32 characters "
                            + "when thermogrid.security.enabled=true");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    /** Empty for anything malformed, unsigned, tampered, expired, of another issuer, or missing sub, role or exp. */
    public Optional<Identity> verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).requireIssuer(Roles.JWT_ISSUER)
                    .clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getPayload();
            String sub = c.getSubject();
            String role = c.get(Roles.JWT_ROLE_CLAIM, String.class);
            if (sub == null || sub.isBlank() || role == null || c.getExpiration() == null) return Optional.empty();
            return Optional.of(new Identity(sub, Role.valueOf(role)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
