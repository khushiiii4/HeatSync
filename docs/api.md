# REST API reference

> Placeholder. Not yet written. Source of truth: master plan, plan section 8.2-8.3. To be populated in: Phases 1-9.

## Authentication and roles (Phase 9)

Every gateway route needs `Authorization: Bearer <JWT>` except `POST /api/auth/login`. Missing, expired, malformed or tampered tokens give 401; an insufficient role gives 403. `/internal/**` is never served by the gateway (404); inside the system it needs the `X-Internal-Token` header (401 otherwise).

| Endpoint | Role |
|----------|------|
| `POST /api/auth/login` body `{"username","password"}` returns `{"token","tokenType":"Bearer","expiresInSeconds":28800,"user":{"username","role"}}` (401 for any bad credentials, same body whether the user exists or not) | public |
| `GET /api/auth/me` returns `{"username","role"}` | any valid token |
| every `GET` and `HEAD` (topology, telemetry, alerts, workloads, scheduler, cooling, sim state and failures, platform services) | VIEWER |
| `POST /api/workloads`, `POST /api/workloads/bulk`, `DELETE /api/workloads/{id}`, `POST /api/scheduler/autopilot`, `POST /api/scheduler/rebalance`, `POST /api/cooling/units/{zoneId}/mode`, `POST /api/sim/failures`, `DELETE /api/sim/failures/{id}` and any other write | OPERATOR |
| `POST /api/sim/control`, `POST /api/workloads/seed-demo` | ADMIN |

The gateway removes any client-sent `X-User-Name` and `X-User-Role` and sets them from the verified token, so downstream services can trust them.

## Phase 12 additions

| Method and path | Role (gateway) | Purpose |
|-----------------|----------------|---------|
| `POST /api/sim/flood` | ADMIN | Body `{"ratePerSec":500,"durationSec":60,"racks":50}` (1 to 5000, 1 to 300, 1 to 200, racks default 50). Publishes synthetic rack readings for racks SYN001 upward. 202 with progress, 409 if a flood is running, 400 if out of range |
| `GET /api/sim/flood` | VIEWER | Progress: running, planned, sent, failed, startedAt, finishedAt |
| `DELETE /api/telemetry/synthetic` | ADMIN | Removes the synthetic racks' readings and forecast states; returns the counts removed |
