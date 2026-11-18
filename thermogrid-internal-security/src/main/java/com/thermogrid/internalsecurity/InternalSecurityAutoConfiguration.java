package com.thermogrid.internalsecurity;

import com.thermogrid.contracts.Headers;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the /internal/** filter and the Feign token interceptor when security is enabled (the default). */
@AutoConfiguration
@EnableConfigurationProperties(InternalSecurityProperties.class)
@ConditionalOnProperty(name = "thermogrid.security.enabled", havingValue = "true", matchIfMissing = true)
public class InternalSecurityAutoConfiguration {

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    InternalTokenFilter internalTokenFilter(InternalSecurityProperties props) {
        return new InternalTokenFilter(props.getInternalToken());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RequestInterceptor.class)
    static class FeignTokenConfig {
        /** Adds the token to every outgoing Feign call; Feign is only used for /internal endpoints. */
        @Bean
        RequestInterceptor internalTokenRequestInterceptor(InternalSecurityProperties props) {
            String token = props.getInternalToken();
            if (token == null || token.isBlank()) {
                throw new IllegalStateException(
                        "THERMOGRID_INTERNAL_TOKEN must be set when thermogrid.security.enabled=true");
            }
            return template -> template.header(Headers.INTERNAL_TOKEN, token);
        }
    }
}
