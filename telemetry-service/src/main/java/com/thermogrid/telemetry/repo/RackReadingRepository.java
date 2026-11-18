package com.thermogrid.telemetry.repo;

import com.thermogrid.telemetry.domain.RackReading;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RackReadingRepository extends JpaRepository<RackReading, Long> {

    boolean existsByRackIdAndRecordedAt(String rackId, Instant recordedAt);

    /** Phase 12 flood cleanup: removes the readings of synthetic racks (id prefix), nothing else. */
    @Modifying
    @Query("delete from RackReading r where r.rackId like concat(:prefix, '%')")
    int deleteByRackIdPrefix(@Param("prefix") String prefix);

    long countByRackId(String rackId);

    List<RackReading> findByRackIdAndRecordedAtGreaterThanEqualOrderByRecordedAtAsc(String rackId, Instant from);

    @Query("select r.id from RackReading r where r.recordedAt < :cutoff order by r.id")
    List<Long> findIdsOlderThan(@Param("cutoff") Instant cutoff, Pageable page);

    @Modifying
    @Query("delete from RackReading r where r.id in :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);
}
