package com.thermogrid.telemetry.repo;

import com.thermogrid.telemetry.domain.Alert;
import com.thermogrid.telemetry.domain.AlertState;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByRackIdAndState(String rackId, AlertState state);

    List<Alert> findByStateOrderByIdDesc(AlertState state, Pageable page);

    List<Alert> findAllByOrderByIdDesc(Pageable page);

    /** Only CLEARED alerts are ever purged. */
    @Query("select a.id from Alert a where a.state = com.thermogrid.telemetry.domain.AlertState.CLEARED "
            + "and a.clearedAt < :cutoff order by a.id")
    List<Long> findClearedIdsOlderThan(@Param("cutoff") Instant cutoff, Pageable page);

    @Modifying
    @Query("delete from Alert a where a.id in :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);
}
