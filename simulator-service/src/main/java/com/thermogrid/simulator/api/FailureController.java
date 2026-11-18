package com.thermogrid.simulator.api;

import com.thermogrid.contracts.Headers;
import com.thermogrid.simulator.failure.FailureExceptions.InvalidFailureException;
import com.thermogrid.simulator.failure.FailureRecord;
import com.thermogrid.simulator.failure.FailureRegistry;
import com.thermogrid.simulator.failure.FailureType;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sim/failures")
public class FailureController {

    private final FailureRegistry registry;

    public FailureController(FailureRegistry registry) {
        this.registry = registry;
    }

    /** active=true: only active failures. Omitted or false: the whole log, history included. */
    @GetMapping
    public List<FailureDto> list(@RequestParam(name = "active", defaultValue = "false") boolean active) {
        List<FailureRecord> rows = active ? registry.active() : registry.all();
        return rows.stream().map(FailureDto::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FailureDto inject(@RequestBody FailureRequest body,
                             @RequestHeader(name = Headers.USER_NAME, required = false) String user) {
        FailureType type = parseType(body.type());
        return FailureDto.from(registry.inject(type, body.targetId(), body.durationSec(), body.param(), user));
    }

    @DeleteMapping("/{id}")
    public FailureDto clear(@PathVariable long id) {
        return FailureDto.from(registry.clear(id));
    }

    private static FailureType parseType(String type) {
        if (type == null || type.isBlank()) {
            throw new InvalidFailureException("type is required: one of COOLING_FAILURE, COOLING_DEGRADED, "
                    + "SENSOR_DROPOUT, HEAT_SPIKE");
        }
        try {
            return FailureType.valueOf(type.strip());
        } catch (IllegalArgumentException e) {
            throw new InvalidFailureException("Unknown type " + type + ": use COOLING_FAILURE, COOLING_DEGRADED, "
                    + "SENSOR_DROPOUT or HEAT_SPIKE");
        }
    }
}
