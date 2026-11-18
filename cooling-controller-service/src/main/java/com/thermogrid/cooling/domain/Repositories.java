package com.thermogrid.cooling.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repositories of the two cooling tables. */
public final class Repositories {

    private Repositories() {
    }

    public interface CoolingUnitRepository extends JpaRepository<CoolingUnit, String> {
    }

    public interface CoolingActionRepository extends JpaRepository<CoolingAction, Long> {

        @Query("select a from CoolingAction a order by a.createdAt desc, a.id desc")
        List<CoolingAction> latest(Pageable page);

        @Modifying
        @Query("delete from CoolingAction a where a.createdAt < :cutoff")
        int deleteOlderThan(@Param("cutoff") Instant cutoff);
    }
}
