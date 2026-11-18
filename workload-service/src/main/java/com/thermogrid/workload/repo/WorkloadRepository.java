package com.thermogrid.workload.repo;

import com.thermogrid.workload.domain.Workload;
import com.thermogrid.workload.domain.WorkloadStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkloadRepository extends JpaRepository<Workload, Long> {

    @Query("select w from Workload w where (:status is null or w.status = :status) "
            + "and (:rackId is null or w.rackId = :rackId) order by w.id")
    List<Workload> search(@Param("status") WorkloadStatus status, @Param("rackId") String rackId);

    List<Workload> findByStatusOrderById(WorkloadStatus status);

    boolean existsByCreatedByAndStatusNot(String createdBy, WorkloadStatus status);

    @Modifying
    @Query("delete from Workload w where w.status = com.thermogrid.workload.domain.WorkloadStatus.REMOVED "
            + "and w.updatedAt < :cutoff")
    int purgeRemovedBefore(@Param("cutoff") Instant cutoff);
}
