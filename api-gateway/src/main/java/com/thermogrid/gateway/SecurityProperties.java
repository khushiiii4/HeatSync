package com.thermogrid.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code thermogrid.security.enabled} switches JWT authentication and role authorization on (the secured setup).
 * The JWT signing secret comes from THERMOGRID_JWT_SECRET and is never logged.
 */
@ConfigurationProperties(prefix = "thermogrid.security")
public record SecurityProperties(@DefaultValue("true") boolean enabled, String jwtSecret) {

    @Override
    public String toString() {
        return "SecurityProperties[enabled=" + enabled + "]";
    }
}
