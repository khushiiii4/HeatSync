package com.thermogrid.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bound from {@code thermogrid.auth.*}. Nothing here has a secret default. */
@ConfigurationProperties(prefix = "thermogrid.auth")
public record AuthProperties(
        String jwtSecret,
        @DefaultValue("28800") long tokenTtlSeconds,
        @DefaultValue List<SeedUser> seedUsers) {

    /** A demo user: username, role, and either a plain password or a BCrypt hash (the hash wins). */
    public record SeedUser(String username, String password, String passwordHash, String role) {
        /** Keeps the password out of any accidental log line. */
        @Override
        public String toString() {
            return "SeedUser[username=" + username + ", role=" + role + "]";
        }
    }

    /** Keeps the signing secret out of any accidental log line. */
    @Override
    public String toString() {
        return "AuthProperties[tokenTtlSeconds=" + tokenTtlSeconds + ", seedUsers=" + seedUsers + "]";
    }
}
