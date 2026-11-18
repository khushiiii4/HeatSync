package com.thermogrid.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.thermogrid.telemetry.forecast.ForecastEngine;
import com.thermogrid.telemetry.forecast.ForecastEngine.Result;
import com.thermogrid.telemetry.forecast.ForecastEngine.State;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Damped Holt with the plan constants a=0.4, b=0.15, phi=0.95, horizon 30. Expected values were computed independently. */
class ForecastEngineTest {

    private final ForecastEngine engine = new ForecastEngine(0.4, 0.15, 0.95, 30);

    private Result run(State start, double... ys) {
        Result r = null;
        State s = start;
        for (double y : ys) {
            r = engine.update(s, y);
            s = r.state();
        }
        return r;
    }

    @Test
    void dampedHorizonSumIsAboutFourteenPointNine() {
        assertThat(engine.dampedHorizonSum()).isCloseTo(14.921863485, within(1e-9));
        assertThat(engine.horizonTicks()).isEqualTo(30);
        double manual = 0;
        for (int i = 1; i <= 30; i++) {
            manual += Math.pow(0.95, i);
        }
        assertThat(engine.dampedHorizonSum()).isCloseTo(manual, within(1e-12));
    }

    @Test
    void firstReadingSetsLevelToTheReadingAndTrendToZero() {
        Result r = engine.update(null, 27.3);
        assertThat(r.state().level()).isEqualTo(27.3);
        assertThat(r.state().trend()).isZero();
        assertThat(r.forecast()).isEqualTo(27.3);
    }

    @Test
    void equationsMatchByHandForTheSecondReading() {
        // L = 0.4*26.3 + 0.6*(26.0 + 0.95*0) = 26.12 ; B = 0.15*(26.12-26.0) + 0.85*0.95*0 = 0.018
        Result r = engine.update(new State(26.0, 0.0), 26.3);
        assertThat(r.state().level()).isCloseTo(26.12, within(1e-12));
        assertThat(r.state().trend()).isCloseTo(0.018, within(1e-12));
        assertThat(r.forecast()).isCloseTo(26.12 + 14.921863485084177 * 0.018, within(1e-9));
    }

    @Test
    void constantSeriesStaysConstantWithNoTrend() {
        Result r = run(null, 26, 26, 26, 26, 26);
        assertThat(r.state().level()).isCloseTo(26.0, within(1e-12));
        assertThat(r.state().trend()).isCloseTo(0.0, within(1e-12));
        assertThat(r.forecast()).isCloseTo(26.0, within(1e-12));
    }

    @Test
    void fixedRampSeriesGivesTheExpectedLevelTrendAndForecast() {
        double[] ramp = {26.0, 26.3, 26.6, 26.9, 27.2, 27.5};
        List<double[]> expected = List.of(
                new double[] {26.0, 0.0, 26.0},
                new double[] {26.12, 0.018, 26.388594},
                new double[] {26.32226, 0.044874, 26.991864},
                new double[] {26.578934, 0.074737, 27.694148},
                new double[] {26.869961, 0.104004, 28.421894},
                new double[] {27.181259, 0.130678, 29.131217});
        State s = null;
        for (int i = 0; i < ramp.length; i++) {
            Result r = engine.update(s, ramp[i]);
            s = r.state();
            assertThat(r.state().level()).as("level " + i).isCloseTo(expected.get(i)[0], within(1e-5));
            assertThat(r.state().trend()).as("trend " + i).isCloseTo(expected.get(i)[1], within(1e-5));
            assertThat(r.forecast()).as("forecast " + i).isCloseTo(expected.get(i)[2], within(1e-5));
        }
    }

    @Test
    void stepSeriesGivesTheExpectedForecastAfterTheJump() {
        Result r = run(null, 26, 26, 26, 30);
        assertThat(r.state().level()).isCloseTo(27.6, within(1e-9));
        assertThat(r.state().trend()).isCloseTo(0.24, within(1e-9));
        assertThat(r.forecast()).isCloseTo(31.181247, within(1e-5));
        Result later = run(r.state(), 30, 30);
        assertThat(later.forecast()).isCloseTo(35.363787, within(1e-5));
    }

    @Test
    void trendIsDampedSoAFallingSeriesForecastsBelowTheLevelButBounded() {
        Result r = run(null, 30, 29.5, 29, 28.5, 28, 27.5);
        assertThat(r.state().trend()).isNegative();
        assertThat(r.forecast()).isLessThan(r.state().level());
        // Damping: the forecast offset is the damped sum times the trend, far below a 30-tick undamped extrapolation.
        assertThat(Math.abs(r.forecast() - r.state().level())).isLessThan(30 * Math.abs(r.state().trend()));
    }

    @Test
    void steadyRampTrendConvergesToBetaTimesRateOverOneMinusDampedRetention() {
        // For a ramp of r per tick the damped trend settles at b*r / (1 - (1-b)*phi) = 0.779 r.
        State s = null;
        for (int i = 0; i < 400; i++) {
            s = engine.update(s, 20.0 + 0.3 * i).state();
        }
        assertThat(s.trend()).isCloseTo(0.15 * 0.3 / (1 - 0.85 * 0.95), within(1e-6));
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new ForecastEngine(0, 0.15, 0.95, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ForecastEngine(0.4, 1.5, 0.95, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ForecastEngine(0.4, 0.15, 0, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ForecastEngine(0.4, 0.15, 0.95, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
