package com.thermogrid.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Target of every route's circuit-breaker fallbackUri (forward:/fallback/{service}). */
@RestController
public class FallbackController {

    public record ServiceUnavailable(int status, String error, String service, String message) {
        public static ServiceUnavailable of(String service, String message) {
            return new ServiceUnavailable(503, "SERVICE_UNAVAILABLE", service, message);
        }
    }

    @RequestMapping("/fallback/{service}")
    public ResponseEntity<ServiceUnavailable> fallback(@PathVariable String service) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ServiceUnavailable.of(service,
                        service + " is unavailable (no instance, timeout or open circuit). Try again shortly."));
    }
}