package com.thermogrid.simulator.physics;

/** Physics constants (plan 6A). Plain values so the model needs no Spring. */
public record PhysicsParams(
        double supplyTempC,
        double alpha,
        double beta,
        double gamma,
        double noiseSigmaC,
        double minCoolingEffectiveness,
        double okFactor,
        double degradedFactor,
        double faultFactor) {

    public double healthFactor(HealthState h) {
        return switch (h) {
            case OK -> okFactor;
            case DEGRADED -> degradedFactor;
            case FAULT -> faultFactor;
        };
    }
}
