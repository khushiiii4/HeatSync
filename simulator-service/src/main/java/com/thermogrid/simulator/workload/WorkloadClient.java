package com.thermogrid.simulator.workload;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Direct, Eureka-resolved call to workload-service. Deliberately does not go through the API gateway. */
@FeignClient(name = "WORKLOAD-SERVICE", primary = false)
public interface WorkloadClient {

    @GetMapping("/internal/workloads")
    List<WorkloadView> workloads(@RequestParam("status") String status);
}
