# Status register

Mirrors the checklist in plan section 15.9. An item is Done only when its listed test was run and passed (plan rule 10). Last updated: 2026-11-18, end of Phase 6.

## Phase 0: Environment and repository setup

| Item | Status | Evidence / note |
|------|--------|-----------------|
| Parent POM, 10 module stubs, Maven wrapper (Bundle 0.1) | Done | `mvnw -DskipTests package` and `mvnw validate`: BUILD SUCCESS, 11 reactor entries |
| docs/ skeleton, status register, decision log (Bundle 0.2) | Done | Files exist. Most docs are placeholders, see below |
| .gitignore, .gitattributes, .env.example, README (Bundle 0.2) | Done | Placeholders only in .env.example |
| infra/ and scripts/ directories (Bundle 0.2) | Done | Empty (.gitkeep) |
| docker-compose.yml with MySQL 8.4 and RabbitMQ 3.13 | Done | Both containers reported healthy |
| infra/mysql/init: six schemas (01-schemas.sql) and users/grants (02-users.sh) | Done | SHOW DATABASES shows all six; grants checked |
| Each service user can reach only its own schema | Done | 6x6 matrix: 36/36 as expected (own schema allowed, others error 1142) |
| RabbitMQ UI reachable on port 15672 | Done | HTTP 200; management API 3.13.7 answers with credentials, 401 without |
| Repository pushed | Pending | Remote `origin` is configured (github.com/khushiiii4/HeatSync); nothing has been pushed from this branch |

Phase 0 is not closed: the branch has not been pushed or merged, and the full-teardown (`down -v`) re-initialisation was verified once only.

## Phase 1: Contracts, Eureka and Gateway

| Item | Status | Evidence / note |
|------|--------|-----------------|
| thermogrid-contracts: envelope, payload records, Exchanges, RoutingKeys, Headers (Bundle 1.1) | Done | 23 JUnit tests pass (`mvnw package`, BUILD SUCCESS); a deliberate field rename was caught by the tests |
| eureka-server (Bundle 1.2) | Done | `mvnw package` BUILD SUCCESS, context-load test passes; ran the JAR: dashboard HTTP 200 on 8761; a probe instance with a 9 s lease was evicted ~12 s after its last renewal; self-preservation reported OFF |
| api-gateway: routes, CORS, JSON 503 fallback, platform endpoint, security flag (Bundle 1.3) | Done | 8 gateway tests pass. Runtime (JARs, gateway on 18080 because **8080 is held by AgentService.exe**): both services registered in Eureka, ping reached through gateway, platform status correct, JSON 503 with CORS header after topology stopped. **Not verified on port 8080 itself** |
| Temporary topology ping (GET /api/topology/ping) | Removed | Replaced by the real topology API in Bundle 2.1 |
| Phase 1 acceptance: request routed through the gateway to a real service | Done | Verified at runtime on port 18080 |

## Phase 2: Topology and simulator core

| Item | Status | Evidence / note |
|------|--------|-----------------|
| topology-service: entities, idempotent seeder, GET /api/topology, GET /api/topology/racks/{id}, GET /internal/topology (Bundle 2.1) | Done | 12 tests pass (7 seeder/constraint, 5 API) on in-memory H2; root `mvnw package` BUILD SUCCESS. Runtime on real MySQL and Eureka: registered as TOPOLOGY-SERVICE, 4 zones / 16 racks, R23 correct, internal payload identical, restart kept 4/16 rows and preserved MAINTENANCE and a changed target temperature |
| Topology reached through the gateway | Not verified | Gateway not started in this bundle; port 8080 conflict still open |
| simulator-service core: Feign topology bootstrap with backoff, PhysicsModel, SimState, scheduled SimulationEngine, GET /api/sim/state (Bundle 2.2) | Done | 41 tests pass (11 physics, 13 engine, 11 bootstrap/mapper, 6 API/config) on in-memory H2 with a stubbed topology client; root `mvnw package` succeeds. Runtime with real Eureka + topology-service: registered as SIMULATOR-SERVICE (1 instance UP), loaded 4 zones / 16 racks, ticks advanced and temperatures changed, one simulator JVM |
| Physics acceptance (plan 6A): 6 kW rack at 37 percent settles within 0.5 C of the formula after 200 ticks; faulted zone rises more than 0.2 C per tick | Done | Unit tests, with and without seeded noise |
| simulator-service: H2 failure_injection table and exclusive file lock (Bundle 2.3) | Done | Tests: schema, persistence across close and reopen, a second JVM is rejected while the first holds the file. Runtime: a second simulator on the same directory exited with the clear message; failures survived a restart |
| simulator-service: FailureRegistry, four failure types, failure and control endpoints (Bundle 2.3) | Done | Validation, expiry, clearing, history retention, RESET and each failure's effect are unit and API tested. Runtime: a cooling failure on Z2 gave FAULT, fan 0 and rising temperatures; clearing restored OK and 40 |
| simulator-service: RabbitMQ publishing of rack and zone readings (Bundle 2.3) | Done | 16 rack + 4 zone events per tick on `thermogrid.events`, seen through a temporary queue bound to `telemetry.#` (deleted afterwards). Broker-unreachable behaviour is unit tested and was also run once against a closed port: ticks continued, one warning per cooldown |
| simulator-service: listeners for workload.assigned, workload.removed | Done in Phase 4 | See Phase 4 below |
| simulator-service: listener for cooling.unit.state | Done in Phase 6 | See Phase 6 below |
| simulator-service: POST /api/sim/flood | Deferred | Load-test work (Phase 11) |
| Phase 2 acceptance: readings on the exchange, temperatures settle, an injected failure changes the readings, re-seeding adds no duplicates | Done, with one gap | Each part was tested or observed. The "stable for 10 minutes" run was not performed |

## Phase 3: Telemetry, prediction and alerts

| Item | Status | Evidence / note |
|------|--------|-----------------|
| telemetry-service: telemetry_db schema (4 tables, keys, indexes) | Done | Schema test on H2; `SHOW CREATE TABLE` on the real MySQL matches the plan, with one deviation (unit_health VARCHAR(8), D-029) |
| RabbitMQ ingestion with durable queues, bounded retry and dead-lettering | Done | Topology and retry rules unit tested. Runtime: 20 messages per tick consumed and stored; a malformed message and an out-of-range one both reached `thermogrid.dlq` at once. The retry path for a database failure was unit tested only |
| Idempotent storage, out-of-order handling, concurrency | Done | Tests with 8 to 16 threads on one rack. Runtime: duplicate and out-of-order messages on the real broker changed nothing |
| ForecastEngine (damped Holt) and RiskClassifier (hysteresis) | Done | 9 + 12 pure unit tests with fixed series and every boundary |
| Alert service and thermal.alert.* events | Done | Lifecycle, one ACTIVE per rack, payloads and routing keys tested. Runtime: raised, lowered and cleared events seen on a temporary queue |
| Stale-sensor watchdog | Done | 10 s boundary and recovery tested with a movable clock. Runtime: racks turned STALE about 12 s after the simulator stopped, frozen, and returned to OK after it restarted |
| Retention job | Done | Cutoff, batching and ACTIVE-alert protection tested with a movable clock. Not run against the real database (6 hours) |
| REST endpoints (latest, history, zones, alerts, internal) | Done | 10 API tests; all five endpoints and the 400 and 404 cases called on port 8084 |
| docs/calibration.md | Done | Constants, fixed series, scenario 2 and 3 measurements. No constant was changed |
| Phase 3 acceptance: degrading Z2 produces a PREDICTED WARNING alert and event | Done | Runtime: COOLING_DEGRADED 0.25 on Z2 gave a PREDICTED WARNING on R23 at 32.04 C (forecast 34.01), 25 ticks (50 s) before a rack observed 34 C. Event seen on `thermal.alert.#` |
| Telemetry reached through the gateway | Not verified | Gateway not started; the port 8080 conflict is unchanged |

## Phases 4 to 13

All Pending. Nothing from these phases has been started.

## Documentation

| File | State |
|------|-------|
| docs/diagrams/*.mmd (6 files) | Written, copied from plan sections 5.1, 5.3, 5.7 and 6B. Not render-tested |
| docs/decision-log.md | Written |
| docs/status.md | Written |
| docs/architecture.md, api.md, events.md, database.md, demo-script.md, testing.md, performance.md, calibration.md | Placeholders only |

## Phase 4: Workload service and simulator integration

| Item | Status | Evidence / note |
|------|--------|-----------------|
| workload-service on 8085, Eureka registration, `workload_db`, lifecycle PENDING / RUNNING / REMOVED | Done | Runtime: registered as WORKLOAD-SERVICE; `SHOW CREATE TABLE workload` on the real MySQL matches plan section 7 (plus enum CHECK constraints added by Hibernate) |
| Public endpoints: submit, bulk, list, get, delete, summary, seed-demo | Done | 8 API tests on H2 (validation 400s, filters, summary arithmetic, soft delete, seed idempotency) |
| Internal list and assignment endpoint | Done | 11 tests: first placement, migration, stale version 409, wrong source 409, repeated migrationId returns the same body with no change and no event, REMOVED 422, unknown 404, bad input 400, 6 concurrent callers on one version give exactly one 200 |
| workload.submitted / assigned / removed published only after commit | Done | 6 tests: nothing sent while the transaction is open, nothing after rollback, retry and give-up behaviour, DB change kept when the broker is down |
| Demo seed layout | Done | Test asserts 36 workloads, 10 to 30 units each, 720 total, Z2 racks 75 to 90 with a LOW job each, other racks 25 to 40. Runtime on MySQL: same |
| Retention of REMOVED workloads (7 days) and optional internal token | Done | 2 tests. Not run for 7 real days |
| Simulator: heat sources driven by workload events, startup bootstrap, 60 s reconcile | Done | 16 new tests (registry rules, engine, capacity cap, listener, malformed message, bootstrap and outage handling). The 96 existing simulator tests are unchanged and green |
| Phase 4 acceptance: a manual assignment visibly changes rack temperatures | Done | Runtime (real MySQL, RabbitMQ, Eureka, topology, workload, simulator): after the seed, moving batch-etl-7 from R23 to R41 changed CPU 85 to 55 and 35 to 65 on the next tick, and temperatures moved 27.04 to 25.90 and 24.01 to 25.54 within 64 s. The replay returned the same body, a stale call 409, an assignment of the removed workload 422 |
| Reconciliation and bootstrap at runtime | Done | A database-only change (no event) was picked up within the 60 s window; a restarted simulator loaded 35 running workloads. A malformed message published on the exchange reached `thermogrid.dlq` once (purged afterwards) |
| Workload reached through the gateway | Not verified | Gateway not started; the port 8080 conflict is unchanged |
| Telemetry and alert behaviour with workload-driven load | Not verified | Telemetry was not started in this phase; Z2 settle temperatures with the 720-unit layout were not measured against the 30/34/38 C thresholds |

## Phase 5: Scheduler

| Item | Status | Evidence / note |
|------|--------|-----------------|
| scheduler-service on 8086, Eureka lease 3 s / 9 s, `scheduler_db` with scheduler user | Done | Runtime: registered, tables created on the real MySQL; `SHOW CREATE TABLE` for `migration` and `placement_decision` matches plan section 7 (columns, keys, both indexes) |
| Tables migration, placement_decision, zone_cooling_state, processed_event | Done | Used at runtime: 24 migrations, 24 decisions (with top-3 candidate JSON), 3 processed events |
| PlacementEngine (plan 6C) | Done | 12 pure tests: the worked example (R41 0.428 vs R31 0.423, gap 0.005, R41 wins), a gap of 0.02 or more keeps the lowest score, HIGH-first ordering, 85 percent cap at the boundary, stale rack / FAULT zone / maintenance / WARNING / 32 C headroom exclusions, reservation, HOLD scoring and 70 percent cap |
| MigrationPlanner and CooldownTracker (plan 6D) | Done | 18 pure tests: shed amount (50, 25, floor 10, CRITICAL 30 C reference), LOW/MEDIUM/HIGH then CPU order, 2 and 4 move limits, 60 s versus 130 s cooldown, source cooldown and CRITICAL bypass, failure backoff, reservation, quarantine, drain order across a zone, HOLD, rebalance |
| MigrationExecutor | Done | Tests: new UUID per attempt, current version and source rack in the PUT, allowed reason, APPLIED/FAILED rows, 409 and 503 handling, the PUT called once only, later cycle re-plans |
| ClusterSnapshotService, Resilience4j, HOLD | Done | Tests: topology cached 30 s and last good copy reused, workload GET 3 calls (2 retries) then the cycle is skipped, open telemetry breaker means HOLD without calling telemetry, return to NORMAL |
| Event consumers and idempotency | Done | 13 tests: duplicate `workload.submitted` ignored, events older than 30 s not acted on (30 s exactly is fresh), alert and cooling reactions, out-of-order cooling state, malformed messages. Runtime: durable queues bound; a manually published `cooling.unit.state` FAULT was stored and acted on |
| Reconcile (5 s) and pending (3 s) loops, autopilot | Done | Tests: a missed submission is repaired by reconcile, autopilot off stops migration but not placement. Runtime: autopilot toggled through the API |
| REST: status, migrations, decisions, autopilot, rebalance | Done | 8 API tests; all five called at runtime |
| Singleton protection | Done | 3 unit tests with a fake connection. Runtime: a second scheduler on port 8096 failed at startup on the lock bean while the first ran |
| Scenario 3 and 4 without a cooling controller | Done | See below |
| 409 and outage at runtime | Not verified at runtime | 409, 503, connection failure and open-circuit handling are covered by tests only; no 409 occurred in the live runs (all 24 moves APPLIED), and workload-service was not stopped |
| Telemetry, scheduler and workload through the gateway | Not verified | Gateway not started; the port 8080 conflict is unchanged |
| Automatic 30 s rebalance loop | Not implemented | Only the manual endpoint (D-054) |

Scenario runs (real MySQL, RabbitMQ, Eureka, topology, simulator, telemetry, workload and scheduler, no cooling controller, demo seed of 720 units, cooling output fixed at the simulator's 40 percent):

- **Seed check.** Z2 settled at 26.5 to 27.2 C with 80 to 85 units per rack and no alerts, as plan section 10 describes. No scheduler action.
- **Placement.** A submitted HIGH 20-unit workload went from PENDING to RUNNING on R42 within 4 s. The decision row holds R42 (0.332) against R44 (0.340) and R34 (0.348).
- **Scenario 3 and 4.** `COOLING_DEGRADED` 0.25 on Z2. The first WARNING (R22, R23) came 40 s after injection. The scheduler then made 10 THERMAL_WARNING moves in 80 s, at most 2 per reconcile cycle (they appear as pairs within 0.1 s, one pair per 5 s cycle), LOW priority first on each rack (R23: LOW 30, LOW 15, then MEDIUM 20; R22: LOW 20, LOW 15, then MEDIUM 30), always into other zones with headroom (largest rack load afterwards 65 units, cap 85). No workload was moved twice (checked against the 120 s cooldown). Z2 peaked at about 32.1 C observed and 33.9 C forecast and never reached 34 C observed. Z2 load fell from 330 to 115 units, temperatures stopped rising about 70 s after injection and then fell. Clearing the degradation was done after that.
- **HOLD.** Telemetry was killed: the scheduler reported HOLD about 3 s later. Two workloads submitted during HOLD were placed with mode HOLD decisions on utilisation only (scores 0.35 and 0.45). A manually published cooling FAULT for Z3 was stored but caused no migration. When telemetry restarted the scheduler returned to NORMAL after 18 s (startup time of telemetry) and drained Z3: 11 COOLING_FAILURE moves, all APPLIED, none into Z3.
- **Demo layout.** The 720-unit layout gives the intended thermal scenario: Z2 is warm but stable, and a 0.25 degradation pushes forecasts over 32 C within a minute. No constants were changed.

## Phase 6: Cooling controller and closed-loop integration

| Item | Status | Evidence / note |
|------|--------|-----------------|
| cooling-controller-service on 8087, Eureka lease 3 s / 9 s, `cooling_db`, one unit per zone | Done | Runtime: registered, 4 units seeded from topology; `SHOW CREATE TABLE` for `cooling_unit` and `cooling_action` matches plan section 7 (columns, key, `(zone_id, created_at)` index) |
| ProportionalController, EWMA and slope, floors, boosts, hold times, clamping, slew | Done | 25 pure table-driven tests in `ControlLawTest`: worked values for ewma 27.0 / slope 1.0 / hot 32.0 / raw 107, floor 30 and 60, boosts 50, 70, 100, 60 s and 20 s holds with exact boundaries, escalation, CRITICAL lowered to WARNING, 10-point slew up and down |
| FaultDetector | Done | 2 FAULT to confirm, 3 OK to recover, DEGRADED counts toward neither. Runtime: FAULT confirmed 4.1 s after the injection (2 readings), recovered 6.1 s after clearing (3 readings) |
| Fail-safe watchdog | Done | Tests at 9.999 s and 10 s, reported once, 3 fresh readings to leave, stale, duplicate and out-of-order readings do not count. Runtime: simulator stopped, all four units entered FAILSAFE at 80 about 10 s later with one `cooling_action` each; after the simulator restarted they returned to AUTO after 12 s |
| Energy model | Done | Tests: 0.5, 1.0, 1.872 and 4.5 kW at 0, 50, 70, 100 percent, integration over time. Runtime summary: 2.81 kW against a 7.49 kW baseline (62.5 percent), labelled modelled |
| RabbitMQ: consume zone readings and alerts, publish cooling.unit.state | Done | Durable queues `q.cooling.zone-readings` (TTL 30 s) and `q.cooling.alerts` (TTL 60 s), dead-lettered. Tests: raw message mapping, duplicates, malformed messages, saved before published (checked at send time), heartbeat with rising seq, broker down then up |
| Persistence | Done | Tests: unit state survives a restart, seq never goes backwards, 24 h retention |
| REST: units, actions, summary, manual mode | Done | 11 API and event tests; all four called at runtime (manual mode only in tests) |
| Simulator consumes cooling.unit.state | Done | 10 new tests (output applied to the right zone on the next tick, duplicates and out-of-order dropped, restart accepted, clamping, last output held, health not applied, failure injection unchanged, listener and malformed message). 122 simulator tests pass |
| Scheduler uses cooling state | Done | 5 new tests: FAULT drains LOW first and is never a destination, confirmed recovery restores placement, stale / duplicate / out-of-order messages, HOLD and move limits still hold. 86 scheduler tests pass |
| Singleton protection | Done | 3 unit tests; runtime lock acquired. A second controller was not started |
| Phase 6 acceptance: scenarios 2, 5 and 6 | Done, with the differences below | See the runs below |
| Event delivery limits | Documented | A lost change event is repaired by the next heartbeat (at most 5 s); alert levels are in memory (D-062, D-063) |
| Controller and RabbitMQ failure at runtime | Not verified | Broker failure is tested with a mocked broker only; the broker was never stopped. The manual-mode endpoint was not called against the live service |
| Cooling reached through the gateway | Not verified | Gateway not started; the port 8080 conflict is unchanged |

Live runs (real MySQL, RabbitMQ, Eureka, topology, simulator, telemetry, workload, scheduler and cooling controller, demo seed of 720 units):

- **Scenario 2, degraded cooling.** `COOLING_DEGRADED` 0.25 on Z2. Output rose from 43 to 77 within 10 s and to 94 to 100 within about a minute (slew of at most 10 points per reading), and the simulator's zone output followed it. Z2 plateaued at 31.2 to 31.4 C with a WATCH alert and no WARNING, so **no migration was needed**: with the controller running, cooling alone contained the degradation (without it, Phase 5 measured 32 C observed and 34 C forecast and ten migrations). To check migration with cooling events flowing, a `HEAT_SPIKE` of 8 kW was added on R23: Z2 reached 33.8 C with output at 100, WARNING was raised, and the scheduler made 4 THERMAL_WARNING moves off R23 (two cycles) while Z2 fell back to about 32 C. Cooling events reached the scheduler (zone state rows) and the simulator.
- **Scenario 5, cooling adjustment.** After both failures were cleared the output stayed at 70 while the alert level fell, the `hold_until` showed a 20 s hold, it was released to 60 and 30, and it settled at 39 to 47 percent with the zone at 26.3 to 26.9 C. Over 500 recorded actions the largest AUTO step was 10 points and the smallest 2. **Not observed:** a boost that is actually larger than the temperature term (WATCH 50, WARNING 70, CRITICAL 100 acting alone); at 30 C and above the raw term already exceeds the boosts, so those paths are covered by unit tests only. A 60 s CRITICAL hold was not observed live.
- **Scenario 6, cooling failure in Z3.** `COOLING_FAILURE` on Z3: FAULT confirmed 4.1 s later, the floor of all units rose to 60 and healthy zones climbed to 60 in 10-point steps, the scheduler stored Z3 as FAULT within a second and made 8 COOLING_FAILURE moves in two cycles (4 per cycle; LOW jobs first, then MEDIUM, then HIGH) emptying Z3, none into Z3. A job submitted afterwards went to R41. Z3 racks stayed at 25.6 to 26.0 C because they were drained at once. After clearing, Z3 and the scheduler showed OK and all floors returned to 30 after 6.1 s (3 OK readings). The plan's expectation of Z3 temperatures peaking near 30 C was not reproduced because the drain completed before the racks warmed.
- **Controller behaviour at steady state.** The output moves by 2 to 5 points around the setpoint every few seconds (about 585 `cooling_action` rows in roughly 25 minutes); this is the proportional law reacting to noise and is within the 24 h retention. No constants were changed.

## Optional (plan section 2 cut list and 15.5)

All Pending and not started.