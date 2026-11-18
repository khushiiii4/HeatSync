# HeatSync / ThermoGrid

A distributed platform for thermal-aware workload balancing and data center cooling optimization.

ThermoGrid is a software-simulated data center (4 cooling zones, 16 racks) built from cooperating microservices that sense rack temperatures, forecast hotspots 60 seconds ahead, migrate workloads away from heat and tune cooling automatically. All hardware is simulated. The full design is in the *ThermoGrid Master Implementation and Technical Architecture Plan*.

## Current status

Phase 0 (environment and repository setup) is in progress. The repository contains the Maven multi-module skeleton and the development infrastructure (MySQL and RabbitMQ). No service code or frontend exists yet. See [docs/status.md](docs/status.md).

## Modules

`thermogrid-contracts`, `eureka-server`, `api-gateway`, `auth-service`, `topology-service`, `simulator-service`, `telemetry-service`, `workload-service`, `scheduler-service`, `cooling-controller-service`. The React `frontend/` is not a Maven module and is not created yet.

## Prerequisites

- JDK 17 or newer (built and checked with JDK 22; release target is 17)
- Git
- Maven is optional: use the included wrapper (`mvnw`), which downloads Maven 3.9.16 on first run
- Docker Desktop with Compose v2 (for MySQL and RabbitMQ)
- Not needed yet: Node 18+

Versions pinned: Spring Boot 3.5.16, Spring Cloud 2025.0.3 (see [docs/decision-log.md](docs/decision-log.md)).

## Build

Windows:

```
.\mvnw.cmd -DskipTests package
```

Linux/macOS:

```
./mvnw -DskipTests package
```

Expected: BUILD SUCCESS with 11 reactor entries (parent plus 10 modules).

## Configuration

Copy `.env.example` to `.env` and replace every `CHANGE_ME` placeholder. `.env` is git-ignored. Secrets must never be committed. Avoid `'` and `\` in passwords only if you edit `02-users.sh`; it escapes them, but keep passwords simple.

## Development infrastructure

Start MySQL 8.4 and RabbitMQ 3.13 from the repository root:

```n docker compose --env-file .env -f infra/docker-compose.yml up -d
 docker compose --env-file .env -f infra/docker-compose.yml ps
```n
- MySQL: `127.0.0.1:3306`. Six schemas (`auth_db`, `topology_db`, `telemetry_db`, `workload_db`, `scheduler_db`, `cooling_db`), each with its own user (`auth`, `topology`, `telemetry`, `workload`, `scheduler`, `cooling`) granted on that schema only.
- RabbitMQ: AMQP `127.0.0.1:5672`, management UI http://127.0.0.1:15672 (login from `RABBITMQ_USER` / `RABBITMQ_PASSWORD`).
- Init scripts in `infra/mysql/init` run only on a fresh volume. To re-initialise: `docker compose --env-file .env -f infra/docker-compose.yml down -v` (deletes all data) then `up -d`.

## Eureka server

Build first (`mvnw -DskipTests package`), then from the repository root:

```n java -jar eureka-server/target/eureka-server-0.1.0-SNAPSHOT.jar
```n
Or during development: `.\mvnw.cmd -pl eureka-server spring-boot:run`. Dashboard: http://localhost:8761. Dev timers apply (3 s renewal, 9 s expiry, 3 s eviction, self-preservation off): every client service must set `eureka.instance.lease-renewal-interval-in-seconds: 3` and `lease-expiration-duration-in-seconds: 9`.

## API gateway

Spring Cloud Gateway 4.3.5 (WebFlux), port 8080, the only public entry point. Start Eureka first, then:

```n java -jar api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar
```n
Routes (`/api/auth`, `/api/topology`, `/api/sim`, `/api/telemetry`, `/api/alerts`, `/api/workloads`, `/api/scheduler`, `/api/cooling`) go to `lb://` services through Eureka. `/internal/**` is not routed. `GET /api/platform/services` is served by the gateway. CORS allows `http://localhost:5173` (override with `thermogrid.cors.allowed-origins`). Security is off (`thermogrid.security.enabled=false`) until phase 9.

If port 8080 is taken (on this machine `AgentService.exe` holds it), find the owner with `Get-NetTCPConnection -LocalPort 8080 -State Listen`; for a temporary run use `--server.port=18080` rather than editing the config.

Temporary demo endpoint: `topology-service` (port 8082) exposes `GET /api/topology/ping`, to be removed in phase 2.

## Repository layout

```
pom.xml            parent POM
docs/              status register, decision log, diagrams, doc placeholders
infra/             docker-compose.yml (MySQL, RabbitMQ), mysql/init; nginx and env arrive later
scripts/           (empty) helper scripts arrive in later phases
<module>/          one directory per Maven module
```

## Git conventions

One commit per brief, named `phase-N: service: summary`, on a feature branch. Merge to `main` only when tests pass.