package com.thermogrid.scheduler.config;

/**
 * Every constant of the placement and migration algorithms (plan sections 6C and 6D). The pure engine classes take this
 * record, so tests can construct it directly; {@link #defaults()} equals the plan's values and the values in
 * application.yml.
 */
public record Tuning(
        double capacityCap,          // 0.85: utilisation after placement
        double holdCapacityCap,      // 0.70: same cap in HOLD mode
        double heatPerCpu,           // 0.08 C per CPU unit
        double maxAfterC,            // 32.0: forecast plus job heat must stay below
        double tempRefC,             // 20.0 and span 20.0 in (Tafter - 20) / 20
        double tempSpanC,
        double wTemp,                // 0.6
        double wUtil,                // 0.3
        double wZone,                // 0.1
        double tieGap,               // 0.02
        double shedRefC,             // 32.0
        double shedRefCriticalC,     // 30.0
        double minShedCpu,           // 10
        int maxMoves,                // 2
        int maxMovesUrgent,          // 4
        long workloadCooldownSec,    // 120
        long sourceCooldownSec,      // 20
        long sourceCooldownCriticalSec, // 10
        long quarantineSec,          // 60: a source or alert rack is not a destination
        long failureBackoffSec,      // 10: no new attempt for a workload whose move just failed
        double rebalanceStdDevPct) { // 15 percentage points

    public static Tuning defaults() {
        return new Tuning(0.85, 0.70, 0.08, 32.0, 20.0, 20.0, 0.6, 0.3, 0.1, 0.02, 32.0, 30.0, 10.0, 2, 4, 120, 20,
                10, 60, 10, 15.0);
    }
}
