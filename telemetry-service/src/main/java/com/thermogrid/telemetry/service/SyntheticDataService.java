package com.thermogrid.telemetry.service;

import com.thermogrid.telemetry.repo.RackForecastStateRepository;
import com.thermogrid.telemetry.repo.RackReadingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes what the simulator's flood generator created (plan 8.2: DELETE /api/telemetry/synthetic). Synthetic racks
 * carry the id prefix {@value #PREFIX}; real racks are R11 to R44, so nothing real can match.
 */
@Service
public class SyntheticDataService {

    public static final String PREFIX = "SYN";

    public record Removed(int readings, int forecastStates) {
    }

    private final RackReadingRepository readings;
    private final RackForecastStateRepository states;

    public SyntheticDataService(RackReadingRepository readings, RackForecastStateRepository states) {
        this.readings = readings;
        this.states = states;
    }

    @Transactional
    public Removed removeAll() {
        return new Removed(readings.deleteByRackIdPrefix(PREFIX), states.deleteByRackIdPrefix(PREFIX));
    }
}
