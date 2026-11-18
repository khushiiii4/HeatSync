package com.thermogrid.gateway;

import com.thermogrid.contracts.Headers;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The single enforcement point for authentication and roles (plan sections 8.1 and 9.6).
 * <ol>
 *   <li>{@code /internal/**} is never served by the gateway: 404, whatever the caller sends.</li>
 *   <li>Client-supplied {@code X-User-Name} and {@code X-User-Role} are always removed.</li>
 *   <li>Except {@code POST /api/auth/login} (and CORS preflight), a valid Bearer JWT is required: 401 otherwise.</li>
 *   <li>The role must satisfy {@link RolePolicy}: 403 otherwise.</li>
 *   <li>The identity headers are then set from the verified claims only.</li>
 * </ol>
 * Runs just after the CORS filter so that 401 and 403 responses carry CORS headers the browser can read.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthFilter implements WebFilter {

    private final JwtVerifier verifier;   // null when thermogrid.security.enabled=false

    public JwtAuthFilter(SecurityProperties props, java.time.Clock clock) {
        this.verifier = props.enabled() ? new JwtVerifier(props.jwtSecret(), clock) : null;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String rawPath = request.getURI().getRawPath();
        if (rawPath == null) rawPath = "/";

        if (rawPath.equals("/internal") || rawPath.startsWith("/internal/")) {
            return reject(exchange, HttpStatus.NOT_FOUND, "NOT_FOUND", "No such resource");
        }

        // Always drop client-supplied identity headers, even when security is switched off.
        ServerHttpRequest.Builder clean = request.mutate().headers(h -> {
            h.remove(Headers.USER_NAME);
            h.remove(Headers.USER_ROLE);
        });

        if (verifier == null || HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(exchange.mutate().request(clean.build()).build());
        }

        // Encoded, parameterised or doubled-slash paths could be decoded differently by a downstream service
        // than by the role rules here. This API never needs them, so they are refused outright.
        if (rawPath.indexOf('%') >= 0 || rawPath.indexOf(';') >= 0 || rawPath.indexOf('\\') >= 0
                || rawPath.contains("//")) {
            return reject(exchange, HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Unsupported characters in the path");
        }

        HttpMethod method = request.getMethod();
        if (RolePolicy.isPublic(method, rawPath)) {
            return chain.filter(exchange.mutate().request(clean.build()).build());
        }

        Optional<JwtVerifier.Identity> identity = bearer(request).flatMap(verifier::verify);
        if (identity.isEmpty()) {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return reject(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing, expired or invalid token");
        }
        JwtVerifier.Identity who = identity.get();
        if (!who.role().atLeast(RolePolicy.minimumRole(method, rawPath))) {
            return reject(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "Your role does not allow this action");
        }
        ServerHttpRequest verified = clean
                .header(Headers.USER_NAME, who.username())
                .header(Headers.USER_ROLE, who.role().name())
                .build();
        return chain.filter(exchange.mutate().request(verified).build());
    }

    private static Optional<String> bearer(ServerHttpRequest request) {
        String h = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (h == null || h.length() < 8 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return Optional.empty();
        String t = h.substring(7).trim();
        return t.isEmpty() ? Optional.empty() : Optional.of(t);
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String error, String message) {
        ServerHttpResponse res = exchange.getResponse();
        res.setStatusCode(status);
        res.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\":" + status.value() + ",\"error\":\"" + error + "\",\"message\":\"" + message + "\"}";
        DataBuffer buf = res.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return res.writeWith(Mono.just(buf));
    }
}
