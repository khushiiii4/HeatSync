package com.thermogrid.telemetry;

import static com.thermogrid.telemetry.TestSupport.T0;
import static org.assertj.core.api.Assertions.assertThat;

import com.thermogrid.telemetry.domain.RackForecastState;
import com.thermogrid.telemetry.domain.SensorStatus;
import com.thermogrid.telemetry.service.IngestionService;
import com.thermogrid.telemetry.service.StaleSensorService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StaleSensorTest extends IntegrationTestBase {

    @Autowired
    IngestionService ingestion;
    @Autowired
    StaleSensorService watchdog;

    private void reading(String rack, long secondsAfterT0, double temp) {
        ingestion.ingestRack(TestSupport.rack(rack, "Z2", T0.plusSeconds(secondsAfterT0), temp));
    }

    @Test
    void aRackIsStaleAtExactlyTenSecondsAndNotBefore() {
        reading("R23", 0, 30.0);

        clock.set(T0.plusMillis(9_999));
        assertThat(watchdog.markStaleRacks()).isEmpty();
        assertThat(states.findById("R23").orElseThrow().getSensorStatus()).isEqualTo(SensorStatus.OK);

        clock.set(T0.plusMillis(10_000));
        assertThat(watchdog.markStaleRacks()).containsExactly("R23");
        assertThat(states.findById("R23").orElseThrow().getSensorStatus()).isEqualTo(SensorStatus.STALE);
    }

    @Test
    void theForecastIsFrozenWhileStaleAndNothingIsFabricated() {
        reading("R23", 0, 26.0);
        reading("R23", 2, 28.0);
        RackForecastState before = states.findById("R23").orElseThrow();
        long readingsBefore = rackReadings.count();

        clock.set(T0.plusSeconds(60));
        watchdog.markStaleRacks();
        watchdog.markStaleRacks();

        RackForecastState after = states.findById("R23").orElseThrow();
        assertThat(after.getSensorStatus()).isEqualTo(SensorStatus.STALE);
        assertThat(after.getLevelC()).isEqualTo(before.getLevelC());
        assertThat(after.getTrendC()).isEqualTo(before.getTrendC());
        assertThat(after.getForecast60sC()).isEqualByComparingTo(before.getForecast60sC());
        assertThat(after.getTempC()).isEqualByComparingTo(before.getTempC());
        assertThat(after.getRiskLevel()).isEqualTo(before.getRiskLevel());
        assertThat(after.getLastReadingAt()).isEqualTo(before.getLastReadingAt());
        assertThat(rackReadings.count()).as("no fabricated readings").isEqualTo(readingsBefore);
    }

    @Test
    void aStaleRackIsNotMarkedAgainAndOnlySilentRacksAreMarked() {
        reading("R21", 0, 26.0);
        reading("R22", 0, 26.0);
        clock.set(T0.plusSeconds(8));
        reading("R22", 8, 26.0);   // R22 keeps talking

        clock.set(T0.plusSeconds(12));
        assertThat(watchdog.markStaleRacks()).containsExactly("R21");
        assertThat(watchdog.markStaleRacks()).as("already stale").isEmpty();
        assertThat(states.findById("R22").orElseThrow().getSensorStatus()).isEqualTo(SensorStatus.OK);

        clock.set(T0.plusSeconds(18));
        assertThat(watchdog.markStaleRacks()).containsExactly("R22");
    }

    @Test
    void aNewValidReadingRestoresOkAndTheForecastContinues() {
        reading("R23", 0, 26.0);
        clock.set(T0.plusSeconds(30));
        watchdog.markStaleRacks();
        assertThat(states.findById("R23").orElseThrow().getSensorStatus()).isEqualTo(SensorStatus.STALE);

        reading("R23", 30, 27.0);

        RackForecastState s = states.findById("R23").orElseThrow();
        assertThat(s.getSensorStatus()).isEqualTo(SensorStatus.OK);
        assertThat(s.getLastReadingAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(s.getUpdatedAt()).isEqualTo(clock.instant());
        // and it can go stale again later
        clock.advance(Duration.ofSeconds(10));
        assertThat(watchdog.markStaleRacks()).containsExactly("R23");
    }

    @Test
    void aDuplicateOrOutOfOrderReadingDoesNotRestoreOk() {
        reading("R23", 0, 26.0);
        reading("R23", 4, 26.0);
        clock.set(T0.plusSeconds(30));
        watchdog.markStaleRacks();

        reading("R23", 4, 26.0);   // duplicate
        reading("R23", 2, 26.0);   // out of order
        assertThat(states.findById("R23").orElseThrow().getSensorStatus()).isEqualTo(SensorStatus.STALE);
    }

    @Test
    void racksThatNeverReportedAreAbsentAndNothingIsMarked() {
        assertThat(states.count()).isZero();
        assertThat(watchdog.markStaleRacks()).isEqualTo(List.of());
        clock.set(T0.plusSeconds(3600));
        assertThat(watchdog.markStaleRacks()).isEmpty();
        assertThat(states.count()).as("no row is invented for a silent rack").isZero();
    }

    @Test
    void activeAlertsStayActiveWhenTheSensorGoesStale() {
        reading("R23", 0, 26.0);
        reading("R23", 2, 40.0);
        assertThat(alerts.count()).isEqualTo(1);
        clock.set(T0.plusSeconds(60));
        watchdog.markStaleRacks();
        assertThat(alerts.findAll().get(0).getState().name()).isEqualTo("ACTIVE");
    }
}
