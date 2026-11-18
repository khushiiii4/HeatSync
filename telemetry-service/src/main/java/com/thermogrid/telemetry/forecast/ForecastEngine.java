package com.thermogrid.telemetry.forecast;

/**
 * Damped Holt exponential smoothing (plan section 6B). Pure Java: no Spring, no state, no clock.
 *
 * <pre>
 * L_t = a*y_t + (1-a)*(L_(t-1) + phi*B_(t-1))
 * B_t = b*(L_t - L_(t-1)) + (1-b)*phi*B_(t-1)
 * forecast = L_t + (sum of phi^i, i = 1..horizon) * B_t
 * </pre>
 *
 * The first reading sets L = y and B = 0.
 */
public final class ForecastEngine {

    /** Smoothed level and trend (trend is per reading, not per minute). */
    public record State(double level, double trend) {
    }

    /** New state plus the forecast {@code horizonTicks} readings ahead. */
    public record Result(State state, double forecast) {
    }

    private final double alpha;
    private final double beta;
    private final double phi;
    private final int horizonTicks;
    private final double dampedHorizonSum;

    public ForecastEngine(double alpha, double beta, double phi, int horizonTicks) {
        if (alpha <= 0 || alpha > 1 || beta <= 0 || beta > 1 || phi <= 0 || phi > 1 || horizonTicks < 1) {
            throw new IllegalArgumentException("Invalid forecast parameters");
        }
        this.alpha = alpha;
        this.beta = beta;
        this.phi = phi;
        this.horizonTicks = horizonTicks;
        double sum = 0.0;
        double power = 1.0;
        for (int i = 1; i <= horizonTicks; i++) {
            power *= phi;
            sum += power;
        }
        this.dampedHorizonSum = sum;
    }

    /** Sum of phi^i for i = 1..horizon (about 14.9 for phi 0.95 and 30 ticks). */
    public double dampedHorizonSum() {
        return dampedHorizonSum;
    }

    public int horizonTicks() {
        return horizonTicks;
    }

    /** @param previous null for the first reading of a rack */
    public Result update(State previous, double reading) {
        State next;
        if (previous == null) {
            next = new State(reading, 0.0);
        } else {
            double level = alpha * reading + (1 - alpha) * (previous.level() + phi * previous.trend());
            double trend = beta * (level - previous.level()) + (1 - beta) * phi * previous.trend();
            next = new State(level, trend);
        }
        return new Result(next, next.level() + dampedHorizonSum * next.trend());
    }
}
