package com.thermogrid.simulator.topology;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/** Direct, Eureka-resolved call to topology-service. Deliberately does not go through the API gateway. */
@FeignClient(name = "TOPOLOGY-SERVICE", primary = false)
public interface TopologyClient {

    @GetMapping("/internal/topology")
    List<TopologyZoneView> topology();
}
