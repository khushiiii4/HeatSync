package com.thermogrid.telemetry.risk;

/**
 * Risk classification with hysteresis (plan section 6B). Pure Java.
 *
 * <p>The classified value is {@code max(current temperature, 60 s forecast)}. A higher level applies at once. A lower
 * level needs {@code calmReadings} consecutive readings that are each at least {@code calmMarginC} below the current
 * level's entry threshold. Any other reading (same level, higher level, or lower but not far enough below) resets the
 * calm count.
 */
public final class RiskClassifier {

    /** New level and calm count after one reading. */
    public record Step(RiskLevel level, int calmCount) {
    }

    private final double watchC;
    private final double warningC;
    private final double criticalC;
    private final double calmMarginC;
    private final int calmReadings;

    public RiskClassifier(double watchC, double warningC, double criticalC, double calmMarginC, int calmReadings) {
        if (!(watchC < warningC && warningC < criticalC) || calmMarginC < 0 || calmReadings < 1) {
            throw new IllegalArgumentException("Invalid risk parameters");
        }
        this.watchC = watchC;
        this.warningC = warningC;
        this.criticalC = criticalC;
        this.calmMarginC = calmMarginC;
        this.calmReadings = calmReadings;
    }

    /** Level the value alone maps to: below 30 NORMAL, 30 to below 34 WATCH, 34 to below 38 WARNING, 38 up CRITICAL. */
    public RiskLevel classify(double value) {
        if (value >= criticalC) {
            return RiskLevel.CRITICAL;
        }
        if (value >= warningC) {
            return RiskLevel.WARNING;
        }
        if (value >= watchC) {
            return RiskLevel.WATCH;
        }
        return RiskLevel.NORMAL;
    }

    /** Entry threshold of a level (NORMAL has none). */
    public double threshold(RiskLevel level) {
        return switch (level) {
            case WATCH -> watchC;
            case WARNING -> warningC;
            case CRITICAL -> criticalC;
            case NORMAL -> Double.NEGATIVE_INFINITY;
        };
    }

    /** The value below which a lowering reading counts as calm for the given current level. */
    public double calmBelow(RiskLevel current) {
        return threshold(current) - calmMarginC;
    }

    public Step next(RiskLevel current, int calmCount, double value) {
        RiskLevel target = classify(value);
        if (target.compareTo(current) > 0) {
            return new Step(target, 0);
        }
        if (target == current || current == RiskLevel.NORMAL) {
            return new Step(current, 0);
        }
        if (value < calmBelow(current)) {
            int calm = calmCount + 1;
            if (calm >= calmReadings) {
                return new Step(target, 0);
            }
            return new Step(current, calm);
        }
        return new Step(current, 0);
    }
}
