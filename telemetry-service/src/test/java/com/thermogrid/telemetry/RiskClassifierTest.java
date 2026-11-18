package com.thermogrid.telemetry;

import static com.thermogrid.telemetry.risk.RiskLevel.CRITICAL;
import static com.thermogrid.telemetry.risk.RiskLevel.NORMAL;
import static com.thermogrid.telemetry.risk.RiskLevel.WARNING;
import static com.thermogrid.telemetry.risk.RiskLevel.WATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thermogrid.telemetry.risk.RiskClassifier;
import com.thermogrid.telemetry.risk.RiskClassifier.Step;
import com.thermogrid.telemetry.risk.RiskLevel;
import org.junit.jupiter.api.Test;

class RiskClassifierTest {

    private final RiskClassifier c = new RiskClassifier(30.0, 34.0, 38.0, 2.0, 5);

    /** Feeds values one by one and returns the final step. */
    private Step feed(RiskLevel start, int calm, double... values) {
        Step s = new Step(start, calm);
        for (double v : values) {
            s = c.next(s.level(), s.calmCount(), v);
        }
        return s;
    }

    // ---- boundaries of classify() ----

    @Test
    void thresholdsAreInclusiveOnTheLowerEdge() {
        assertThat(c.classify(-5)).isEqualTo(NORMAL);
        assertThat(c.classify(29.99)).isEqualTo(NORMAL);
        assertThat(c.classify(30.0)).isEqualTo(WATCH);
        assertThat(c.classify(33.99)).isEqualTo(WATCH);
        assertThat(c.classify(34.0)).isEqualTo(WARNING);
        assertThat(c.classify(37.99)).isEqualTo(WARNING);
        assertThat(c.classify(38.0)).isEqualTo(CRITICAL);
        assertThat(c.classify(80.0)).isEqualTo(CRITICAL);
    }

    // ---- rising is immediate ----

    @Test
    void higherRiskTakesEffectImmediatelyAndSkipsLevels() {
        assertThat(c.next(NORMAL, 0, 30.0)).isEqualTo(new Step(WATCH, 0));
        assertThat(c.next(WATCH, 3, 34.0)).isEqualTo(new Step(WARNING, 0));
        assertThat(c.next(WARNING, 0, 38.0)).isEqualTo(new Step(CRITICAL, 0));
        assertThat(c.next(NORMAL, 0, 45.0)).isEqualTo(new Step(CRITICAL, 0));
    }

    @Test
    void equalRiskResetsTheCalmCount() {
        assertThat(c.next(WARNING, 4, 35.0)).isEqualTo(new Step(WARNING, 0));
        assertThat(c.next(NORMAL, 0, 12.0)).isEqualTo(new Step(NORMAL, 0));
    }

    // ---- hysteresis: the three transitions from the plan ----

    @Test
    void criticalFallsToWarningOnlyAfterFiveReadingsBelow36() {
        assertThat(feed(CRITICAL, 0, 35.9, 35.9, 35.9, 35.9).level()).isEqualTo(CRITICAL);
        assertThat(feed(CRITICAL, 0, 35.9, 35.9, 35.9, 35.9).calmCount()).isEqualTo(4);
        assertThat(feed(CRITICAL, 0, 35.9, 35.9, 35.9, 35.9, 35.9)).isEqualTo(new Step(WARNING, 0));
    }

    @Test
    void warningFallsToWatchOnlyAfterFiveReadingsBelow32() {
        assertThat(feed(WARNING, 0, 31.9, 31.9, 31.9, 31.9).level()).isEqualTo(WARNING);
        assertThat(feed(WARNING, 0, 31.9, 31.9, 31.9, 31.9, 31.9)).isEqualTo(new Step(WATCH, 0));
    }

    @Test
    void watchFallsToNormalOnlyAfterFiveReadingsBelow28() {
        assertThat(feed(WATCH, 0, 27.9, 27.9, 27.9, 27.9).level()).isEqualTo(WATCH);
        assertThat(feed(WATCH, 0, 27.9, 27.9, 27.9, 27.9, 27.9)).isEqualTo(new Step(NORMAL, 0));
    }

    @Test
    void exactlyOnTheCalmLimitIsNotCalm() {
        // "below 36" is strict: 36.0 resets the count
        assertThat(c.calmBelow(CRITICAL)).isEqualTo(36.0);
        assertThat(c.calmBelow(WARNING)).isEqualTo(32.0);
        assertThat(c.calmBelow(WATCH)).isEqualTo(28.0);
        assertThat(feed(CRITICAL, 4, 36.0)).isEqualTo(new Step(CRITICAL, 0));
        assertThat(feed(WARNING, 4, 32.0)).isEqualTo(new Step(WARNING, 0));
        assertThat(feed(WATCH, 4, 28.0)).isEqualTo(new Step(WATCH, 0));
    }

    @Test
    void aNonCalmReadingBreaksTheRun() {
        // four calm readings, one in the 36..38 band, then four more: still CRITICAL
        Step s = feed(CRITICAL, 0, 35, 35, 35, 35, 37.0, 35, 35, 35, 35);
        assertThat(s).isEqualTo(new Step(CRITICAL, 4));
        assertThat(feed(CRITICAL, 0, 35, 35, 35, 35, 37.0, 35, 35, 35, 35, 35).level()).isEqualTo(WARNING);
    }

    @Test
    void aSpikeBackUpResetsTheRunAndKeepsTheHigherLevel() {
        Step s = feed(WARNING, 0, 31, 31, 31, 31, 34.5);
        assertThat(s).isEqualTo(new Step(WARNING, 0));
        assertThat(feed(WARNING, 0, 31, 31, 31, 31, 38.5)).isEqualTo(new Step(CRITICAL, 0));
    }

    @Test
    void calmCountIsResetAfterATransition() {
        Step s = feed(CRITICAL, 0, 35, 35, 35, 35, 35);
        assertThat(s).isEqualTo(new Step(WARNING, 0));
        // WARNING needs a fresh run of five below 32 (35 is not calm for WARNING)
        assertThat(feed(WARNING, 0, 35, 35, 35, 35, 35)).isEqualTo(new Step(WARNING, 0));
    }

    @Test
    void aLargeDropMovesStraightToTheLevelOfTheValue() {
        assertThat(feed(CRITICAL, 0, 20, 20, 20, 20, 20)).isEqualTo(new Step(NORMAL, 0));
        assertThat(feed(WARNING, 0, 29, 29, 29, 29, 29)).isEqualTo(new Step(NORMAL, 0));
        assertThat(feed(CRITICAL, 0, 31, 31, 31, 31, 31)).isEqualTo(new Step(WATCH, 0));
    }

    @Test
    void thresholdLookupAndInvalidConfiguration() {
        assertThat(c.threshold(WATCH)).isEqualTo(30.0);
        assertThat(c.threshold(WARNING)).isEqualTo(34.0);
        assertThat(c.threshold(CRITICAL)).isEqualTo(38.0);
        assertThatThrownBy(() -> new RiskClassifier(34, 30, 38, 2, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskClassifier(30, 34, 38, -1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskClassifier(30, 34, 38, 2, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
