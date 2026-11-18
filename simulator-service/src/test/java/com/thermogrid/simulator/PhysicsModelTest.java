package com.thermogrid.simulator;

import static com.thermogrid.simulator.TestTopology.PLAN;
import static com.thermogrid.simulator.TestTopology.PLAN_NO_NOISE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.thermogrid.simulator.physics.HealthState;
import com.thermogrid.simulator.physics.PhysicsModel;
import com.thermogrid.simulator.physics.RackSpec;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PhysicsModelTest {

    private static final RackSpec RACK = new RackSpec("R23", "Z2", 2, 3, 100, 2.0, 10.0, true);
    private final PhysicsModel quiet = new PhysicsModel(PLAN_NO_NOISE, () -> 0.0);

    @Test
    void powerUsesIdlePlusPerUnitSlopePlusSpike() {
        assertThat(quiet.kwPerCpuUnit(RACK)).isCloseTo(0.08, within(1e-12));
        assertThat(quiet.powerKw(RACK, 50, 0)).isCloseTo(6.0, within(1e-12));
        assertThat(quiet.powerKw(RACK, 60, 1.5)).isCloseTo(8.3, within(1e-12));
        assertThat(quiet.powerKw(RACK, 0, 0)).isCloseTo(2.0, within(1e-12));
    }

    @Test
    void heatingAndCoolingTermsMatchFormula() {
        // T=20, P=6, f=0.37, neighbours equal to T: 20 + 0.06*6 - 0.12*0.37*(20-18)
        double expected = 20 + 0.36 - 0.0888;
        assertThat(quiet.nextTemperature(20.0, 6.0, 0.37, 20.0)).isCloseTo(expected, within(1e-12));
    }

    @Test
    void heatingTermAloneWhenNoCoolingAndNoCoupling() {
        assertThat(quiet.nextTemperature(18.0, 5.0, 0.0, 18.0)).isCloseTo(18.3, within(1e-12));
    }

    @Test
    void couplingTermPullsTowardsNeighbourMean() {
        // P=0 and f=0 isolate the gamma term: 0.05 * (22 - 20)
        assertThat(quiet.nextTemperature(20.0, 0.0, 0.0, 22.0)).isCloseTo(20.1, within(1e-12));
        assertThat(quiet.nextTemperature(20.0, 0.0, 0.0, 18.0)).isCloseTo(19.9, within(1e-12));
    }

    @Test
    void healthFactorsAreOneQuarterAndZero() {
        assertThat(PLAN.healthFactor(HealthState.OK)).isEqualTo(1.0);
        assertThat(PLAN.healthFactor(HealthState.DEGRADED)).isEqualTo(0.25);
        assertThat(PLAN.healthFactor(HealthState.FAULT)).isEqualTo(0.0);
        assertThat(quiet.effectiveness(80, HealthState.OK)).isCloseTo(0.80, within(1e-12));
        assertThat(quiet.effectiveness(80, HealthState.DEGRADED)).isCloseTo(0.20, within(1e-12));
        assertThat(quiet.effectiveness(100, HealthState.DEGRADED)).isCloseTo(0.25, within(1e-12));
    }

    @Test
    void effectivenessFloorIsTenPercent() {
        assertThat(quiet.effectiveness(100, HealthState.FAULT)).isEqualTo(0.10);
        assertThat(quiet.effectiveness(0, HealthState.OK)).isEqualTo(0.10);
        assertThat(quiet.effectiveness(5, HealthState.OK)).isEqualTo(0.10);
        assertThat(quiet.effectiveness(30, HealthState.DEGRADED)).isEqualTo(0.10);
        assertThat(quiet.effectiveness(20, HealthState.OK)).isCloseTo(0.20, within(1e-12));
    }

    @Test
    void noiseIsScaledBySigmaAndDisabledWhenSigmaIsZero() {
        PhysicsModel noisy = new PhysicsModel(PLAN, () -> 2.0);
        double base = quiet.nextTemperature(20.0, 6.0, 0.37, 20.0);
        assertThat(noisy.nextTemperature(20.0, 6.0, 0.37, 20.0)).isCloseTo(base + 2.0 * 0.05, within(1e-12));
        PhysicsModel ignored = new PhysicsModel(PLAN_NO_NOISE, () -> 99.0);
        assertThat(ignored.nextTemperature(20.0, 6.0, 0.37, 20.0)).isCloseTo(base, within(1e-12));
    }

    @Test
    void seededNoiseIsReproducible() {
        Random a = new Random(42);
        Random b = new Random(42);
        PhysicsModel m1 = new PhysicsModel(PLAN, a::nextGaussian);
        PhysicsModel m2 = new PhysicsModel(PLAN, b::nextGaussian);
        double t1 = 26.0;
        double t2 = 26.0;
        for (int i = 0; i < 20; i++) {
            t1 = m1.nextTemperature(t1, 6.8, 0.4, t1);
            t2 = m2.nextTemperature(t2, 6.8, 0.4, t2);
        }
        assertThat(t1).isEqualTo(t2);
    }

    /** Plan 6A: 6 kW rack (50 units), output 37 percent, no coupling settles at T = Ts + alpha*P/(beta*f). */
    @Test
    void sixKilowattRackAtThirtySevenPercentSettlesAtSteadyState() {
        double p = quiet.powerKw(RACK, 50, 0);
        double f = quiet.effectiveness(37, HealthState.OK);
        double steady = 18.0 + 0.06 * p / (0.12 * f);
        double t = 26.0;
        for (int i = 0; i < 200; i++) {
            t = quiet.nextTemperature(t, p, f, t);
        }
        assertThat(steady).isCloseTo(26.1, within(0.05));
        assertThat(t).isCloseTo(steady, within(0.5));
    }

    @Test
    void sixKilowattRackSettlesWithinHalfADegreeEvenWithSeededNoise() {
        Random r = new Random(7);
        PhysicsModel noisy = new PhysicsModel(PLAN, r::nextGaussian);
        double p = noisy.powerKw(RACK, 50, 0);
        double f = noisy.effectiveness(37, HealthState.OK);
        double steady = 18.0 + 0.06 * p / (0.12 * f);
        double t = 26.0;
        for (int i = 0; i < 200; i++) {
            t = noisy.nextTemperature(t, p, f, t);
        }
        assertThat(t).isCloseTo(steady, within(0.5));
    }

    /** Plan 6A: a faulted cooling unit leaves only the 10 percent floor, so a 6 kW rack climbs fast. */
    @Test
    void faultedZoneWithSixKilowattRackRisesMoreThanTwoTenthsPerTick() {
        double p = quiet.powerKw(RACK, 50, 0);
        double f = quiet.effectiveness(100, HealthState.FAULT);
        assertThat(f).isEqualTo(0.10);
        double t0 = 26.0;
        double t1 = quiet.nextTemperature(t0, p, f, t0);
        assertThat(t1 - t0).isCloseTo(0.264, within(1e-9)).isGreaterThan(0.2);
        double t2 = quiet.nextTemperature(t1, p, f, t1);
        assertThat(t2 - t1).isGreaterThan(0.2);
    }
}
