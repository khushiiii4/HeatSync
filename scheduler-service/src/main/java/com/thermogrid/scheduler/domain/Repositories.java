package com.thermogrid.scheduler.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repositories of the four scheduler tables. */
public final class Repositories {

    private Repositories() {
    }

    public interface MigrationRepository extends JpaRepository<Migration, String> {

        List<Migration> findByStatusAndCreatedAtAfter(String status, Instant after);

        @Query("select m from Migration m order by m.createdAt desc, m.id")
        List<Migration> latest(Pageable page);
    }

    public interface PlacementDecisionRepository extends JpaRepository<PlacementDecision, Long> {

        @Query("select d from PlacementDecision d order by d.createdAt desc, d.id desc")
        List<PlacementDecision> latest(Pageable page);
    }

    public interface ZoneCoolingStateRepository extends JpaRepository<ZoneCoolingState, String> {
    }

    public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

        @Modifying
        @Query("delete from ProcessedEvent e where e.processedAt < :cutoff")
        int deleteOlderThan(@Param("cutoff") Instant cutoff);
    }
}
