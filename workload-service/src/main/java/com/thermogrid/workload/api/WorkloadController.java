package com.thermogrid.workload.api;

import com.thermogrid.contracts.Headers;
import com.thermogrid.workload.api.Dtos.SeedResult;
import com.thermogrid.workload.api.Dtos.SubmitRequest;
import com.thermogrid.workload.api.Dtos.Summary;
import com.thermogrid.workload.api.Dtos.WorkloadDto;
import com.thermogrid.workload.domain.WorkloadStatus;
import com.thermogrid.workload.service.DemoSeeder;
import com.thermogrid.workload.service.WorkloadService;
import com.thermogrid.workload.service.WorkloadExceptions.InvalidRequestException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public API (plan section 8.1), reached through the gateway. Roles are enforced in a later phase. */
@RestController
@RequestMapping("/api/workloads")
public class WorkloadController {

    private final WorkloadService service;
    private final DemoSeeder seeder;
    private final Validator validator;

    public WorkloadController(WorkloadService service, DemoSeeder seeder, Validator validator) {
        this.validator = validator;
        this.service = service;
        this.seeder = seeder;
    }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public WorkloadDto submit(@Valid @RequestBody SubmitRequest req,
                              @RequestHeader(value = Headers.USER_NAME, required = false) String user) {
        return WorkloadDto.of(service.submit(req, user(user)));
    }

    @PostMapping("/bulk")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public List<WorkloadDto> bulk(@RequestBody List<SubmitRequest> requests,
                                  @RequestHeader(value = Headers.USER_NAME, required = false) String user) {
        if (requests != null) {
            for (int i = 0; i < requests.size(); i++) {
                var violations = validator.validate(requests.get(i));
                if (!violations.isEmpty()) {
                    var v = violations.iterator().next();
                    throw new InvalidRequestException("item " + i + ": " + v.getPropertyPath() + " " + v.getMessage());
                }
            }
        }
        return service.submitBulk(requests, user(user)).stream().map(WorkloadDto::of).toList();
    }

    @GetMapping
    public List<WorkloadDto> list(@RequestParam(required = false) WorkloadStatus status,
                                  @RequestParam(required = false) String rackId) {
        return service.list(status, rackId).stream().map(WorkloadDto::of).toList();
    }

    @GetMapping("/summary")
    public Summary summary() {
        return service.summary();
    }

    @GetMapping("/{id}")
    public WorkloadDto get(@PathVariable long id) {
        return WorkloadDto.of(service.get(id));
    }

    @DeleteMapping("/{id}")
    public WorkloadDto remove(@PathVariable long id) {
        return WorkloadDto.of(service.remove(id));
    }

    @PostMapping("/seed-demo")
    public ResponseEntity<SeedResult> seedDemo() {
        SeedResult result = seeder.seed();
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    private static String user(String header) {
        return header == null || header.isBlank() ? "operator" : header.length() > 50 ? header.substring(0, 50) : header;
    }
}
