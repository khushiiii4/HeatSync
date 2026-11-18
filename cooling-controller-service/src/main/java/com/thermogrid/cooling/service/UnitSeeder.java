package com.thermogrid.cooling.service;

import com.thermogrid.cooling.client.TopologyClient;
import com.thermogrid.cooling.config.CoolingProperties;
import com.thermogrid.cooling.domain.CoolingUnit;
import com.thermogrid.cooling.domain.Repositories.CoolingUnitRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds one cooling unit per zone at first start (plan section 5). Zone ids and target temperatures come from
 * topology-service; if it cannot be reached after a few tries the configured default zones and the default target of
 * 26.0 C are used. Idempotent: units that already exist are never changed, missing ones are added.
 */
@Component
public class UnitSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UnitSeeder.class);

    private final CoolingUnitRepository repo;
    private final ObjectProvider<TopologyClient> topology;
    private final CoolingProperties props;
    private final CoolingControlService control;
    private final Clock clock;

    public UnitSeeder(CoolingUnitRepository repo, ObjectProvider<TopologyClient> topology, CoolingProperties props,
                      CoolingControlService control, Clock clock) {
        this.repo = repo;
        this.topology = topology;
        this.props = props;
        this.control = control;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        if (props.seedOnStartup()) {
            List<TopologyClient.Zone> zones = fetchTopology(3, 1000);
            seed(zones);
        }
        control.load();
    }

    private List<TopologyClient.Zone> fetchTopology(int attempts, long waitMs) throws InterruptedException {
        TopologyClient client = topology.getIfAvailable();
        for (int i = 1; client != null && i <= attempts; i++) {
            try {
                List<TopologyClient.Zone> z = client.topology();
                if (z != null && !z.isEmpty()) {
                    return z;
                }
            } catch (RuntimeException e) {
                log.warn("Topology not available for seeding (attempt {}/{}): {}", i, attempts, e.toString());
            }
            if (i < attempts) {
                Thread.sleep(waitMs);
            }
        }
        log.warn("Seeding cooling units from the default zones {} with target {} C", props.defaultZones(),
                props.defaultTargetC());
        return null;
    }

    /** @param zones from topology, or null to use the configured defaults */
    public int seed(List<TopologyClient.Zone> zones) {
        List<TopologyClient.Zone> list = zones;
        if (list == null) {
            list = new ArrayList<>();
            for (String id : props.defaultZones()) {
                list.add(new TopologyClient.Zone(id, null));
            }
        }
        int created = 0;
        for (TopologyClient.Zone z : list) {
            if (repo.existsById(z.id())) {
                continue;
            }
            BigDecimal target = (z.targetTempC() == null ? BigDecimal.valueOf(props.defaultTargetC()) : z.targetTempC())
                    .setScale(1, RoundingMode.HALF_UP);
            repo.save(new CoolingUnit(z.id(), CoolingControlService.AUTO, props.initialOutputPct(), target, "OK",
                    props.control().floorNormal(), clock.instant()));
            created++;
        }
        if (created > 0) {
            log.info("Seeded {} cooling units", created);
        }
        return created;
    }
}
