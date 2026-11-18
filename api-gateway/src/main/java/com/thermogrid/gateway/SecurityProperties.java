package com.thermogrid.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Phase 9 reads this flag to switch the JWT filter on. Nothing consumes it yet. */
@ConfigurationProperties(prefix = "thermogrid.security")
public record SecurityProperties(@DefaultValue("false") boolean enabled) {
}