# Deployment, scaling and performance results (Phase 12)

Source: plan sections 3, 5.8, 12.3 (P-01 to P-03), 13.4 and roadmap Phase 12. Everything below was **run on the development machine** (Windows 11, Docker Desktop with 8 GB RAM and 12 CPUs), not on a lab server. There is no lab server; the final deployment is Docker on one computer, and these numbers are **local-machine measurements**.

## 1. What was built

| Item | File | Notes |
|------|------|-------|
| Multi-stage Dockerfile per service | `<service>/Dockerfile` (9 services) | Stage 1 `maven:3.9-eclipse-temurin-17` builds only that module and what it needs; stage 2 `eclipse-temurin:17-jre-alpine`, non-root user, `-Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC`. Build context is the repository root |
| Frontend image | `frontend/Dockerfile`, `infra/nginx/default.conf` | Node build, then nginx on port 80: serves the SPA (history fallback), proxies `/api/` to `api-gateway:8080`, returns 404 for `/internal/` |
| Full deployment | `infra/docker-compose.full.yml` | MySQL, RabbitMQ, Eureka, gateway, 7 business services, telemetry x3, nginx. Project name `thermogrid-full`, so it does not collide with the development compose file |
| Environment files | `infra/env/lab.env.example`, `infra/env/dev.env.example`, `.dockerignore` | Placeholders only. Real values live in the git-ignored `.env`. No secret is in any committed file |
| Health checks and start order | `healthcheck` per service (TCP check of its port) and `depends_on: condition: service_healthy` | mysql and rabbitmq, then eureka, then gateway, auth, topology, workload, telemetry x3, scheduler and cooling, then the simulator last (plan 13.4), then nginx after the gateway |
| Telemetry scaling | `deploy.replicas: ${TELEMETRY_REPLICAS:-3}` (also `--scale telemetry-service=N`) | Competing consumers on `q.telemetry.rack-readings` |
| Flood generator | `POST /api/sim/flood` (simulator), `DELETE /api/telemetry/synthetic` (telemetry), both ADMIN at the gateway | Plan 8.2 endpoints that had been deferred. Synthetic racks are `SYN001...`, so real racks (R11 to R44) can never match the cleanup |
| k6 and flood scripts | `load-tests/k6-read.js`, `load-tests/README.md`, `scripts/flood-test.sh` | |

**How to deploy (from the repository root, with `.env` filled in from `.env.example`):**

```
docker compose --env-file .env -f infra/docker-compose.full.yml up -d --build
# dashboard: http://localhost:${HTTP_PORT:-80}/     Eureka: http://127.0.0.1:8762     RabbitMQ UI: http://127.0.0.1:15673
docker compose --env-file .env -f infra/docker-compose.full.yml up -d --scale telemetry-service=3
```

Only nginx (`HTTP_PORT`) is published publicly. MySQL, the gateway and all services have no published port; the Eureka dashboard and RabbitMQ UI are bound to 127.0.0.1.

## 2. Deployment result (local, full stack in Docker)

- `docker compose ... build`: all 10 images built (images 348 to 408 MB each, frontend 75 MB).
- `docker compose ... up -d` on an empty database: every container reached **healthy** in dependency order; the whole stack (14 containers including 3 telemetry replicas) was healthy about 4 minutes after the command (first start: MySQL initialisation and nine JVMs).
- Eureka / `/api/platform/services`: 8 services UP, `TELEMETRY-SERVICE` with 3 instances.
- nginx: `/` returned the SPA (200), a deep link `/racks/R11` returned the SPA (200), `/internal/topology` returned 404, `/api/*` reached the gateway.
- `scripts/e2e.sh` through nginx: **37 passed, 0 failed** (run twice after the gateway fix, the second time with 3 telemetry and 2 workload instances; a first run before the fix, with an older version of the script, had 5 failures that were only the script not handling closed internal ports). The 38th check of the Phase 10 run (calling a service's internal port directly with the real token) is skipped because the Docker deployment publishes no service port; the "without token" internal checks pass because the port is unreachable, which is a stronger refusal than 401.
- Memory per container with 3 telemetry replicas (JVM heap cap 256 MB, container limit 448 MB): gateway 248 MB, auth 257, cooling 277, eureka 207, scheduler 296, simulator 212, telemetry 300 / 266 / 272, topology 232, workload 276; mysql 576 of 768; rabbitmq 195 of 512; nginx 13. Total about 3.6 GB; the configured limits add up to about 6.3 GB. No container was restarted or killed for memory.

## 3. P-01: read APIs under load (k6)

`load-tests/k6-read.js`: 50 virtual users, each logs in once as `viewer`, then calls `GET /api/telemetry/racks/latest` and `GET /api/alerts?state=ACTIVE&limit=50` every second for 2 minutes, through nginx and the gateway (k6 ran in a container on the compose network).

| Measure | Target | Measured |
|---------|--------|----------|
| Error rate | below 1 percent | **0.00 %** (0 of 11,664 requests) |
| p95 latency | below 500 ms | **21.0 ms** (avg 17.8 ms, median 7.2 ms, max 1.9 s) |
| Checks | | 17,471 of 17,471 passed (50 logins, racks 200 and non-empty, alerts 200) |
| Throughput | | about 101 requests/s, 5,807 iterations |

**Defect found and fixed by this test (D-095).** The first run failed with 2.36 % errors and 271 failed logins, all `503 AUTH-SERVICE is unavailable`. Auth itself handled 50 concurrent logins fine when called directly (0 failures); the gateway did not. Debug logging showed `BulkheadFullException: Bulkhead 'AUTH-SERVICE' is full`: Resilience4j adds a semaphore bulkhead of 25 concurrent calls per route by default, the 25 overflow calls counted as circuit-breaker failures, the circuit opened and every call returned 503 for 10 s at a time. The plan asks for a circuit breaker and a 5 s timeout, not a concurrency cap, so `spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled=false` was set in the gateway configuration. A regression test now sends 60 concurrent requests through a circuit-breaker route to a slow stub (it fails with the bulkhead on and passes with it off). After the fix: 0 login failures in the same 50-user test.

## 4. P-02: telemetry flood, 1 instance against 3 instances

`scripts/flood-test.sh`: the simulator publishes 500 messages/s for 60 s (30,000 `telemetry.rack.reading` events over 50 synthetic racks, normal temperatures so no alert fires) while the script samples the queue `q.telemetry.rack-readings` through the RabbitMQ management API every 2 s. The real simulation kept running alongside. Synthetic data was removed afterwards with `DELETE /api/telemetry/synthetic`.

| | 1 telemetry instance | 3 telemetry instances |
|---|---|---|
| Messages sent / failed | 30,000 / 0 | 30,000 / 0 |
| Rows stored (equals sent: nothing lost or duplicated) | 30,000 | 30,000 |
| Forecast states created | 50 | 50 |
| Peak queue depth | **23,685** | **17,652** |
| Last message sent at | 58 s | 59 s |
| Queue drained (20 or fewer) | 256 s | 119 s |
| Drain time after the flood ended | **198 s** | **60 s** |
| Average ingest rate over the whole run | about 117 msg/s | about 252 msg/s |

Queue depth over time (seconds:messages): 1 instance `0:0 31:9936 63:23685 95:19474 127:15266 159:11730 192:8024 224:3905 256:0`; 3 instances `0:0 17:4707 34:10358 51:14880 67:15214 83:10828 100:5002 116:687`.

Reading the result: three competing consumers drained the backlog 3.3 times faster and the peak was lower, as the plan expected ("three instances should drain faster"). The speed-up is a little more than 2x in throughput, not 3x: every message does an insert plus a per-rack row-locked forecast update on one MySQL container with a 128 MB buffer pool, and 50 racks share the locks. The queue never came near its 50,000 cap, so no message was dropped. This also covers I-07 (three instances, no duplicate rows, one forecast state per rack). The 500 messages/s rate exceeds what one instance ingests (about 117 msg/s), which is why a backlog builds; the system stayed healthy and caught up on its own.

## 5. P-03: two workload-service instances

With `--scale workload-service=2`, Eureka listed 2 instances and `scripts/e2e.sh` passed 37 of 37 through the gateway. 300 requests to `GET /api/workloads?status=RUNNING` were split almost evenly: network received by instance 1 grew by 1.06 MB and by instance 2 by 1.10 MB. The services do not log requests or return an instance id, so the alternation is shown by traffic volume, not by an id in each response. Scaled back to 1 afterwards.

## 6. Not done / limits

- **No lab server and no lab URL.** The plan's "lab URL" criterion is met by the Docker stack on your own computer (`http://localhost:<HTTP_PORT>/`). Nothing was run on a lab server. Plan for 5 to 6 GB of free memory for the stack.
- The numbers above are from one developer machine with Docker Desktop; they show the mechanism and relative scaling, not the capacity of a different machine.
- Port 80 was not used locally (`HTTP_PORT=8088`), because 80 and 8080 may be taken on this machine; the default in the compose file is 80.
- Health checks are TCP port checks (the services expose no actuator health endpoint), so "healthy" means the port is open, which for Spring Boot is after the application context has started.
- Telemetry is scaled with `deploy.replicas` and `--scale`; MySQL table creation by three instances starting at the same time worked in this run (no race observed) but was not stress-tested.
- Heap caps were set to 256 MB per JVM; GC and latency under heavier load than P-01 were not measured.
- `/api/sim/flood` and `/api/telemetry/synthetic` are ADMIN-only at the gateway; no dashboard button was added for them.
