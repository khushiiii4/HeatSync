package com.thermogrid.telemetry.repo;

import com.thermogrid.telemetry.domain.ZoneReading;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ZoneReadingRepository extends JpaRepository<ZoneReading, Long> {

    boolean existsByZoneIdAndRecordedAt(String zoneId, Instant recordedAt);

    long countByZoneId(String zoneId);

    /** The newest reading of every zone, ordered by zone id. */
    @Query("select z from ZoneReading z where z.recordedAt = "
            + "(select max(z2.recordedAt) from ZoneReading z2 where z2.zoneId = z.zoneId) order by z.zoneId")
    List<ZoneReading> findLatestPerZone();

    @Query("select z.id from ZoneReading z where z.recordedAt < :cutoff order by z.id")
    List<Long> findIdsOlderThan(@Param("cutoff") Instant cutoff, Pageable page);

    @Modifying
    @Query("delete from ZoneReading z where z.id in :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);
}
