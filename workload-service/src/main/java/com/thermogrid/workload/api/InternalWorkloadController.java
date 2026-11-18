package com.thermogrid.workload.api;

import com.thermogrid.workload.api.Dtos.AssignmentRequest;
import com.thermogrid.workload.api.Dtos.AssignmentResponse;
import com.thermogrid.workload.api.Dtos.WorkloadDto;
import com.thermogrid.workload.domain.WorkloadStatus;
import com.thermogrid.workload.service.WorkloadService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service API, never routed by the gateway (plan section 8.2). */
@RestController
@RequestMapping("/internal/workloads")
public class InternalWorkloadController {

    private final WorkloadService service;

    public InternalWorkloadController(WorkloadService service) {
        this.service = service;
    }

    @GetMapping
    public List<WorkloadDto> list(@RequestParam(required = false) WorkloadStatus status,
                                  @RequestParam(required = false) String rackId) {
        return service.list(status, rackId).stream().map(WorkloadDto::of).toList();
    }

    @PutMapping("/{id}/assignment")
    public AssignmentResponse assign(@PathVariable long id, @Valid @RequestBody AssignmentRequest request) {
        return service.assign(id, request);
    }
}
