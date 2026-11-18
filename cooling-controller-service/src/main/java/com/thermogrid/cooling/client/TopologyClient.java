package com.thermogrid.cooling.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/** Direct, Eureka-resolved call to topology-service, used once to seed zone targets. */
@FeignClient(name = "TOPOLOGY-SERVICE", contextId = "coolingTopologyClient", primary = false)
public interface TopologyClient {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Zone(String id, BigDecimal targetTempC) {
    }

    @GetMapping("/internal/topology")
    List<Zone> topology();
}
