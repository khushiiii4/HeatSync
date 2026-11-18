package com.thermogrid.telemetry.repo;

import com.thermogrid.telemetry.domain.RackForecastState;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RackForecastStateRepository extends JpaRepository<RackForecastState, String> {

    /** SELECT ... FOR UPDATE: serialises every writer of one rack's state. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RackForecastState s where s.rackId = :rackId")
    Optional<RackForecastState> lockByRackId(@Param("rackId") String rackId);

    List<RackForecastState> findAllByOrderByRackIdAsc();

    /** Racks whose last accepted reading arrived at or before the cutoff and that are not yet marked STALE. */
    @Query("select s.rackId from RackForecastState s "
            + "where s.sensorStatus = com.thermogrid.telemetry.domain.SensorStatus.OK "
            + "and s.updatedAt <= :cutoff order by s.rackId")
    List<String> findStaleCandidates(@Param("cutoff") Instant cutoff);
}
