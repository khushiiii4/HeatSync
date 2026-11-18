# Status register

Mirrors the checklist in plan section 15.9. An item is Done only when its listed test was run and passed (plan rule 10). Last updated: 2026-11-18, end of Bundle 2.3.

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
| simulator-service: listeners for workload.assigned, workload.removed, cooling.unit.state | Deferred | Contract mapping and a logging handler exist; no queues or consumers until Phase 4 and Phase 6 |
| simulator-service: POST /api/sim/flood | Deferred | Load-test work (Phase 11) |
| Phase 2 acceptance: readings on the exchange, temperatures settle, an injected failure changes the readings, re-seeding adds no duplicates | Done, with one gap | Each part was tested or observed. The "stable for 10 minutes" run was not performed |

## Phases 3 to 13

All Pending. Nothing from these phases has been started.

## Documentation

| File | State |
|------|-------|
| docs/diagrams/*.mmd (6 files) | Written, copied from plan sections 5.1, 5.3, 5.7 and 6B. Not render-tested |
| docs/decision-log.md | Written |
| docs/status.md | Written |
| docs/architecture.md, api.md, events.md, database.md, demo-script.md, testing.md, performance.md, calibration.md | Placeholders only |

## Optional (plan section 2 cut list and 15.5)

All Pending and not started.