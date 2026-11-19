package com.thermogrid.simulator.api;

import com.thermogrid.simulator.flood.FloodGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Plan 8.2: POST /api/sim/flood (ADMIN at the gateway) publishes synthetic readings for load tests. */
@RestController
@RequestMapping("/api/sim/flood")
public class FloodController {

    /** Body: ratePerSec 1..5000, durationSec 1..300, racks 1..200 (default 50). */
    public record FloodRequest(Integer ratePerSec, Integer durationSec, Integer racks) {
    }

    private final FloodGenerator generator;

    public FloodController(FloodGenerator generator) {
        this.generator = generator;
    }

    @PostMapping
    public ResponseEntity<FloodGenerator.Status> start(@RequestBody FloodRequest body) {
        int rate = body.ratePerSec() == null ? 0 : body.ratePerSec();
        int seconds = body.durationSec() == null ? 0 : body.durationSec();
        int racks = body.racks() == null ? 50 : body.racks();
        if (rate < 1 || rate > FloodGenerator.MAX_RATE) {
            throw new InvalidControlException("ratePerSec must be 1 to " + FloodGenerator.MAX_RATE);
        }
        if (seconds < 1 || seconds > FloodGenerator.MAX_SECONDS) {
            throw new InvalidControlException("durationSec must be 1 to " + FloodGenerator.MAX_SECONDS);
        }
        if (racks < 1 || racks > FloodGenerator.MAX_RACKS) {
            throw new InvalidControlException("racks must be 1 to " + FloodGenerator.MAX_RACKS);
        }
        FloodGenerator.Status started = generator.start(rate, seconds, racks);
        if (started == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(generator.status());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(started);
    }

    @GetMapping
    public FloodGenerator.Status status() {
        return generator.status();
    }
}
