# HeatSync / ThermoGrid

A distributed platform for thermal-aware workload balancing and data center cooling optimization.

ThermoGrid is a software-simulated data center (4 cooling zones, 16 racks) built from cooperating microservices that sense rack temperatures, forecast hotspots 60 seconds ahead, migrate workloads away from heat and tune cooling automatically. All hardware is simulated. The full design is in the *ThermoGrid Master Implementation and Technical Architecture Plan*.

## Current status

Phases 0 to 12 are implemented and Phase 13 (demo preparation) is written up: infrastructure, contracts, Eureka and the API gateway, topology, the simulator, telemetry with forecasting and alerts, the workload service, the scheduler (thermal-aware placement, migration, reconcile and HOLD mode), the cooling controller (proportional control, fault detection, fail-safe, modelled energy), RabbitMQ hardening, the React dashboard in `frontend/`, authentication and security at the gateway, integration and failure testing, and a Docker deployment with nginx, telemetry scaling and load tests.

**Honest status.** The full stack has been deployed and measured with Docker **on a development machine only**. **There is no lab server: the final demonstration runs on Docker on one computer** (see "Run the whole system with Docker"), and no lab URL exists or is claimed. The demo has been scripted but **not yet rehearsed by a person, and no backup video has been recorded** (see [docs/demo-checklists.md](docs/demo-checklists.md)). Automated evidence: 549 Java tests (0 failures, 15 live-broker tests run separately), 64 frontend tests, 38 end-to-end checks, three clean runs of the 59-check failure suite, k6 at 0.00 percent errors.

See [docs/status.md](docs/status.md) for evidence per item and [docs/decision-log.md](docs/decision-log.md) for decisions. Start everything in the right order with `scripts/start-all.sh` (consumers before producers).

## Deployment, testing and demo

| I want to | Go to |
|-----------|-------|
| Understand the design (slide, diagrams, contracts) | [docs/architecture.md](docs/architecture.md) |
| Run it for the first time, step by step | [docs/USER-RUN-GUIDE.md](docs/USER-RUN-GUIDE.md) |
| Deploy the whole stack in Docker (nginx, telemetry x3) | `docker compose --env-file .env -f infra/docker-compose.full.yml up -d --build`; details and measured results in [docs/performance.md](docs/performance.md); env template `.env.example` |
| See what was tested and what was not | [docs/testing.md](docs/testing.md); `./mvnw -B verify`, `scripts/e2e.sh`, `scripts/failure-tests.sh` |
| Load test | `load-tests/` (k6) and `scripts/flood-test.sh` |
| Give the demo | [docs/demo-script.md](docs/demo-script.md) (about 10 minutes), [docs/slides/architecture.html](docs/slides/architecture.html) (one slide, PNG next to it) |
| Reset between demo runs | `scripts/reset-demo.sh` (clears failures, removes all workloads, re-seeds, resets the simulator; about 10 to 20 s; keeps alert and migration history) |
| Prepare for the viva | [docs/viva.md](docs/viva.md) |
| Screenshots, backup video, three rehearsals | [docs/demo-checklists.md](docs/demo-checklists.md) |

## Run the whole system with Docker (final demonstration)

The final deployment is `infra/docker-compose.full.yml` on **one computer** (there is no lab server): MySQL, RabbitMQ, Eureka, the gateway, seven business services, telemetry-service at 3 instances, and nginx serving the dashboard. It uses its own Compose project (`thermogrid-full`), so it never touches the development containers started by `infra/docker-compose.yml`. Security is unchanged: JWT at the gateway, role rules, the internal token on `/internal/**`.

**You need:** Docker Desktop with Compose v2; about 8 GB of memory given to Docker (the running stack used about 3.6 GB, its limits add up to about 6.3 GB); about 10 GB of free disk (the images are about 4 GB, the build cache is extra); internet for the first build; Git Bash (Windows) or any bash plus Node.js for the helper scripts (`reset-demo.sh`, `e2e.sh`, `failure-tests.sh`, `flood-test.sh`).

### 1. Configure `.env`

```
cp .env.example .env            # PowerShell: Copy-Item .env.example .env
```

Open `.env` and replace every `CHANGE_ME`. Use letters and digits for passwords (no `$`, `#`, quotes or spaces). `THERMOGRID_JWT_SECRET` needs at least 32 characters; `docs/USER-RUN-GUIDE.md` section B2 shows how to generate secrets. If port 80 is already used on your computer, set `HTTP_PORT=8088`. `.env` is git-ignored: never commit it or paste it into chat. The `*_DB_PASSWORD` values are applied to MySQL only when its volume is created for the first time; changing them later needs `down -v` (see Troubleshooting).

### 2. Build and start

```
docker compose --env-file .env -f infra/docker-compose.full.yml up -d --build
docker compose --env-file .env -f infra/docker-compose.full.yml ps
```

The first build downloads base images and compiles every module, so allow 10 to 20 minutes (not timed precisely). In the local run, every container reported healthy about 4 minutes after `up`. Start order is enforced by health checks: MySQL and RabbitMQ, Eureka, the gateway and business services, the simulator last, nginx at the end.

### 3. Open it

| What | Address |
|------|---------|
| **Dashboard** (log in as `viewer`, `operator` or `admin` with the `SEED_*_PASSWORD` values from `.env`) | `http://localhost:<HTTP_PORT>/` (`http://localhost/` if you kept 80) |
| API (same origin; nginx forwards `/api/` to the gateway) | `http://localhost:<HTTP_PORT>/api/...` |
| Eureka dashboard (registered services, 3 telemetry instances) | `http://127.0.0.1:8762` (`EUREKA_PORT`) |
| RabbitMQ management UI (queues; user and password are `RABBITMQ_USER` and `RABBITMQ_PASSWORD`) | `http://127.0.0.1:15673` (`RABBITMQ_UI_PORT`) |

Only nginx is public. Inside the Docker network the services listen on their usual ports: eureka 8761, api-gateway 8080, auth 8081, topology 8082, simulator 8083, telemetry 8084, workload 8085, scheduler 8086, cooling-controller 8087; MySQL 3306 and RabbitMQ 5672 are not published to your computer. When the services run from Maven instead (`scripts/start-all.sh`), the gateway is on `GATEWAY_PORT` (default 8080) and the dev dashboard is `http://localhost:5173`.

### 4. Seed and check

```
BASE_URL=http://localhost:8088 scripts/reset-demo.sh      # seeds the demo and waits for a calm state (use your HTTP_PORT)
GATEWAY_URL=http://localhost:8088 scripts/e2e.sh          # optional: 37 end-to-end checks through nginx
```

### 5. Stop

```
docker compose --env-file .env -f infra/docker-compose.full.yml down       # stops and removes containers, KEEPS the data volumes
docker compose --env-file .env -f infra/docker-compose.full.yml down -v    # also DELETES MySQL, RabbitMQ and simulator data: only for a fresh start
```

During the demo: `docker compose --env-file .env -f infra/docker-compose.full.yml stop scheduler-service` and `... start scheduler-service` (see `docs/demo-script.md`). To change the telemetry instance count: `... up -d --scale telemetry-service=1`.

The load-test figures in `docs/performance.md` are **local-machine measurements** (Docker Desktop, 8 GB, 12 CPUs); they show the mechanism and relative scaling, not the capacity of another computer.

### Docker troubleshooting

| Symptom | Likely cause and fix |
|---------|----------------------|
| `required variable ... is missing a value` | A secret or password is missing in `.env`; the message names it. Always pass `--env-file .env` |
| `port is already allocated` or nginx will not start | Port 80 is in use; set `HTTP_PORT=8088` in `.env` and run `up -d` again |
| Containers restart or show `Killed` / exit 137 | Docker has too little memory; raise it in Docker Desktop, Settings, Resources (about 8 GB), or lower `TELEMETRY_REPLICAS` |
| A service is `unhealthy` or `starting` for a long time | Look at its log: `docker compose --env-file .env -f infra/docker-compose.full.yml logs --tail 100 <service>`. MySQL must be healthy first; the first start takes the longest |
| Dashboard opens but shows 502 or 503 | The gateway or a service is still starting; wait for `ps` to show healthy, then reload |
| Login says the credentials were not accepted | Use the `SEED_*_PASSWORD` values from `.env`. After changing one, `docker compose --env-file .env -f infra/docker-compose.full.yml restart auth-service` |
| MySQL `Access denied` after you changed a `*_DB_PASSWORD` | The MySQL volume was created with the old value. Restore the old value, or accept losing the data with `down -v` and start again |
| Build fails while downloading dependencies | Network or proxy problem; run the same `up -d --build` again, the cache keeps finished layers |
| Helper scripts fail with "command not found" or odd errors on Windows | Run them in Git Bash and make sure Node.js is installed (`node -v`) |
| Docker stack and the Maven services at once are slow | Stop the Maven services with `scripts/stop-all.sh`; the dev MySQL and RabbitMQ containers can stay running |

## Modules

`thermogrid-contracts`, `eureka-server`, `api-gateway`, `auth-service`, `topology-service`, `simulator-service`, `telemetry-service`, `workload-service`, `scheduler-service`, `cooling-controller-service`. The React `frontend/` is not a Maven module (Node project, see "Frontend dashboard").

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

Spring Cloud Gateway 4.3.5 (WebFlux), the only public entry point. It listens on `GATEWAY_PORT` (default 8080). Start Eureka first, then:

```
java -jar api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar
```

Routes (`/api/auth`, `/api/topology`, `/api/sim`, `/api/telemetry`, `/api/alerts`, `/api/workloads`, `/api/scheduler`, `/api/cooling`) go to `lb://` services through Eureka. `/internal/**` is not routed. `GET /api/platform/services` is served by the gateway. CORS allows `http://localhost:5173` (override with `thermogrid.cors.allowed-origins`). Every route except `POST /api/auth/login` requires a JWT (see "Authentication and security").

**Choosing the gateway port.** Set `GATEWAY_PORT` in the environment or in `.env` (see `.env.example`); it feeds `server.port` in `api-gateway/application.yml` (`${GATEWAY_PORT:8080}`). `scripts/start-all.sh` (and the PowerShell twin) reads it, passes it to the gateway, and checks that the port is free before starting anything: if it is taken, the script stops with an error and nothing is killed. Example: `GATEWAY_PORT=18080 scripts/start-all.sh`, then `http://localhost:18080/api/platform/services`. If 8080 is taken (on one development machine `AgentService.exe` holds it), find the owner with `Get-NetTCPConnection -LocalPort 8080 -State Listen`. Running the jar by hand: `GATEWAY_PORT=18080 java -jar ...` or `--server.port=18080`.

## Topology service

Port 8082, registers as `TOPOLOGY-SERVICE`, owns `topology_db` (zones and racks, seeded idempotently on startup: 4 zones, 16 racks). Needs MySQL and Eureka running. The password is read from the environment (it is not loaded from `.env` automatically):

```powershell
$env:DB_PASSWORD = '<value of TOPOLOGY_DB_PASSWORD from .env>'
java -jar topology-service/target/topology-service-0.1.0-SNAPSHOT.jar
```

| Endpoint | Purpose |
|---|---|
| `GET /api/topology` | Array of zones, each with its racks (public, via the gateway) |
| `GET /api/topology/racks/{rackId}` | One rack, or 404 with the standard error body |
| `GET /internal/topology` | Same payload as `/api/topology`, for service-to-service calls. Never routed by the gateway |

Overrides: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `EUREKA_URL`. Tests use an in-memory H2 database (profile `test`) and never touch MySQL.

## Simulator service

Port 8083, registers as `SIMULATOR-SERVICE`. It owns the simulation clock and publishes sensor readings to RabbitMQ.

**Needs:** Eureka (8761), topology-service (8082, which needs MySQL) and RabbitMQ (5672). The RabbitMQ login is read from the environment (it is not loaded from `.env` automatically):

```powershell
$env:RABBITMQ_USER = '<RABBITMQ_USER from .env>'; $env:RABBITMQ_PASSWORD = '<RABBITMQ_PASSWORD from .env>'
cd simulator-service
java -jar target/simulator-service-0.1.0-SNAPSHOT.jar
```

Overrides: `EUREKA_URL`, `RABBITMQ_HOST`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `SIM_DB_URL`. Building it alone (`mvnw -pl simulator-service test`) needs the contracts module in the local Maven repository: run `mvnw -N install` and `mvnw -pl thermogrid-contracts install -DskipTests` once, or add `-am` to the command.

**Startup.** It fetches `GET /internal/topology` straight from `TOPOLOGY-SERVICE` (not through the gateway), retrying with backoff (1 s doubling to 15 s, 12 attempts), and refuses to start unless it gets 4 zones and 16 racks.

**H2 database and single-instance lock.** Failures are stored in an embedded H2 file, `./data/simdb.mv.db`, relative to the working directory, so start the service from `simulator-service/`. The folder is git-ignored. The file is opened at startup and held open for the life of the process, and H2 refuses a second process on the same file. A second simulator started from the same directory therefore exits at once with "Another simulator-service process already holds the simulator database lock". This protects one host and one data directory. It is not leader election: two hosts with separate data directories, or a different `SIM_DB_URL`, would both run.

Table `failure_injection` (created by `schema.sql` on every start, never dropped): `id BIGINT` auto-increment PK, `type VARCHAR(20)`, `target_id VARCHAR(8)`, `param DOUBLE NULL`, `started_at TIMESTAMP`, `ends_at TIMESTAMP NULL`, `cleared_at TIMESTAMP NULL`, `injected_by VARCHAR(50)`, index on `cleared_at`. Timestamps are UTC. A failure is active while `cleared_at` is null. Active failures are reloaded after a restart. Rows are never deleted: clearing, expiry and RESET set `cleared_at`.

| Endpoint | Purpose |
|---|---|
| `GET /api/sim/state` | Ground truth: tick, ISO-8601 UTC timestamp, paused flag, rack temperature, CPU, power and heat spike, zone cooling output and health, `activeFailures` |
| `GET /api/sim/failures?active=true` | Active failures. Without `active=true`, the whole log including history |
| `POST /api/sim/failures` | Inject a failure, 201 with the stored record |
| `DELETE /api/sim/failures/{id}` | Clear one. 404 if unknown, 409 if already cleared |
| `POST /api/sim/control` | `{"action":"PAUSE"}`, `RESUME` or `RESET` |

Failure types (`durationSec` 0 or omitted means until cleared, otherwise 1 to 86400 seconds):

```json
{ "type": "COOLING_FAILURE",  "targetId": "Z3",  "durationSec": 0 }
{ "type": "COOLING_DEGRADED", "targetId": "Z3",  "durationSec": 300, "param": 0.25 }
{ "type": "SENSOR_DROPOUT",   "targetId": "R23", "durationSec": 60 }
{ "type": "HEAT_SPIKE",       "targetId": "R23", "durationSec": 120, "param": 3.5 }
```

- `COOLING_FAILURE` (zone): health FAULT, so cooling effectiveness drops to the 0.10 floor.
- `COOLING_DEGRADED` (zone): `param` is the remaining efficiency, greater than 0 and less than 1, used as the health factor.
- `SENSOR_DROPOUT` (rack): no `telemetry.rack.reading` for that rack. The physics and the zone reading continue.
- `HEAT_SPIKE` (rack): `param` is extra kW, greater than 0 and at most 50.
- An unknown zone or rack, a missing or out-of-range `param`, or a bad duration gives 400 with the standard error body. A `param` on a type that takes none is also rejected.

**Control.** `PAUSE` stops ticks. `RESUME` continues. `RESET` clears every active failure, restores the initial state at tick 0 and leaves the simulator running (not paused).

**RabbitMQ.** Every tick, after the new state is committed, it publishes to the durable topic exchange `thermogrid.events` one `telemetry.rack.reading` per active rack (minus dropouts) and one `telemetry.zone.reading` per zone (20 messages with 16 active racks). Messages use the plan 8.5 envelope with producer `SIMULATOR-SERVICE`, version 1 and persistent delivery. Temperatures and power are rounded to 2 decimals. If the broker is unreachable, the tick's readings are skipped, one warning is logged, the broker is not tried again for 10 s, nothing is buffered, and the simulation keeps running.

**Deferred (not in Bundle 2.3):** the simulator does not yet consume `workload.assigned`, `workload.removed` or `cooling.unit.state`: no queues and no listeners. The contract mapping exists and is tested, and its handler only logs. Applying workload load arrives with Phase 4 and cooling output with Phase 6, so load stays at 60 units per rack and cooling at 40 percent. `POST /api/sim/flood` is deferred to the load-test work. Roles are enforced at the gateway (Phase 9).

Tests use in-memory or temp-directory H2, a stubbed topology client and a mocked RabbitMQ template, so they need no other service.

## Telemetry service

Port 8084, registers as `TELEMETRY-SERVICE`, owns `telemetry_db`. It consumes the simulator's rack and zone readings, stores them, forecasts each rack 60 s ahead, classifies risk, raises and clears alerts, and marks silent sensors STALE.

**Needs:** MySQL (schema `telemetry_db`), RabbitMQ and Eureka. Start it before the simulator, so the queues exist before readings are published. The passwords are read from the environment (not from `.env` automatically):

```powershell
$env:DB_PASSWORD = '<TELEMETRY_DB_PASSWORD from .env>'
$env:RABBITMQ_USER = '<RABBITMQ_USER from .env>'; $env:RABBITMQ_PASSWORD = '<RABBITMQ_PASSWORD from .env>'
java -jar telemetry-service/target/telemetry-service-0.1.0-SNAPSHOT.jar
```

Overrides: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `EUREKA_URL`. Tests use in-memory H2 and a mocked RabbitMQ template, so they touch no real database or broker.

| Endpoint | Purpose |
|---|---|
| `GET /api/telemetry/racks/latest` | Latest state per rack: temperature, 60 s forecast, trend per minute, risk level, sensor status, power, CPU load. Racks that never reported are absent |
| `GET /api/telemetry/racks/{rackId}/history?minutes=10` | Readings of the last 1 to 360 minutes with the forecast series (replayed through the same forecast engine) |
| `GET /api/telemetry/zones/latest` | Newest reading per zone |
| `GET /api/alerts?state=ACTIVE&limit=50` | Alerts, newest first. `state` is ACTIVE, CLEARED or ALL; `limit` 1 to 500 |
| `GET /internal/telemetry/racks/latest` | The rack view for the scheduler, plus `lastReadingAt`. Not routed by the gateway; requires `X-Internal-Token` |

**RabbitMQ.** It declares `thermogrid.events` (topic, durable), the dead-letter exchange `thermogrid.dlx` and queue `thermogrid.dlq` (bound with `#`), and two durable queues: `q.telemetry.rack-readings` (key `telemetry.rack.reading`, max 50,000) and `q.telemetry.zone-readings` (key `telemetry.zone.reading`, max 20,000). Both have no TTL, drop the oldest when full and dead-letter to `thermogrid.dlx`. A failing message is tried 3 times (1 s, 2 s, 4 s backoff) and then rejected without requeue, so it lands in `thermogrid.dlq`. Malformed or impossible messages (bad JSON, NaN, out-of-range values) skip the retries and go to the DLQ at once. A message is acknowledged only after its database transaction committed. Messages dropped because a queue is full are also dead-lettered by RabbitMQ.

**Readings and forecast.** Duplicates `(rackId, time)` and `(zoneId, time)` are ignored. A reading older than the newest applied one is stored but never changes the forecast state. Each rack is updated under a row lock. Forecast: damped Holt, `a=0.4, b=0.15, phi=0.95`, 30 ticks ahead. Risk uses `max(temperature, forecast)`: WATCH from 30, WARNING from 34, CRITICAL from 38; going down needs 5 consecutive readings at least 2 C below the current level's threshold. Details and evidence are in [docs/calibration.md](docs/calibration.md).

**Alerts.** At most one ACTIVE alert per rack. `thermal.alert.raised` when the level rises (also when an active alert rises again), `thermal.alert.lowered` when it falls but stays above NORMAL, `thermal.alert.cleared` on return to NORMAL. Basis is PREDICTED when the observed temperature alone would not justify the level. Events are published after the database commit. Limitation: if RabbitMQ is down for all 3 publish attempts, that event is lost (logged at ERROR) while the alert stays correct in the database and API. There is no outbox yet.

**Stale sensors.** A rack with no accepted reading for 10 s becomes `STALE` (checked every 2 s). Its forecast and risk stay frozen, active alerts stay active, and nothing is invented. The next newer valid reading restores `OK`.

**Retention** (every 10 minutes, batches of 5,000, at most 20 batches per run): rack and zone readings 6 hours, CLEARED alerts 7 days. ACTIVE alerts and `rack_forecast_state` are never deleted.

## Frontend dashboard (Phase 8)

React 18, Vite, Tailwind CSS 4, Recharts, TanStack Query, React Router and Axios, in `frontend/` (not a Maven module). The browser talks only to the API gateway.

```
scripts/start-all.sh                 # backend (set GATEWAY_PORT=18080 if 8080 is taken)
cd frontend && npm install
GATEWAY_PORT=18080 npm run dev       # http://localhost:5173, /api is proxied to the gateway
npm test                             # unit and component tests (vitest)
npm run build                        # production bundle in frontend/dist
GATEWAY_PORT=18080 npm run preview   # serve the production bundle on :4173
```

`GATEWAY_PORT` (default 8080) is the same variable the backend uses; the Vite dev and preview servers read it to proxy `/api`. `VITE_API_BASE` points the app at a gateway URL directly (the gateway then has to allow the page origin, default `http://localhost:5173`).

| Route | Page | Role |
|-------|------|------|
| `/login` | Sign in with a username and password (real JWT login, Phase 9) | public |
| `/` | Overview: KPI strip, service health strip, rack heat map, active alerts, decision feed, cooling zone cards, workload distribution | VIEWER+ |
| `/racks/:rackId` | Rack detail: temperature and forecast chart (10 min), power and CPU chart, workloads on the rack, alerts for the rack | VIEWER+ |
| `/workloads` | Table with filters, submit form, remove, CPU by rack stacked by priority, pending queue, capacity banner | VIEWER+ (submit and remove: OPERATOR) |
| `/cooling` | Output per zone over time, modelled power against the static baseline, action log, manual override | VIEWER+ (override: OPERATOR) |
| `/events` | Alerts, migrations and cooling actions merged into one timeline | VIEWER+ |
| `/control` | Failure injection with confirmation, scenario presets, autopilot switch, active failures with Clear, simulation and demo controls (ADMIN) | OPERATOR+ |

How it behaves when services fail: every data source is a separate polled query (2 to 5 s, per plan 9.4) inside its own panel and error boundary. When a service stops, only the panels fed by it show a stale badge ("Scheduler unavailable, last update 12:41:05", time from the server) and keep their last data; everything else keeps updating, and the service health strip shows healthy, degraded, unavailable or stale per service. Polling pauses while the tab is hidden. The checklist is automated by `frontend/scripts/stale-check.sh <service-name>`; `frontend/scripts/screenshots.mjs` captures every page at three resolutions and flags overflow and console errors; `frontend/scripts/scenario.mjs degrade|fail` drives a plan scenario through the Control Room UI. Screenshots are in `docs/screenshots/`.

Limits: polling only (no SSE), no Docker or nginx packaging yet (Phase 12).

## Authentication and security (Phase 9)

**To run and demonstrate the project, start with [docs/USER-RUN-GUIDE.md](docs/USER-RUN-GUIDE.md).**

- `auth-service` (port 8081, Eureka `AUTH-SERVICE`, schema `auth_db`): `POST /api/auth/login` returns `{token, tokenType: "Bearer", expiresInSeconds: 28800, user: {username, role}}`; `GET /api/auth/me` returns the current user. Passwords are BCrypt hashes; tokens are HS256 JWTs (JJWT) carrying the username (`sub`) and `role`. Demo users `viewer`, `operator`, `admin` are seeded from `.env` (`SEED_*_PASSWORD`), never from source code.
- `api-gateway` `JwtAuthFilter` is the single enforcement point: no token, expired, malformed or tampered token gives **401**; an insufficient role gives **403**; incoming `X-User-Name` and `X-User-Role` are removed and re-set from the verified token; `/internal/**` is always **404**; CORS preflight needs no token.
- Roles: **VIEWER** reads everything. **OPERATOR** adds writes (workloads, autopilot, rebalance, cooling mode, failure injection). **ADMIN** adds `POST /api/sim/control` and `POST /api/workloads/seed-demo`. Any other write defaults to OPERATOR.
- Internal calls: every service with `/internal/**` endpoints rejects a missing or wrong `X-Internal-Token` with **401**; every Feign client sends it (module `thermogrid-internal-security`).
- Configuration (see `.env.example`): `THERMOGRID_JWT_SECRET` (at least 32 characters), `THERMOGRID_INTERNAL_TOKEN`, `SEED_VIEWER_PASSWORD` / `SEED_OPERATOR_PASSWORD` / `SEED_ADMIN_PASSWORD` (or `..._PASSWORD_HASH`), `THERMOGRID_SECURITY_ENABLED` (default `true`, leave it), `GATEWAY_PORT`. Services refuse to start without the secrets. Nothing secret is committed.
- Frontend: real login form, JWT kept in memory and localStorage (lab demo, plan 9.6), `Authorization` header on every call, 401 returns to login, 403 shows a toast, Control Room and write controls hidden by role.
- Lab limits (not for production): token in localStorage, HS256 shared secret, no rate limiting or lockout on login, no refresh tokens or revocation (a token is valid until it expires, 8 h), HTTP not HTTPS.

## Messaging reliability (Phase 7)

Queues, retry and dead-letter rules, idempotency, publisher reliability and operating procedures are in [docs/messaging.md](docs/messaging.md). In short:

- **Lossless queues** (`q.telemetry.rack-readings`, `q.telemetry.zone-readings`, `q.scheduler.workloads`, `q.simulator.workloads`): durable, no TTL, bounded, dead-lettered. A failing message gets 3 attempts (waits of 1 s and 2 s), then goes to `thermogrid.dlq` with its failure headers. Malformed JSON, invalid values and unsupported event versions are dead-lettered at once.
- **State queues** (`q.cooling.zone-readings` 30 s, `q.cooling.alerts`, `q.scheduler.alerts`, `q.scheduler.cooling`, `q.simulator.cooling` 60 s): short TTL, **no dead-lettering**; a bad message is logged and dropped.
- **Publishers** use confirms and mandatory publishing; the cooling controller never publishes a state its database does not hold.
- **Upgrading an existing broker:** the five state queues changed arguments, so each must be deleted once before its service starts (RabbitMQ will not redeclare a queue with different arguments). See "Changing queue arguments" in docs/messaging.md. Nothing deletes queues automatically.
- **Scripts:** `scripts/start-all.sh` (or `start-all.ps1`) starts MySQL, RabbitMQ and the services in order and waits for each to be ready; `scripts/stop-all.sh` stops what it started; `scripts/test-start-order.sh` checks the order without starting anything; `scripts/verify-queues.sh` checks a live broker against the queue table (read-only).
- **Live tests:** each consuming service has a `LiveBrokerTest` that is skipped unless `THERMOGRID_LIVE_RABBIT_PORT` is set. Point it only at a throwaway broker, for example `docker run -d -p 127.0.0.1:25672:5672 -e RABBITMQ_DEFAULT_USER=p7test -e RABBITMQ_DEFAULT_PASS=p7test rabbitmq:3.13-management`, then `THERMOGRID_LIVE_RABBIT_PORT=25672 ./mvnw -pl telemetry-service,scheduler-service,cooling-controller-service,simulator-service test`.

## Repository layout

```
pom.xml            parent POM
docs/              status register, decision log, testing, performance, demo script, viva, checklists, diagrams, slides
infra/             docker-compose.yml (dev MySQL and RabbitMQ), docker-compose.full.yml (whole stack), mysql/init, nginx, env examples
scripts/           start-all / stop-all, reset-demo, e2e, failure-tests, flood-test, demo.http
load-tests/        k6 read-API test
postman/           Postman collection (generated by gen-collection.mjs)
frontend/          React dashboard (also has a Dockerfile)
<module>/          one directory per Maven module (each service has a Dockerfile)
```

## Git conventions

One commit per brief, named `phase-N: service: summary`, on a feature branch. Merge to `main` only when tests pass.