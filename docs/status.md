# Status register

Mirrors the checklist in plan section 15.9. An item is Done only when its listed test was run and passed (plan rule 10). Last updated: 2026-11-18, end of Phase 4.

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
| simulator-service: listener for cooling.unit.state | Deferred | No queue or consumer until Phase 6 |
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

## Optional (plan section 2 cut list and 15.5)

All Pending and not started.