# HeatSync / ThermoGrid

A distributed platform for thermal-aware workload balancing and data center cooling optimization.

ThermoGrid is a software-simulated data center (4 cooling zones, 16 racks) built from cooperating microservices that sense rack temperatures, forecast hotspots 60 seconds ahead, migrate workloads away from heat and tune cooling automatically. All hardware is simulated. The full design is in the *ThermoGrid Master Implementation and Technical Architecture Plan*.

## Current status

Phase 0 (environment and repository setup) is in progress. The repository currently contains the Maven multi-module skeleton only: no service code, infrastructure or frontend exists yet. See [docs/status.md](docs/status.md).

## Modules

`thermogrid-contracts`, `eureka-server`, `api-gateway`, `auth-service`, `topology-service`, `simulator-service`, `telemetry-service`, `workload-service`, `scheduler-service`, `cooling-controller-service`. The React `frontend/` is not a Maven module and is not created yet.

## Prerequisites

- JDK 17 or newer (built and checked with JDK 22; release target is 17)
- Git
- Maven is optional: use the included wrapper (`mvnw`), which downloads Maven 3.9.16 on first run
- Not needed yet: Node 18+, Docker (required from later bundles and phases)

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

Copy `.env.example` to `.env` and replace the placeholders. `.env` is git-ignored. Nothing reads it yet. Secrets must never be committed.

## Repository layout

```
pom.xml            parent POM
docs/              status register, decision log, diagrams, doc placeholders
infra/             (empty) Docker, MySQL and nginx config arrive in later bundles
scripts/           (empty) helper scripts arrive in later phases
<module>/          one directory per Maven module
```

## Git conventions

One commit per brief, named `phase-N: service: summary`, on a feature branch. Merge to `main` only when tests pass.