package com.thermogrid.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thermogrid.simulator.config.SimulatorProperties;
import com.thermogrid.simulator.physics.HealthState;
import com.thermogrid.simulator.physics.RackSpec;
import com.thermogrid.simulator.topology.InvalidTopologyException;
import com.thermogrid.simulator.topology.RackGrid;
import com.thermogrid.simulator.topology.TopologyBootstrapper;
import com.thermogrid.simulator.topology.TopologyClient;
import com.thermogrid.simulator.topology.TopologyMapper;
import com.thermogrid.simulator.topology.TopologyRackView;
import com.thermogrid.simulator.topology.TopologyZoneView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TopologyBootstrapTest {

    private static SimulatorProperties props(int attempts, long initial, long max) {
        return new SimulatorProperties(2000, false, null, TestTopology.GRID, TestTopology.INITIAL,
                new SimulatorProperties.Physics(18.0, 0.06, 0.12, 0.05, 0.05, 0.10,
                        new SimulatorProperties.HealthFactors(1.0, 0.25, 0.0)),
                new SimulatorProperties.Publishing(false, "SIMULATOR-SERVICE", 1, 10000),
                new SimulatorProperties.Failures(86400, 50.0),
                new SimulatorProperties.Bootstrap(attempts, initial, max));
    }

    private static TopologyClient client(Supplier<List<TopologyZoneView>> s) {
        return s::get;
    }

    // ---- grid building ----

    @Test
    void validTopologyBuildsTheFourByFourGrid() {
        RackGrid g = TopologyMapper.toGrid(TestTopology.payload(), TestTopology.GRID);
        assertThat(g.zones()).hasSize(4);
        assertThat(g.racks()).hasSize(16);
        assertThat(g.racks().get(0).id()).isEqualTo("R11");
        assertThat(g.racks().get(15).id()).isEqualTo("R44");
        RackSpec r23 = g.rack("R23");
        assertThat(r23.zoneId()).isEqualTo("Z2");
        assertThat(r23.row()).isEqualTo(2);
        assertThat(r23.col()).isEqualTo(3);
        assertThat(r23.cpuCapacity()).isEqualTo(100);
        assertThat(r23.idlePowerKw()).isEqualTo(2.0);
        assertThat(r23.maxPowerKw()).isEqualTo(10.0);
        assertThat(r23.active()).isTrue();
        assertThat(g.zones().get(1).targetTempC()).isEqualTo(26.0);
    }

    @Test
    void maintenanceStatusIsRetainedAsInactive() {
        RackGrid g = TestTopology.grid(Set.of("R33"));
        assertThat(g.rack("R33").active()).isFalse();
        assertThat(g.racks().stream().filter(RackSpec::active)).hasSize(15);
    }

    @Test
    void wrongZoneCountFailsClearly() {
        List<TopologyZoneView> three = TestTopology.payload().subList(0, 3);
        assertThatThrownBy(() -> TopologyMapper.toGrid(three, TestTopology.GRID))
                .isInstanceOf(InvalidTopologyException.class).hasMessageContaining("expected 4 zones but got 3");
    }

    @Test
    void wrongRackCountFailsClearly() {
        List<TopologyZoneView> zones = new ArrayList<>(TestTopology.payload());
        TopologyZoneView z4 = zones.get(3);
        zones.set(3, new TopologyZoneView(z4.id(), z4.name(), z4.rowIndex(), z4.targetTempC(),
                z4.racks().subList(0, 3)));
        assertThatThrownBy(() -> TopologyMapper.toGrid(zones, TestTopology.GRID))
                .isInstanceOf(InvalidTopologyException.class).hasMessageContaining("expected 16 racks but got 15");
    }

    @Test
    void emptyAndNullPayloadsFail() {
        assertThatThrownBy(() -> TopologyMapper.toGrid(List.of(), TestTopology.GRID))
                .isInstanceOf(InvalidTopologyException.class).hasMessageContaining("no zones");
        assertThatThrownBy(() -> TopologyMapper.toGrid(null, TestTopology.GRID))
                .isInstanceOf(InvalidTopologyException.class);
    }

    @Test
    void malformedRackFieldsFailClearly() {
        assertThat(mutateRack("R23", r -> new TopologyRackView(r.id(), r.zoneId(), r.rowIndex(), r.colIndex(), null,
                r.idlePowerKw(), r.maxPowerKw(), r.status()))).contains("missing field");
        assertThat(mutateRack("R23", r -> new TopologyRackView(r.id(), r.zoneId(), r.rowIndex(), r.colIndex(),
                r.cpuCapacity(), r.idlePowerKw(), r.maxPowerKw(), "BROKEN"))).contains("unknown status BROKEN");
        assertThat(mutateRack("R23", r -> new TopologyRackView(r.id(), "Z3", r.rowIndex(), r.colIndex(),
                r.cpuCapacity(), r.idlePowerKw(), r.maxPowerKw(), r.status()))).contains("does not belong to zone");
        assertThat(mutateRack("R23", r -> new TopologyRackView(r.id(), r.zoneId(), r.rowIndex(), 9,
                r.cpuCapacity(), r.idlePowerKw(), r.maxPowerKw(), r.status()))).contains("outside the 4x4 grid");
        assertThat(mutateRack("R23", r -> new TopologyRackView(r.id(), r.zoneId(), r.rowIndex(), 2,
                r.cpuCapacity(), r.idlePowerKw(), r.maxPowerKw(), r.status()))).contains("two racks at row 2, col 2");
        assertThat(mutateRack("R23", r -> new TopologyRackView("R22", r.zoneId(), r.rowIndex(), r.colIndex(),
                r.cpuCapacity(), r.idlePowerKw(), r.maxPowerKw(), r.status()))).contains("duplicate rack id R22");
        assertThat(mutateRack("R23", r -> new TopologyRackView(r.id(), r.zoneId(), r.rowIndex(), r.colIndex(),
                0, r.idlePowerKw(), r.maxPowerKw(), r.status()))).contains("non-positive cpuCapacity");
        assertThat(mutateRack("R23", r -> new TopologyRackView(r.id(), r.zoneId(), r.rowIndex(), r.colIndex(),
                r.cpuCapacity(), new BigDecimal("12.00"), r.maxPowerKw(), r.status()))).contains("power range invalid");
    }

    private static String mutateRack(String rackId, java.util.function.UnaryOperator<TopologyRackView> change) {
        List<TopologyZoneView> zones = new ArrayList<>();
        for (TopologyZoneView z : TestTopology.payload()) {
            List<TopologyRackView> racks = new ArrayList<>();
            for (TopologyRackView r : z.racks()) {
                racks.add(r.id().equals(rackId) ? change.apply(r) : r);
            }
            zones.add(new TopologyZoneView(z.id(), z.name(), z.rowIndex(), z.targetTempC(), racks));
        }
        try {
            TopologyMapper.toGrid(zones, TestTopology.GRID);
        } catch (InvalidTopologyException e) {
            return e.getMessage();
        }
        throw new AssertionError("topology was accepted but should have been rejected");
    }

    // ---- retry behaviour ----

    @Test
    void retriesWithExponentialBackoffUntilTopologyIsAvailable() {
        AtomicInteger calls = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        TopologyClient client = client(() -> {
            if (calls.incrementAndGet() <= 3) {
                throw new IllegalStateException("no instances for TOPOLOGY-SERVICE");
            }
            return TestTopology.payload();
        });

        RackGrid g = new TopologyBootstrapper(client, props(10, 1000, 15000), sleeps::add).load();

        assertThat(g.racks()).hasSize(16);
        assertThat(calls).hasValue(4);
        assertThat(sleeps).containsExactly(1000L, 2000L, 4000L);
    }

    @Test
    void backoffIsCappedAtTheConfiguredMaximum() {
        List<Long> sleeps = new ArrayList<>();
        TopologyClient client = client(() -> {
            throw new IllegalStateException("down");
        });
        assertThatThrownBy(() -> new TopologyBootstrapper(client, props(6, 1000, 3000), sleeps::add).load())
                .isInstanceOf(IllegalStateException.class);
        assertThat(sleeps).containsExactly(1000L, 2000L, 3000L, 3000L, 3000L);
    }

    @Test
    void givesUpAfterMaxAttemptsInsteadOfStartingEmpty() {
        AtomicInteger calls = new AtomicInteger();
        TopologyClient client = client(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("down");
        });
        assertThatThrownBy(() -> new TopologyBootstrapper(client, props(4, 10, 100), ms -> { }).load())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after 4 attempts")
                .hasMessageContaining("empty topology")
                .hasRootCauseMessage("down");
        assertThat(calls).hasValue(4);
    }

    @Test
    void invalidTopologyIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        TopologyClient client = client(() -> {
            calls.incrementAndGet();
            return TestTopology.payload().subList(0, 2);
        });
        assertThatThrownBy(() -> new TopologyBootstrapper(client, props(5, 10, 100), ms -> { }).load())
                .isInstanceOf(InvalidTopologyException.class).hasMessageContaining("expected 4 zones but got 2");
        assertThat(calls).hasValue(1);
    }

    @Test
    void initialHealthEnumIsUsable() {
        assertThat(TestTopology.INITIAL.health()).isEqualTo(HealthState.OK);
    }
}
