package com.thermogrid.cooling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.thermogrid.cooling.control.AlertLevel;
import com.thermogrid.cooling.control.CoolingParams;
import com.thermogrid.cooling.control.EnergyModel;
import com.thermogrid.cooling.control.FailSafeWatchdog;
import com.thermogrid.cooling.control.FaultDetector;
import com.thermogrid.cooling.control.FaultDetector.Transition;
import com.thermogrid.cooling.control.ProportionalController;
import com.thermogrid.cooling.control.ProportionalController.Step;
import com.thermogrid.cooling.control.ProportionalController.ZoneControl;
import com.thermogrid.cooling.control.ReadingGate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure, table-driven tests of plan section 6E. No Spring, no clock, no I/O. */
class ControlLawTest {

    private static final Instant T0 = Instant.parse("2026-11-18T07:10:00Z");
    private final CoolingParams p = CoolingParams.defaults();
    private final ProportionalController c = new ProportionalController(p);

    private Step step(ZoneControl z, double max, int last, boolean fault, AlertLevel alert, Instant now) {
        return c.step(z, max, 26.0, last, fault, alert, now);
    }

    // ---- law ----

    @Test
    void atTargetTheRawOutputIsThirtyFivePercent() {
        Step s = step(new ZoneControl(), 26.0, 35, false, AlertLevel.NONE, T0);
        assertThat(s.hot()).isEqualTo(26.0);
        assertThat(s.raw()).isEqualTo(35.0);
        assertThat(s.desired()).isEqualTo(35);
        assertThat(s.output()).isEqualTo(35);
    }

    @Test
    void firstReadingStartsTheEwmaAtTheReadingWithZeroSlope() {
        ZoneControl z = new ZoneControl();
        Step s = step(z, 29.0, 40, false, AlertLevel.NONE, T0);
        assertThat(s.ewma()).isEqualTo(29.0);
        assertThat(s.slope()).isZero();
        assertThat(z.ewma()).isEqualTo(29.0);
    }

    @Test
    void ewmaUsesAlphaPointFiveAndSlopeLooksFiveReadingsAhead() {
        ZoneControl z = new ZoneControl();
        step(z, 26.0, 35, false, AlertLevel.NONE, T0);
        Step s = step(z, 28.0, 35, false, AlertLevel.NONE, T0.plusSeconds(2));
        assertThat(s.ewma()).isCloseTo(27.0, within(1e-12));           // 0.5 * 28 + 0.5 * 26
        assertThat(s.slope()).isCloseTo(1.0, within(1e-12));
        assertThat(s.hot()).isCloseTo(32.0, within(1e-12));            // 27 + 5 * 1
        assertThat(s.raw()).isCloseTo(107.0, within(1e-9));            // 35 + 12 * (32 - 26)
        assertThat(s.desired()).isEqualTo(100);                         // clamped
        Step s3 = step(z, 28.0, 45, false, AlertLevel.NONE, T0.plusSeconds(4));
        assertThat(s3.ewma()).isCloseTo(27.5, within(1e-12));
        assertThat(s3.slope()).isCloseTo(0.5, within(1e-12));
        assertThat(s3.hot()).isCloseTo(30.0, within(1e-12));
    }

    @Test
    void fallingTemperatureLowersTheControlTemperatureThroughNegativeSlope() {
        ZoneControl z = new ZoneControl();
        step(z, 30.0, 80, false, AlertLevel.NONE, T0);
        Step s = step(z, 28.0, 80, false, AlertLevel.NONE, T0.plusSeconds(2));
        assertThat(s.slope()).isCloseTo(-1.0, within(1e-12));
        assertThat(s.hot()).isCloseTo(24.0, within(1e-12));            // 29 - 5
    }

    // ---- floors, boosts, clamping ----

    @Test
    void floorIsThirtyNormallyAndSixtyWhileAnyUnitIsFaulty() {
        Step cold = step(new ZoneControl(), 18.0, 30, false, AlertLevel.NONE, T0);   // raw 35 - 96 < 0
        assertThat(cold.raw()).isLessThan(0);
        assertThat(cold.floor()).isEqualTo(30);
        assertThat(cold.desired()).isEqualTo(30);
        Step faulty = step(new ZoneControl(), 18.0, 60, true, AlertLevel.NONE, T0);
        assertThat(faulty.floor()).isEqualTo(60);
        assertThat(faulty.desired()).isEqualTo(60);
    }

    @Test
    void boostsRaiseTheDesiredOutputOnACoolZone() {
        int[][] table = {{0, 30}, {1, 50}, {2, 70}, {3, 100}};     // NONE, WATCH, WARNING, CRITICAL
        for (int[] row : table) {
            AlertLevel level = AlertLevel.values()[row[0]];
            Step s = step(new ZoneControl(), 20.0, 30, false, level, T0);
            assertThat(s.desired()).as(level.name()).isEqualTo(row[1]);
        }
    }

    @Test
    void theLargestOfRawFloorAndBoostWins() {
        // raw 35 + 12 * 4 = 83 beats a WARNING boost of 70, but not CRITICAL's 100.
        assertThat(step(new ZoneControl(), 30.0, 83, false, AlertLevel.WARNING, T0).desired()).isEqualTo(83);
        assertThat(step(new ZoneControl(), 30.0, 83, false, AlertLevel.CRITICAL, T0).desired()).isEqualTo(100);
    }

    @Test
    void outputIsClampedToZeroToOneHundred() {
        assertThat(step(new ZoneControl(), 80.0, 100, false, AlertLevel.NONE, T0).output()).isEqualTo(100);
        assertThat(step(new ZoneControl(), -50.0, 30, false, AlertLevel.NONE, T0).output()).isEqualTo(30);
        // A previous output outside the range can not escape the clamp either.
        assertThat(step(new ZoneControl(), 26.0, 105, false, AlertLevel.NONE, T0).output()).isBetween(0, 100);
    }

    // ---- slew ----

    @Test
    void outputMovesByAtMostTenPointsPerReadingInBothDirections() {
        Step up = step(new ZoneControl(), 40.0, 40, false, AlertLevel.NONE, T0);
        assertThat(up.desired()).isEqualTo(100);
        assertThat(up.output()).isEqualTo(50);
        Step down = step(new ZoneControl(), 18.0, 90, false, AlertLevel.NONE, T0);
        assertThat(down.desired()).isEqualTo(30);
        assertThat(down.output()).isEqualTo(80);
        Step near = step(new ZoneControl(), 26.0, 38, false, AlertLevel.NONE, T0);
        assertThat(near.output()).isEqualTo(35);                        // within 10 of desired: reaches it
    }

    @Test
    void repeatedReadingsClimbToTheTargetInTenPointSteps() {
        ZoneControl z = new ZoneControl();
        int out = 40;
        int[] seen = new int[6];
        for (int i = 0; i < 6; i++) {
            out = step(z, 40.0, out, false, AlertLevel.NONE, T0.plusSeconds(2L * i)).output();
            seen[i] = out;
        }
        assertThat(seen).containsExactly(50, 60, 70, 80, 90, 100);
    }

    // ---- boost hold ----

    @Test
    void criticalBoostIsHeldSixtySecondsAfterItClears() {
        ZoneControl z = new ZoneControl();
        assertThat(step(z, 20.0, 30, false, AlertLevel.CRITICAL, T0).boost()).isEqualTo(100);
        // Alert gone at T0+2: still boosted until T0+2+60.
        assertThat(step(z, 20.0, 30, false, AlertLevel.NONE, T0.plusSeconds(2)).boost()).isEqualTo(100);
        assertThat(step(z, 20.0, 30, false, AlertLevel.NONE, T0.plusSeconds(61)).boost()).isEqualTo(100);
        assertThat(step(z, 20.0, 30, false, AlertLevel.NONE, T0.plusSeconds(62)).boost()).isZero();   // exactly 60 s
    }

    @Test
    void warningAndWatchBoostsAreHeldTwentySecondsAfterTheyClear() {
        for (AlertLevel level : new AlertLevel[] {AlertLevel.WARNING, AlertLevel.WATCH}) {
            ZoneControl z = new ZoneControl();
            int boost = level == AlertLevel.WARNING ? 70 : 50;
            assertThat(step(z, 20.0, 30, false, level, T0).boost()).isEqualTo(boost);
            assertThat(step(z, 20.0, 30, false, AlertLevel.NONE, T0.plusSeconds(10)).boost()).isEqualTo(boost);
            assertThat(step(z, 20.0, 30, false, AlertLevel.NONE, T0.plusSeconds(29)).boost()).isEqualTo(boost);
            assertThat(step(z, 20.0, 30, false, AlertLevel.NONE, T0.plusSeconds(30)).boost()).isZero();
        }
    }

    @Test
    void aNewAlertDuringTheHoldRestartsItAndEscalationIsImmediate() {
        ZoneControl z = new ZoneControl();
        step(z, 20.0, 30, false, AlertLevel.WARNING, T0);
        step(z, 20.0, 30, false, AlertLevel.NONE, T0.plusSeconds(10));              // hold starts, ends at +30
        assertThat(step(z, 20.0, 30, false, AlertLevel.WARNING, T0.plusSeconds(20)).boost()).isEqualTo(70);
        // The alert is active again: no hold timer runs. It clears at +40, so the hold now ends at +60, not +30.
        assertThat(step(z, 20.0, 30, false, AlertLevel.NONE, T0.plusSeconds(40)).boost()).isEqualTo(70);
        assertThat(step(z, 20.0, 30, false, AlertLevel.NONE, T0.plusSeconds(59)).boost()).isEqualTo(70);
        assertThat(step(z, 20.0, 30, false, AlertLevel.NONE, T0.plusSeconds(60)).boost()).isZero();
        assertThat(step(z, 20.0, 30, false, AlertLevel.CRITICAL, T0.plusSeconds(62)).boost()).isEqualTo(100);
    }

    @Test
    void criticalLoweredToWarningHoldsCriticalThenFallsBackToTheActiveWarning() {
        ZoneControl z = new ZoneControl();
        step(z, 20.0, 30, false, AlertLevel.CRITICAL, T0);
        assertThat(step(z, 20.0, 30, false, AlertLevel.WARNING, T0.plusSeconds(2)).boost()).isEqualTo(100);
        assertThat(step(z, 20.0, 30, false, AlertLevel.WARNING, T0.plusSeconds(61)).boost()).isEqualTo(100);
        Step later = step(z, 20.0, 30, false, AlertLevel.WARNING, T0.plusSeconds(62));
        assertThat(later.boostLevel()).isEqualTo(AlertLevel.WARNING);
        assertThat(later.boost()).isEqualTo(70);
    }

    // ---- fault detector ----

    @Test
    void faultIsConfirmedByTwoConsecutiveReadingsAndRecoveredByThreeOk() {
        FaultDetector f = new FaultDetector(p);
        assertThat(f.observe("OK")).isEqualTo(Transition.NONE);
        assertThat(f.observe("FAULT")).isEqualTo(Transition.NONE);
        assertThat(f.isFaulty()).isFalse();
        assertThat(f.observe("FAULT")).isEqualTo(Transition.CONFIRMED_FAULT);
        assertThat(f.isFaulty()).isTrue();
        assertThat(f.observe("FAULT")).isEqualTo(Transition.NONE);                 // already confirmed
        assertThat(f.observe("OK")).isEqualTo(Transition.NONE);
        assertThat(f.observe("OK")).isEqualTo(Transition.NONE);
        assertThat(f.isFaulty()).isTrue();
        assertThat(f.observe("OK")).isEqualTo(Transition.CONFIRMED_RECOVERY);
        assertThat(f.isFaulty()).isFalse();
    }

    @Test
    void aSingleFaultReadingBetweenOkReadingsNeverConfirms() {
        FaultDetector f = new FaultDetector(p);
        for (int i = 0; i < 10; i++) {
            f.observe("FAULT");
            assertThat(f.isFaulty()).isEqualTo(false);
            f.observe("OK");
        }
    }

    @Test
    void degradedReadingsCountTowardNeitherFaultNorRecovery() {
        FaultDetector f = new FaultDetector(p);
        f.observe("FAULT");
        assertThat(f.observe("DEGRADED")).isEqualTo(Transition.NONE);              // interrupts the FAULT streak
        assertThat(f.observe("FAULT")).isEqualTo(Transition.NONE);
        assertThat(f.isFaulty()).isFalse();
        f.observe("FAULT");
        assertThat(f.isFaulty()).isTrue();

        f.observe("OK");
        f.observe("OK");
        assertThat(f.observe("DEGRADED")).isEqualTo(Transition.NONE);              // interrupts the OK streak
        f.observe("OK");
        f.observe("OK");
        assertThat(f.isFaulty()).isTrue();
        assertThat(f.observe("OK")).isEqualTo(Transition.CONFIRMED_RECOVERY);
    }

    @Test
    void unknownOrMissingHealthIsTreatedAsNeitherFaultNorOk() {
        FaultDetector f = new FaultDetector(p);
        f.observe("FAULT");
        f.observe(null);
        f.observe("FAULT");
        assertThat(f.isFaulty()).isFalse();
    }

    // ---- watchdog and reading gate ----

    @Test
    void watchdogFiresExactlyAtTenSecondsAndOnlyOnce() {
        FailSafeWatchdog w = new FailSafeWatchdog(p, T0);
        assertThat(w.check(T0.plusMillis(9_999))).isFalse();
        assertThat(w.check(T0.plusSeconds(10))).isTrue();
        assertThat(w.isActive()).isTrue();
        assertThat(w.check(T0.plusSeconds(11))).isFalse();                         // not reported twice
        assertThat(w.check(T0.plusSeconds(60))).isFalse();
    }

    @Test
    void aReadingResetsTheTimeout() {
        FailSafeWatchdog w = new FailSafeWatchdog(p, T0);
        w.onReading(T0.plusSeconds(8));
        assertThat(w.check(T0.plusSeconds(17))).isFalse();
        assertThat(w.check(T0.plusMillis(17_999))).isFalse();
        assertThat(w.check(T0.plusSeconds(18))).isTrue();
    }

    @Test
    void threeFreshReadingsAreNeededToLeaveFailsafe() {
        FailSafeWatchdog w = new FailSafeWatchdog(p, T0);
        w.check(T0.plusSeconds(10));
        assertThat(w.onReading(T0.plusSeconds(12))).isFalse();
        assertThat(w.onReading(T0.plusSeconds(14))).isFalse();
        assertThat(w.isActive()).isTrue();
        assertThat(w.freshReadings()).isEqualTo(2);
        assertThat(w.onReading(T0.plusSeconds(16))).isTrue();
        assertThat(w.isActive()).isFalse();
        // A new outage enters FAILSAFE again.
        assertThat(w.check(T0.plusSeconds(26))).isTrue();
    }

    @Test
    void recoveryCountRestartsAfterANewOutage() {
        FailSafeWatchdog w = new FailSafeWatchdog(p, T0);
        w.check(T0.plusSeconds(10));
        w.onReading(T0.plusSeconds(11));
        w.onReading(T0.plusSeconds(12));
        assertThat(w.freshReadings()).isEqualTo(2);
        w.restoreActive(true);
        assertThat(w.freshReadings()).isZero();
    }

    @Test
    void readingGateRejectsDuplicatesOutOfOrderAndStaleReadings() {
        ReadingGate g = new ReadingGate(p);
        Instant now = T0.plusSeconds(100);
        assertThat(g.accept(now.minusSeconds(1), now)).isTrue();
        assertThat(g.accept(now.minusSeconds(1), now)).isFalse();                  // duplicate
        assertThat(g.accept(now.minusSeconds(3), now)).isFalse();                  // out of order
        assertThat(g.accept(now, now)).isTrue();
        assertThat(g.accept(now.plusSeconds(20).minusSeconds(10), now.plusSeconds(20))).isTrue();   // exactly 10 s old
        assertThat(g.accept(now.plusSeconds(11), now.plusSeconds(22))).isFalse();                   // 11 s old: stale
        assertThat(g.accept(now.plusSeconds(21), now.plusSeconds(22))).isTrue();                    // stale one did not block
    }

    // ---- energy ----

    @Test
    void coolingPowerFollowsTheCubeLaw() {
        Map<Integer, Double> table = new LinkedHashMap<>();
        table.put(0, 0.5);
        table.put(50, 1.0);
        table.put(70, 1.872);
        table.put(100, 4.5);
        table.forEach((out, kw) -> assertThat(EnergyModel.coolingKw(out)).as("output " + out)
                .isCloseTo(kw, within(1e-9)));
        assertThat(EnergyModel.coolingKw(-5)).isEqualTo(0.5);
        assertThat(EnergyModel.coolingKw(150)).isEqualTo(4.5);
        assertThat(EnergyModel.baselineKwPerZone()).isCloseTo(1.872, within(1e-9));
    }

    @Test
    void meterIntegratesModelledEnergyAgainstTheSeventyPercentBaseline() {
        EnergyModel.Meter m = new EnergyModel.Meter(T0);
        Map<String, Integer> outputs = new LinkedHashMap<>();
        outputs.put("Z1", 40);
        outputs.put("Z2", 40);
        m.advance(T0.plus(Duration.ofHours(1)), outputs);
        double actual = 2 * EnergyModel.coolingKw(40);
        double baseline = 2 * 1.872;
        assertThat(m.actualKwh()).isCloseTo(actual, within(1e-9));
        assertThat(m.baselineKwh()).isCloseTo(baseline, within(1e-9));
        assertThat(m.savedKwh()).isCloseTo(baseline - actual, within(1e-9));
        // Outputs are piecewise constant: the next hour uses the new outputs only.
        outputs.put("Z1", 100);
        m.advance(T0.plus(Duration.ofHours(2)), outputs);
        assertThat(m.actualKwh()).isCloseTo(actual + EnergyModel.coolingKw(100) + EnergyModel.coolingKw(40),
                within(1e-9));
        // No time passed: nothing changes.
        double before = m.actualKwh();
        m.advance(T0.plus(Duration.ofHours(2)), outputs);
        assertThat(m.actualKwh()).isEqualTo(before);
    }
}
