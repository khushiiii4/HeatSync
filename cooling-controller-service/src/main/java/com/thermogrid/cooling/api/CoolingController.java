package com.thermogrid.cooling.api;

import com.thermogrid.cooling.config.CoolingProperties;
import com.thermogrid.cooling.domain.CoolingAction;
import com.thermogrid.cooling.domain.Repositories.CoolingActionRepository;
import com.thermogrid.cooling.service.CoolingControlService;
import com.thermogrid.cooling.service.CoolingControlService.Summary;
import com.thermogrid.cooling.service.CoolingControlService.UnitView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cooling API (plan section 8.1). DTOs only; roles are enforced in a later phase. */
@RestController
@RequestMapping("/api/cooling")
public class CoolingController {

    public record ActionView(long id, String zoneId, int oldOutputPct, int newOutputPct, String mode, String reason,
                             Instant createdAt) {
        static ActionView of(CoolingAction a) {
            return new ActionView(a.getId(), a.getZoneId(), a.getOldOutputPct(), a.getNewOutputPct(), a.getMode(),
                    a.getReason(), a.getCreatedAt());
        }
    }

    public record ModeRequest(@NotBlank String mode, @Min(0) @Max(100) Integer outputPct) {
    }

    private final CoolingControlService control;
    private final CoolingActionRepository actions;
    private final CoolingProperties props;

    public CoolingController(CoolingControlService control, CoolingActionRepository actions,
                             CoolingProperties props) {
        this.control = control;
        this.actions = actions;
        this.props = props;
    }

    @GetMapping("/units")
    public List<UnitView> units() {
        return control.unitViews();
    }

    @GetMapping("/actions")
    public List<ActionView> actions(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return actions.latest(PageRequest.of(0, Math.min(limit, props.maxListLimit()))).stream()
                .map(ActionView::of).toList();
    }

    @GetMapping("/summary")
    public Summary summary() {
        return control.summary();
    }

    @PostMapping("/units/{zoneId}/mode")
    public UnitView mode(@PathVariable String zoneId, @Valid @RequestBody ModeRequest request) {
        return control.setMode(zoneId, request.mode(), request.outputPct());
    }
}
