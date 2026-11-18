package com.thermogrid.internalsecurity;

import com.thermogrid.contracts.Headers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** Requires {@code X-Internal-Token} on every {@code /internal/**} request; other paths pass untouched. */
public class InternalTokenFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public InternalTokenFilter(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "THERMOGRID_INTERNAL_TOKEN must be set when thermogrid.security.enabled=true");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/internal") || path.startsWith("/internal/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getHeader(Headers.INTERNAL_TOKEN);
        if (given != null && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType("application/json");
        res.getWriter().write("{\"status\":401,\"error\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid "
                + Headers.INTERNAL_TOKEN + "\"}");
    }
}
