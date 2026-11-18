package com.thermogrid.topology.repo;

import com.thermogrid.topology.domain.Rack;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RackRepository extends JpaRepository<Rack, String> {
    List<Rack> findAllByOrderByRowIndexAscColIndexAsc();
}