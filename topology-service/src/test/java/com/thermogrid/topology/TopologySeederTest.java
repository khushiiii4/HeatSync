package com.thermogrid.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thermogrid.topology.domain.CoolingZone;
import com.thermogrid.topology.domain.Rack;
import com.thermogrid.topology.domain.RackStatus;
import com.thermogrid.topology.repo.RackRepository;
import com.thermogrid.topology.repo.ZoneRepository;
import com.thermogrid.topology.service.TopologySeeder;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** @DataJpaTest swaps in an embedded H2, so the developer's MySQL is never touched. */
@DataJpaTest
@Import(TopologySeeder.class)
@ActiveProfiles("test")
class TopologySeederTest {

    @Autowired TopologySeeder seeder;
    @Autowired ZoneRepository zones;
    @Autowired RackRepository racks;

    @BeforeEach
    void seed() {
        seeder.seed();
    }

    @Test
    void createsExactlyFourZonesAndSixteenRacks() {
        assertThat(zones.count()).isEqualTo(4);
        assertThat(racks.count()).isEqualTo(16);
        assertThat(zones.findAllByOrderByRowIndexAsc()).extracting(CoolingZone::getId)
                .containsExactly("Z1", "Z2", "Z3", "Z4");
    }

    @Test
    void rackIdsZonesAndCoordinatesFollowTheGrid() {
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 4; col++) {
                Rack r = racks.findById("R" + row + col).orElseThrow();
                assertThat(r.getRowIndex()).isEqualTo(row);
                assertThat(r.getColIndex()).isEqualTo(col);
                assertThat(r.getZone().getId()).isEqualTo("Z" + row);
            }
        }
        assertThat(zones.findAllByOrderByRowIndexAsc()).extracting(CoolingZone::getRowIndex)
                .containsExactly(1, 2, 3, 4);
    }

    @Test
    void initialValuesMatchThePlan() {
        zones.findAll().forEach(z -> assertThat(z.getTargetTempC()).isEqualByComparingTo("26.0"));
        racks.findAll().forEach(r -> {
            assertThat(r.getCpuCapacity()).isEqualTo(100);
            assertThat(r.getIdlePowerKw()).isEqualByComparingTo("2.00");
            assertThat(r.getMaxPowerKw()).isEqualByComparingTo("10.00");
            assertThat(r.getStatus()).isEqualTo(RackStatus.ACTIVE);
        });
    }

    @Test
    void runningTheSeederTwiceCreatesNoDuplicates() {
        seeder.seed();
        seeder.seed();
        assertThat(zones.count()).isEqualTo(4);
        assertThat(racks.count()).isEqualTo(16);
    }

    @Test
    void reseedingPreservesOperatorChanges() {
        Rack r23 = racks.findById("R23").orElseThrow();
        r23.setStatus(RackStatus.MAINTENANCE);
        CoolingZone z3 = zones.findById("Z3").orElseThrow();
        z3.setTargetTempC(new BigDecimal("24.5"));
        racks.saveAndFlush(r23);
        zones.saveAndFlush(z3);

        seeder.seed();

        assertThat(racks.findById("R23").orElseThrow().getStatus()).isEqualTo(RackStatus.MAINTENANCE);
        assertThat(zones.findById("Z3").orElseThrow().getTargetTempC()).isEqualByComparingTo("24.5");
        assertThat(racks.count()).isEqualTo(16);
    }

    @Test
    void databaseRejectsADuplicateGridPosition() {
        CoolingZone z1 = zones.findById("Z1").orElseThrow();
        assertThatThrownBy(() -> racks.saveAndFlush(new Rack("R99", z1, 1, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void databaseRejectsARackWithoutAnExistingZone() {
        racks.flush();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO rack (id, zone_id, row_index, col_index) VALUES ('R95', 'Z9', 9, 5)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}