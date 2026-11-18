package com.thermogrid.topology;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEMPORARY, DEMO ONLY (phase 1, bundle 1.3). Exists only to prove that a request reaches a real
 * registered service through the gateway. Delete it when phase 2 implements the real topology API.
 */
@RestController
public class TemporaryPingController {

    @GetMapping("/api/topology/ping")
    public Map<String, String> ping() {
        return Map.of("service", "TOPOLOGY-SERVICE", "status", "UP", "note", "temporary phase 1 ping");
    }
}