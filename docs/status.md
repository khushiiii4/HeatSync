# Status register

Mirrors the checklist in plan section 15.9. An item is Done only when its listed test was run and passed (plan rule 10). Last updated: 2026-11-18, end of Bundle 1.1.

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
| eureka-server | Pending | |
| api-gateway (routes, CORS, fallback, platform endpoint) | Pending | |

## Phases 2 to 13

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