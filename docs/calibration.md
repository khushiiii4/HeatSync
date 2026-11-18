# Calibration notes

Source of truth: master plan section 6 and Phase 3. Everything here was measured on 2026-11-18 against the code in this repository. **No constant was changed in Phase 3.** The forecast constants are the ones fixed by the Phase 3 brief, and the simulator physics constants are untouched since Bundle 2.2.

## 1. Constants

| Item | Value | Where |
|---|---|---|
| Level smoothing `a` | 0.4 | `thermogrid.telemetry.forecast.alpha` |
| Trend smoothing `b` | 0.15 | `...forecast.beta` |
| Damping `phi` | 0.95 | `...forecast.phi` |
| Horizon | 30 readings (60 s at the 2 s tick) | `...forecast.horizon-ticks` |
| Damped horizon sum, sum of phi^i for i = 1..30 | 14.9219 | computed |
| Trend unit conversion | 30 readings per minute | `...forecast.ticks-per-minute` (API `trendCPerMin` = trend x 30) |
| WATCH / WARNING / CRITICAL entry | 30 / 34 / 38 C | `thermogrid.telemetry.risk.*` |
| Lowering margin and count | 2.0 C below the current level's threshold, 5 consecutive readings | `...risk.calm-margin-c`, `...risk.calm-readings` |
| Stale after | 10 s of silence | `...stale.after-sec` |

Simulator physics (unchanged): Ts 18.0, alpha 0.06, beta 0.12, gamma 0.05, sigma 0.05, tick 2 s, cooling effectiveness floor 0.10.

## 2. Equations

```
first reading:   L = y,  B = 0
every newer one: L_t = a*y_t + (1-a)*(L_(t-1) + phi*B_(t-1))
                 B_t = b*(L_t - L_(t-1)) + (1-b)*phi*B_(t-1)
forecast_60s     = L_t + (sum of phi^i, i = 1..30) * B_t
value            = max(current temperature, forecast_60s)
```

Risk from `value`: NORMAL below 30, WATCH 30 to below 34, WARNING 34 to below 38, CRITICAL 38 and above. A higher level applies at once. A lower level needs five consecutive readings whose `value` is below 36 (from CRITICAL), 32 (from WARNING) or 28 (from WATCH). Any reading that is not that far below resets the count. On the fifth, the level becomes the level of that value (see decision D-028). A reading older than the newest applied one is stored but changes nothing. Racks with no reading for 10 s become STALE and their forecast state is frozen.

## 3. Fixed-series results

These are asserted in `ForecastEngineTest` (values computed independently of the code).

| Series (readings) | After the last reading: level, trend per reading, forecast |
|---|---|
| 26.0, 26.3, 26.6, 26.9, 27.2, 27.5 (ramp 0.3 per reading) | level 27.1813, trend 0.1307, forecast 29.1312 |
| 26, 26, 26, 30 (step up 4 C) | level 27.6000, trend 0.2400, forecast 31.1812 |
| 26, 26, 26, 30, 30, 30 | level 29.4223, trend 0.3982, forecast 35.3638 |
| 26 repeated | level 26, trend 0, forecast 26 |

Steady-state behaviour for a long ramp of `r` per reading (measured by running the engine 400 readings):

| Ramp r (C per reading) | Settled trend B | `trendCPerMin` | forecast minus current temperature |
|---|---|---|---|
| 0.1 | 0.0779 | 2.34 | +1.12 |
| 0.2 | 0.1558 | 4.68 | +2.25 |
| 0.3 | 0.2338 | 7.01 | +3.37 |
| 0.4 | 0.3117 | 9.35 | +4.50 |

The trend settles at `b*r / (1 - (1-b)*phi)`, which is 0.779 r.

## 4. Plan estimates against measurement

**Plan 6B example:** "a rack at 29.5 C rising 0.3 C per tick forecasts about 34 C". With these constants a steady 0.3 per reading ramp forecasts 3.37 C above the current temperature, so about 32.9 C. A forecast 4.5 C above needs a ramp of 0.4 per reading. The plan's figure is optimistic by roughly 1 C for that ramp. Early in a ramp, while it is still accelerating, the forecast overshoots the settled offset (a ramp starting at 29.5 gives +2.95 at the 10th reading, 32.5 observed and 35.45 forecast), which is what makes early warning work.

**Plan 6A, "6 kW rack, cooling FAULT"**, and the other physics estimates, were already checked in Bundle 2.2 and are not repeated here.

## 5. Scenario 2 and 3 (degrade Z2, prediction), live run

Setup: Eureka, topology-service, telemetry-service and the simulator running with real MySQL and RabbitMQ. Simulator RESET, then `COOLING_DEGRADED` on Z2 with `param` 0.25, no duration (efficiency 0.25, which with 40 percent output and the 0.10 floor leaves effectiveness 0.10). Z2 racks carry 60 CPU units (6.8 kW). Samples were taken every 2 s from `GET /api/telemetry/racks/latest` (raw data kept outside the repository).

Measured:

| Observation | Plan expectation | Measured |
|---|---|---|
| Temperature rise per tick right after the failure | about 0.2 C per tick at first | 0.17 to 0.35 C per tick on R23 (noise sigma 0.05 and neighbour coupling included) |
| Z2 racks shift from green to amber | over 1 to 2 minutes | 26.2 C to 32.0 C in 28 ticks (56 s) after injection |
| First alert | not specified | WATCH on all four Z2 racks, basis PREDICTED, 7 to 8 ticks (about 15 s) after injection, at 28.0 to 28.2 C with a forecast of 30.2 to 30.6 |
| **WARNING with basis PREDICTED** (scenario 3) | forecast crosses 34 C before any rack reaches it | R23: WARNING at tick 34 with temperature **32.04 C** and forecast **34.01 C**. R22 and R24 followed. Event `thermal.alert.raised` WATCH to WARNING, basis PREDICTED, seen on the broker |
| Lead time of the prediction | about 30 s | the first Z2 rack observed at 34.0 C was R22 at tick 59, so the WARNING led it by **25 ticks (50 s)** |
| CRITICAL | not expected in this run | not reached (peak 34.0 C) |
| Forecast accuracy while the failure lasted | n/a | forecast 30 readings earlier was 0.9 to 2.2 C below the observed temperature, so the forecast is conservative, not overshooting |

Recovery after clearing the failure (hysteresis check):

| Time after clearing | Observation |
|---|---|
| 13 s | temperatures falling from 34 C; R21 and R24 still WATCH, R22 and R23 WARNING (forecast below 28 but five calm readings not yet seen) |
| 17 s | all four at WATCH or lower, three `lowered` events (WARNING to WATCH), basis OBSERVED |
| 51 to 57 s | all four NORMAL, four `cleared` events (WATCH to NORMAL), 0 active alerts |

In total 14 alert events were published for the four racks: 7 raised, 3 lowered and 4 cleared, every one with the contract fields and producer `TELEMETRY-SERVICE`, version 1.

## 6. Timing and sampling diagnosis

- **Reading interval.** The simulator publishes every 2 s. All 16 racks arrive within about 20 ms of each other, and the forecast horizon of 30 readings is therefore 60 s only while no reading is lost. Lost or delayed readings stretch the horizon in time, not in readings. The stale watchdog bounds this to 10 s.
- **Timestamp precision.** Readings carry nanoseconds and are stored at millisecond precision (`DATETIME(3)`). Duplicate detection is done on the truncated value, otherwise MySQL rounding could turn a duplicate into a new row.
- **Stale detection.** A silent rack turned STALE 11.8 s after the simulator stopped (10 s limit plus the 2 s check interval), and all 16 were stale after 18 s. While stale, 16 of 16 records were identical 8 s apart, so nothing is fabricated or drifting. The first accepted reading after restart restored OK.
- **Ordering.** Out-of-order readings are stored for history and never move the forecast state. The history endpoint replays readings in time order, so its forecast line can differ from the stored live forecast only by the warm-up (60 readings), and the two agree to the cent in the tests.

## 7. Calibration changes

None. The measurements above satisfy the Phase 3 acceptance: the forecast crosses 34 C before the temperature does, hysteresis needs five calm readings, and no alert flapped during recovery. Candidates to revisit later, only with new evidence from the scheduler and cooling-controller phases:

1. If predictions feel too early for a real hotspot, lower `a` or `b`, or shorten the horizon. Nothing in this run argues for it.
2. The plan's 6B example would be reproduced by a ramp of 0.4 per reading, not 0.3. This is a documentation correction rather than a tuning change.

Scenario 3 "reproduces" in the sense of the phase's Done criterion: degrading Z2 produced a PREDICTED WARNING and its event.
