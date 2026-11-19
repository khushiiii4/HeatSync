package com.thermogrid.telemetry.api;

import com.thermogrid.telemetry.service.SyntheticDataService;
import com.thermogrid.telemetry.service.TelemetryQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TelemetryController {

    private final TelemetryQueryService queries;
    private final SyntheticDataService synthetic;

    public TelemetryController(TelemetryQueryService queries, SyntheticDataService synthetic) {
        this.synthetic = synthetic;
        this.queries = queries;
    }

    @GetMapping("/api/telemetry/racks/latest")
    public List<Dtos.RackLatest> racksLatest() {
        return queries.latestRacks();
    }

    @GetMapping("/api/telemetry/racks/{rackId}/history")
    public Dtos.History history(@PathVariable String rackId,
                                @RequestParam(name = "minutes", defaultValue = "10") int minutes) {
        return queries.history(rackId, minutes);
    }

    /** ADMIN only (gateway rule): removes racks and readings created by the simulator flood generator. */
    @DeleteMapping("/api/telemetry/synthetic")
    public SyntheticDataService.Removed removeSynthetic() {
        return synthetic.removeAll();
    }

    @GetMapping("/api/telemetry/zones/latest")
    public List<Dtos.ZoneLatest> zonesLatest() {
        return queries.latestZones();
    }

    /** Service-to-service view for the scheduler. Never routed by the gateway. The token check arrives with auth. */
    @GetMapping("/internal/telemetry/racks/latest")
    public List<Dtos.InternalRack> internalRacksLatest() {
        return queries.internalRacks();
    }

    @GetMapping("/api/alerts")
    public List<Dtos.AlertView> alerts(@RequestParam(name = "state", defaultValue = "ACTIVE") String state,
                                       @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return queries.alerts(state, limit);
    }
}
