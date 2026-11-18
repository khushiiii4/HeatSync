package com.thermogrid.topology.service;

import com.thermogrid.topology.api.RackDto;
import com.thermogrid.topology.api.ZoneDto;
import com.thermogrid.topology.domain.Rack;
import com.thermogrid.topology.repo.RackRepository;
import com.thermogrid.topology.repo.ZoneRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TopologyService {

    private final ZoneRepository zones;
    private final RackRepository racks;

    public TopologyService(ZoneRepository zones, RackRepository racks) {
        this.zones = zones;
        this.racks = racks;
    }

    /** Zones ordered by row, each with its racks ordered by row and column. */
    @Transactional(readOnly = true)
    public List<ZoneDto> topology() {
        Map<String, List<RackDto>> byZone = new LinkedHashMap<>();
        for (Rack r : racks.findAllByOrderByRowIndexAscColIndexAsc()) {
            byZone.computeIfAbsent(r.getZone().getId(), k -> new java.util.ArrayList<>()).add(RackDto.from(r));
        }
        return zones.findAllByOrderByRowIndexAsc().stream()
                .map(z -> ZoneDto.from(z, byZone.getOrDefault(z.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<RackDto> rack(String rackId) {
        return racks.findById(rackId).map(RackDto::from);
    }
}