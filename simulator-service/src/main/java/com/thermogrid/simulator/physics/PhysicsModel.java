package com.thermogrid.simulator.physics;

import java.util.function.DoubleSupplier;

/**
 * First-order thermal model of plan section 6A. No Spring, no state.
 *
 * <pre>
 * T_next = T + alpha*P - beta*f*(T - Ts) + gamma*(neighbourMean - T) + noise
 * P = idleKw + kwPerCpuUnit*cpuUnits + heatSpikeKw
 * f = max(floor, coolingOutputPct/100 * healthFactor)
 * </pre>
 */
public final class PhysicsModel {

    private final PhysicsParams params;
    private final DoubleSupplier standardNormal;

    /**
     * @param standardNormal source of N(0,1) samples, scaled by sigma here. Inject a fixed or seeded source in tests.
     */
    public PhysicsModel(PhysicsParams params, DoubleSupplier standardNormal) {
        this.params = params;
        this.standardNormal = standardNormal;
    }

    public PhysicsParams params() {
        return params;
    }

    public double kwPerCpuUnit(RackSpec rack) {
        return (rack.maxPowerKw() - rack.idlePowerKw()) / rack.cpuCapacity();
    }

    public double powerKw(RackSpec rack, double cpuUnits, double heatSpikeKw) {
        return rack.idlePowerKw() + kwPerCpuUnit(rack) * cpuUnits + heatSpikeKw;
    }

    public double effectiveness(double coolingOutputPct, HealthState health) {
        return effectiveness(coolingOutputPct, health, null);
    }

    /** Health factor in force: the override (remaining efficiency of a COOLING_DEGRADED failure) if present. */
    public double healthFactor(HealthState health, Double override) {
        return override != null ? override : params.healthFactor(health);
    }

    public double effectiveness(double coolingOutputPct, HealthState health, Double healthFactorOverride) {
        return Math.max(params.minCoolingEffectiveness(),
                coolingOutputPct / 100.0 * healthFactor(health, healthFactorOverride));
    }

    /**
     * @param neighbourMeanC mean of the previous-tick temperatures of the existing neighbours; pass the rack's own
     *                       temperature when it has none (the coupling term then vanishes)
     */
    public double nextTemperature(double tempC, double powerKw, double effectiveness, double neighbourMeanC) {
        double noise = params.noiseSigmaC() == 0.0 ? 0.0 : standardNormal.getAsDouble() * params.noiseSigmaC();
        return tempC
                + params.alpha() * powerKw
                - params.beta() * effectiveness * (tempC - params.supplyTempC())
                + params.gamma() * (neighbourMeanC - tempC)
                + noise;
    }
}
