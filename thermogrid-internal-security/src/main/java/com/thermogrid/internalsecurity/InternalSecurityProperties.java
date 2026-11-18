package com.thermogrid.internalsecurity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shared secret for service-to-service calls. {@code security.enabled} defaults to true; only test profiles switch
 * it off. The token comes from THERMOGRID_INTERNAL_TOKEN and is never logged.
 */
@ConfigurationProperties(prefix = "thermogrid")
public class InternalSecurityProperties {

    private String internalToken = "";
    private final Security security = new Security();

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public Security getSecurity() {
        return security;
    }

    public static class Security {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
