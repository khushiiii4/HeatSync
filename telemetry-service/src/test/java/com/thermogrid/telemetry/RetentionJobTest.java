package com.thermogrid.telemetry;

import static com.thermogrid.telemetry.TestSupport.T0;
import static org.assertj.core.api.Assertions.assertThat;

import com.thermogrid.telemetry.config.TelemetryProperties;
import com.thermogrid.telemetry.domain.Alert;
import com.thermogrid.telemetry.domain.AlertBasis;
import com.thermogrid.telemetry.domain.RackReading;
import com.thermogrid.telemetry.domain.ZoneReading;
import com.thermogrid.telemetry.risk.RiskLevel;
import com.thermogrid.telemetry.service.RetentionJob;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Plan section 7: readings 6 h, CLEARED alerts 7 days, with a movable clock. */
class RetentionJobTest extends IntegrationTestBase {

    @Autowired
    TransactionTemplate tx;

    private RetentionJob job(int batchSize, int maxBatches) {
        return new RetentionJob(rackReadings, zoneReadings, alerts, tx,
                TestSupport.props(new TelemetryProperties.Retention(6, 7, batchSize, maxBatches, 600000)), clock);
    }

    private void rackAt(String rack, Instant at) {
        rackReadings.saveAndFlush(new RackReading(rack, at, new BigDecimal("26.00"), new BigDecimal("6.80"),
                new BigDecimal("60.0")));
    }

    private void zoneAt(String zone, Instant at) {
        zoneReadings.saveAndFlush(new ZoneReading(zone, at, new BigDecimal("26.00"), new BigDecimal("27.00"),
                new BigDecimal("18.00"), 40, "OK"));
    }

    @Test
    void readingsOlderThanSixHoursAreDeletedAndTheCutoffRowIsKept() {
        Instant now = T0.plus(Duration.ofHours(10));
        clock.set(now);
        Instant cutoff = now.minus(Duration.ofHours(6));
        rackAt("R11", cutoff.minusMillis(1));   // just too old
        rackAt("R12", cutoff);                  // exactly at the cutoff: kept
        rackAt("R13", cutoff.plusMillis(1));
        rackAt("R14", now);
        zoneAt("Z1", cutoff.minusSeconds(1));
        zoneAt("Z2", cutoff);

        RetentionJob.Result r = job(5000, 20).runOnce();

        assertThat(r).isEqualTo(new RetentionJob.Result(1, 1, 0));
        assertThat(rackReadings.findAll()).extracting(RackReading::getRackId).containsExactlyInAnyOrder("R12", "R13",
                "R14");
        assertThat(zoneReadings.findAll()).extracting(ZoneReading::getZoneId).containsExactly("Z2");
    }

    @Test
    void nothingIsDeletedWhenEverythingIsRecent() {
        clock.set(T0.plus(Duration.ofHours(5)));
        rackAt("R11", T0);
        zoneAt("Z1", T0);
        assertThat(job(5000, 20).runOnce()).isEqualTo(new RetentionJob.Result(0, 0, 0));
        assertThat(rackReadings.count()).isEqualTo(1);
    }

    @Test
    void theCutoffMovesWithTheClock() {
        rackAt("R11", T0);
        clock.set(T0.plus(Duration.ofHours(6)));
        assertThat(job(5000, 20).runOnce().rackReadings()).as("exactly 6 h old is kept").isZero();
        clock.set(T0.plus(Duration.ofHours(6)).plusMillis(1));
        assertThat(job(5000, 20).runOnce().rackReadings()).isEqualTo(1);
    }

    @Test
    void deletionIsBatchedAndBoundedPerRun() {
        clock.set(T0.plus(Duration.ofHours(10)));
        for (int i = 0; i < 7; i++) {
            rackAt("R" + (10 + i), T0.plusSeconds(i));
        }
        RetentionJob small = job(2, 2);   // at most 2 batches of 2 per run

        assertThat(small.runOnce().rackReadings()).isEqualTo(4);
        assertThat(rackReadings.count()).isEqualTo(3);
        assertThat(small.runOnce().rackReadings()).isEqualTo(3);
        assertThat(rackReadings.count()).isZero();
        assertThat(small.runOnce().rackReadings()).isZero();
    }

    @Test
    void onlyClearedAlertsOlderThanSevenDaysAreDeleted() {
        Instant now = T0.plus(Duration.ofDays(30));
        clock.set(now);
        BigDecimal t = new BigDecimal("36.00");

        Alert oldCleared = new Alert("R11", "Z1", RiskLevel.WARNING, AlertBasis.OBSERVED, t, t, now.minus(Duration.ofDays(9)));
        oldCleared.clear(t, t, now.minus(Duration.ofDays(8)));
        Alert borderline = new Alert("R12", "Z1", RiskLevel.WARNING, AlertBasis.OBSERVED, t, t, now.minus(Duration.ofDays(9)));
        borderline.clear(t, t, now.minus(Duration.ofDays(7)));   // exactly 7 days: kept
        Alert recentCleared = new Alert("R13", "Z1", RiskLevel.WARNING, AlertBasis.OBSERVED, t, t, now.minus(Duration.ofDays(6)));
        recentCleared.clear(t, t, now.minus(Duration.ofDays(5)));
        Alert oldActive = new Alert("R14", "Z1", RiskLevel.CRITICAL, AlertBasis.OBSERVED, t, t, now.minus(Duration.ofDays(20)));
        alerts.saveAllAndFlush(java.util.List.of(oldCleared, borderline, recentCleared, oldActive));

        RetentionJob.Result r = job(5000, 20).runOnce();

        assertThat(r.alerts()).isEqualTo(1);
        assertThat(alerts.findAll()).extracting(Alert::getRackId).containsExactlyInAnyOrder("R12", "R13", "R14");
    }

    @Autowired
    com.thermogrid.telemetry.service.IngestionService ingestion;

    @Test
    void forecastStateRowsAreNeverPurged() {
        ingestion.ingestRack(TestSupport.rack("R11", "Z1", T0, 26.0));
        clock.set(T0.plus(Duration.ofDays(30)));
        RetentionJob.Result r = job(5000, 20).runOnce();
        assertThat(r.rackReadings()).isEqualTo(1);
        assertThat(states.findById("R11")).as("current state is not history").isPresent();
    }
}
