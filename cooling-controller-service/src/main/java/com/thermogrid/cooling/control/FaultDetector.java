package com.thermogrid.cooling.control;

/**
 * Debounces a unit's reported health (plan 6E): FAULT is confirmed after two consecutive FAULT readings and cleared
 * after three consecutive OK readings. Any other value, DEGRADED included, interrupts both streaks and counts toward
 * neither: a half-working unit is neither a confirmed fault nor evidence of recovery.
 */
public class FaultDetector {

    public enum Transition {
        NONE, CONFIRMED_FAULT, CONFIRMED_RECOVERY
    }

    private final int faultNeeded;
    private final int okNeeded;
    private int faultStreak;
    private int okStreak;
    private boolean faulty;

    public FaultDetector(CoolingParams p) {
        this(p.faultConfirmReadings(), p.okConfirmReadings());
    }

    public FaultDetector(int faultNeeded, int okNeeded) {
        this.faultNeeded = faultNeeded;
        this.okNeeded = okNeeded;
    }

    public Transition observe(String unitHealth) {
        boolean isFault = "FAULT".equals(unitHealth);
        boolean isOk = "OK".equals(unitHealth);
        faultStreak = isFault ? faultStreak + 1 : 0;
        okStreak = isOk ? okStreak + 1 : 0;
        if (!faulty && faultStreak >= faultNeeded) {
            faulty = true;
            return Transition.CONFIRMED_FAULT;
        }
        if (faulty && okStreak >= okNeeded) {
            faulty = false;
            return Transition.CONFIRMED_RECOVERY;
        }
        return Transition.NONE;
    }

    /** Restores a persisted confirmed state at startup. */
    public void restore(boolean confirmedFault) {
        this.faulty = confirmedFault;
        this.faultStreak = 0;
        this.okStreak = 0;
    }

    public boolean isFaulty() {
        return faulty;
    }

    public int faultStreak() { return faultStreak; }
    public int okStreak() { return okStreak; }
}
