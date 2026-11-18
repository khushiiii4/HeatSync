package com.thermogrid.scheduler.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thermogrid.scheduler.domain.PlacementDecision;
import com.thermogrid.scheduler.domain.Repositories.PlacementDecisionRepository;
import com.thermogrid.scheduler.engine.PlacementEngine.Candidate;
import com.thermogrid.scheduler.engine.PlacementEngine.Placement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Persists every placement decision with its top three candidates and scores (plan section 7). */
@Component
public class DecisionRecorder {

    private final PlacementDecisionRepository repo;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DecisionRecorder(PlacementDecisionRepository repo, ObjectMapper mapper, Clock clock) {
        this.repo = repo;
        this.mapper = mapper;
        this.clock = clock;
    }

    public PlacementDecision record(long workloadId, Placement p) {
        List<Map<String, Object>> cands = new ArrayList<>();
        for (Candidate c : p.top3()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rackId", c.rackId());
            m.put("score", round3(c.score()));
            if (!Double.isNaN(c.tAfterC())) {
                m.put("tAfterC", round3(c.tAfterC()));
            }
            m.put("utilAfter", round3(c.utilAfter()));
            cands.add(m);
        }
        String json;
        try {
            json = mapper.writeValueAsString(cands);
        } catch (JsonProcessingException e) {
            json = "[]";
        }
        return repo.save(new PlacementDecision(workloadId, p.rackId(),
                BigDecimal.valueOf(p.score()).setScale(3, RoundingMode.HALF_UP), json, p.mode().name(),
                clock.instant()));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
