# Demo script (about 10 minutes)

Source: plan section 10 (scenarios 1 to 8; scenario 9 is an optional extra) and roadmap Phase 13. Every timing below was **measured on the development machine** (local services, Windows 11) in the Phase 11 and Phase 13 runs; it will differ on another machine. **The full demo has not yet been rehearsed end to end by a person** (see `docs/demo-checklists.md`), and **no lab-server deployment exists yet**: the demo runs from the development machine or from the Docker stack on it.

## Before you start (5 minutes, not part of the 10)

1. Start everything: `scripts/start-all.sh` (local) or `docker compose --env-file .env -f infra/docker-compose.full.yml up -d` (Docker). Wait until the health strip on the Overview shows every service green.
2. Reset to the initial state: `scripts/reset-demo.sh` (Docker behind nginx: `BASE_URL=http://localhost:8088 scripts/reset-demo.sh`). It prints "calm: 16 racks NORMAL, no alerts..." in about 10 to 20 seconds. Run it again before every rehearsal and before the real demo.
3. Open these tabs, signed in where needed: **Dashboard** (`http://localhost:5173` in development, or the nginx address), **Eureka** (`http://localhost:8761`), **RabbitMQ UI** (`http://localhost:15672`), the **architecture slide** (`docs/slides/architecture.html`), and a terminal in the repository root. Log in to the dashboard as `admin` (password from your `.env`, `SEED_ADMIN_PASSWORD`).
4. Have the commands for the scheduler step ready in the terminal (section 7 below), but do not run them yet.

## Timeline

| Time | Part | What you do | Where |
|------|------|-------------|-------|
| 0:00 | 1 Architecture | Talk over the slide | Slide |
| 1:15 | 2 Normal operation (scenario 1) | Watch, point things out | Overview |
| 2:15 | 3 Hotspot prediction (scenarios 2, 3) | Inject the Z2 degradation | Control Room, then Overview |
| 3:30 | 4 Workload migration (scenario 4) | Read the decision feed | Overview, Rack detail |
| 4:30 | 5 Cooling response (scenario 5) | Clear the failure, watch release | Cooling, Overview |
| 5:30 | 6 Cooling unit failure (scenario 6) | Fail the Z3 unit | Control Room, Overview |
| 7:00 | 7 Scheduler failure and recovery (scenarios 7, 8) | Stop and restart the scheduler | Terminal, Overview |
| 9:00 | 8 Wrap-up | Reset and summarise | Overview |

If you are behind, drop part 5 (it is shown again as part of part 4) or shorten part 2. Never skip part 7: it is the resilience proof.

## 1. Architecture (about 75 s)

**Show:** `docs/slides/architecture.html`. **Say:** ThermoGrid is a software-simulated data center, 4 cooling zones and 16 racks, run by cooperating microservices. The browser talks only to the gateway. Services find each other through Eureka. Each service owns its own database. Everything else travels as events on one RabbitMQ exchange. Walk the five-step loop at the bottom of the slide: simulator, telemetry forecast, cooling, scheduler, back to the simulator. **Say what is simulated:** the simulator is the physical plant and the safest way to inject failures on demand; real sensors would replace only that one service; there is no machine learning. **Then show** the Eureka tab (all services registered) and the RabbitMQ tab (queues with consumers, `thermogrid.dlq` empty), about 15 seconds each.

## 2. Normal operation, scenario 1 (60 s)

**Show:** Overview. **Say:** all racks are blue or green, no alerts, Autopilot is ON, cooling runs at about 30 to 50 percent. Point at the heat map (fill is the temperature, outline is the 60-second forecast), the cooling zone cards and the modelled cooling saving, the service health strip, and the 36 workloads in the CPU-by-rack chart. Zone Z2 is a little warmer because it carries more work.
**Expected (measured):** 16 racks NORMAL, no active alerts, nothing pending, all outputs at or below 60 percent.

## 3. Hotspot prediction, scenarios 2 and 3 (about 75 s)

**Do:** Control Room, preset **Degrade cooling in Z2**, review the dialog, confirm. (It injects `COOLING_DEGRADED` on Z2 with remaining efficiency 0.15; see "Notes" for why not 0.25.) Switch to the Overview.
**Say:** Z2 lost most of its cooling. The system does not wait for a rack to be hot: telemetry smooths each rack and forecasts 60 seconds ahead.
**See (measured over three runs):** a WARNING alert in Z2 with basis **PREDICTED** after 44, 51 and 63 seconds; the alert card shows the forecast value; a heat-map cell whose fill is still green or amber but whose outline is orange (the prediction made visible); the Z2 cooling card climbing to 100 percent (it reached 90 percent or more at once in every run). Open **Rack detail** for the alerting rack: the forecast line runs above the temperature line.

## 4. Automatic workload migration, scenario 4 (60 s)

**Do:** stay on the Overview and read the decision feed.
**Say:** the scheduler reacted to the alert. It moves low-priority work first, to cool racks with spare capacity, at most two moves per cycle, with cooldowns so work does not bounce.
**See (measured):** thermal migrations appeared in the feed (applied in every run), each with its reason, for example "Moved batch-etl-7 (LOW, 30 units) from R23 to R41 because R23 forecast ... (WARNING, predicted)"; the source rack bars shrink, destination bars grow, and Z2 stops rising.

## 5. Cooling response, scenario 5 (60 s)

**Do:** Control Room, **Active failures**, press **Clear** next to the Z2 entry. Open the **Cooling** page.
**See (measured):** active alerts cleared 19, 19 and 21 seconds after clearing; Z2 output released to 60 percent or less (10, 14 and 14 seconds after the alerts cleared) and settles back to 40 to 50 percent; the action log lists every change with its reason; the output line climbed to 100 and fell.
**Say:** the controller is proportional with alert feed-forward, rate-limited to 10 points per reading, so it does not jump.

## 6. Cooling unit failure, scenario 6 (about 90 s)

**Do:** Control Room, preset **Fail the cooling unit in Z3**, confirm. Watch the Overview.
**See (measured over three runs):** Z3's unit shows FAULT after 5 seconds (the controller needs two bad readings); within 3 to 5 seconds the other three zones raise their output to at least 60 percent; the scheduler marks Z3 as failed, drains it (its CPU bars empty) and never places work there; no CRITICAL alert was raised in any run.
**Say:** healthy zones take over the load of the failed unit, and the scheduler evacuates the failed zone. **Then** press **Clear** on the Z3 failure (unit back to OK, floors back to 30).

## 7. Scheduler failure and recovery, scenarios 7 and 8 (about 2 minutes)

**First** inject the Z2 degradation again (as in part 3) so an alert is active; wait until an alert shows.
**Do (stop):** in the terminal, local: `scripts/stop-all.sh scheduler-service`. Docker: `docker compose --env-file .env -f infra/docker-compose.full.yml stop scheduler-service`.
**See (measured):** the scheduler badge turns red about 21 to 24 seconds later (Eureka lease expiry); the decision feed shows a stale badge with its last update time; the heat map, cooling cards and alerts **keep updating**; Z2 cooling stays AUTO at 90 percent or more; a workload submitted now stays PENDING.
**Say:** cooling does not depend on the scheduler; panels fail one at a time because each polls its own source.
**Do (restart):** local: `SKIP_BUILD=1 START_INFRA=0 scripts/start-all.sh` (skips everything already running). Docker: `docker compose ... start scheduler-service`.
**See (measured):** the local restart took about 25 seconds; the badge turns green, the pending workload is placed within about a second of the service being up, pending returns to 0, and alerts clear about 20 to 24 seconds after the failure is cleared. (The Docker restart time was not measured.) **Finally** clear the Z2 failure.
**Optional extra, scenario 9 (telemetry failure):** `scripts/stop-all.sh telemetry-service`. The heat map goes stale, the scheduler badge shows HOLD (measured: within a second of the check, after the service had been down about 22 to 26 seconds), cooling keeps updating, the RabbitMQ queue `q.telemetry.rack-readings` grows; after restart (about 24 seconds locally) the backlog drains in about 8 to 11 seconds. Skip it if you are short of time.

## 8. Wrap-up and reset (about 60 s)

**Say:** four things the demo showed: prediction before the problem, an automatic decision with a stated reason, independent failure handling, recovery without manual repair. **Do:** `scripts/reset-demo.sh` so the system is ready for questions or the next run (about 10 to 20 seconds).

## Notes and honest limits

- **Why 0.15 and not 0.25.** Plan section 10 uses a remaining efficiency of 0.25. With the cooling controller running, 0.25 only reached WATCH (Z2 peaked at about 31 C), so scenarios 3 and 4 did not happen. Measured: 0.20 reached 32.8 C (still WATCH) and 0.15 gave a PREDICTED WARNING and migrations. The Control Room preset now uses 0.15 (decision D-093).
- The measured numbers come from the automated runs of `scripts/failure-tests.sh` (three consecutive full passes) and from `scripts/reset-demo.sh`; they are not from a rehearsed live demo.
- Autopilot off against on (plan presenter note) is possible but not scripted here: switch Autopilot off in the Control Room, repeat part 3, and watch Z2 heat unmanaged by migration; switch it on again.
- **Lab server:** not deployed. If the demo is given from a lab URL, that deployment must be done and checked first (`docs/performance.md`, section 6).
- **If something goes wrong:** run `scripts/reset-demo.sh`; if a service shows red, `scripts/start-all.sh` (local) or `docker compose ... up -d` (Docker) starts what is missing; use the backup screenshots and, if one was recorded, the backup video (`docs/demo-checklists.md`).
