package com.thermogrid.cooling.control;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Modelled (not measured) cooling power, plan section 6E. Fan power follows the cube law:
 * {@code coolingKw = 0.5 + 4.0 * (output / 100)^3}. The baseline is a static 70 percent policy, 1.872 kW per zone.
 * Every figure here comes from this formula; nothing is a physical measurement.
 */
public final class EnergyModel {

    public static final double IDLE_KW = 0.5;
    public static final double FAN_KW = 4.0;
    public static final int BASELINE_OUTPUT_PCT = 70;

    private EnergyModel() {
    }

    public static double coolingKw(double outputPct) {
        double f = Math.max(0.0, Math.min(100.0, outputPct)) / 100.0;
        return IDLE_KW + FAN_KW * f * f * f;
    }

    public static double baselineKwPerZone() {
        return coolingKw(BASELINE_OUTPUT_PCT);
    }

    /**
     * Integrates modelled power over time. Outputs are treated as piecewise constant between calls, so the result is
     * exact for the commanded outputs.
     */
    public static class Meter {
        private Instant last;
        private double actualKwh;
        private double baselineKwh;

        public Meter(Instant start) {
            this.last = start;
        }

        /** Accounts for the time since the previous call at the given (current) outputs. */
        public synchronized void advance(Instant now, Map<String, Integer> outputs) {
            double hours = Duration.between(last, now).toMillis() / 3_600_000.0;
            if (hours > 0) {
                double kw = 0;
                for (int o : outputs.values()) {
                    kw += coolingKw(o);
                }
                actualKwh += kw * hours;
                baselineKwh += baselineKwPerZone() * outputs.size() * hours;
                last = now;
            }
        }

        public synchronized double actualKwh() { return actualKwh; }
        public synchronized double baselineKwh() { return baselineKwh; }
        public synchronized double savedKwh() { return baselineKwh - actualKwh; }
    }
}
