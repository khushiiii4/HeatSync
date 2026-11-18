package com.thermogrid.topology.api;

import com.thermogrid.topology.service.TopologyService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TopologyController {

    private final TopologyService service;

    public TopologyController(TopologyService service) {
        this.service = service;
    }

    @GetMapping("/api/topology")
    public List<ZoneDto> topology() {
        return service.topology();
    }

    @GetMapping("/api/topology/racks/{rackId}")
    public RackDto rack(@PathVariable String rackId) {
        return service.rack(rackId).orElseThrow(() -> new RackNotFoundException(rackId));
    }

    /**
     * Service-to-service copy of the same payload (plan section 8.2). The gateway never routes /internal/**.
     * Token protection is added in phase 9.
     */
    @GetMapping("/internal/topology")
    public List<ZoneDto> internalTopology() {
        return service.topology();
    }
}