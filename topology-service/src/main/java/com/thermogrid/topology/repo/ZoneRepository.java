package com.thermogrid.topology.repo;

import com.thermogrid.topology.domain.CoolingZone;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZoneRepository extends JpaRepository<CoolingZone, String> {
    List<CoolingZone> findAllByOrderByRowIndexAsc();
}