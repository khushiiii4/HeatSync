# REST API reference

The contract is plan section 8.2 and 8.3. This page lists the endpoints that **actually exist in the code** (taken from the controllers) with the role the gateway enforces, then the authentication rules. Request and response examples are in the plan (section 8.3) and in [USER-RUN-GUIDE.md](USER-RUN-GUIDE.md) (section F). All public paths go through the gateway (`/api/**`); `/internal/**` is service to service only and is never routed.

## Endpoint list

| Service | Method and path | Role |
|---------|-----------------|------|
| auth | `POST /api/auth/login` | public |
| auth | `GET /api/auth/me` | VIEWER |
| topology | `GET /api/topology`, `GET /api/topology/racks/{rackId}` | VIEWER |
| topology | `GET /internal/topology` | internal token |
| simulator | `GET /api/sim/state`, `GET /api/sim/failures?active=true`, `GET /api/sim/flood` | VIEWER |
| simulator | `POST /api/sim/failures`, `DELETE /api/sim/failures/{id}` | OPERATOR |
| simulator | `POST /api/sim/control` (PAUSE, RESUME, RESET), `POST /api/sim/flood` | ADMIN |
| telemetry | `GET /api/telemetry/racks/latest`, `GET /api/telemetry/racks/{rackId}/history?minutes=10`, `GET /api/telemetry/zones/latest`, `GET /api/alerts?state=ACTIVE&limit=50` | VIEWER |
| telemetry | `DELETE /api/telemetry/synthetic` | ADMIN |
| telemetry | `GET /internal/telemetry/racks/latest` | internal token |
| workload | `GET /api/workloads?status=&rackId=`, `GET /api/workloads/{id}`, `GET /api/workloads/summary` | VIEWER |
| workload | `POST /api/workloads`, `POST /api/workloads/bulk`, `DELETE /api/workloads/{id}` | OPERATOR |
| workload | `POST /api/workloads/seed-demo` | ADMIN |
| workload | `GET /internal/workloads`, `PUT /internal/workloads/{id}/assignment` | internal token |
| scheduler | `GET /api/scheduler/status`, `GET /api/scheduler/migrations?limit=50`, `GET /api/scheduler/decisions?limit=20` | VIEWER |
| scheduler | `POST /api/scheduler/autopilot`, `POST /api/scheduler/rebalance` | OPERATOR |
| cooling | `GET /api/cooling/units`, `GET /api/cooling/actions?limit=50`, `GET /api/cooling/summary` | VIEWER |
| cooling | `POST /api/cooling/units/{zoneId}/mode` | OPERATOR |
| gateway | `GET /api/platform/services` (registered services and instance counts) | VIEWER |

Not implemented: `PUT /api/topology/racks/{rackId}/status` (rack maintenance mode, an optional item on the plan's cut list).

## Authentication and roles (Phase 9)

Every gateway route needs `Authorization: Bearer <JWT>` except `POST /api/auth/login`. Missing, expired, malformed or tampered tokens give 401; an insufficient role gives 403. `/internal/**` is never served by the gateway (404); inside the system it needs the `X-Internal-Token` header (401 otherwise).

- `POST /api/auth/login` body `{"username","password"}` returns `{"token","tokenType":"Bearer","expiresInSeconds":28800,"user":{"username","role"}}`. Any bad credentials give 401 with the same body whether the user exists or not.
- Roles: every `GET` and `HEAD` needs VIEWER; `POST /api/sim/control`, `POST /api/sim/flood`, `POST /api/workloads/seed-demo` and `DELETE /api/telemetry/synthetic` need ADMIN; every other write needs OPERATOR (a new write endpoint is therefore never open by accident).
- The gateway removes any client-sent `X-User-Name` and `X-User-Role` and sets them from the verified token, so downstream services can trust them.

## Load-test endpoints (Phase 12)

`POST /api/sim/flood` body `{"ratePerSec":500,"durationSec":60,"racks":50}` (1 to 5000, 1 to 300, 1 to 200; racks default 50) publishes synthetic rack readings for racks `SYN001` upward; 202 with progress, 409 if a flood is already running, 400 if out of range. `GET /api/sim/flood` returns the progress (running, planned, sent, failed, startedAt, finishedAt). `DELETE /api/telemetry/synthetic` removes the synthetic racks' readings and forecast states and returns the counts removed.
