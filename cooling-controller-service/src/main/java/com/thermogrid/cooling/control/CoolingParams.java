package com.thermogrid.cooling.control;

/**
 * Every constant of the control law (plan section 6E). The pure classes take this record so tests can build it directly;
 * {@link #defaults()} equals the plan and application.yml.
 */
public record CoolingParams(
        double ewmaAlpha,          // 0.5
        double slopeGain,          // 5 readings (10 s) ahead
        double base,               // 35 percent at target
        double gain,               // 12 points per degree C
        int floorNormal,           // 30
        int floorFault,            // 60 while any unit is confirmed FAULT
        int boostWatch,            // 50
        int boostWarning,          // 70
        int boostCritical,         // 100
        long holdCriticalSec,      // 60
        long holdWarningSec,       // 20 (also WATCH)
        int maxStep,               // 10 points per reading
        int minPublishDelta,       // 2 points
        int failsafeOutput,        // 80
        long failsafeAfterSec,     // 10
        int recoveryReadings,      // 3 fresh readings
        int faultConfirmReadings,  // 2 consecutive FAULT readings
        int okConfirmReadings,     // 3 consecutive OK readings
        long staleReadingSec) {    // a reading older than this is not fresh

    public static CoolingParams defaults() {
        return new CoolingParams(0.5, 5.0, 35.0, 12.0, 30, 60, 50, 70, 100, 60, 20, 10, 2, 80, 10, 3, 2, 3, 10);
    }
}
